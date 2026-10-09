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
        override fun record(scope: String, key: String, operationId: OperationId, requestDigestSha256: String) =
            IdempotencyRecordResult.Recorded
    }

    @Test
    fun `runtime UNAVAILABLE throws and does NOT submit`() {
        val runner = StubRunner()
        val uc = StartRunUseCase(runner, StubIdempotency(), runtimeUnavailable())
        assertThrows<IllegalStateException> {
            uc.execute(snap, sc, rid, IdempotencyKey.new(), Sha256.digest("x".toByteArray()))
        }
        assertEquals(0, runner.submits)
    }

    @Test
    fun `runtime FAILURE throws`() {
        val runner = StubRunner()
        val uc = StartRunUseCase(runner, StubIdempotency(), runtimeFailing("boom"))
        assertThrows<IllegalStateException> { uc.execute(snap, sc, rid, IdempotencyKey.new(), Sha256.digest("x".toByteArray())) }
    }

    @Test
    fun `runner refused yields REFUSED state`() {
        val runner = StubRunner(responder = { SubmissionResult.Refused("plugin sdk mismatch") })
        val uc = StartRunUseCase(runner, StubIdempotency(), runtimeAvailable())
        val binding = uc.execute(snap, sc, rid, IdempotencyKey.new(), Sha256.digest("x".toByteArray()))
        assertEquals(RunState.REFUSED, binding.state)
        assertTrue(binding.upstreamRunId == null)
    }

    @Test
    fun `runner admitted yields QUEUED state with upstream id`() {
        val runner = StubRunner(responder = { SubmissionResult.Admitted("upstream-1", java.nio.file.Path.of("/tmp/ctrl")) })
        val uc = StartRunUseCase(runner, StubIdempotency(), runtimeAvailable())
        val binding = uc.execute(snap, sc, rid, IdempotencyKey.new(), Sha256.digest("x".toByteArray()))
        assertEquals(RunState.QUEUED, binding.state)
        assertEquals(UpstreamRunId("upstream-1"), binding.upstreamRunId)
        assertTrue(binding.runDbRef != null)
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
