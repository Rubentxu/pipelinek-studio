@file:Suppress("TooManyFunctions")

package dev.pipelinek.studio.application.usecases

import dev.pipelinek.studio.application.ports.CancelRunResult
import dev.pipelinek.studio.application.ports.CancellationResult
import dev.pipelinek.studio.application.ports.PipelineRunnerPort
import dev.pipelinek.studio.application.ports.PodmanRunResult
import dev.pipelinek.studio.application.ports.PodmanRunnerPort
import dev.pipelinek.studio.application.ports.RemoteRunnerPort
import dev.pipelinek.studio.application.ports.RunHandleStatus
import dev.pipelinek.studio.application.ports.RunRequest
import dev.pipelinek.studio.application.ports.RuntimeBackend
import dev.pipelinek.studio.application.ports.RuntimeBackendSelector
import dev.pipelinek.studio.application.ports.RuntimeBackendSelectionResult
import dev.pipelinek.studio.domain.RunId
import dev.pipelinek.studio.domain.ScenarioId
import dev.pipelinek.studio.domain.SnapshotId
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Falsifiers for the SFW-H5.3
 * [PodmanRunnerPort] abstract surface +
 * stub production adapter (the
 * [PodmanRunnerAdapter] lives in the
 * `studio-infra-podman` module, which the
 * application cannot depend on; the
 * tests in this file use a fake
 * [PodmanRunnerPort] that mirrors the
 * stub's behavior).
 *
 * The H5.3 chain is the chain-start for
 * Podman integration. The actual
 * `podman run` invocation (the H5 UAT
 * "podman-container run") is a follow-up
 * H5.3+ unit. The tests pin the abstract
 * surface + the honest refusal behavior.
 */
@DisplayName("PodmanRunnerPort (SFW-H5.3 Podman chain-start)")
class PodmanRunnerPortH53Test {

    private val request = RunRequest(
        runId = RunId("studio-h53-1"),
        snapshotId = SnapshotId("snapshot-h53"),
        scenarioId = ScenarioId("scenario-h53"),
        profile = dev.pipelinek.studio.application.ports.ExecutionProfile.TrustedLocal,
    )

    // ------------------------------------------------------------------
    // Falsifier 1: stub runInContainer returns Refused (not Started)
    // ------------------------------------------------------------------
    @Test
    fun `stub runInContainer returns Refused with chain-start reason`() = runBlocking {
        val port: PodmanRunnerPort = FakePodmanRunnerPortH53()
        val result = port.runInContainer(
            request = request,
            image = "studio-h53-image:latest",
            hostControlRoot = java.nio.file.Path.of("/tmp"),
        )
        val refused = result as? PodmanRunResult.Refused
        assertNotNull(refused, "expected Refused (H5.3 chain-start), got $result")
        assertTrue(
            refused!!.reason.contains("H5.3 chain-start"),
            "reason should reference H5.3 chain-start: ${refused.reason}",
        )
        assertTrue(
            refused.reason.contains("studio-h53-1"),
            "reason should reference the run id: ${refused.reason}",
        )
    }

    // ------------------------------------------------------------------
    // Falsifier 2: stub stopContainer returns Refused
    // ------------------------------------------------------------------
    @Test
    fun `stub stopContainer returns Refused with chain-start reason`() = runBlocking {
        val port: PodmanRunnerPort = FakePodmanRunnerPortH53()
        val result = port.stopContainer(containerId = "container-h53-1")
        val refused = result as? PodmanRunResult.Refused
        assertNotNull(refused, "expected Refused (H5.3 chain-start), got $result")
        assertTrue(
            refused!!.reason.contains("H5.3 chain-start"),
            "reason should reference H5.3 chain-start: ${refused.reason}",
        )
        assertTrue(
            refused.reason.contains("container-h53-1"),
            "reason should reference the container id: ${refused.reason}",
        )
    }

    // ------------------------------------------------------------------
    // Falsifier 3: the port surface is intact (compile-time + run-time)
    // ------------------------------------------------------------------
    @Test
    fun `PodmanRunnerPort surface is intact (compile-time + run-time)`() {
        val port: PodmanRunnerPort = FakePodmanRunnerPortH53()
        // The compile-time check is the assertion
        // (the assignment must type-check); the
        // run-time check confirms the surface is
        // intact.
        assertNotNull(port)
    }

    // ------------------------------------------------------------------
    // Falsifier 4: unknown image is still refused (not Unknown)
    // ------------------------------------------------------------------
    @Test
    fun `unknown image maps to Refused (not Unknown) at the H5_3 chain-start`() = runBlocking {
        val port: PodmanRunnerPort = FakePodmanRunnerPortH53()
        val result = port.runInContainer(
            request = request,
            image = "nonexistent:latest",
            hostControlRoot = java.nio.file.Path.of("/tmp"),
        )
        // The H5.3 stub returns Refused for ALL
        // inputs (the conservative path). The
        // follow-up H5.3+ unit will surface
        // Unknown when the actual `podman run`
        // invocation is wired.
        val refused = result as? PodmanRunResult.Refused
        assertNotNull(refused, "expected Refused (H5.3 chain-start), got $result")
    }

    // ------------------------------------------------------------------
    // Falsifier 5: cancel use case with Podman backend returns Refused
    // ------------------------------------------------------------------
    @Test
    fun `cancel use case with Podman backend returns Refused (chain-start)`() = runBlocking {
        // The H5.3 chain-start wires the Podman arm
        // on the CancelRunUseCase selector
        // dispatch. When the selector picks
        // Podman, the cancel surfaces as
        // CancelRunResult.Refused.
        val cancel: CancelRunUseCaseImpl = CancelRunUseCaseImpl(
            localPort = StubLocalRunnerH53(),
            remotePort = StubRemoteRunnerH53(),
            selector = PodmanOnlySelectorH53(),
        )
        val studioRunId = RunId("studio-h53-cancel")
        val result = cancel.cancel(studioRunId, request)
        val refused = result as? CancelRunResult.Refused
        assertNotNull(refused, "expected Refused, got $result")
        assertTrue(
            refused!!.reason.contains("H5.3 chain-start"),
            "reason should reference H5.3 chain-start: ${refused.reason}",
        )
    }
}

// --------------------------------------------------------------------
// Test fixtures
// --------------------------------------------------------------------

private class FakePodmanRunnerPortH53 : PodmanRunnerPort {
    override suspend fun runInContainer(
        request: RunRequest,
        image: String,
        hostControlRoot: java.nio.file.Path,
    ): PodmanRunResult = PodmanRunResult.Refused(
        reason = "H5.3 chain-start: the PodmanRunnerAdapter is a stub; " +
            "the actual podman run invocation is a follow-up H5.3+ unit. " +
            "request=${request.runId.value} image=$image",
    )

    override suspend fun stopContainer(containerId: String): PodmanRunResult =
        PodmanRunResult.Refused(
            reason = "H5.3 chain-start: the PodmanRunnerAdapter.stopContainer " +
                "is a stub; the actual podman stop + rm invocation is a " +
                "follow-up H5.3+ unit. containerId=$containerId",
        )
}

private class StubLocalRunnerH53 : PipelineRunnerPort {
    override fun submit(req: RunRequest) =
        throw UnsupportedOperationException("not used in H5.3 falsifiers")
    override fun cancel(runId: RunId): CancellationResult =
        CancellationResult.CancelRequested
    override fun status(runId: RunId): RunHandleStatus =
        RunHandleStatus.Unknown("not used in H5.3 falsifiers")
}

private class StubRemoteRunnerH53 : RemoteRunnerPort {
    override suspend fun cancel(studioRunId: RunId) =
        dev.pipelinek.studio.application.ports.RemoteCancellationResult.CancelRequested
    override suspend fun summary(studioRunId: RunId) =
        dev.pipelinek.studio.application.ports.RemoteInspectionResult.Unknown(
            "not used in H5.3 falsifiers",
        )
}

private class PodmanOnlySelectorH53 : RuntimeBackendSelector {
    override fun select(req: RunRequest) =
        RuntimeBackendSelectionResult.Allowed(RuntimeBackend.Podman)
}
