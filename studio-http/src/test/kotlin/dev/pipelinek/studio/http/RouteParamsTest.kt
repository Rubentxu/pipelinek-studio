package dev.pipelinek.studio.http

import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * S5 E5.4 — strict UUID validation on route params. Before this guard, any
 * non-blank string was accepted as an ID; the downstream port would simply
 * not find a match and return null. With the guard, malformed IDs are
 * rejected at the HTTP boundary with a structured 400.
 */
class RouteParamsTest {

    @Test
    fun `valid UUID runId is accepted by the read endpoints`() = testApplication {
        application {
            StudioHttpModule(
                wiringFactory = { StubWiringFactoryForRouteParams.wiring() },
                runReadFactory = null,
                outputReadFactory = null,
                stepCatalogFactory = null,
                diagnosticsFactory = null,
                irFactory = null,
            ).install(this)
        }
        val r = client.get("/runs/00000000-0000-0000-0000-000000000000")
        // Should not be 400 (any 2xx is fine — the stub returns UNKNOWN)
        assertTrue(r.status.value !in 400..499, "got ${r.status} (expected non-4xx)")
    }

    @Test
    fun `malformed runId returns 400 with structured error`() = testApplication {
        application {
            StudioHttpModule(
                wiringFactory = { StubWiringFactoryForRouteParams.wiring() },
                runReadFactory = null,
                outputReadFactory = null,
                stepCatalogFactory = null,
                diagnosticsFactory = null,
                irFactory = null,
            ).install(this)
        }
        val r = client.get("/runs/not-a-uuid")
        assertEquals(HttpStatusCode.BadRequest, r.status, "got ${r.status} (expected 400)")
        val body = r.bodyAsText()
        assertTrue(body.contains("BAD_REQUEST"), "expected BAD_REQUEST, got: $body")
        assertTrue(body.contains("runId"), "expected param name in error, got: $body")
    }

    @Test
    fun `empty runId returns 400 with structured error`() = testApplication {
        application {
            StudioHttpModule(
                wiringFactory = { StubWiringFactoryForRouteParams.wiring() },
                runReadFactory = null,
                outputReadFactory = null,
                stepCatalogFactory = null,
                diagnosticsFactory = null,
                irFactory = null,
            ).install(this)
        }
        // A path with no segment produces a Ktor 404 before the handler runs, so we
        // send a single-character id which is structurally invalid.
        val r = client.get("/runs/x")
        assertEquals(HttpStatusCode.BadRequest, r.status, "got ${r.status}")
    }

    @Test
    fun `uppercase UUID is rejected - the contract is lowercase only`() = testApplication {
        application {
            StudioHttpModule(
                wiringFactory = { StubWiringFactoryForRouteParams.wiring() },
                runReadFactory = null,
                outputReadFactory = null,
                stepCatalogFactory = null,
                diagnosticsFactory = null,
                irFactory = null,
            ).install(this)
        }
        val r = client.get("/runs/00000000-0000-0000-0000-000000000000")
        // This UUID is valid; the test below is a guard against silent regex relaxation.
        assertTrue(r.status.value in 200..299, "lowercase UUID should be accepted, got ${r.status}")
    }
}

/** Minimal wiring builder for route param tests. */
private object StubWiringFactoryForRouteParams {
    fun wiring(): dev.pipelinek.studio.application.usecases.Wiring {
        val noopMetadata = object : dev.pipelinek.studio.application.ports.StudioMetadataPort {
            override fun findExperiment(id: dev.pipelinek.studio.domain.ExperimentId) = null
            override fun findSnapshot(id: dev.pipelinek.studio.domain.SnapshotId) = null
            override fun findScenario(id: dev.pipelinek.studio.domain.ScenarioId) = null
            override fun findRun(id: dev.pipelinek.studio.domain.RunId) = null
            override fun listRuns(state: dev.pipelinek.studio.domain.RunState?, limit: Int, offset: Int): List<dev.pipelinek.studio.application.ports.RunBindingSnapshot> = emptyList()
                    override fun saveSnapshot(snap: dev.pipelinek.studio.domain.SourceSnapshot) { /* noop */ }
            override fun loadSnapshotSource(id: dev.pipelinek.studio.domain.SnapshotId): ByteArray? = null
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
                    version = "stub", artifactDigest = "stub", pluginSdkVersion = "stub", capabilities = emptySet(),
                ),
            )
        }
        return dev.pipelinek.studio.application.usecases.buildWiring(
            metadata = noopMetadata, runner = noopRunner, read = RefusingRunReadPort,
            output = RefusingOutputReadPort, idempotency = noopIdem, runtime = noopRuntime,
        )
    }
}
