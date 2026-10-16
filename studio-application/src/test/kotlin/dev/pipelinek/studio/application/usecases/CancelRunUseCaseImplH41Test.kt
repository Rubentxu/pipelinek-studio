@file:Suppress("TooManyFunctions")

package dev.pipelinek.studio.application.usecases

import dev.pipelinek.studio.application.ports.CancelRunResult
import dev.pipelinek.studio.application.ports.CancellationResult
import dev.pipelinek.studio.application.ports.PipelineRunnerPort
import dev.pipelinek.studio.application.ports.RemoteCancellationResult
import dev.pipelinek.studio.application.ports.RemoteInspectionResult
import dev.pipelinek.studio.application.ports.RemoteRunnerPort
import dev.pipelinek.studio.application.ports.RunHandleStatus
import dev.pipelinek.studio.application.ports.RunRequest
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

/**
 * Falsifiers for the SFW-H4.1
 * [CancelRunUseCaseImpl] — the use case that
 * consolidates cancellation across the local
 * SDK subprocess and a remote Fabric server.
 *
 * The seam under test is a hand-built
 * [FakePipelineRunnerPort] +
 * [FakeRemoteRunnerPort] +
 * [FakeRuntimeBackendSelector] + the H4.1
 * use case. The tests pin the four arms of
 * [CancelRunResult] for each backend + the
 * selector's `Refused` arm.
 */
@DisplayName("CancelRunUseCaseImpl (SFW-H4.1 consolidate local + Fabric cancel)")
class CancelRunUseCaseImplH41Test {

    private val studioRunId = RunId("studio-run-h41-1")
    private val request = RunRequest(
        runId = studioRunId,
        snapshotId = SnapshotId("snapshot-1"),
        scenarioId = ScenarioId("scenario-1"),
        profile = dev.pipelinek.studio.application.ports.ExecutionProfile.TrustedLocal,
    )

    // ------------------------------------------------------------------
    // Falsifier 1: Local backend + CancelRequested
    // ------------------------------------------------------------------
    @Test
    fun `Local backend with local CancelRequested maps to CancelRunResult CancelRequested`() = runBlocking {
        val local = FakePipelineRunnerPort().apply {
            nextCancel = CancellationResult.CancelRequested
        }
        val remote = FakeRemoteRunnerPort()
        val selector = FakeRuntimeBackendSelector(allowedBackend = RuntimeBackend.Local)
        val useCase = CancelRunUseCaseImpl(local, remote, selector)

        val result = useCase.cancel(studioRunId, request)

        assertEquals(CancelRunResult.CancelRequested, result)
        assertEquals(1, local.cancelCalls)
        assertEquals(studioRunId, local.lastCancelRunId)
        assertEquals(0, remote.cancelCalls, "remote port must not be called for Local backend")
    }

    // ------------------------------------------------------------------
    // Falsifier 2: Local backend + AlreadyTerminal(finalState)
    // ------------------------------------------------------------------
    @Test
    fun `Local backend with local AlreadyTerminal maps to CancelRunResult AlreadyTerminal`() = runBlocking {
        val local = FakePipelineRunnerPort().apply {
            nextCancel = CancellationResult.AlreadyTerminal(finalState = "SUCCEEDED")
        }
        val remote = FakeRemoteRunnerPort()
        val selector = FakeRuntimeBackendSelector(allowedBackend = RuntimeBackend.Local)
        val useCase = CancelRunUseCaseImpl(local, remote, selector)

        val result = useCase.cancel(studioRunId, request)

        assertEquals(CancelRunResult.AlreadyTerminal("SUCCEEDED"), result)
    }

    // ------------------------------------------------------------------
    // Falsifier 3: Local backend + Unknown(reason)
    // ------------------------------------------------------------------
    @Test
    fun `Local backend with local Unknown maps to CancelRunResult Unknown`() = runBlocking {
        val local = FakePipelineRunnerPort().apply {
            nextCancel = CancellationResult.Unknown(reason = "no live subprocess for runId")
        }
        val remote = FakeRemoteRunnerPort()
        val selector = FakeRuntimeBackendSelector(allowedBackend = RuntimeBackend.Local)
        val useCase = CancelRunUseCaseImpl(local, remote, selector)

        val result = useCase.cancel(studioRunId, request)

        assertEquals(
            CancelRunResult.Unknown("no live subprocess for runId"),
            result,
        )
    }

    // ------------------------------------------------------------------
    // Falsifier 4: Fabric backend + CancelRequested
    // ------------------------------------------------------------------
    @Test
    fun `Fabric backend with remote CancelRequested maps to CancelRunResult CancelRequested`() = runBlocking {
        val local = FakePipelineRunnerPort()
        val remote = FakeRemoteRunnerPort().apply {
            nextCancel = RemoteCancellationResult.CancelRequested
        }
        val selector = FakeRuntimeBackendSelector(allowedBackend = RuntimeBackend.Fabric)
        val useCase = CancelRunUseCaseImpl(local, remote, selector)

        val result = useCase.cancel(studioRunId, request)

        assertEquals(CancelRunResult.CancelRequested, result)
        assertEquals(1, remote.cancelCalls)
        assertEquals(studioRunId, remote.lastCancelRunId)
        assertEquals(0, local.cancelCalls, "local port must not be called for Fabric backend")
    }

    // ------------------------------------------------------------------
    // Falsifier 5: Fabric backend + Refused(reason) - the H4.1
    // new arm
    // ------------------------------------------------------------------
    @Test
    fun `Fabric backend with remote Refused maps to CancelRunResult Refused`() = runBlocking {
        val local = FakePipelineRunnerPort()
        val remote = FakeRemoteRunnerPort().apply {
            nextCancel = RemoteCancellationResult.Refused(reason = "transport failure on RunControl.cancelRun: connection reset")
        }
        val selector = FakeRuntimeBackendSelector(allowedBackend = RuntimeBackend.Fabric)
        val useCase = CancelRunUseCaseImpl(local, remote, selector)

        val result = useCase.cancel(studioRunId, request)

        // The H4.1 contract: the H2.2c `Refused`
        // arm maps to `CancelRunResult.Refused`
        // (the new arm added by H4.1).
        val refused = result as? CancelRunResult.Refused
        assertTrue(
            refused != null,
            "expected CancelRunResult.Refused, got $result",
        )
        assertTrue(
            refused!!.reason.contains("transport", ignoreCase = true),
            "Refused reason should reference transport: ${refused.reason}",
        )
    }

    // ------------------------------------------------------------------
    // Falsifier 6: selector refusal - the use case returns
    // CancelRunResult.Refused without dispatching to any port
    // ------------------------------------------------------------------
    @Test
    fun `selector refusal returns CancelRunResult Refused without dispatching to any port`() = runBlocking {
        val local = FakePipelineRunnerPort()
        val remote = FakeRemoteRunnerPort()
        val selector = FakeRuntimeBackendSelector(
            refusedReason = "runtime-backend config is invalid: typo in 'locall'",
        )
        val useCase = CancelRunUseCaseImpl(local, remote, selector)

        val result = useCase.cancel(studioRunId, request)

        val refused = result as? CancelRunResult.Refused
        assertTrue(
            refused != null,
            "expected CancelRunResult.Refused on selector refusal, got $result",
        )
        assertTrue(
            refused!!.reason.contains("selector"),
            "Refused reason should reference the selector: ${refused.reason}",
        )
        assertEquals(0, local.cancelCalls, "local port must not be called when selector refused")
        assertEquals(0, remote.cancelCalls, "remote port must not be called when selector refused")
    }
}

/**
 * A test-only [PipelineRunnerPort] that records
 * every call and returns caller-configured
 * answers. The H4.1 tests inject a
 * [FakePipelineRunnerPort] and configure the
 * `nextCancel` value the port returns.
 */
private class FakePipelineRunnerPort : PipelineRunnerPort {
    var nextCancel: CancellationResult = CancellationResult.CancelRequested
    var cancelCalls: Int = 0
        private set
    var lastCancelRunId: RunId? = null
        private set

    override fun submit(req: RunRequest): SubmissionResult =
        SubmissionResult.Admitted(upstreamRunId = "fake-upstream", controlRoot = java.nio.file.Path.of("/tmp/fake"))

    override fun cancel(runId: RunId): CancellationResult {
        cancelCalls += 1
        lastCancelRunId = runId
        return nextCancel
    }

    override fun status(runId: RunId): RunHandleStatus =
        RunHandleStatus.Live(upstreamRunId = "fake-upstream", pid = 12345L)
}

/**
 * A test-only [RemoteRunnerPort] that records
 * every call and returns caller-configured
 * answers.
 */
private class FakeRemoteRunnerPort : RemoteRunnerPort {
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

    override suspend fun summary(studioRunId: RunId): RemoteInspectionResult =
        RemoteInspectionResult.Unknown("not used in cancel test")
}

/**
 * A test-only [RuntimeBackendSelector] that
 * returns a caller-configured
 * [RuntimeBackendSelectionResult]. The H4.1
 * tests inject a [FakeRuntimeBackendSelector]
 * and configure `allowedBackend` (for
 * `Allowed`) or `refusedReason` (for
 * `Refused`).
 */
private class FakeRuntimeBackendSelector(
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
