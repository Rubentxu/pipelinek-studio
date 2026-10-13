@file:Suppress("TooManyFunctions")

package dev.pipelinek.studio.application.usecases

import dev.pipelinek.studio.application.ports.CancelRunResult
import dev.pipelinek.studio.application.ports.CancelRunUseCase
import dev.pipelinek.studio.application.ports.PipelineRunnerPort
import dev.pipelinek.studio.application.ports.RemoteCancellationResult
import dev.pipelinek.studio.application.ports.RemoteInspectionResult
import dev.pipelinek.studio.application.ports.RemoteRunnerPort
import dev.pipelinek.studio.application.ports.RemoteSubmissionResult
import dev.pipelinek.studio.application.ports.RemoteSubmitPort
import dev.pipelinek.studio.application.ports.RunHandleStatus
import dev.pipelinek.studio.application.ports.RunRequest
import dev.pipelinek.studio.application.ports.RetryRunResult
import dev.pipelinek.studio.application.ports.RuntimeBackend
import dev.pipelinek.studio.application.ports.RuntimeBackendSelectionResult
import dev.pipelinek.studio.application.ports.RuntimeBackendSelector
import dev.pipelinek.studio.application.ports.SubmissionResult
import dev.pipelinek.studio.domain.RunId
import dev.pipelinek.studio.domain.ScenarioId
import dev.pipelinek.studio.domain.SnapshotId
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.nio.file.Path

/**
 * Falsifiers for the SFW-H4.3
 * [RetryRunUseCaseImpl] — the use case that
 * chains the H4.1 cancel with the submit path.
 */
@DisplayName("RetryRunUseCaseImpl (SFW-H4.3 cancel + re-submit)")
class RetryRunUseCaseImplH43Test {

    private val originalRunId = RunId("studio-run-h43-orig")
    private val newRunId = RunId("studio-run-h43-new")
    private val newRequest = RunRequest(
        runId = newRunId,
        snapshotId = SnapshotId("snapshot-1"),
        scenarioId = ScenarioId("scenario-1"),
        profile = dev.pipelinek.studio.application.ports.ExecutionProfile.TrustedLocal,
    )

    // ------------------------------------------------------------------
    // Falsifier 1: cancel + Local submit Admitted -> Retried
    // ------------------------------------------------------------------
    @Test
    fun `cancel CancelRequested + Local submit Admitted maps to Retried`() = runBlocking {
        val cancel = FakeCancelRunUseCase().apply {
            nextResult = CancelRunResult.CancelRequested
        }
        val local = FakePipelineRunnerPortForH43().apply {
            nextSubmit = SubmissionResult.Admitted(
                upstreamRunId = "upstream-new",
                controlRoot = Path.of("/tmp/new"),
            )
        }
        val remote = FakeRemoteSubmitPortForH43()
        val selector = FakeRuntimeBackendSelectorForH43(allowedBackend = RuntimeBackend.Local)
        val useCase = RetryRunUseCaseImpl(cancel, local, remote, selector)

        val result = useCase.retry(originalRunId, newRequest)

        val retried = result as? RetryRunResult.Retried
        assertTrue(retried != null, "expected RetryRunResult.Retried, got $result")
        assertEquals(newRunId, retried!!.newStudioRunId)
        assertEquals("upstream-new", retried.newUpstreamRunId)
        assertEquals(Path.of("/tmp/new"), retried.newControlRoot)
        assertEquals(1, cancel.cancelCalls)
        assertEquals(1, local.submitCalls)
        assertEquals(0, remote.submitCalls, "remote must not be called for Local backend")
    }

    // ------------------------------------------------------------------
    // Falsifier 2: cancel + Fabric submit Admitted -> Retried
    // ------------------------------------------------------------------
    @Test
    fun `cancel CancelRequested + Fabric submit Admitted maps to Retried`() = runBlocking {
        val cancel = FakeCancelRunUseCase().apply {
            nextResult = CancelRunResult.CancelRequested
        }
        val local = FakePipelineRunnerPortForH43()
        val remote = FakeRemoteSubmitPortForH43().apply {
            nextSubmit = RemoteSubmissionResult.Admitted(
                upstreamRunId = "fabric-upstream-new",
                controlRoot = Path.of("/tmp/fabric-new"),
            )
        }
        val selector = FakeRuntimeBackendSelectorForH43(allowedBackend = RuntimeBackend.Fabric)
        val useCase = RetryRunUseCaseImpl(cancel, local, remote, selector)

        val result = useCase.retry(originalRunId, newRequest)

        val retried = result as? RetryRunResult.Retried
        assertTrue(retried != null, "expected RetryRunResult.Retried, got $result")
        assertEquals(newRunId, retried!!.newStudioRunId)
        assertEquals("fabric-upstream-new", retried.newUpstreamRunId)
        assertEquals(0, local.submitCalls, "local must not be called for Fabric backend")
        assertEquals(1, remote.submitCalls)
    }

    // ------------------------------------------------------------------
    // Falsifier 3: cancel AlreadyTerminal + Local submit Admitted -> Retried
    // (the original was already terminal; the retry is a fresh submit)
    // ------------------------------------------------------------------
    @Test
    fun `cancel AlreadyTerminal + Local submit Admitted maps to Retried`() = runBlocking {
        val cancel = FakeCancelRunUseCase().apply {
            nextResult = CancelRunResult.AlreadyTerminal(finalState = "SUCCEEDED")
        }
        val local = FakePipelineRunnerPortForH43().apply {
            nextSubmit = SubmissionResult.Admitted(
                upstreamRunId = "upstream-new",
                controlRoot = Path.of("/tmp/new"),
            )
        }
        val remote = FakeRemoteSubmitPortForH43()
        val selector = FakeRuntimeBackendSelectorForH43(allowedBackend = RuntimeBackend.Local)
        val useCase = RetryRunUseCaseImpl(cancel, local, remote, selector)

        val result = useCase.retry(originalRunId, newRequest)

        assertTrue(result is RetryRunResult.Retried, "expected Retried, got $result")
    }

    // ------------------------------------------------------------------
    // Falsifier 4: cancel Unknown -> RetryRunResult.Unknown
    // ------------------------------------------------------------------
    @Test
    fun `cancel Unknown maps to RetryRunResult Unknown without submitting`() = runBlocking {
        val cancel = FakeCancelRunUseCase().apply {
            nextResult = CancelRunResult.Unknown("no binding for studioRunId")
        }
        val local = FakePipelineRunnerPortForH43()
        val remote = FakeRemoteSubmitPortForH43()
        val selector = FakeRuntimeBackendSelectorForH43(allowedBackend = RuntimeBackend.Local)
        val useCase = RetryRunUseCaseImpl(cancel, local, remote, selector)

        val result = useCase.retry(originalRunId, newRequest)

        val unknown = result as? RetryRunResult.Unknown
        assertTrue(unknown != null, "expected RetryRunResult.Unknown, got $result")
        assertEquals(0, local.submitCalls, "submit must not be called when cancel returned Unknown")
        assertEquals(0, remote.submitCalls)
    }

    // ------------------------------------------------------------------
    // Falsifier 5: cancel Refused -> RetryRunResult.CancelRefused
    // ------------------------------------------------------------------
    @Test
    fun `cancel Refused maps to RetryRunResult CancelRefused`() = runBlocking {
        val cancel = FakeCancelRunUseCase().apply {
            nextResult = CancelRunResult.Refused(reason = "transport failure on cancel")
        }
        val local = FakePipelineRunnerPortForH43()
        val remote = FakeRemoteSubmitPortForH43()
        val selector = FakeRuntimeBackendSelectorForH43(allowedBackend = RuntimeBackend.Local)
        val useCase = RetryRunUseCaseImpl(cancel, local, remote, selector)

        val result = useCase.retry(originalRunId, newRequest)

        val refused = result as? RetryRunResult.CancelRefused
        assertTrue(refused != null, "expected RetryRunResult.CancelRefused, got $result")
        assertEquals(0, local.submitCalls, "submit must not be called when cancel refused")
    }

    // ------------------------------------------------------------------
    // Falsifier 6: cancel + Local submit Refused -> RetryRunResult.SubmitRefused
    // ------------------------------------------------------------------
    @Test
    fun `cancel CancelRequested + Local submit Refused maps to SubmitRefused`() = runBlocking {
        val cancel = FakeCancelRunUseCase().apply {
            nextResult = CancelRunResult.CancelRequested
        }
        val local = FakePipelineRunnerPortForH43().apply {
            nextSubmit = SubmissionResult.Refused(reason = "local submit refused")
        }
        val remote = FakeRemoteSubmitPortForH43()
        val selector = FakeRuntimeBackendSelectorForH43(allowedBackend = RuntimeBackend.Local)
        val useCase = RetryRunUseCaseImpl(cancel, local, remote, selector)

        val result = useCase.retry(originalRunId, newRequest)

        val refused = result as? RetryRunResult.SubmitRefused
        assertTrue(refused != null, "expected RetryRunResult.SubmitRefused, got $result")
        assertEquals(1, cancel.cancelCalls, "cancel must have been called")
        assertEquals(1, local.submitCalls, "submit must have been called (and refused)")
    }

    // ------------------------------------------------------------------
    // Falsifier 7: selector refusal on submit -> SubmitRefused
    // ------------------------------------------------------------------
    @Test
    fun `selector refusal on submit maps to SubmitRefused`() = runBlocking {
        val cancel = FakeCancelRunUseCase().apply {
            nextResult = CancelRunResult.CancelRequested
        }
        val local = FakePipelineRunnerPortForH43()
        val remote = FakeRemoteSubmitPortForH43()
        val selector = FakeRuntimeBackendSelectorForH43(
            refusedReason = "runtime-backend config is invalid",
        )
        val useCase = RetryRunUseCaseImpl(cancel, local, remote, selector)

        val result = useCase.retry(originalRunId, newRequest)

        val refused = result as? RetryRunResult.SubmitRefused
        assertTrue(refused != null, "expected RetryRunResult.SubmitRefused, got $result")
        assertEquals(0, local.submitCalls, "submit must not be called when selector refused")
        assertEquals(0, remote.submitCalls)
    }
}

// --------------------------------------------------------------------
// Test fixtures
// --------------------------------------------------------------------

private class FakeCancelRunUseCase : CancelRunUseCase {
    var nextResult: CancelRunResult = CancelRunResult.CancelRequested
    var cancelCalls: Int = 0
        private set
    var lastRunId: RunId? = null
        private set
    var lastRequest: RunRequest? = null
        private set

    override suspend fun cancel(studioRunId: RunId, request: RunRequest): CancelRunResult {
        cancelCalls += 1
        lastRunId = studioRunId
        lastRequest = request
        return nextResult
    }
}

private class FakePipelineRunnerPortForH43 : PipelineRunnerPort {
    var nextSubmit: SubmissionResult = SubmissionResult.Admitted(
        upstreamRunId = "fake-upstream",
        controlRoot = Path.of("/tmp/fake"),
    )
    var submitCalls: Int = 0
        private set
    var lastSubmitRequest: RunRequest? = null
        private set

    override fun submit(req: RunRequest): SubmissionResult {
        submitCalls += 1
        lastSubmitRequest = req
        return nextSubmit
    }

    override fun cancel(runId: RunId): dev.pipelinek.studio.application.ports.CancellationResult =
        dev.pipelinek.studio.application.ports.CancellationResult.CancelRequested

    override fun status(runId: RunId): RunHandleStatus =
        RunHandleStatus.Unknown("not used in retry test")
}

private class FakeRemoteSubmitPortForH43 : RemoteSubmitPort {
    var nextSubmit: RemoteSubmissionResult = RemoteSubmissionResult.Admitted(
        upstreamRunId = "fake-fabric-upstream",
        controlRoot = Path.of("/tmp/fabric-fake"),
    )
    var submitCalls: Int = 0
        private set
    var lastSubmitRequest: RunRequest? = null
        private set

    override suspend fun submit(req: RunRequest): RemoteSubmissionResult {
        submitCalls += 1
        lastSubmitRequest = req
        return nextSubmit
    }
}

private class FakeRuntimeBackendSelectorForH43(
    private val allowedBackend: RuntimeBackend = RuntimeBackend.Local,
    private val refusedReason: String? = null,
) : RuntimeBackendSelector {
    override fun select(req: RunRequest): RuntimeBackendSelectionResult =
        if (refusedReason != null) {
            RuntimeBackendSelectionResult.Refused(refusedReason)
        } else {
            RuntimeBackendSelectionResult.Allowed(allowedBackend)
        }
}
