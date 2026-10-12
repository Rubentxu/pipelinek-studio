package dev.pipelinek.studio.http

import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * S5 E5.3 — /openapi.json is the contract for SDK generators. The endpoint must
 * serve a valid OpenAPI 3.1 document, advertise the studio version, and list
 * every documented HTTP path. The test does not pull in a JSON parser; it
 * relies on string presence checks against the curated doc.
 */
class OpenApiDocTest {

    @Test
    fun `openapi json is served and parses as JSON-shaped content`() = testApplication {
        application {
            StudioHttpModule(
                wiringFactory = { StubWiringFactoryForOpenApi.wiring() },
                runReadFactory = null,
                outputReadFactory = null,
                stepCatalogFactory = null,
                diagnosticsFactory = null,
                irFactory = null,
            ).install(this)
        }
        val r = hostClient().get("/openapi.json")
        assertEquals(HttpStatusCode.OK, r.status)
        val text = r.bodyAsText()
        assertTrue(text.contains("\"openapi\": \"3.1.0\""), "expected OpenAPI 3.1 declaration, got: ${text.take(200)}")
        assertTrue(text.contains("\"title\": \"PipelineK Studio HTTP API\""), "expected title")
        assertTrue(text.contains("\"version\": \"1.0.0-rc.11\""), "expected version")
    }

    @Test
    fun `openapi doc lists every documented HTTP path`() {
        val doc = OpenApiDoc.current
        val expectedPaths = listOf(
            "/health",
            "/api/version",
            "/openapi.json",
            "/experiments",
            "/experiments/{experimentId}/snapshots",
            "/runs",
            "/runs/{runId}",
            "/runs/{runId}/events",
            "/runs/{runId}/stream",
            "/runs/{runId}/output",
            "/runs/{runId}/diagnostics",
            "/runs/{runId}/ir",
            "/steps",
            "/steps/{stepId}",
        )
        for (p in expectedPaths) {
            assertTrue(doc.contains("\"$p\""), "openapi doc missing path $p")
        }
    }

    @Test
    fun `openapi doc documents the documented refusal codes for output and ir`() {
        val doc = OpenApiDoc.current
        assertTrue(doc.contains("OUTPUT_REFUSED"), "doc should document OUTPUT_REFUSED")
        assertTrue(doc.contains("IR_UNAVAILABLE"), "doc should document IR_UNAVAILABLE")
    }
}

/** Minimal wiring builder (no SDK) — used for the smoke test of /openapi.json. */
private object StubWiringFactoryForOpenApi {
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
        override fun listScenarios(limit: Int, offset: Int): List<dev.pipelinek.studio.application.ports.ScenarioSnapshot> = emptyList()
        override fun saveScenario(scenario: dev.pipelinek.studio.domain.SourceScenario) { /* noop */ }
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
