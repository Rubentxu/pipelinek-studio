package dev.pipelinek.studio.application.usecases

import dev.pipelinek.studio.application.ports.*
import dev.pipelinek.studio.domain.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class StartRunUseCaseTest {
    private val fid = ExperimentId.new()
    private val snap = SnapshotId.new()
    private val sc = ScenarioId.new()
    private val rid = RunId.new()

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
     * Audit C-2 (2026-10-10): StartRunUseCase now needs metadata to
     * resolve the snapshot's experimentId. The stub returns the
     * snapshot's experimentId as the test's `fid`; tests that want
     * the "snapshot not found" path pass a stub returning null.
     */
    private fun metadataWith(snapshot: SnapshotId, experimentId: ExperimentId?): StudioMetadataPort =
        object : StudioMetadataPort {
            override fun findExperiment(id: ExperimentId) = null
            override fun findSnapshot(id: SnapshotId) =
                if (id == snapshot && experimentId != null)
                    SnapshotSnapshot(id, experimentId, "sha", java.time.Instant.now()) else null
            override fun findScenario(id: ScenarioId) = null
            override fun findRun(id: RunId) = null
            override fun listRuns(state: RunState?, limit: Int, offset: Int) = emptyList<RunBindingSnapshot>()
            override fun saveSnapshot(snap: SourceSnapshot) { /* noop */ }
            override fun loadSnapshotSource(id: SnapshotId): ByteArray? = null
        }

    private class StubRunner(
        var responder: (RunRequest) -> SubmissionResult = { SubmissionResult.Refused("not-stubbed") },
    ) : PipelineRunnerPort {
        var submits: Int = 0
        override fun submit(req: RunRequest): SubmissionResult {
            submits++
            return responder(req)
        }
        override fun cancel(runId: RunId): CancellationResult = CancellationResult.Unknown("not-stubbed")
        override fun status(runId: RunId): RunHandleStatus = RunHandleStatus.Unknown("not-stubbed")
    }

    private class StubIdempotency : IdempotencyPort {
        override fun lookup(scope: String, key: String, requestDigestSha256: String) =
            IdempotencyLookupResult.NotFound
        override fun record(scope: String, key: String, operationId: OperationId, requestDigestSha256: String, responseRef: String?) =
            IdempotencyRecordResult.Recorded
    }

    @Test
    fun `runtime UNAVAILABLE throws and does NOT submit`() {
        val runner = StubRunner()
        val uc = StartRunUseCase(runner, StubIdempotency(), runtimeUnavailable(), metadataWith(snap, fid))
        assertThrows<IllegalStateException> {
            uc.execute(snap, sc, rid, IdempotencyKey.new(), Sha256.digest("x".toByteArray()))
        }
        assertEquals(0, runner.submits)
    }

    @Test
    fun `runtime FAILURE throws`() {
        val runner = StubRunner()
        val uc = StartRunUseCase(runner, StubIdempotency(), runtimeFailing("boom"), metadataWith(snap, fid))
        assertThrows<IllegalStateException> { uc.execute(snap, sc, rid, IdempotencyKey.new(), Sha256.digest("x".toByteArray())) }
    }

    @Test
    fun `runner refused yields REFUSED state`() {
        val runner = StubRunner(responder = { SubmissionResult.Refused("plugin sdk mismatch") })
        val uc = StartRunUseCase(runner, StubIdempotency(), runtimeAvailable(), metadataWith(snap, fid))
        val binding = uc.execute(snap, sc, rid, IdempotencyKey.new(), Sha256.digest("x".toByteArray()))
        assertEquals(RunState.REFUSED, binding.state)
        assertTrue(binding.upstreamRunId == null)
    }

    @Test
    fun `runner admitted yields QUEUED state with upstream id`() {
        val runner = StubRunner(responder = { SubmissionResult.Admitted("upstream-1", java.nio.file.Path.of("/tmp/ctrl")) })
        val uc = StartRunUseCase(runner, StubIdempotency(), runtimeAvailable(), metadataWith(snap, fid))
        val binding = uc.execute(snap, sc, rid, IdempotencyKey.new(), Sha256.digest("x".toByteArray()))
        assertEquals(RunState.QUEUED, binding.state)
        assertEquals(UpstreamRunId("upstream-1"), binding.upstreamRunId)
        assertTrue(binding.runDbRef != null)
    }

    /**
     * Audit C-2: RunBinding.experimentId must be the snapshot's real
     * experimentId, not the NIL UUID placeholder. This pins the fix.
     */
    @Test
    fun `runner admitted binding carries real experimentId from snapshot not NIL UUID`() {
        val runner = StubRunner(responder = { SubmissionResult.Admitted("upstream-1", java.nio.file.Path.of("/tmp/ctrl")) })
        val uc = StartRunUseCase(runner, StubIdempotency(), runtimeAvailable(), metadataWith(snap, fid))
        val binding = uc.execute(snap, sc, rid, IdempotencyKey.new(), Sha256.digest("x".toByteArray()))
        assertEquals(fid, binding.experimentId)
        assertTrue(binding.experimentId.value != "00000000-0000-0000-0000-000000000000",
            "experimentId must not be the NIL UUID placeholder")
    }

    /**
     * Audit C-3: RunBinding.runtimeVersion, runtimeDigest, pluginSetDigest
     * must come from the live RuntimeDescriptor, not empty strings.
     */
    @Test
    fun `runner admitted binding propagates runtime descriptor fields`() {
        val runner = StubRunner(responder = { SubmissionResult.Admitted("upstream-1", java.nio.file.Path.of("/tmp/ctrl")) })
        val uc = StartRunUseCase(runner, StubIdempotency(), runtimeAvailable(), metadataWith(snap, fid))
        val binding = uc.execute(snap, sc, rid, IdempotencyKey.new(), Sha256.digest("x".toByteArray()))
        assertEquals("0.48.0-rc1", binding.runtimeVersion)
        assertEquals("sha-pin", binding.runtimeDigest)
        assertEquals("0.48.0-rc1", binding.pluginSetDigest)
    }

    /**
     * Audit C-2: if the snapshot row is missing, the use case refuses
     * to mint a NIL UUID. It throws IllegalStateException with an
     * explicit SNAPSHOT_NOT_FOUND reason so the caller can distinguish
     * broken replay from fresh run.
     */
    @Test
    fun `runner admitted throws when snapshot is missing instead of substituting NIL UUID`() {
        val runner = StubRunner(responder = { SubmissionResult.Admitted("upstream-1", java.nio.file.Path.of("/tmp/ctrl")) })
        val uc = StartRunUseCase(runner, StubIdempotency(), runtimeAvailable(), metadataWith(snap, null))
        val ex = assertThrows<IllegalStateException> {
            uc.execute(snap, sc, rid, IdempotencyKey.new(), Sha256.digest("x".toByteArray()))
        }
        assertTrue(ex.message!!.contains("SNAPSHOT_NOT_FOUND"),
            "expected SNAPSHOT_NOT_FOUND, got: ${ex.message}")
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
            override fun saveSnapshot(snap: dev.pipelinek.studio.domain.SourceSnapshot) { /* noop */ }
            override fun loadSnapshotSource(id: dev.pipelinek.studio.domain.SnapshotId): ByteArray? = null
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
