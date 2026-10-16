@file:Suppress("TooManyFunctions")

package dev.pipelinek.studio.application.usecases

import dev.pipelinek.studio.application.ports.PipelineRunnerPort
import dev.pipelinek.studio.application.ports.RemoteCancellationResult
import dev.pipelinek.studio.application.ports.RemoteInspectionResult
import dev.pipelinek.studio.application.ports.RemoteRunnerPort
import dev.pipelinek.studio.application.ports.RunHandleStatus
import dev.pipelinek.studio.application.ports.RunInspectionResult
import dev.pipelinek.studio.application.ports.RunRequest
import dev.pipelinek.studio.application.ports.RunSummary
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
import java.time.Instant

/**
 * Falsifiers for the SFW-H4.2
 * [RunStateInspectionUseCaseImpl] — the use
 * case that consolidates run state inspection
 * across the local SDK subprocess and a remote
 * Fabric server.
 */
@DisplayName("RunStateInspectionUseCaseImpl (SFW-H4.2 consolidate local + Fabric inspect)")
class RunStateInspectionUseCaseImplH42Test {

    private val studioRunId = RunId("studio-run-h42-1")
    private val request = RunRequest(
        runId = studioRunId,
        snapshotId = SnapshotId("snapshot-1"),
        scenarioId = ScenarioId("scenario-1"),
        profile = dev.pipelinek.studio.application.ports.ExecutionProfile.TrustedLocal,
    )

    // ------------------------------------------------------------------
    // Falsifier 1: Local backend + Live -> RunInspectionResult.Live
    // ------------------------------------------------------------------
    @Test
    fun `Local backend with local Live maps to RunInspectionResult Live`() = runBlocking {
        val local = FakePipelineRunnerPortForH42().apply {
            nextStatus = RunHandleStatus.Live(upstreamRunId = "upstream-1", pid = 12345L)
        }
        val remote = FakeRemoteRunnerPortForH42()
        val selector = FakeRuntimeBackendSelectorForH42(allowedBackend = RuntimeBackend.Local)
        val useCase = RunStateInspectionUseCaseImpl(local, remote, selector)

        val result = useCase.inspect(studioRunId, request)

        val live = result as? RunInspectionResult.Live
        assertTrue(live != null, "expected RunInspectionResult.Live, got $result")
        assertEquals("upstream-1", live!!.upstreamRunId)
        // Local path: finalState is null (the
        // local port has pid but no state name).
        assertEquals(null, live.finalState)
        assertEquals(1, local.statusCalls)
        assertEquals(0, remote.summaryCalls, "remote port must not be called for Local backend")
    }

    // ------------------------------------------------------------------
    // Falsifier 2: Local backend + Terminal -> RunInspectionResult.Terminal
    // ------------------------------------------------------------------
    @Test
    fun `Local backend with local Terminal maps to RunInspectionResult Terminal`() = runBlocking {
        val local = FakePipelineRunnerPortForH42().apply {
            nextStatus = RunHandleStatus.Terminal(finalState = "SUCCEEDED")
        }
        val remote = FakeRemoteRunnerPortForH42()
        val selector = FakeRuntimeBackendSelectorForH42(allowedBackend = RuntimeBackend.Local)
        val useCase = RunStateInspectionUseCaseImpl(local, remote, selector)

        val result = useCase.inspect(studioRunId, request)

        val terminal = result as? RunInspectionResult.Terminal
        assertTrue(terminal != null, "expected RunInspectionResult.Terminal, got $result")
        assertEquals("SUCCEEDED", terminal!!.finalState)
        // Local path: completedAt is null.
        assertEquals(null, terminal.completedAt)
    }

    // ------------------------------------------------------------------
    // Falsifier 3: Local backend + Unknown -> RunInspectionResult.Unknown
    // ------------------------------------------------------------------
    @Test
    fun `Local backend with local Unknown maps to RunInspectionResult Unknown`() = runBlocking {
        val local = FakePipelineRunnerPortForH42().apply {
            nextStatus = RunHandleStatus.Unknown(reason = "no live subprocess for runId")
        }
        val remote = FakeRemoteRunnerPortForH42()
        val selector = FakeRuntimeBackendSelectorForH42(allowedBackend = RuntimeBackend.Local)
        val useCase = RunStateInspectionUseCaseImpl(local, remote, selector)

        val result = useCase.inspect(studioRunId, request)

        val unknown = result as? RunInspectionResult.Unknown
        assertTrue(unknown != null, "expected RunInspectionResult.Unknown, got $result")
        assertEquals("no live subprocess for runId", unknown!!.reason)
    }

    // ------------------------------------------------------------------
    // Falsifier 4: Fabric backend + Found(state=Running)
    // ------------------------------------------------------------------
    @Test
    fun `Fabric backend with remote Found Running maps to RunInspectionResult Live with finalState=Running`() = runBlocking {
        val local = FakePipelineRunnerPortForH42()
        val remote = FakeRemoteRunnerPortForH42().apply {
            nextSummary = RemoteInspectionResult.Found(
                summary = useCaseLayerRunSummary(
                    upstreamRunId = "fabric-run-1",
                    finalState = "Running",
                    isTerminal = false,
                    finishedAt = null,
                ),
            )
        }
        val selector = FakeRuntimeBackendSelectorForH42(allowedBackend = RuntimeBackend.Fabric)
        val useCase = RunStateInspectionUseCaseImpl(local, remote, selector)

        val result = useCase.inspect(studioRunId, request)

        val live = result as? RunInspectionResult.Live
        assertTrue(live != null, "expected RunInspectionResult.Live, got $result")
        // Remote path: upstreamRunId is the Fabric RunId.
        assertEquals("fabric-run-1", live!!.upstreamRunId)
        // finalState is the variant class name.
        assertEquals("Running", live.finalState)
        assertEquals(1, remote.summaryCalls)
        assertEquals(0, local.statusCalls, "local port must not be called for Fabric backend")
    }

    // ------------------------------------------------------------------
    // Falsifier 5: Fabric backend + Found(state=Terminal(Success))
    // ------------------------------------------------------------------
    @Test
    fun `Fabric backend with remote Found Terminal Success maps to RunInspectionResult Terminal`() = runBlocking {
        val finishedAt = Instant.parse("2026-10-13T12:00:00Z")
        val local = FakePipelineRunnerPortForH42()
        val remote = FakeRemoteRunnerPortForH42().apply {
            nextSummary = RemoteInspectionResult.Found(
                summary = useCaseLayerRunSummary(
                    upstreamRunId = "fabric-run-1",
                    finalState = "Success",
                    isTerminal = true,
                    finishedAt = finishedAt,
                ),
            )
        }
        val selector = FakeRuntimeBackendSelectorForH42(allowedBackend = RuntimeBackend.Fabric)
        val useCase = RunStateInspectionUseCaseImpl(local, remote, selector)

        val result = useCase.inspect(studioRunId, request)

        val terminal = result as? RunInspectionResult.Terminal
        assertTrue(terminal != null, "expected RunInspectionResult.Terminal, got $result")
        assertEquals("Success", terminal!!.finalState)
        assertEquals(finishedAt, terminal.completedAt)
    }

    // ------------------------------------------------------------------
    // Falsifier 6: Fabric backend + Found(state=Waiting) -> Live
    // ------------------------------------------------------------------
    @Test
    fun `Fabric backend with remote Found Waiting maps to RunInspectionResult Live`() = runBlocking {
        val local = FakePipelineRunnerPortForH42()
        val remote = FakeRemoteRunnerPortForH42().apply {
            nextSummary = RemoteInspectionResult.Found(
                summary = useCaseLayerRunSummary(
                    upstreamRunId = "fabric-run-1",
                    finalState = "Waiting",
                    isTerminal = false,
                    finishedAt = null,
                ),
            )
        }
        val selector = FakeRuntimeBackendSelectorForH42(allowedBackend = RuntimeBackend.Fabric)
        val useCase = RunStateInspectionUseCaseImpl(local, remote, selector)

        val result = useCase.inspect(studioRunId, request)

        val live = result as? RunInspectionResult.Live
        assertTrue(live != null, "expected RunInspectionResult.Live for Waiting state")
        assertEquals("Waiting", live!!.finalState)
    }

    // ------------------------------------------------------------------
    // Falsifier 7: Fabric backend + Refused -> RunInspectionResult.Refused
    // ------------------------------------------------------------------
    @Test
    fun `Fabric backend with remote Refused maps to RunInspectionResult Refused`() = runBlocking {
        val local = FakePipelineRunnerPortForH42()
        val remote = FakeRemoteRunnerPortForH42().apply {
            nextSummary = RemoteInspectionResult.Refused(
                reason = "transport failure on RunIndex.summaryOf: connection reset",
            )
        }
        val selector = FakeRuntimeBackendSelectorForH42(allowedBackend = RuntimeBackend.Fabric)
        val useCase = RunStateInspectionUseCaseImpl(local, remote, selector)

        val result = useCase.inspect(studioRunId, request)

        val refused = result as? RunInspectionResult.Refused
        assertTrue(refused != null, "expected RunInspectionResult.Refused, got $result")
        assertTrue(
            refused!!.reason.contains("transport", ignoreCase = true),
            "Refused reason should reference transport: ${refused.reason}",
        )
    }

    // ------------------------------------------------------------------
    // Falsifier 8: selector refusal -> Refused without dispatching
    // ------------------------------------------------------------------
    @Test
    fun `selector refusal returns RunInspectionResult Refused without dispatching to any port`() = runBlocking {
        val local = FakePipelineRunnerPortForH42()
        val remote = FakeRemoteRunnerPortForH42()
        val selector = FakeRuntimeBackendSelectorForH42(
            refusedReason = "runtime-backend config is invalid",
        )
        val useCase = RunStateInspectionUseCaseImpl(local, remote, selector)

        val result = useCase.inspect(studioRunId, request)

        val refused = result as? RunInspectionResult.Refused
        assertTrue(refused != null, "expected RunInspectionResult.Refused on selector refusal, got $result")
        assertTrue(
            refused!!.reason.contains("selector"),
            "Refused reason should reference the selector: ${refused.reason}",
        )
        assertEquals(0, local.statusCalls)
        assertEquals(0, remote.summaryCalls)
    }
}

// --------------------------------------------------------------------
// Test fixtures
// --------------------------------------------------------------------

private class FakePipelineRunnerPortForH42 : PipelineRunnerPort {
    var nextStatus: RunHandleStatus = RunHandleStatus.Unknown("default")
    var statusCalls: Int = 0
        private set
    var lastStatusRunId: RunId? = null
        private set

    override fun submit(req: RunRequest): SubmissionResult =
        SubmissionResult.Admitted(upstreamRunId = "fake-upstream", controlRoot = java.nio.file.Path.of("/tmp/fake"))

    override fun cancel(runId: RunId): dev.pipelinek.studio.application.ports.CancellationResult =
        dev.pipelinek.studio.application.ports.CancellationResult.CancelRequested

    override fun status(runId: RunId): RunHandleStatus {
        statusCalls += 1
        lastStatusRunId = runId
        return nextStatus
    }
}

private class FakeRemoteRunnerPortForH42 : RemoteRunnerPort {
    var nextSummary: RemoteInspectionResult = RemoteInspectionResult.Unknown("default")
    var summaryCalls: Int = 0
        private set
    var lastSummaryRunId: RunId? = null
        private set

    override suspend fun cancel(studioRunId: RunId): RemoteCancellationResult =
        RemoteCancellationResult.CancelRequested

    override suspend fun summary(studioRunId: RunId): RemoteInspectionResult {
        summaryCalls += 1
        lastSummaryRunId = studioRunId
        return nextSummary
    }
}

private class FakeRuntimeBackendSelectorForH42(
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

private fun useCaseLayerRunSummary(
    upstreamRunId: String,
    finalState: String,
    isTerminal: Boolean,
    finishedAt: Instant?,
): RunSummary = RunSummary(
    upstreamRunId = upstreamRunId,
    displayName = upstreamRunId,
    finalState = finalState,
    isTerminal = isTerminal,
    startedAt = Instant.parse("2026-10-13T11:00:00Z"),
    finishedAt = finishedAt,
)
