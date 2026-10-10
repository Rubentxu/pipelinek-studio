package dev.pipelinek.studio.http

import dev.pipelinek.studio.application.ports.*
import dev.pipelinek.studio.domain.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.client.statement.bodyAsText
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlin.test.Test
import kotlin.test.assertEquals

class StudioHttpModuleTest {

    @Test
    fun `stream endpoint returns 200 with error event in body when runReadPort missing`() = testApplication {
        application { StudioHttpModule(fakeWiringFactory(), runReadFactory = null).install(this) }
        val r = hostClient().get("/runs/00000000-0000-0000-0000-000000000000/stream")
        // SSE always returns 200 once opened; the error is communicated via the wire body.
        // Per W3C SSE, status 200 + event:error means "stream opened, then a notification".
        assertEquals(HttpStatusCode.OK, r.status)
        val text = r.bodyAsText()
        assertEquals(true, text.contains("READ_PORT_UNAVAILABLE") || text.contains("error"),
            "expected error event in body, got: ${text.take(200)}")
    }

    @Test
    fun `stream endpoint sends SSE events when runReadPort present`() = testApplication {
        application {
            StudioHttpModule(
                fakeWiringFactory(),
                runReadFactory = { SequenceWithRuns(events = runReadList()) },
            ).install(this)
        }
        val r = hostClient().get("/runs/00000000-0000-0000-0000-000000000000/stream")
        // 200 OK with text/event-stream content type — even with no events the SSE response streams.
        assertEquals(HttpStatusCode.OK, r.status)
        assertEquals(true, r.headers["Content-Type"]?.contains("event-stream") ?: false, "got ${r.headers["Content-Type"]}")
    }

    @Test
    fun `stream endpoint accepts Last-Event-ID header for reconnect (S1 E1_4)`() = testApplication {
        application {
            StudioHttpModule(
                fakeWiringFactory(),
                runReadFactory = { SequenceWithRuns(events = runReadList()) },
            ).install(this)
        }
        val r = hostClient().get("/runs/00000000-0000-0000-0000-000000000000/stream") {
            headers.append("Last-Event-ID", "0")
        }
        assertEquals(HttpStatusCode.OK, r.status)
        val body = r.bodyAsText()
        // Resume marker event is emitted on reconnect with a non-null Last-Event-ID.
        assertEquals(true, body.contains("SSE_RESUMED"), "expected SSE_RESUMED marker; got: ${body.take(200)}")
    }

    @Test
    fun `stream endpoint accepts after query param as an alternative to Last-Event-ID`() = testApplication {
        application {
            StudioHttpModule(
                fakeWiringFactory(),
                runReadFactory = { SequenceWithRuns(events = runReadList()) },
            ).install(this)
        }
        val r = hostClient().get("/runs/00000000-0000-0000-0000-000000000000/stream?after=0")
        assertEquals(HttpStatusCode.OK, r.status)
        val body = r.bodyAsText()
        assertEquals(true, body.contains("SSE_RESUMED"), "expected SSE_RESUMED marker; got: ${body.take(200)}")
    }

    private fun fakeWiringFactory(): () -> dev.pipelinek.studio.application.usecases.Wiring {
        val noopMetadata = object : StudioMetadataPort {
            override fun findExperiment(id: ExperimentId) = null
            override fun findSnapshot(id: SnapshotId) = null
            override fun findScenario(id: ScenarioId) = null
            override fun findRun(id: RunId) = null
            override fun listRuns(state: dev.pipelinek.studio.domain.RunState?, limit: Int, offset: Int): List<RunBindingSnapshot> = emptyList()
                    override fun saveSnapshot(snap: dev.pipelinek.studio.domain.SourceSnapshot) { /* noop */ }
            override fun loadSnapshotSource(id: dev.pipelinek.studio.domain.SnapshotId): ByteArray? = null
            override fun saveExperiment(experiment: dev.pipelinek.studio.domain.Experiment) { /* noop */ }
            override fun persistRunBinding(binding: dev.pipelinek.studio.domain.RunBinding) { /* noop */ }
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
            override fun record(scope: String, key: String, operationId: OperationId, requestDigestSha256: String, responseRef: String?) =
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
        val r = hostClient().get("/health")
        assertEquals(HttpStatusCode.OK, r.status)
    }

    @Test
    fun `api version reports runtime descriptor`() = testApplication {
        application { StudioHttpModule(fakeWiringFactory()).install(this) }
        val r = hostClient().get("/api/version")
        assertEquals(HttpStatusCode.OK, r.status)
        val body = r.bodyAsText()
        assertEquals(true, body.contains("test-0.0.1"), "expected runtime version in $body")
        assertEquals(true, body.contains("test-digest"))
    }

    @Test
    fun `POST experiments creates with generated id`() = testApplication {
        application { StudioHttpModule(fakeWiringFactory()).install(this) }
        val r = hostClient().post("/experiments") {
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
        val r = hostClient().post("/runs") {
            contentType(ContentType.Application.Json)
            setBody(
                """{"experiment_id":"00000000-0000-0000-0000-000000000000","snapshot_id":"00000000-0000-0000-0000-000000000000","scenario_id":"00000000-0000-0000-0000-000000000000"}""",
            )
        }
        assertEquals(HttpStatusCode.BadRequest, r.status)
    }

    @Test
    fun `GET runs id returns 404 when no run_bindings row exists (B1_5 honest 404)`() = testApplication {
        application { StudioHttpModule(fakeWiringFactory()).install(this) }
        val r = hostClient().get("/runs/00000000-0000-0000-0000-000000000000")
        // B1.5 (2026-10-10): the pre-B1.5 handler returned 200 with
        // state=STARTING or 202 with state=UNKNOWN for any uuid —
        // that was a false PASS that masked missing rows. The
        // new handler returns 404 RUN_NOT_FOUND when the
        // run_bindings row is missing, regardless of whether the
        // uuid is well-formed.
        assertEquals(HttpStatusCode.NotFound, r.status)
        val body = r.bodyAsText()
        assertEquals(true, body.contains("RUN_NOT_FOUND"),
            "expected RUN_NOT_FOUND, got $body")
    }

    @Test
    fun `GET runs output returns 202 with explicit refusal (S0 honest)`() = testApplication {
        application { StudioHttpModule(fakeWiringFactory()).install(this) }
        val r = hostClient().get("/runs/00000000-0000-0000-0000-000000000000/output")
        assertEquals(HttpStatusCode.Accepted, r.status)
        val body = r.bodyAsText()
        assertEquals(true, body.contains("OUTPUT_REFUSED") || body.contains("OUTPUT_UNKNOWN_STREAM"),
            "expected honest refusal, got $body")
    }
}
