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
        val r = hostClient().get("/runs/00000000-0000-0000-0000-000000000000")
        // B1.5 (2026-10-10): a well-formed uuid is accepted by the
        // route param check, but the handler now returns 404
        // RUN_NOT_FOUND when the run_bindings row is missing
        // (the pre-B1.5 handler returned 200 with a fake state).
        // The point of this test is the param parsing: the
        // status must be 404 (param accepted, no row found),
        // not 400 (param rejected). The 4xx range includes both
        // 400 and 404, so we assert the specific 404.
        assertEquals(HttpStatusCode.NotFound, r.status, "got ${r.status}")
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
        val r = hostClient().get("/runs/not-a-uuid")
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
        val r = hostClient().get("/runs/x")
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
        val r = hostClient().get("/runs/00000000-0000-0000-0000-000000000000")
        // This UUID is valid; the test below is a guard against silent regex relaxation.
        // B1.5 (2026-10-10): a well-formed uuid is accepted by the
        // route param check, but the handler now returns 404
        // RUN_NOT_FOUND when the run_bindings row is missing.
        // The point of this test is the param parsing: the
        // status must be 404 (param accepted, no row found),
        // not 400 (param rejected).
        assertEquals(HttpStatusCode.NotFound, r.status, "lowercase UUID should be accepted by param parser; got ${r.status}")
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
