package dev.pipelinek.studio.application.usecases

import dev.pipelinek.studio.application.ports.*
import dev.pipelinek.studio.domain.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class StartRunUseCaseTest {
    private val fid = ExperimentId.new()
    private val snap = SnapshotId.new()
    private val sc = ScenarioId.new()

    private fun runtimeAvailable(): PipelineKRuntimePort = object : PipelineKRuntimePort {
        override fun describe(): RuntimeDescriptorResult =
            RuntimeDescriptorResult.Available(
                RuntimeDescriptor(
                    version = "0.48.0-rc1",
                    artifactDigest = "sha-pin",
                    pluginSdkVersion = "0.48.0-rc1",
                    capabilities = setOf("compile", "eval", "durable"),
                ),
            )
    }

    private fun runtimeUnavailable(): PipelineKRuntimePort = object : PipelineKRuntimePort {
        override fun describe(): RuntimeDescriptorResult = RuntimeDescriptorResult.Unavailable
    }

    private fun runtimeFailing(reason: String): PipelineKRuntimePort = object : PipelineKRuntimePort {
        override fun describe(): RuntimeDescriptorResult = RuntimeDescriptorResult.Failure(reason)
    }

    /**
     * B1.1 + B1.3 (2026-10-10): the metadata stub now persists the
     * experiment + the run binding so the test exercises the real
     * shape of the port contract. The snapshot lookup is controlled
     * per-test via the [snapshotVisible] flag.
     */
    private class RecordingMetadata(
        val snapshot: SnapshotId,
        val experimentId: ExperimentId,
    ) : StudioMetadataPort {
        var snapshotVisible: Boolean = true
        val persistedExperiments = mutableListOf<Experiment>()
        val persistedBindings = mutableListOf<RunBinding>()
        override fun findExperiment(id: ExperimentId) = null
        override fun findSnapshot(id: SnapshotId): SnapshotSnapshot? =
            if (snapshotVisible && id == snapshot)
                SnapshotSnapshot(id, experimentId, "sha", java.time.Instant.now())
            else null
        override fun findScenario(id: ScenarioId) = null
        override fun findRun(id: RunId) = null
        override fun listRuns(state: RunState?, limit: Int, offset: Int): List<RunBindingSnapshot> = emptyList()
        override fun listScenarios(limit: Int, offset: Int): List<ScenarioSnapshot> = emptyList()
        override fun saveScenario(scenario: dev.pipelinek.studio.domain.SourceScenario) { /* drop */ }
        override fun saveExperiment(experiment: Experiment) { persistedExperiments += experiment }
        override fun saveSnapshot(snap: SourceSnapshot) { /* noop */ }
        override fun loadSnapshotSource(id: SnapshotId): ByteArray? = null
        override fun persistRunBinding(binding: RunBinding) { persistedBindings += binding }
        // SFW-H4.5: state transition noop (the H4.5 recovery use case is not exercised here).
        override fun transitionRunState(studioRunId: RunId, newState: RunState) { /* drop */ }
    }

    private class StubRunner(
        var responder: (RunRequest) -> SubmissionResult = { SubmissionResult.Refused("not-stubbed") },
    ) : PipelineRunnerPort {
        var submits: Int = 0
        val submittedRunIds = mutableListOf<RunId>()
        override fun submit(req: RunRequest): SubmissionResult {
            submits++
            submittedRunIds += req.runId
            return responder(req)
        }
        override fun cancel(runId: RunId): CancellationResult = CancellationResult.Unknown("not-stubbed")
        override fun status(runId: RunId): RunHandleStatus = RunHandleStatus.Unknown("not-stubbed")
    }

    /**
     * B1.3 + B1.4: the idempotency stub mirrors the real
     * IdempotencyRepository semantics — key is (scope, key) and the
     * stored digest is checked against the lookup's digest to
     * distinguish Match from Conflict.
     */
    private class RecordingIdempotency : IdempotencyPort {
        private data class Row(val opId: OperationId, val responseRef: String?, val storedDigest: String)
        private val rows = mutableMapOf<String, Row>()
        private fun k(scope: String, key: String) = "$scope|$key"
        override fun lookup(scope: String, key: String, requestDigestSha256: String): IdempotencyLookupResult {
            val recorded = rows[k(scope, key)] ?: return IdempotencyLookupResult.NotFound
            return if (recorded.storedDigest == requestDigestSha256) {
                IdempotencyLookupResult.Match(recorded.opId, recorded.responseRef)
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
            val key2 = k(scope, key)
            return if (rows.containsKey(key2)) {
                IdempotencyRecordResult.AlreadyExists(rows[key2]!!.opId)
            } else {
                rows[key2] = Row(operationId, responseRef, requestDigestSha256)
                IdempotencyRecordResult.Recorded
            }
        }
    }

    @Test
    fun `runtime UNAVAILABLE throws and does NOT submit`() {
        val runner = StubRunner()
        val uc = StartRunUseCase(runner, RecordingIdempotency(), runtimeUnavailable(), RecordingMetadata(snap, fid))
        assertThrows<IllegalStateException> {
            uc.execute(snap, sc, IdempotencyKey.new(), Sha256.digest("x".toByteArray()))
        }
        assertEquals(0, runner.submits)
    }

    @Test
    fun `runtime FAILURE throws`() {
        val runner = StubRunner()
        val uc = StartRunUseCase(runner, RecordingIdempotency(), runtimeFailing("boom"), RecordingMetadata(snap, fid))
        assertThrows<IllegalStateException> {
            uc.execute(snap, sc, IdempotencyKey.new(), Sha256.digest("x".toByteArray()))
        }
    }

    @Test
    fun `runner refused yields ReservationOutcome Refused with deterministic studio_run_id`() {
        val runner = StubRunner(responder = { SubmissionResult.Refused("plugin sdk mismatch") })
        val meta = RecordingMetadata(snap, fid)
        val uc = StartRunUseCase(runner, RecordingIdempotency(), runtimeAvailable(), meta)
        val key = IdempotencyKey.new()
        val digest = Sha256.digest("x".toByteArray())
        val out = uc.execute(snap, sc, key, digest)
        assertTrue(out is ReservationOutcome.Refused, "expected Refused, got $out")
        val expectedId = DeterministicId.studioRunId(key.value, digest)
        assertEquals(expectedId, out.studioRunId)
        assertEquals(1, runner.submits)
        assertEquals(expectedId, runner.submittedRunIds[0])
        // B1.3: the row is reserved in run_bindings even when the
        // runner refuses. The recovery path on next Studio start
        // sees this and surfaces it as a refusal rather than a
        // fabrication of FAILED.
        assertEquals(1, meta.persistedBindings.size)
        assertEquals(expectedId, meta.persistedBindings[0].runId)
    }

    @Test
    fun `runner admitted yields ReservationOutcome Admitted with deterministic studio_run_id`() {
        val runner = StubRunner(responder = {
            SubmissionResult.Admitted("upstream-1", java.nio.file.Path.of("/tmp/ctrl"))
        })
        val meta = RecordingMetadata(snap, fid)
        val uc = StartRunUseCase(runner, RecordingIdempotency(), runtimeAvailable(), meta)
        val key = IdempotencyKey.new()
        val digest = Sha256.digest("x".toByteArray())
        val out = uc.execute(snap, sc, key, digest)
        assertTrue(out is ReservationOutcome.Admitted, "expected Admitted, got $out")
        val expectedId = DeterministicId.studioRunId(key.value, digest)
        assertEquals(expectedId, out.studioRunId)
        assertEquals("upstream-1", out.upstreamRunId)
        assertEquals(java.nio.file.Path.of("/tmp/ctrl"), out.controlRoot)
    }

    /**
     * B1.3: the binding's experimentId must come from the snapshot,
     * not be a NIL UUID. Audit C-2 fix preserved.
     */
    @Test
    fun `reserved binding carries real experimentId from snapshot not NIL UUID`() {
        val runner = StubRunner(responder = { SubmissionResult.Refused("nope") })
        val meta = RecordingMetadata(snap, fid)
        val uc = StartRunUseCase(runner, RecordingIdempotency(), runtimeAvailable(), meta)
        val out = uc.execute(snap, sc, IdempotencyKey.new(), Sha256.digest("x".toByteArray()))
        assertEquals(fid, meta.persistedBindings[0].experimentId)
        assertTrue(meta.persistedBindings[0].experimentId.value != "00000000-0000-0000-0000-000000000000",
            "experimentId must not be the NIL UUID placeholder")
    }

    /**
     * B1.4: the studio_run_id is deterministic for a given
     * (key, digest). Two calls with the same inputs return the
     * same id, and the second call is a no-op against the runner
     * (the idempotency lookup finds the recorded responseRef).
     */
    @Test
    fun `studio_run_id is deterministic for same key and digest (B1_4)`() {
        val runner = StubRunner(responder = { SubmissionResult.Refused("nope") })
        val meta = RecordingMetadata(snap, fid)
        val uc = StartRunUseCase(runner, RecordingIdempotency(), runtimeAvailable(), meta)
        val key = IdempotencyKey.new()
        val digest = Sha256.digest("replay".toByteArray())
        val a = uc.execute(snap, sc, key, digest)
        val b = uc.execute(snap, sc, key, digest)
        // First call is new → record + submit(Refused) → Refused.
        // Second call replays → lookup Match → Reserved without
        // touching the runner. The studio_run_id must be the same
        // because it is derived deterministically from (key, digest).
        assertTrue(a is ReservationOutcome.Refused, "first call expected Refused, got $a")
        assertTrue(b is ReservationOutcome.Reserved, "replay expected Reserved, got $b")
        val aId = (a as ReservationOutcome.Refused).studioRunId
        val bId = (b as ReservationOutcome.Reserved).studioRunId
        assertEquals(aId, bId, "B1.4: studio_run_id must be deterministic for the same (key, digest)")
        // The replay must NOT have called the runner again.
        assertEquals(1, runner.submits, "replay must be a no-op against the runner (AAT-DUR-002)")
    }

    /**
     * B1.4: same key + different digest must yield a clear conflict,
     * never a silent acceptance.
     */
    @Test
    fun `same key with different digest yields IDEMPOTENCY_CONFLICT (B1_4)`() {
        val runner = StubRunner(responder = { SubmissionResult.Refused("nope") })
        val meta = RecordingMetadata(snap, fid)
        val uc = StartRunUseCase(runner, RecordingIdempotency(), runtimeAvailable(), meta)
        val key = IdempotencyKey.new()
        uc.execute(snap, sc, key, Sha256.digest("first".toByteArray()))
        val ex = assertThrows<IllegalStateException> {
            uc.execute(snap, sc, key, Sha256.digest("second".toByteArray()))
        }
        assertTrue(ex.message!!.contains("IDEMPOTENCY_CONFLICT"), "got: ${ex.message}")
    }

    /**
     * Audit C-2: snapshot missing throws SNAPSHOT_NOT_FOUND.
     */
    @Test
    fun `snapshot missing throws SNAPSHOT_NOT_FOUND (B1_3)`() {
        val runner = StubRunner(responder = { SubmissionResult.Refused("nope") })
        val meta = RecordingMetadata(snap, experimentId = fid).apply { snapshotVisible = false }
        val uc = StartRunUseCase(runner, RecordingIdempotency(), runtimeAvailable(), meta)
        val ex = assertThrows<IllegalStateException> {
            uc.execute(snap, sc, IdempotencyKey.new(), Sha256.digest("x".toByteArray()))
        }
        assertTrue(ex.message!!.contains("SNAPSHOT_NOT_FOUND"))
    }
}

class CreateExperimentUseCaseTest {
    @Test
    fun `blank name rejected`() {
        val uc = CreateExperimentUseCase(NoopMetadata)
        assertThrows<IllegalArgumentException> { uc.execute("") }
    }

    @Test
    fun `unique ids per invocation`() {
        val uc = CreateExperimentUseCase(NoopMetadata)
        val a = uc.execute("first")
        val b = uc.execute("second")
        kotlin.test.assertTrue(a.id != b.id)
    }
}

private object NoopMetadata : StudioMetadataPort {
    override fun findExperiment(id: ExperimentId) = null
    override fun findSnapshot(id: SnapshotId) = null
    override fun findScenario(id: ScenarioId) = null
    override fun findRun(id: RunId) = null
    override fun listRuns(state: RunState?, limit: Int, offset: Int): List<RunBindingSnapshot> = emptyList()
        override fun listScenarios(limit: Int, offset: Int): List<ScenarioSnapshot> = emptyList()
        override fun saveScenario(scenario: dev.pipelinek.studio.domain.SourceScenario) { /* drop */ }
    override fun saveExperiment(experiment: Experiment) { /* noop */ }
    override fun saveSnapshot(snap: dev.pipelinek.studio.domain.SourceSnapshot) { /* noop */ }
    override fun loadSnapshotSource(id: dev.pipelinek.studio.domain.SnapshotId): ByteArray? = null
    override fun persistRunBinding(binding: dev.pipelinek.studio.domain.RunBinding) { /* noop */ }
    // SFW-H4.5: state transition noop.
    override fun transitionRunState(studioRunId: dev.pipelinek.studio.domain.RunId, newState: dev.pipelinek.studio.domain.RunState) { /* noop */ }
}

class SealSnapshotUseCaseTest {
    @Test
    fun `sha256 matches content`() {
        val uc = SealSnapshotUseCase(NoopMetadata)
        val bytes = "println(123)".toByteArray()
        val snap = uc.execute(ExperimentId.new(), bytes, "tester")
        assertEquals(Sha256.digest(bytes), snap.sha256)
        assertTrue(snap.isContentValid())
    }

    @Test
    fun `creator blank rejected`() {
        val uc = SealSnapshotUseCase(NoopMetadata)
        assertThrows<IllegalArgumentException> {
            uc.execute(ExperimentId.new(), "x".toByteArray(), "")
        }
    }
}
