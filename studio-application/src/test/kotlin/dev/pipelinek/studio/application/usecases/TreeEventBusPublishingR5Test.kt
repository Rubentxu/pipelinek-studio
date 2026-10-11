package dev.pipelinek.studio.application.usecases

import dev.pipelinek.studio.application.ports.CancellationResult
import dev.pipelinek.studio.application.ports.IdempotencyLookupResult
import dev.pipelinek.studio.application.ports.IdempotencyPort
import dev.pipelinek.studio.application.ports.IdempotencyRecordResult
import dev.pipelinek.studio.application.ports.PipelineKRuntimePort
import dev.pipelinek.studio.application.ports.PipelineRunnerPort
import dev.pipelinek.studio.application.ports.RunBindingSnapshot
import dev.pipelinek.studio.application.ports.RunHandleStatus
import dev.pipelinek.studio.application.ports.RunRequest
import dev.pipelinek.studio.application.ports.RuntimeDescriptor
import dev.pipelinek.studio.application.ports.RuntimeDescriptorResult
import dev.pipelinek.studio.application.ports.ScenarioSnapshot
import dev.pipelinek.studio.application.ports.SnapshotSnapshot
import dev.pipelinek.studio.application.ports.StudioMetadataPort
import dev.pipelinek.studio.application.ports.SubmissionResult
import dev.pipelinek.studio.application.ports.TreeEvent
import dev.pipelinek.studio.application.ports.TreeEventBusPort
import dev.pipelinek.studio.domain.Experiment
import dev.pipelinek.studio.domain.ExperimentId
import dev.pipelinek.studio.domain.IdempotencyKey
import dev.pipelinek.studio.domain.OperationId
import dev.pipelinek.studio.domain.RunBinding
import dev.pipelinek.studio.domain.RunId
import dev.pipelinek.studio.domain.RunState
import dev.pipelinek.studio.domain.ScenarioId
import dev.pipelinek.studio.domain.Sha256
import dev.pipelinek.studio.domain.SnapshotId
import dev.pipelinek.studio.domain.SourceScenario
import dev.pipelinek.studio.domain.SourceSnapshot
import kotlinx.coroutines.channels.Channel
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * R5 EVO-C.5 (2026-10-11): tests for the use cases wiring
 * their success paths to the tree-event bus. The contract:
 *  - CreateExperimentUseCase publishes `ExperimentCreated` after persist.
 *  - SealSnapshotUseCase publishes `SnapshotCreated` after persist.
 *  - StartRunUseCase publishes `RunStarted` ONCE on first call, never
 *    on idempotent replays.
 *  - The use cases remain runnable when the bus is null (legacy boot).
 */
class TreeEventBusPublishingR5Test {

    @Test
    fun `R5 EVO-C5 CreateExperiment publishes ExperimentCreated after persist`() {
        val bus = RecordingBus()
        val metadata = RecordingMetadata(snapshotVisible = false, experimentId = ExperimentId.new())
        val uc = CreateExperimentUseCase(
            metadata = metadata,
            clock = { Instant.parse("2026-10-11T20:00:00Z") },
            treeEventBus = bus,
        )
        val created = uc.execute("nightly-bench")
        assertEquals(1, bus.events.size)
        val ev = bus.events[0] as TreeEvent.ExperimentCreated
        assertEquals(created.id, ev.experimentId)
        assertEquals("nightly-bench", ev.name)
        assertEquals(Instant.parse("2026-10-11T20:00:00Z"), ev.at)
    }

    @Test
    fun `R5 EVO-C5 SealSnapshot publishes SnapshotCreated with byte count`() {
        val bus = RecordingBus()
        val metadata = RecordingMetadata(snapshotVisible = false, experimentId = ExperimentId.new())
        val uc = SealSnapshotUseCase(
            metadata = metadata,
            clock = { Instant.parse("2026-10-11T20:01:00Z") },
            treeEventBus = bus,
        )
        val expId = ExperimentId.new()
        val src = "println(\"hi\")".toByteArray(Charsets.UTF_8)
        val snap = uc.execute(expId, src, "agent")
        assertEquals(1, bus.events.size)
        val ev = bus.events[0] as TreeEvent.SnapshotCreated
        assertEquals(expId, ev.experimentId)
        assertEquals(snap.id, ev.snapshotId)
        assertEquals(src.size.toLong(), ev.bytes)
    }

    @Test
    fun `R5 EVO-C5 StartRun publishes RunStarted once, never on replay`() {
        val bus = RecordingBus()
        val expId = ExperimentId.new()
        val snapId = SnapshotId.new()
        val metadata = RecordingMetadata(snapshotVisible = true, experimentId = expId, snapshotId = snapId)
        val runtime = object : PipelineKRuntimePort {
            override fun describe(): RuntimeDescriptorResult = RuntimeDescriptorResult.Available(
                RuntimeDescriptor(
                    version = "0.48.0-rc1",
                    artifactDigest = "sha-pin",
                    pluginSdkVersion = "0.48.0-rc1",
                    capabilities = setOf("compile", "eval", "durable"),
                ),
            )
        }
        val runner = object : PipelineRunnerPort {
            override fun submit(req: RunRequest): SubmissionResult =
                SubmissionResult.Admitted(upstreamRunId = "upstream-1", controlRoot = java.nio.file.Path.of("/tmp"))
            override fun cancel(runId: RunId): CancellationResult = CancellationResult.Unknown("not-stubbed")
            override fun status(runId: RunId): RunHandleStatus = RunHandleStatus.Unknown("not-stubbed")
        }
        val idemp = RecordingIdempotency()
        val uc = StartRunUseCase(
            runner = runner,
            idempotency = idemp,
            runtime = runtime,
            metadata = metadata,
            admissionLock = null,
            treeEventBus = bus,
            clock = { Instant.parse("2026-10-11T20:02:30Z") },
        )
        val key = IdempotencyKey.new()
        val digest = Sha256.digest("req-1".toByteArray())
        val r1 = uc.execute(snapId, ScenarioId("sc-1"), key, digest)
        assertNotNull(r1)
        assertEquals(1, bus.events.size)
        val first = bus.events[0] as TreeEvent.RunStarted
        assertEquals(expId, first.experimentId)
        assertEquals(Instant.parse("2026-10-11T20:02:30Z"), first.at)
        // Replay with same key + digest: idempotency port returns
        // Match, use case returns Reserved BEFORE reaching the
        // publish path. Subscribers see exactly one RunStarted
        // per logical run.
        val r2 = uc.execute(snapId, ScenarioId("sc-1"), key, digest)
        assertNotNull(r2)
        assertEquals(1, bus.events.size, "idempotent replays must NOT republish RunStarted")
    }

    @Test
    fun `R5 EVO-C5 use cases work without a bus (legacy boot is silent)`() {
        val metadata = RecordingMetadata(snapshotVisible = false, experimentId = ExperimentId.new())
        val create = CreateExperimentUseCase(metadata) // no bus
        val seal = SealSnapshotUseCase(metadata) // no bus
        // No throw, no event emission. The wiring must remain
        // runnable when SSE is not wired (R5 EVO-C.0 contract).
        create.execute("e1")
        seal.execute(ExperimentId.new(), "println()".toByteArray(), "test")
    }
}

// --- Test fakes (mirror StartRunUseCaseTest's pattern) -------------------

private class RecordingBus : TreeEventBusPort {
    val events: MutableList<TreeEvent> = mutableListOf()
    override fun publish(event: TreeEvent) { events += event }
    override fun subscribe(afterId: Long?): Channel<TreeEvent> = Channel(8)
    override fun lastId(): Long? = if (events.isEmpty()) null else events.size.toLong()
}

private class RecordingMetadata(
    val snapshotId: SnapshotId = SnapshotId.new(),
    val experimentId: ExperimentId,
    val snapshotVisible: Boolean = true,
) : StudioMetadataPort {
    val persistedExperiments = mutableListOf<Experiment>()
    val persistedSnapshots = mutableListOf<SourceSnapshot>()
    val persistedBindings = mutableListOf<RunBinding>()
    override fun findExperiment(id: ExperimentId) = null
    override fun findSnapshot(id: SnapshotId): SnapshotSnapshot? =
        if (snapshotVisible && id == snapshotId)
            SnapshotSnapshot(id, experimentId, "sha", Instant.now())
        else null
    override fun findScenario(id: ScenarioId): ScenarioSnapshot? = null
    override fun findRun(id: RunId): RunBindingSnapshot? = null
    override fun listRuns(state: RunState?, limit: Int, offset: Int): List<RunBindingSnapshot> = emptyList()
    override fun listScenarios(limit: Int, offset: Int): List<ScenarioSnapshot> = emptyList()
    override fun saveExperiment(experiment: Experiment) { persistedExperiments += experiment }
    override fun saveSnapshot(snap: SourceSnapshot) { persistedSnapshots += snap }
    override fun saveScenario(scenario: SourceScenario) { /* drop */ }
    override fun loadSnapshotSource(id: SnapshotId): ByteArray? = null
    override fun persistRunBinding(binding: RunBinding) { persistedBindings += binding }
}

private class RecordingIdempotency : IdempotencyPort {
    private data class Row(val opId: OperationId, val responseRef: String?, val storedDigest: String)
    private val rows = mutableMapOf<String, Row>()
    private fun k(scope: String, key: String) = "$scope|$key"
    override fun lookup(scope: String, key: String, requestDigestSha256: String): IdempotencyLookupResult {
        val r = rows[k(scope, key)] ?: return IdempotencyLookupResult.NotFound
        return if (r.storedDigest == requestDigestSha256) {
            IdempotencyLookupResult.Match(r.opId, r.responseRef)
        } else {
            IdempotencyLookupResult.Conflict("request_digest mismatch")
        }
    }
    override fun record(
        scope: String,
        key: String,
        operationId: OperationId,
        requestDigestSha256: String,
        responseRef: String?,
    ): IdempotencyRecordResult {
        val kk = k(scope, key)
        if (rows.containsKey(kk)) return IdempotencyRecordResult.AlreadyExists(operationId)
        rows[kk] = Row(operationId, responseRef, requestDigestSha256)
        return IdempotencyRecordResult.Recorded
    }
}
