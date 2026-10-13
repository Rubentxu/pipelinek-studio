@file:Suppress("TooManyFunctions")

package dev.pipelinek.studio.application.usecases

import dev.pipelinek.studio.application.ports.CancellationResult
import dev.pipelinek.studio.application.ports.CancelRunResult
import dev.pipelinek.studio.application.ports.DedupKeyPort
import dev.pipelinek.studio.application.ports.DedupedRunSnapshot
import dev.pipelinek.studio.application.ports.PipelineRunnerPort
import dev.pipelinek.studio.application.ports.RemoteRunnerPort
import dev.pipelinek.studio.application.ports.RunBindingSnapshot
import dev.pipelinek.studio.application.ports.RunHandleStatus
import dev.pipelinek.studio.application.ports.RunRequest
import dev.pipelinek.studio.application.ports.RuntimeBackend
import dev.pipelinek.studio.application.ports.RuntimeBackendSelector
import dev.pipelinek.studio.application.ports.RuntimeBackendSelectionResult
import dev.pipelinek.studio.application.ports.StudioMetadataPort
import dev.pipelinek.studio.domain.ExperimentId
import dev.pipelinek.studio.domain.RunId
import dev.pipelinek.studio.domain.RunState
import dev.pipelinek.studio.domain.ScenarioId
import dev.pipelinek.studio.domain.SnapshotId
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Falsifiers for the SFW-H6.4
 * [CancelRunUseCaseImpl] dedup release
 * (cancel-before-event releases the
 * dedup key so a future request with
 * the same content may re-submit).
 */
@DisplayName("CancelRunUseCaseImpl (SFW-H6.4 dedup release)")
class CancelRunUseCaseImplH64Test {

    private val request = RunRequest(
        runId = RunId("studio-h64-1"),
        snapshotId = SnapshotId("snapshot-h64"),
        scenarioId = ScenarioId("scenario-h64"),
        profile = dev.pipelinek.studio.application.ports.ExecutionProfile.TrustedLocal,
    )

    // ------------------------------------------------------------------
    // Falsifier 1: cancel-before-event (state=CREATED) releases the key
    // ------------------------------------------------------------------
    @Test
    fun `cancel-before-event (state CREATED) releases the dedup key`() = runBlocking {
        val port = FakeDedupKeyPortForH64()
        val meta = FakeMetadataForH64(state = "CREATED")
        val cancel = CancelRunUseCaseImpl(
            localPort = StubLocalRunnerForH64(cancelResult = CancellationResult.CancelRequested),
            remotePort = StubRemoteRunnerForH64(),
            selector = LocalOnlySelectorForH64(),
            metadata = meta,
            dedupKeyRelease = port,
        )
        val result = cancel.cancel(RunId("studio-h64-existing"), request)
        assertTrue(
            result is CancelRunResult.CancelRequested,
            "expected CancelRequested, got $result",
        )
        assertEquals(1, port.releaseCalls, "release must be called once on cancel-before-event")
    }

    // ------------------------------------------------------------------
    // Falsifier 2: cancel-after-event (state=RUNNING) does NOT release
    // ------------------------------------------------------------------
    @Test
    fun `cancel-after-event (state RUNNING) does NOT release the dedup key`() = runBlocking {
        val port = FakeDedupKeyPortForH64()
        val meta = FakeMetadataForH64(state = "RUNNING")
        val cancel = CancelRunUseCaseImpl(
            localPort = StubLocalRunnerForH64(cancelResult = CancellationResult.CancelRequested),
            remotePort = StubRemoteRunnerForH64(),
            selector = LocalOnlySelectorForH64(),
            metadata = meta,
            dedupKeyRelease = port,
        )
        val result = cancel.cancel(RunId("studio-h64-running"), request)
        assertTrue(
            result is CancelRunResult.CancelRequested,
            "expected CancelRequested, got $result",
        )
        assertEquals(0, port.releaseCalls, "release must NOT be called when state is RUNNING")
    }

    // ------------------------------------------------------------------
    // Falsifier 3: cancel of a terminal run does NOT release
    // ------------------------------------------------------------------
    @Test
    fun `cancel of a terminal run does NOT release the dedup key`() = runBlocking {
        val port = FakeDedupKeyPortForH64()
        val meta = FakeMetadataForH64(state = "CANCELLED")
        val cancel = CancelRunUseCaseImpl(
            localPort = StubLocalRunnerForH64(
                cancelResult = CancellationResult.AlreadyTerminal("CANCELLED"),
            ),
            remotePort = StubRemoteRunnerForH64(),
            selector = LocalOnlySelectorForH64(),
            metadata = meta,
            dedupKeyRelease = port,
        )
        val result = cancel.cancel(RunId("studio-h64-terminal"), request)
        assertTrue(
            result is CancelRunResult.AlreadyTerminal,
            "expected AlreadyTerminal, got $result",
        )
        assertEquals(0, port.releaseCalls, "release must NOT be called when cancel returns AlreadyTerminal")
    }

    // ------------------------------------------------------------------
    // Falsifier 4: dedupKeyRelease=null is a noop (backwards-compatible)
    // ------------------------------------------------------------------
    @Test
    fun `dedupKeyRelease null is a noop and the cancel still succeeds`() = runBlocking {
        val meta = FakeMetadataForH64(state = "CREATED")
        val cancel = CancelRunUseCaseImpl(
            localPort = StubLocalRunnerForH64(cancelResult = CancellationResult.CancelRequested),
            remotePort = StubRemoteRunnerForH64(),
            selector = LocalOnlySelectorForH64(),
            metadata = meta,
            dedupKeyRelease = null,
        )
        val result = cancel.cancel(RunId("studio-h64-noport"), request)
        assertTrue(
            result is CancelRunResult.CancelRequested,
            "expected CancelRequested, got $result",
        )
    }

    // ------------------------------------------------------------------
    // Falsifier 5: release port failure is best-effort (does not surface)
    // ------------------------------------------------------------------
    @Test
    fun `release port failure is best-effort and does not surface to the operator`() = runBlocking {
        val port = FakeDedupKeyPortForH64(throwOnRelease = RuntimeException("SQLite locked"))
        val meta = FakeMetadataForH64(state = "CREATED")
        val cancel = CancelRunUseCaseImpl(
            localPort = StubLocalRunnerForH64(cancelResult = CancellationResult.CancelRequested),
            remotePort = StubRemoteRunnerForH64(),
            selector = LocalOnlySelectorForH64(),
            metadata = meta,
            dedupKeyRelease = port,
        )
        val result = cancel.cancel(RunId("studio-h64-portfail"), request)
        // The cancel still succeeds; the port
        // failure is swallowed (best-effort).
        assertTrue(
            result is CancelRunResult.CancelRequested,
            "expected CancelRequested (port failure must NOT surface), got $result",
        )
    }
}

// --------------------------------------------------------------------
// Test fixtures
// --------------------------------------------------------------------

private class FakeDedupKeyPortForH64(
    var throwOnRelease: Throwable? = null,
) : DedupKeyPort {
    var releaseCalls: Int = 0
        private set
    var lastReleaseRunId: RunId? = null
        private set

    override suspend fun findByDedupKey(dedupKey: String): DedupedRunSnapshot? = null
    override suspend fun release(studioRunId: RunId): Boolean {
        releaseCalls += 1
        lastReleaseRunId = studioRunId
        throwOnRelease?.let { throw it }
        return true
    }
}

private class FakeMetadataForH64(
    val state: String,
) : StudioMetadataPort {
    override fun findExperiment(id: ExperimentId) = null
    override fun findSnapshot(id: SnapshotId): dev.pipelinek.studio.application.ports.SnapshotSnapshot? = null
    override fun findScenario(id: ScenarioId) = null
    override fun findRun(id: RunId): RunBindingSnapshot? = RunBindingSnapshot(
        id = id,
        experimentId = ExperimentId("00000000-0000-0000-0000-000000000000"),
        snapshotId = SnapshotId("snapshot-h64"),
        scenarioId = ScenarioId("scenario-h64"),
        state = state,
        createdAt = java.time.Instant.now(),
    )
    override fun listRuns(state: RunState?, limit: Int, offset: Int) = emptyList<RunBindingSnapshot>()
    override fun listScenarios(limit: Int, offset: Int) = emptyList<dev.pipelinek.studio.application.ports.ScenarioSnapshot>()
    override fun saveScenario(scenario: dev.pipelinek.studio.domain.SourceScenario) { /* drop */ }
    override fun saveExperiment(experiment: dev.pipelinek.studio.domain.Experiment) { /* drop */ }
    override fun saveSnapshot(snap: dev.pipelinek.studio.domain.SourceSnapshot) { /* noop */ }
    override fun loadSnapshotSource(id: SnapshotId): ByteArray? = null
    override fun persistRunBinding(binding: dev.pipelinek.studio.domain.RunBinding) { /* drop */ }
    override fun transitionRunState(studioRunId: RunId, newState: RunState) { /* drop */ }
}

private class StubLocalRunnerForH64(
    val cancelResult: CancellationResult,
) : PipelineRunnerPort {
    override fun submit(req: RunRequest) =
        throw UnsupportedOperationException("not used in H6.4 falsifiers")
    override fun cancel(runId: RunId): CancellationResult = cancelResult
    override fun status(runId: RunId): RunHandleStatus =
        RunHandleStatus.Unknown("not used in H6.4 falsifiers")
}

private class StubRemoteRunnerForH64 : RemoteRunnerPort {
    override suspend fun cancel(studioRunId: RunId) =
        dev.pipelinek.studio.application.ports.RemoteCancellationResult.CancelRequested
    override suspend fun summary(studioRunId: RunId) =
        dev.pipelinek.studio.application.ports.RemoteInspectionResult.Unknown(
            "not used in H6.4 falsifiers",
        )
}

private class LocalOnlySelectorForH64 : RuntimeBackendSelector {
    override fun select(req: RunRequest) =
        RuntimeBackendSelectionResult.Allowed(RuntimeBackend.Local)
}
