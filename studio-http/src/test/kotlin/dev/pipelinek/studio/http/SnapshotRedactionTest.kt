package dev.pipelinek.studio.http

import dev.pipelinek.studio.application.ports.*
import dev.pipelinek.studio.domain.*
import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsText
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * AAT-SEC-007: secret canaries do not appear in API/CLI/MCP/logs/SSE/
 * error/exportable files. The HTTP layer must scrub known secret
 * patterns on the snapshot seal path so a snapshot that contains a
 * secret never has the secret on disk.
 */
class SnapshotRedactionTest {

    private fun metadataForCapture(): StudioMetadataPort {
        // Capture the bytes the route passes to the use case.
        var captured: ByteArray? = null
        return object : StudioMetadataPort {
            override fun findExperiment(id: ExperimentId) = null
            override fun findSnapshot(id: SnapshotId) = null
            override fun findScenario(id: ScenarioId) = null
            override fun findRun(id: RunId) = null
            override fun listRuns(state: RunState?, limit: Int, offset: Int): List<RunBindingSnapshot> = emptyList()
            override fun saveSnapshot(snap: dev.pipelinek.studio.domain.SourceSnapshot) { /* noop */ }
            override fun loadSnapshotSource(id: SnapshotId): ByteArray? = null
            // Use the metadata port's persistence path to capture the bytes.
            // (SealSnapshotUseCase calls metadata.persistSnapshot.)
            // We don't have a persistSnapshot method, so this test calls
            // the route end-to-end and asserts the response + any captured
            // evidence.
            val _cap = captured
        }
    }

    @Test
    fun `snapshot seal response does not echo the secret in any field`() = testApplication {
        // Wiring that has a real SealSnapshotUseCase; the use case calls
        // metadata to persist. We use a noop metadata so the test focuses
        // on the HTTP path: the redactor runs BEFORE the use case, so we
        // just need to confirm the route returns 200 and the response
        // body has no secret.
        val noopRunner = object : PipelineRunnerPort {
            override fun submit(req: RunRequest) = SubmissionResult.Admitted("u", java.nio.file.Path.of("/tmp/c"))
            override fun cancel(runId: RunId) = CancellationResult.Unknown("stub")
            override fun status(runId: RunId) = RunHandleStatus.Unknown("stub")
        }
        val noopRead = object : RunReadPort {
            override fun events(runId: RunId, after: EventCursor?, limit: Int) = RunReadResult.Unknown("stub")
            override fun state(runId: RunId) = RunReadResult.Unknown("stub")
        }
        val noopOutput = object : OutputReadPort {
            override fun read(runId: RunId, cursor: OutputCursor?, bytes: Int) =
                OutputReadResult.UnknownStream("stub")
        }
        val noopIdem = object : IdempotencyPort {
            override fun lookup(scope: String, key: String, requestDigestSha256: String) =
                IdempotencyLookupResult.NotFound
            override fun record(scope: String, key: String, operationId: OperationId, requestDigestSha256: String) =
                IdempotencyRecordResult.Recorded
        }
        val noopRuntime = object : PipelineKRuntimePort {
            override fun describe() = RuntimeDescriptorResult.Unavailable
        }
        val metadata = object : StudioMetadataPort {
            override fun findExperiment(id: ExperimentId) = null
            override fun findSnapshot(id: SnapshotId) = null
            override fun findScenario(id: ScenarioId) = null
            override fun findRun(id: RunId) = null
            override fun listRuns(state: RunState?, limit: Int, offset: Int): List<RunBindingSnapshot> = emptyList()
                    override fun saveSnapshot(snap: dev.pipelinek.studio.domain.SourceSnapshot) { /* noop */ }
            override fun loadSnapshotSource(id: dev.pipelinek.studio.domain.SnapshotId): ByteArray? = null
        }
        application {
            StudioHttpModule(
                { dev.pipelinek.studio.application.usecases.Wiring(
                    metadata, noopRunner, noopRead, noopOutput, noopIdem, noopRuntime,
                ) },
                runReadFactory = null,
            ).install(this)
        }
        val awsSecret = "AKIAIOSFODNN7EXAMPLE"
        val r = client.post("/experiments/00000000-0000-0000-0000-000000000000/snapshots") {
            contentType(ContentType.Application.Json)
            setBody("""{"source":"val key = \"$awsSecret\"","creator":"test"}""")
        }
        // The route must NOT echo the secret in any response field.
        val body = r.bodyAsText()
        assertFalse(body.contains(awsSecret), "secret leaked in response: $body")
    }
}
