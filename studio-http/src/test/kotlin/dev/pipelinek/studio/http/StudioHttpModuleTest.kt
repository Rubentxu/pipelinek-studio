package dev.pipelinek.studio.http

import dev.pipelinek.studio.application.ports.*
import dev.pipelinek.studio.domain.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlin.test.Test
import kotlin.test.assertEquals

class StudioHttpModuleTest {

    private fun fakeWiringFactory(): () -> dev.pipelinek.studio.application.usecases.Wiring {
        val noopMetadata = object : StudioMetadataPort {
            override fun findExperiment(id: ExperimentId) = null
            override fun findSnapshot(id: SnapshotId) = null
            override fun findScenario(id: ScenarioId) = null
            override fun findRun(id: RunId) = null
        }
        val noopRunner = object : PipelineRunnerPort {
            override fun submit(req: RunRequest): SubmissionResult =
                SubmissionResult.Admitted("upstream-1", java.nio.file.Path.of("/tmp/ctrl"))
            override fun cancel(runId: RunId): CancellationResult = CancellationResult.Unknown("stub")
            override fun status(runId: RunId): RunHandleStatus = RunHandleStatus.Unknown("stub")
        }
        val noopIdempotency = object : IdempotencyPort {
            override fun lookup(scope: String, key: String, requestDigestSha256: String) =
                IdempotencyLookupResult.NotFound
            override fun record(scope: String, key: String, operationId: OperationId, requestDigestSha256: String) =
                IdempotencyRecordResult.Recorded
        }
        val noopRuntime = object : PipelineKRuntimePort {
            override fun describe(): RuntimeDescriptorResult =
                RuntimeDescriptorResult.Available(
                    RuntimeDescriptor(
                        version = "test-0.0.1",
                        artifactDigest = "test-digest",
                        pluginSdkVersion = "test-0.0.1",
                        capabilities = setOf("compile", "eval"),
                    ),
                )
        }
        return {
            dev.pipelinek.studio.application.usecases.buildWiring(
                noopMetadata,
                noopRunner,
                RefusingRunReadPort,
                RefusingOutputReadPort,
                noopIdempotency,
                noopRuntime,
            )
        }
    }

    @Test
    fun `health responds ok`() = testApplication {
        application { StudioHttpModule(fakeWiringFactory()).install(this) }
        val r = client.get("/health")
        assertEquals(HttpStatusCode.OK, r.status)
    }

    @Test
    fun `api version reports runtime descriptor`() = testApplication {
        application { StudioHttpModule(fakeWiringFactory()).install(this) }
        val r = client.get("/api/version")
        assertEquals(HttpStatusCode.OK, r.status)
        val body = r.bodyAsText()
        assertEquals(true, body.contains("test-0.0.1"), "expected runtime version in $body")
        assertEquals(true, body.contains("test-digest"))
    }

    @Test
    fun `POST experiments creates with generated id`() = testApplication {
        application { StudioHttpModule(fakeWiringFactory()).install(this) }
        val r = client.post("/experiments") {
            contentType(ContentType.Application.Json)
            setBody("""{"name":"my-experiment"}""")
        }
        assertEquals(HttpStatusCode.OK, r.status)
        val body = r.bodyAsText()
        assertEquals(true, body.contains("experiment_id"))
    }

    @Test
    fun `POST runs without idempotency header is 400`() = testApplication {
        application { StudioHttpModule(fakeWiringFactory()).install(this) }
        val r = client.post("/runs") {
            contentType(ContentType.Application.Json)
            setBody(
                """{"experiment_id":"00000000-0000-0000-0000-000000000000","snapshot_id":"00000000-0000-0000-0000-000000000000","scenario_id":"00000000-0000-0000-0000-000000000000"}""",
            )
        }
        assertEquals(HttpStatusCode.BadRequest, r.status)
    }

    @Test
    fun `GET runs id returns 200 with RUNNING or TERMINAL or ACCEPTED`() = testApplication {
        application { StudioHttpModule(fakeWiringFactory()).install(this) }
        val r = client.get("/runs/00000000-0000-0000-0000-000000000000")
        val expected = setOf(HttpStatusCode.OK, HttpStatusCode.Accepted)
        assertEquals(true, r.status in expected, "got ${r.status}")
    }

    @Test
    fun `GET runs output returns 202 with explicit refusal (S0 honest)`() = testApplication {
        application { StudioHttpModule(fakeWiringFactory()).install(this) }
        val r = client.get("/runs/00000000-0000-0000-0000-000000000000/output")
        assertEquals(HttpStatusCode.Accepted, r.status)
        val body = r.bodyAsText()
        assertEquals(true, body.contains("OUTPUT_REFUSED") || body.contains("OUTPUT_UNKNOWN_STREAM"),
            "expected honest refusal, got $body")
    }
}
