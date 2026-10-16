@file:Suppress("TooManyFunctions")

package dev.pipelinek.studio.application.usecases

import dev.pipelinek.studio.application.ports.CancelRunResult
import dev.pipelinek.studio.application.ports.CancelRunUseCase
import dev.pipelinek.studio.application.ports.CancelSelectionLookup
import dev.pipelinek.studio.application.ports.CancellationResult
import dev.pipelinek.studio.application.ports.PipelineRunnerPort
import dev.pipelinek.studio.application.ports.RemoteCancellationResult
import dev.pipelinek.studio.application.ports.RemoteRunnerPort
import dev.pipelinek.studio.application.ports.RunHandleStatus
import dev.pipelinek.studio.application.ports.RunRequest
import dev.pipelinek.studio.application.ports.RuntimeBackend
import dev.pipelinek.studio.application.ports.RuntimeBackendSelectionResult
import dev.pipelinek.studio.application.ports.RuntimeBackendSelector
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
 * Falsifiers for the SFW-H4.8
 * [CancelRunUseCaseImpl] extension — the
 * use case change to read the H4.7
 * `cancel_selection` value when the
 * production boot wires a
 * [CancelSelectionLookup].
 */
@DisplayName("CancelRunUseCaseImpl (SFW-H4.8 read cancel_selection)")
class CancelRunUseCaseImplH48Test {

    private val studioRunId = RunId("studio-h48-1")
    private val newRequest = RunRequest(
        runId = studioRunId,
        snapshotId = SnapshotId("snapshot-h48"),
        scenarioId = ScenarioId("scenario-h48"),
        profile = dev.pipelinek.studio.application.ports.ExecutionProfile.TrustedLocal,
    )

    // ------------------------------------------------------------------
    // Falsifier 1: cancelSelection = "Local" -> local port receives the
    // cancel; the selector is NOT called.
    // ------------------------------------------------------------------
    @Test
    fun `cancelSelection = Local dispatches to local port without running selector`() = runBlocking {
        val local = FakePipelineRunnerPortForH48().apply {
            nextCancel = CancellationResult.CancelRequested
        }
        val remote = FakeRemoteRunnerPortForH48()
        val selector = FakeRuntimeBackendSelectorForH48(
            // If the selector IS called, it returns Fabric -- the
            // test would observe a remote cancel and FAIL.
            allowedBackend = RuntimeBackend.Fabric,
        )
        val lookup = FakeCancelSelectionLookupForH48().apply {
            nextSelection = "Local"
        }
        val useCase = CancelRunUseCaseImpl(local, remote, selector, lookup)

        val result = useCase.cancel(studioRunId, newRequest)

        assertEquals(CancelRunResult.CancelRequested, result)
        assertEquals(1, local.cancelCalls, "local port must be called when cancelSelection = Local")
        assertEquals(0, remote.cancelCalls, "remote port must NOT be called when cancelSelection = Local")
        assertEquals(0, selector.selectCalls, "selector must NOT be called when cancelSelection is read successfully")
        assertEquals(1, lookup.lookupCalls, "lookup must be called once")
    }

    // ------------------------------------------------------------------
    // Falsifier 2: cancelSelection = "Fabric" -> remote port receives the
    // cancel; the selector is NOT called.
    // ------------------------------------------------------------------
    @Test
    fun `cancelSelection = Fabric dispatches to remote port without running selector`() = runBlocking {
        val local = FakePipelineRunnerPortForH48()
        val remote = FakeRemoteRunnerPortForH48().apply {
            nextCancel = RemoteCancellationResult.CancelRequested
        }
        val selector = FakeRuntimeBackendSelectorForH48(
            // If the selector IS called, it returns Local -- the
            // test would observe a local cancel and FAIL.
            allowedBackend = RuntimeBackend.Local,
        )
        val lookup = FakeCancelSelectionLookupForH48().apply {
            nextSelection = "Fabric"
        }
        val useCase = CancelRunUseCaseImpl(local, remote, selector, lookup)

        val result = useCase.cancel(studioRunId, newRequest)

        assertEquals(CancelRunResult.CancelRequested, result)
        assertEquals(0, local.cancelCalls, "local port must NOT be called when cancelSelection = Fabric")
        assertEquals(1, remote.cancelCalls, "remote port must be called when cancelSelection = Fabric")
        assertEquals(0, selector.selectCalls, "selector must NOT be called when cancelSelection is read successfully")
    }

    // ------------------------------------------------------------------
    // Falsifier 3: cancelSelection = null -> selector IS called (the
    // honest default for a pre-H4.7 row).
    // ------------------------------------------------------------------
    @Test
    fun `cancelSelection = null falls back to selector (pre-H47 row honest default)`() = runBlocking {
        val local = FakePipelineRunnerPortForH48().apply {
            nextCancel = CancellationResult.CancelRequested
        }
        val remote = FakeRemoteRunnerPortForH48()
        val selector = FakeRuntimeBackendSelectorForH48(allowedBackend = RuntimeBackend.Local)
        val lookup = FakeCancelSelectionLookupForH48().apply {
            nextSelection = null  // a pre-H4.7 row
        }
        val useCase = CancelRunUseCaseImpl(local, remote, selector, lookup)

        val result = useCase.cancel(studioRunId, newRequest)

        assertEquals(CancelRunResult.CancelRequested, result)
        assertEquals(1, local.cancelCalls, "local port must be called when selector returns Local")
        assertEquals(1, selector.selectCalls, "selector must be called when cancelSelection is null (the H4.1 honest default)")
    }

    // ------------------------------------------------------------------
    // Falsifier 4: cancelSelection = "Local" + local port returns
    // Refused -> CancelRunResult.Refused (the cancelSelection read
    // does NOT bypass the local port's own Refused arm).
    // ------------------------------------------------------------------
    @Test
    fun `cancelSelection = Local + local port Refused maps to CancelRunResult Refused`() = runBlocking {
        val local = FakePipelineRunnerPortForH48().apply {
            nextCancel = CancellationResult.Unknown("the local port is not the source of truth for refusal")
        }
        val remote = FakeRemoteRunnerPortForH48()
        val selector = FakeRuntimeBackendSelectorForH48(allowedBackend = RuntimeBackend.Local)
        val lookup = FakeCancelSelectionLookupForH48().apply {
            nextSelection = "Local"
        }
        val useCase = CancelRunUseCaseImpl(local, remote, selector, lookup)

        val result = useCase.cancel(studioRunId, newRequest)

        // The local port returned Unknown, which maps to Unknown
        // (not Refused). The H4.1 contract is that the local
        // port's Unknown is preserved.
        val unknown = result as? CancelRunResult.Unknown
        assertTrue(unknown != null, "expected CancelRunResult.Unknown, got $result")
    }

    // ------------------------------------------------------------------
    // Falsifier 5: cancelSelection lookup is unwired (null) -> the use
    // case falls back to the selector (the existing H4.1 behavior).
    // ------------------------------------------------------------------
    @Test
    fun `cancelSelection lookup unwired (null) falls back to selector (H41 backwards compat)`() = runBlocking {
        val local = FakePipelineRunnerPortForH48().apply {
            nextCancel = CancellationResult.CancelRequested
        }
        val remote = FakeRemoteRunnerPortForH48()
        val selector = FakeRuntimeBackendSelectorForH48(allowedBackend = RuntimeBackend.Local)
        // No lookup -- the constructor's default parameter is null.
        val useCase = CancelRunUseCaseImpl(local, remote, selector)

        val result = useCase.cancel(studioRunId, newRequest)

        assertEquals(CancelRunResult.CancelRequested, result)
        assertEquals(1, local.cancelCalls, "local port must be called when selector returns Local")
        assertEquals(1, selector.selectCalls, "selector must be called when lookup is unwired (the H4.1 default)")
    }
}

// --------------------------------------------------------------------
// Test fixtures
// --------------------------------------------------------------------

private class FakePipelineRunnerPortForH48 : PipelineRunnerPort {
    var nextCancel: CancellationResult = CancellationResult.CancelRequested
    var cancelCalls: Int = 0
        private set
    var lastCancelRunId: RunId? = null
        private set

    override fun submit(req: RunRequest): dev.pipelinek.studio.application.ports.SubmissionResult =
        dev.pipelinek.studio.application.ports.SubmissionResult.Admitted(
            upstreamRunId = "fake-upstream",
            controlRoot = Path.of("/tmp/fake"),
        )

    override fun cancel(runId: RunId): CancellationResult {
        cancelCalls += 1
        lastCancelRunId = runId
        return nextCancel
    }

    override fun status(runId: RunId): RunHandleStatus = RunHandleStatus.Unknown("not used in H4.8 test")
}

private class FakeRemoteRunnerPortForH48 : RemoteRunnerPort {
    var nextCancel: RemoteCancellationResult = RemoteCancellationResult.CancelRequested
    var cancelCalls: Int = 0
        private set
    var lastCancelRunId: RunId? = null
        private set

    override suspend fun cancel(studioRunId: RunId): RemoteCancellationResult {
        cancelCalls += 1
        lastCancelRunId = studioRunId
        return nextCancel
    }

    override suspend fun summary(studioRunId: RunId) =
        dev.pipelinek.studio.application.ports.RemoteInspectionResult.Refused("not used in H4.8 test")
}

private class FakeRuntimeBackendSelectorForH48(
    private val allowedBackend: RuntimeBackend = RuntimeBackend.Local,
) : RuntimeBackendSelector {
    var selectCalls: Int = 0
        private set

    override fun select(req: RunRequest): RuntimeBackendSelectionResult {
        selectCalls += 1
        return RuntimeBackendSelectionResult.Allowed(allowedBackend)
    }
}

private class FakeCancelSelectionLookupForH48 : CancelSelectionLookup {
    var nextSelection: String? = null
    var lookupCalls: Int = 0
        private set
    var lastLookupRunId: RunId? = null
        private set

    override suspend fun cancelSelectionFor(studioRunId: RunId): String? {
        lookupCalls += 1
        lastLookupRunId = studioRunId
        return nextSelection
    }
}
