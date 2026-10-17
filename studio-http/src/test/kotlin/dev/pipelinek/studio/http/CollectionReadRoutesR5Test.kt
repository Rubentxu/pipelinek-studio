package dev.pipelinek.studio.http

import dev.pipelinek.studio.application.ports.CancellationResult
import dev.pipelinek.studio.application.ports.ExperimentSnapshot
import dev.pipelinek.studio.application.ports.IdempotencyLookupResult
import dev.pipelinek.studio.application.ports.IdempotencyPort
import dev.pipelinek.studio.application.ports.IdempotencyRecordResult
import dev.pipelinek.studio.application.ports.OutputReadPort
import dev.pipelinek.studio.application.ports.OutputReadResult
import dev.pipelinek.studio.application.ports.PipelineKRuntimePort
import dev.pipelinek.studio.application.ports.PipelineRunnerPort
import dev.pipelinek.studio.application.ports.RunBindingSnapshot
import dev.pipelinek.studio.application.ports.RunHandleStatus
import dev.pipelinek.studio.application.ports.RunReadPort
import dev.pipelinek.studio.application.ports.RunReadResult
import dev.pipelinek.studio.application.ports.RunRequest
import dev.pipelinek.studio.application.ports.RuntimeDescriptorResult
import dev.pipelinek.studio.application.ports.SnapshotSnapshot
import dev.pipelinek.studio.application.ports.StudioMetadataPort
import dev.pipelinek.studio.application.ports.SubmissionResult
import dev.pipelinek.studio.domain.ExperimentId
import dev.pipelinek.studio.domain.OperationId
import dev.pipelinek.studio.domain.RunId
import dev.pipelinek.studio.domain.RunState
import dev.pipelinek.studio.domain.ScenarioId
import dev.pipelinek.studio.domain.SnapshotId
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * R5 EVO-C.T3 — authoritative collection reads for the VS Code tree.
 *
 * The SSE stream is a live tail, not a journal: the extension cannot
 * rebuild the tree from it after a server restart. These two routes are
 * the minimum additive read surface that lets the client bootstrap and
 * re-authorise a full tree on every (re)connect:
 *
 *   GET /experiments
 *   GET /experiments/{experimentId}/snapshots
 *
 * Contract asserted here:
 *   - 200 with `items[]` in the order the metadata port returned them
 *     (the port owns ordering; the route must not re-sort);
 *   - limit/offset are forwarded and bounded by the documented ceiling;
 *   - a non-integer `limit`/`offset` or a negative offset is a 400;
 *   - a malformed experimentId is a 400 (shared RouteParams rule);
 *   - an unknown experiment is an empty collection (the resource exists);
 *   - a metadata port that does not implement the read is a truthful 503,
 *     never an invented empty/success payload.
 */
class CollectionReadRoutesR5Test {

    private val experimentA = ExperimentSnapshot(
        ExperimentId("11111111-1111-4111-8111-111111111111"),
        "alpha",
        Instant.parse("2026-03-02T00:00:00Z"),
    )
    private val experimentB = ExperimentSnapshot(
        ExperimentId("22222222-2222-4222-8222-222222222222"),
        "beta",
        Instant.parse("2026-03-01T00:00:00Z"),
    )
    private val snapshotA = SnapshotSnapshot(
        SnapshotId("33333333-3333-4333-8333-333333333333"),
        experimentA.id,
        "a".repeat(64),
        Instant.parse("2026-03-02T01:00:00Z"),
    )

    private class RecordingMetadata(
        val experiments: List<ExperimentSnapshot> = emptyList(),
        val snapshots: List<SnapshotSnapshot> = emptyList(),
    ) : StudioMetadataPort {
        var lastExperimentPage: Pair<Int, Int>? = null
        var lastSnapshotPage: Pair<Int, Int>? = null

        override fun findExperiment(id: ExperimentId) = experiments.firstOrNull { it.id == id }
        override fun findSnapshot(id: SnapshotId) = snapshots.firstOrNull { it.id == id }
        override fun findScenario(id: ScenarioId) = null
        override fun findRun(id: RunId) = null
        override fun listRuns(state: RunState?, limit: Int, offset: Int): List<RunBindingSnapshot> = emptyList()
        override fun listScenarios(limit: Int, offset: Int) = emptyList<dev.pipelinek.studio.application.ports.ScenarioSnapshot>()
        override fun saveSnapshot(snap: dev.pipelinek.studio.domain.SourceSnapshot) { /* noop */ }
        override fun loadSnapshotSource(id: SnapshotId): ByteArray? = null
        override fun saveExperiment(experiment: dev.pipelinek.studio.domain.Experiment) { /* noop */ }
        override fun persistRunBinding(binding: dev.pipelinek.studio.domain.RunBinding) { /* noop */ }
        override fun transitionRunState(studioRunId: RunId, newState: RunState) { /* noop */ }
        override fun saveScenario(scenario: dev.pipelinek.studio.domain.SourceScenario) { /* noop */ }

        override fun listExperiments(limit: Int, offset: Int): List<ExperimentSnapshot> {
            lastExperimentPage = limit to offset
            return experiments.drop(offset).take(limit)
        }

        override fun listSnapshots(experimentId: ExperimentId, limit: Int, offset: Int): List<SnapshotSnapshot> {
            lastSnapshotPage = limit to offset
            return snapshots.filter { it.experimentId == experimentId }.drop(offset).take(limit)
        }
    }

    private fun wiring(metadata: StudioMetadataPort): () -> dev.pipelinek.studio.application.usecases.Wiring {
        val runner = object : PipelineRunnerPort {
            override fun submit(req: RunRequest): SubmissionResult =
                SubmissionResult.Admitted("u", java.nio.file.Path.of("/tmp/c"))
            override fun cancel(runId: RunId): CancellationResult = CancellationResult.Unknown("stub")
            override fun status(runId: RunId): RunHandleStatus = RunHandleStatus.Unknown("stub")
        }
        val read = object : RunReadPort {
            override fun events(runId: RunId, after: dev.pipelinek.studio.application.ports.EventCursor?, limit: Int) =
                RunReadResult.Unknown("stub")
            override fun state(runId: RunId) = RunReadResult.Unknown("stub")
        }
        val output = object : OutputReadPort {
            override fun read(runId: RunId, cursor: dev.pipelinek.studio.application.ports.OutputCursor?, bytes: Int) =
                OutputReadResult.UnknownStream("stub")
        }
        val idempotency = object : IdempotencyPort {
            override fun lookup(scope: String, key: String, requestDigestSha256: String) = IdempotencyLookupResult.NotFound
            override fun record(
                scope: String,
                key: String,
                operationId: OperationId,
                requestDigestSha256: String,
                responseRef: String?,
            ) = IdempotencyRecordResult.Recorded
        }
        val runtime = object : PipelineKRuntimePort {
            override fun describe() = RuntimeDescriptorResult.Unavailable
        }
        return { dev.pipelinek.studio.application.usecases.Wiring(metadata, runner, read, output, idempotency, runtime) }
    }

    @Test
    fun `GET experiments returns the port order with snake_case wire fields`() = testApplication {
        val metadata = RecordingMetadata(experiments = listOf(experimentA, experimentB))
        application { StudioHttpModule(wiring(metadata)).install(this) }
        val r = hostClient().get("/experiments")
        assertEquals(HttpStatusCode.OK, r.status)
        val body = r.bodyAsText()
        assertTrue(body.contains("\"items\""), "expected items[] in response, got: $body")
        assertTrue(body.contains("\"experiment_id\":\"${experimentA.id.value}\""), "got: $body")
        assertTrue(body.contains("\"name\":\"alpha\""), "got: $body")
        assertTrue(body.contains("\"created_at\":\"2026-03-02T00:00:00Z\""), "got: $body")
        assertTrue(
            body.indexOf("alpha") < body.indexOf("beta"),
            "route must preserve the port's ordering (newest first), got: $body",
        )
    }

    @Test
    fun `GET experiments forwards limit and offset and rejects an out-of-contract ceiling`() = testApplication {
        val metadata = RecordingMetadata(experiments = listOf(experimentA, experimentB))
        application { StudioHttpModule(wiring(metadata)).install(this) }

        val page = hostClient().get("/experiments?limit=1&offset=1")
        assertEquals(HttpStatusCode.OK, page.status)
        assertEquals(1 to 1, metadata.lastExperimentPage)
        assertTrue(page.bodyAsText().contains("beta"), page.bodyAsText())
        assertTrue(!page.bodyAsText().contains("alpha"), page.bodyAsText())

        // 200 is the documented ceiling; asking for more is a client bug, not
        // a page the studio silently shrinks.
        val oversized = hostClient().get("/experiments?limit=201")
        assertEquals(HttpStatusCode.BadRequest, oversized.status)
        assertEquals(1 to 1, metadata.lastExperimentPage)
    }

    @Test
    fun `GET experiments rejects malformed pagination parameters`() = testApplication {
        val metadata = RecordingMetadata(experiments = listOf(experimentA))
        application { StudioHttpModule(wiring(metadata)).install(this) }
        for (query in listOf("limit=abc", "limit=0", "limit=201", "offset=-1", "offset=abc")) {
            val r = hostClient().get("/experiments?$query")
            assertEquals(HttpStatusCode.BadRequest, r.status, "/experiments?$query")
            assertTrue(r.bodyAsText().contains("BAD_REQUEST"), r.bodyAsText())
        }
        assertEquals(null, metadata.lastExperimentPage, "a rejected request must not reach the metadata port")
    }

    @Test
    fun `GET experiment snapshots returns only that experiment`() = testApplication {
        val metadata = RecordingMetadata(
            experiments = listOf(experimentA, experimentB),
            snapshots = listOf(
                snapshotA,
                SnapshotSnapshot(
                    SnapshotId("44444444-4444-4444-8444-444444444444"),
                    experimentB.id,
                    "b".repeat(64),
                    Instant.parse("2026-03-01T02:00:00Z"),
                ),
            ),
        )
        application { StudioHttpModule(wiring(metadata)).install(this) }
        val r = hostClient().get("/experiments/${experimentA.id.value}/snapshots")
        assertEquals(HttpStatusCode.OK, r.status)
        val body = r.bodyAsText()
        assertTrue(body.contains("\"snapshot_id\":\"${snapshotA.id.value}\""), body)
        assertTrue(body.contains("\"experiment_id\":\"${experimentA.id.value}\""), body)
        assertTrue(body.contains("\"sha256\":\"${"a".repeat(64)}\""), body)
        assertTrue(!body.contains("44444444-4444-4444-8444-444444444444"), body)
    }

    @Test
    fun `GET experiment snapshots rejects a malformed experimentId`() = testApplication {
        val metadata = RecordingMetadata(experiments = listOf(experimentA))
        application { StudioHttpModule(wiring(metadata)).install(this) }
        val r = hostClient().get("/experiments/not-a-uuid/snapshots")
        assertEquals(HttpStatusCode.BadRequest, r.status)
        assertTrue(r.bodyAsText().contains("BAD_REQUEST"), r.bodyAsText())
        assertEquals(null, metadata.lastSnapshotPage)
    }

    @Test
    fun `GET experiment snapshots on an unknown experiment is an empty collection`() = testApplication {
        val metadata = RecordingMetadata(experiments = listOf(experimentA))
        application { StudioHttpModule(wiring(metadata)).install(this) }
        val r = hostClient().get("/experiments/99999999-9999-4999-8999-999999999999/snapshots")
        assertEquals(HttpStatusCode.OK, r.status)
        assertTrue(r.bodyAsText().contains("\"items\":[]"), r.bodyAsText())
    }

    @Test
    fun `GET experiments is a truthful 503 when the metadata port does not support collection reads`() = testApplication {
        // The default port implementation is `NOT_SUPPORTED`: test fakes and
        // older adapters keep compiling, and the wire says so instead of
        // pretending the studio has zero experiments.
        val unsupported = object : StudioMetadataPort {
            override fun findExperiment(id: ExperimentId) = null
            override fun findSnapshot(id: SnapshotId) = null
            override fun findScenario(id: ScenarioId) = null
            override fun findRun(id: RunId) = null
            override fun listRuns(state: RunState?, limit: Int, offset: Int): List<RunBindingSnapshot> = emptyList()
            override fun listScenarios(limit: Int, offset: Int) = emptyList<dev.pipelinek.studio.application.ports.ScenarioSnapshot>()
            override fun saveSnapshot(snap: dev.pipelinek.studio.domain.SourceSnapshot) { /* noop */ }
            override fun loadSnapshotSource(id: SnapshotId): ByteArray? = null
            override fun saveExperiment(experiment: dev.pipelinek.studio.domain.Experiment) { /* noop */ }
            override fun persistRunBinding(binding: dev.pipelinek.studio.domain.RunBinding) { /* noop */ }
            override fun transitionRunState(studioRunId: RunId, newState: RunState) { /* noop */ }
            override fun saveScenario(scenario: dev.pipelinek.studio.domain.SourceScenario) { /* noop */ }
        }
        application { StudioHttpModule(wiring(unsupported)).install(this) }
        val r = hostClient().get("/experiments")
        assertEquals(HttpStatusCode.ServiceUnavailable, r.status)
        assertTrue(r.bodyAsText().contains("COLLECTION_UNAVAILABLE"), r.bodyAsText())
    }
}
