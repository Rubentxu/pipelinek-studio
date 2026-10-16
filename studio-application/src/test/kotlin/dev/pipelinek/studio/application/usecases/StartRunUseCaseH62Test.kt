@file:Suppress("TooManyFunctions")

package dev.pipelinek.studio.application.usecases

import dev.pipelinek.studio.application.ports.DedupRunResult
import dev.pipelinek.studio.application.ports.DedupRunUseCase
import dev.pipelinek.studio.application.ports.DedupedRunSnapshot
import dev.pipelinek.studio.application.ports.IdempotencyLookupResult
import dev.pipelinek.studio.application.ports.IdempotencyPort
import dev.pipelinek.studio.application.ports.IdempotencyRecordResult
import dev.pipelinek.studio.application.ports.PipelineKRuntimePort
import dev.pipelinek.studio.application.ports.PipelineRunnerPort
import dev.pipelinek.studio.application.ports.RunRequest
import dev.pipelinek.studio.application.ports.RuntimeDescriptor
import dev.pipelinek.studio.application.ports.RuntimeDescriptorResult
import dev.pipelinek.studio.application.ports.StudioMetadataPort
import dev.pipelinek.studio.application.ports.SubmissionResult
import dev.pipelinek.studio.domain.ExperimentId
import dev.pipelinek.studio.domain.IdempotencyKey
import dev.pipelinek.studio.domain.OperationId
import dev.pipelinek.studio.domain.ReservationOutcome
import dev.pipelinek.studio.domain.RunId
import dev.pipelinek.studio.domain.RunState
import dev.pipelinek.studio.domain.ScenarioId
import dev.pipelinek.studio.domain.Sha256
import dev.pipelinek.studio.domain.SnapshotId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Falsifiers for the SFW-H6.2
 * StartRunUseCase dedup integration. The
 * H6.2 unit is the use case change that
 * consumes the H6.1 persistence: a new
 * optional [DedupRunUseCase] parameter
 * short-circuits the runner submit when the
 * request is content-equivalent to an
 * existing run.
 */
@DisplayName("StartRunUseCase (SFW-H6.2 dedup integration)")
class StartRunUseCaseH62Test {

    private val fid: ExperimentId = ExperimentId.new()
    private val snap: SnapshotId = SnapshotId.new()
    private val sc: ScenarioId = ScenarioId.new()

    // ------------------------------------------------------------------
    // Falsifier 1: dedup hit BEFORE submit -> Reserved(existingRunId)
    // ------------------------------------------------------------------
    @Test
    fun `dedup hit before submit returns Reserved with existing run id and does not submit`() {
        val existingRunId = RunId("studio-h62-existing")
        val dedup = FakeDedupRunUseCaseForH62(
            result = DedupRunResult.DedupedRun(
                studioRunId = existingRunId,
                state = RunState.RUNNING,
            ),
        )
        val runner = FakeRunnerForH62()
        val meta = RecordingMetaH62(snap, fid)
        val uc = StartRunUseCase(
            runner, StubIdempotencyH62(), runtimeAvailable(), meta, dedupRun = dedup,
        )
        val key = IdempotencyKey.new()
        val digest = Sha256.digest("x".toByteArray())

        val outcome = uc.execute(snap, sc, key, digest)

        val reserved = outcome as? ReservationOutcome.Reserved
        assertNotNull(reserved, "expected Reserved, got $outcome")
        assertEquals(existingRunId, reserved!!.studioRunId)
        assertEquals(0, runner.submits, "dedup hit must NOT submit to the runner")
        assertEquals(1, dedup.findCalls, "dedup check must be called once")
    }

    // ------------------------------------------------------------------
    // Falsifier 2: dedup miss -> proceed with submit (current behavior)
    // ------------------------------------------------------------------
    @Test
    fun `dedup miss proceeds with submit and returns the runner outcome`() {
        val dedup = FakeDedupRunUseCaseForH62(
            result = DedupRunResult.NewRun,
        )
        val runner = FakeRunnerForH62(
            responder = { SubmissionResult.Admitted("upstream-h62", java.nio.file.Path.of("/tmp/ctrl")) },
        )
        val meta = RecordingMetaH62(snap, fid)
        val uc = StartRunUseCase(
            runner, StubIdempotencyH62(), runtimeAvailable(), meta, dedupRun = dedup,
        )
        val key = IdempotencyKey.new()
        val digest = Sha256.digest("x".toByteArray())

        val outcome = uc.execute(snap, sc, key, digest)

        assertTrue(outcome is ReservationOutcome.Admitted, "expected Admitted, got $outcome")
        assertEquals(1, runner.submits, "dedup miss must submit to the runner")
        assertEquals(1, dedup.findCalls, "dedup check must be called once")
    }

    // ------------------------------------------------------------------
    // Falsifier 3: dedup refused -> Refused (conservative path)
    // ------------------------------------------------------------------
    @Test
    fun `dedup refused returns Refused and does not submit`() {
        val dedup = FakeDedupRunUseCaseForH62(
            result = DedupRunResult.Refused("dedup port failed: SQLite database is locked"),
        )
        val runner = FakeRunnerForH62()
        val meta = RecordingMetaH62(snap, fid)
        val uc = StartRunUseCase(
            runner, StubIdempotencyH62(), runtimeAvailable(), meta, dedupRun = dedup,
        )
        val key = IdempotencyKey.new()
        val digest = Sha256.digest("x".toByteArray())

        val outcome = uc.execute(snap, sc, key, digest)

        val refused = outcome as? ReservationOutcome.Refused
        assertNotNull(refused, "expected Refused, got $outcome")
        assertTrue(
            refused!!.reason.contains("dedup port refused"),
            "reason should mention dedup port refused: ${refused.reason}",
        )
        assertEquals(0, runner.submits, "dedup refused must NOT submit to the runner")
    }

    // ------------------------------------------------------------------
    // Falsifier 4: dedup unknown -> proceed with submit (race)
    // ------------------------------------------------------------------
    @Test
    fun `dedup unknown proceeds with submit (future H6+ race, idempotency is the safety net)`() {
        val dedup = FakeDedupRunUseCaseForH62(
            result = DedupRunResult.Unknown("dedup port does not know: race between lookup and concurrent insert"),
        )
        val runner = FakeRunnerForH62(
            responder = { SubmissionResult.Admitted("upstream-h62", java.nio.file.Path.of("/tmp/ctrl")) },
        )
        val meta = RecordingMetaH62(snap, fid)
        val uc = StartRunUseCase(
            runner, StubIdempotencyH62(), runtimeAvailable(), meta, dedupRun = dedup,
        )
        val key = IdempotencyKey.new()
        val digest = Sha256.digest("x".toByteArray())

        val outcome = uc.execute(snap, sc, key, digest)

        assertTrue(outcome is ReservationOutcome.Admitted, "expected Admitted, got $outcome")
        assertEquals(1, runner.submits, "dedup unknown must submit to the runner (the existing idempotency is the safety net)")
    }

    // ------------------------------------------------------------------
    // Falsifier 5: dedup use case null -> noop (backwards-compatible)
    // ------------------------------------------------------------------
    @Test
    fun `dedup use case null is a noop and the use case still submits to the runner`() {
        val runner = FakeRunnerForH62(
            responder = { SubmissionResult.Admitted("upstream-h62", java.nio.file.Path.of("/tmp/ctrl")) },
        )
        val meta = RecordingMetaH62(snap, fid)
        // SFW-H6.2 backwards-compat: the dedupRun parameter defaults
        // to null; the existing use case signature is preserved.
        val uc = StartRunUseCase(
            runner, StubIdempotencyH62(), runtimeAvailable(), meta,
        )
        val key = IdempotencyKey.new()
        val digest = Sha256.digest("x".toByteArray())

        val outcome = uc.execute(snap, sc, key, digest)

        assertTrue(outcome is ReservationOutcome.Admitted, "expected Admitted, got $outcome")
        assertEquals(1, runner.submits, "dedupRun=null must submit to the runner (noop)")
    }
}

// --------------------------------------------------------------------
// Test fixtures
// --------------------------------------------------------------------

private class FakeDedupRunUseCaseForH62(
    var result: DedupRunResult,
) : DedupRunUseCase {
    var findCalls: Int = 0
        private set
    var lastRequest: RunRequest? = null
        private set

    override suspend fun checkDuplicate(request: RunRequest): DedupRunResult {
        findCalls += 1
        lastRequest = request
        return result
    }
}

private class FakeRunnerForH62(
    var responder: (RunRequest) -> SubmissionResult = { SubmissionResult.Refused("not-stubbed") },
) : PipelineRunnerPort {
    var submits: Int = 0
        private set
    val submittedRunIds = mutableListOf<RunId>()

    override fun submit(req: RunRequest): SubmissionResult {
        submits += 1
        submittedRunIds += req.runId
        return responder(req)
    }

    override fun cancel(runId: RunId) =
        throw UnsupportedOperationException("not used in H6.2 falsifiers")

    override fun status(runId: RunId) =
        throw UnsupportedOperationException("not used in H6.2 falsifiers")
}

private class RecordingMetaH62(
    val snapshot: SnapshotId,
    val experimentId: ExperimentId,
) : StudioMetadataPort {
    var snapshotVisible: Boolean = true
    val persistedBindings = mutableListOf<dev.pipelinek.studio.domain.RunBinding>()
    override fun findExperiment(id: ExperimentId) = null
    override fun findSnapshot(id: SnapshotId) =
        if (snapshotVisible && id == snapshot)
            dev.pipelinek.studio.application.ports.SnapshotSnapshot(
                id, experimentId, "sha", java.time.Instant.now(),
            )
        else null
    override fun findScenario(id: ScenarioId) = null
    override fun findRun(id: RunId) = null
    override fun listRuns(state: RunState?, limit: Int, offset: Int) = emptyList<dev.pipelinek.studio.application.ports.RunBindingSnapshot>()
    override fun listScenarios(limit: Int, offset: Int) = emptyList<dev.pipelinek.studio.application.ports.ScenarioSnapshot>()
    override fun saveScenario(scenario: dev.pipelinek.studio.domain.SourceScenario) { /* drop */ }
    override fun saveExperiment(experiment: dev.pipelinek.studio.domain.Experiment) { /* drop */ }
    override fun saveSnapshot(snap: dev.pipelinek.studio.domain.SourceSnapshot) { /* noop */ }
    override fun loadSnapshotSource(id: SnapshotId): ByteArray? = null
    override fun persistRunBinding(binding: dev.pipelinek.studio.domain.RunBinding) {
        persistedBindings += binding
    }
    override fun transitionRunState(studioRunId: RunId, newState: RunState) { /* drop */ }
}

private class StubIdempotencyH62 : IdempotencyPort {
    override fun lookup(scope: String, key: String, requestDigestSha256: String) =
        IdempotencyLookupResult.NotFound
    override fun record(
        scope: String,
        key: String,
        operationId: OperationId,
        requestDigestSha256: String,
        responseRef: String?,
    ): IdempotencyRecordResult = IdempotencyRecordResult.Recorded
}

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
