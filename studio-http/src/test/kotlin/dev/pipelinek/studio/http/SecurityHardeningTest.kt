package dev.pipelinek.studio.http

import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * S5 E5.4 — Security hardening: request body size limit.
 *
 * The full ktor-server-body-limit plugin is not on the classpath; we ship a small
 * `installBodySizeLimit` interceptor in this module that consults `Content-Length` and
 * rejects oversized requests with 413 + a structured JSON error. The tests assert:
 *  - small bodies pass through (200)
 *  - bodies with Content-Length > limit are rejected (413)
 *  - the error body carries the documented JSON shape
 */
class SecurityHardeningTest {

    @Test
    fun `request body under limit is accepted`() = testApplication {
        application {
            installBodySizeLimit(maxBytes = 1024)
            routing {
                post("/echo") {
                    val body = call.receiveText()
                    call.respondText("ok:$body")
                }
            }
        }
        val r = hostClient().post("/echo") {
            header(HttpHeaders.ContentType, "text/plain")
            setBody("hello world")
        }
        assertEquals(HttpStatusCode.OK, r.status)
    }

    @Test
    fun `request body over limit is rejected with 413 and structured error`() = testApplication {
        application {
            installBodySizeLimit(maxBytes = 1024)
            routing {
                post("/echo") {
                    val body = call.receiveText()
                    call.respondText("ok:$body")
                }
            }
        }
        val r = hostClient().post("/echo") {
            header(HttpHeaders.ContentType, "text/plain")
            setBody("x".repeat(2048))
        }
        assertEquals(HttpStatusCode.PayloadTooLarge, r.status, "got ${r.status}: ${r.bodyAsText()}")
        val body = r.bodyAsText()
        assertTrue(body.contains("BODY_TOO_LARGE"), "expected error code in body, got: $body")
    }

    @Test
    fun `body-size limit is wired into StudioHttpModule so production endpoints are protected`() = testApplication {
        application {
            StudioHttpModule(
                wiringFactory = { StubWiringFactoryForSecurity.wiring() },
                runReadFactory = null,
                outputReadFactory = null,
                stepCatalogFactory = null,
                diagnosticsFactory = null,
                irFactory = null,
            ).install(this)
        }
        // 2MB body — well above the 1MB default. POST /experiments expects JSON, but
        // the body-size interceptor runs *before* the router, so we should never see
        // a JSON parse error; we should see 413.
        val r = hostClient().post("/experiments") {
            header(HttpHeaders.ContentType, "application/json")
            setBody("""{"name":"${"x".repeat(2 * 1024 * 1024)}"}""")
        }
        assertEquals(HttpStatusCode.PayloadTooLarge, r.status, "got ${r.status}: ${r.bodyAsText()}")
    }

    @Test
    fun `health endpoint is exempt from body limit because it has no body`() = testApplication {
        application {
            installBodySizeLimit(maxBytes = 1024)
            routing { get("/health") { call.respondText("ok") } }
        }
        val r = hostClient().get("/health")
        assertEquals(HttpStatusCode.OK, r.status)
    }
}

/** Minimal Wiring builder for the security test (no SDK needed). */
private object StubWiringFactoryForSecurity {
    fun wiring(): dev.pipelinek.studio.application.usecases.Wiring {
        val noopMetadata = object : dev.pipelinek.studio.application.ports.StudioMetadataPort {
            override fun findExperiment(id: dev.pipelinek.studio.domain.ExperimentId) = null
            override fun findSnapshot(id: dev.pipelinek.studio.domain.SnapshotId) = null
            override fun findScenario(id: dev.pipelinek.studio.domain.ScenarioId) = null
            override fun findRun(id: dev.pipelinek.studio.domain.RunId) = null
            override fun listRuns(state: dev.pipelinek.studio.domain.RunState?, limit: Int, offset: Int): List<dev.pipelinek.studio.application.ports.RunBindingSnapshot> = emptyList()
                    override fun saveSnapshot(snap: dev.pipelinek.studio.domain.SourceSnapshot) { /* noop */ }
            override fun loadSnapshotSource(id: dev.pipelinek.studio.domain.SnapshotId): ByteArray? = null
            override fun saveExperiment(experiment: dev.pipelinek.studio.domain.Experiment) { /* noop */ }
            override fun persistRunBinding(binding: dev.pipelinek.studio.domain.RunBinding) { /* noop */ }
        }
        val noopRunner = object : dev.pipelinek.studio.application.ports.PipelineRunnerPort {
            override fun submit(req: dev.pipelinek.studio.application.ports.RunRequest) =
                dev.pipelinek.studio.application.ports.SubmissionResult.Admitted("u", java.nio.file.Path.of("/tmp"))
            override fun cancel(runId: dev.pipelinek.studio.domain.RunId) =
                dev.pipelinek.studio.application.ports.CancellationResult.Unknown("stub")
            override fun status(runId: dev.pipelinek.studio.domain.RunId) =
                dev.pipelinek.studio.application.ports.RunHandleStatus.Unknown("stub")
        }
        val noopIdem = object : dev.pipelinek.studio.application.ports.IdempotencyPort {
            override fun lookup(scope: String, key: String, requestDigestSha256: String) =
                dev.pipelinek.studio.application.ports.IdempotencyLookupResult.NotFound
            override fun record(scope: String, key: String, operationId: dev.pipelinek.studio.domain.OperationId, requestDigestSha256: String, responseRef: String?,) =
                dev.pipelinek.studio.application.ports.IdempotencyRecordResult.Recorded
        }
        val noopRuntime = object : dev.pipelinek.studio.application.ports.PipelineKRuntimePort {
            override fun describe() = dev.pipelinek.studio.application.ports.RuntimeDescriptorResult.Available(
                dev.pipelinek.studio.application.ports.RuntimeDescriptor(
                    version = "stub",
                    artifactDigest = "stub",
                    pluginSdkVersion = "stub",
                    capabilities = emptySet(),
                ),
            )
        }
        return dev.pipelinek.studio.application.usecases.buildWiring(
            metadata = noopMetadata,
            runner = noopRunner,
            read = RefusingRunReadPort,
            output = RefusingOutputReadPort,
            idempotency = noopIdem,
            runtime = noopRuntime,
        )
    }
}
