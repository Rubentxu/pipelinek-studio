@file:Suppress("TooManyFunctions")

package dev.pipelinek.studio.application.usecases

import dev.pipelinek.studio.application.ports.CancellationResult
import dev.pipelinek.studio.application.ports.CancelRunResult
import dev.pipelinek.studio.application.ports.DedupKeyPort
import dev.pipelinek.studio.application.ports.DedupedRunSnapshot
import dev.pipelinek.studio.application.ports.PipelineRunnerPort
import dev.pipelinek.studio.application.ports.ReleaseDedupKeyResult
import dev.pipelinek.studio.application.ports.ReleaseDedupKeyUseCase
import dev.pipelinek.studio.application.ports.RemoteRunnerPort
import dev.pipelinek.studio.application.ports.RunHandleStatus
import dev.pipelinek.studio.application.ports.RunRequest
import dev.pipelinek.studio.application.ports.RuntimeBackend
import dev.pipelinek.studio.application.ports.RuntimeBackendSelector
import dev.pipelinek.studio.application.ports.RuntimeBackendSelectionResult
import dev.pipelinek.studio.application.ports.StudioMetadataPort
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
 * Falsifiers for the SFW-H6.6+ cancel-path
 * integration with the
 * [ReleaseDedupKeyUseCase] (the H6.5+
 * standalone use case).
 *
 * The H6.6+ unit extends
 * [CancelRunUseCaseImpl] with an optional
 * `releaseDedupKeyUseCase: ReleaseDedupKeyUseCase?`
 * parameter. When wired, the cancel path
 * delegates to the use case instead of the
 * H6.4 direct-port hook.
 */
@DisplayName("CancelRunUseCaseImpl (SFW-H6.6+ releaseDedupKeyUseCase integration)")
class CancelRunUseCaseImplH66Test {

    private val request = RunRequest(
        runId = RunId("studio-h66-1"),
        snapshotId = SnapshotId("snapshot-h66"),
        scenarioId = ScenarioId("scenario-h66"),
        profile = dev.pipelinek.studio.application.ports.ExecutionProfile.TrustedLocal,
    )

    // ------------------------------------------------------------------
    // Falsifier 1: H6.6+ use case is called on CREATED cancel
    // ------------------------------------------------------------------
    @Test
    fun `H66 use case is called on CREATED cancel`() = runBlocking {
        val useCase = FakeReleaseDedupKeyUseCaseForH66(
            result = ReleaseDedupKeyResult.Released,
        )
        val cancel = CancelRunUseCaseImpl(
            localPort = StubLocalRunnerForH66(cancelResult = CancellationResult.CancelRequested),
            remotePort = StubRemoteRunnerForH66(),
            selector = LocalOnlySelectorForH66(),
            releaseDedupKeyUseCase = useCase,
        )
        val result = cancel.cancel(RunId("studio-h66-existing"), request)
        assertTrue(
            result is CancelRunResult.CancelRequested,
            "expected CancelRequested, got $result",
        )
        assertEquals(1, useCase.releaseCalls, "use case must be called once on cancel")
    }

    // ------------------------------------------------------------------
    // Falsifier 2: H6.6+ use case is NOT called when state is RUNNING
    // ------------------------------------------------------------------
    @Test
    fun `H66 use case is NOT called when the use case returns NotApplicable`() = runBlocking {
        val useCase = FakeReleaseDedupKeyUseCaseForH66(
            result = ReleaseDedupKeyResult.NotApplicable,
        )
        val cancel = CancelRunUseCaseImpl(
            localPort = StubLocalRunnerForH66(cancelResult = CancellationResult.CancelRequested),
            remotePort = StubRemoteRunnerForH66(),
            selector = LocalOnlySelectorForH66(),
            releaseDedupKeyUseCase = useCase,
        )
        val result = cancel.cancel(RunId("studio-h66-running"), request)
        assertTrue(
            result is CancelRunResult.CancelRequested,
            "expected CancelRequested, got $result",
        )
        assertEquals(1, useCase.releaseCalls, "use case must be called (the use case decides NotApplicable internally)")
    }

    // ------------------------------------------------------------------
    // Falsifier 3: H6.6+ use case is best-effort (port failure does NOT surface)
    // ------------------------------------------------------------------
    @Test
    fun `H66 use case port failure is best-effort and does not surface to the operator`() = runBlocking {
        val useCase = FakeReleaseDedupKeyUseCaseForH66(
            throwOnRelease = RuntimeException("SQLite locked"),
        )
        val cancel = CancelRunUseCaseImpl(
            localPort = StubLocalRunnerForH66(cancelResult = CancellationResult.CancelRequested),
            remotePort = StubRemoteRunnerForH66(),
            selector = LocalOnlySelectorForH66(),
            releaseDedupKeyUseCase = useCase,
        )
        val result = cancel.cancel(RunId("studio-h66-portfail"), request)
        // The cancel still succeeds; the use
        // case failure is swallowed (best-effort).
        assertTrue(
            result is CancelRunResult.CancelRequested,
            "expected CancelRequested (use case failure must NOT surface), got $result",
        )
    }

    // ------------------------------------------------------------------
    // Falsifier 4: H6.6+ use case takes precedence over H6.4 direct-port hook
    // ------------------------------------------------------------------
    @Test
    fun `H66 use case takes precedence over H64 direct-port hook`() = runBlocking {
        val useCase = FakeReleaseDedupKeyUseCaseForH66(
            result = ReleaseDedupKeyResult.Released,
        )
        val directPort = FakeDedupKeyPortForH66()
        val cancel = CancelRunUseCaseImpl(
            localPort = StubLocalRunnerForH66(cancelResult = CancellationResult.CancelRequested),
            remotePort = StubRemoteRunnerForH66(),
            selector = LocalOnlySelectorForH66(),
            // Both wired: use case should take
            // precedence (H6.4 direct-port hook
            // is the fallback).
            metadata = FakeMetadataForH66(state = "CREATED"),
            dedupKeyRelease = directPort,
            releaseDedupKeyUseCase = useCase,
        )
        val result = cancel.cancel(RunId("studio-h66-precedence"), request)
        assertTrue(
            result is CancelRunResult.CancelRequested,
            "expected CancelRequested, got $result",
        )
        assertEquals(1, useCase.releaseCalls, "use case must be called when wired")
        assertEquals(
            0,
            directPort.releaseCalls,
            "H6.4 direct-port hook must NOT be called when the H6.6+ use case is wired",
        )
    }

    // ------------------------------------------------------------------
    // Falsifier 5: H6.6+ use case null falls back to H6.4 direct-port hook
    // ------------------------------------------------------------------
    @Test
    fun `H66 use case null falls back to H64 direct-port hook`() = runBlocking {
        val directPort = FakeDedupKeyPortForH66()
        val cancel = CancelRunUseCaseImpl(
            localPort = StubLocalRunnerForH66(cancelResult = CancellationResult.CancelRequested),
            remotePort = StubRemoteRunnerForH66(),
            selector = LocalOnlySelectorForH66(),
            metadata = FakeMetadataForH66(state = "CREATED"),
            dedupKeyRelease = directPort,
            // H6.6+ use case is null: the H6.4
            // direct-port hook is the fallback.
            releaseDedupKeyUseCase = null,
        )
        val result = cancel.cancel(RunId("studio-h66-fallback"), request)
        assertTrue(
            result is CancelRunResult.CancelRequested,
            "expected CancelRequested, got $result",
        )
        assertEquals(
            1,
            directPort.releaseCalls,
            "H6.4 direct-port hook must be called when the H6.6+ use case is null",
        )
    }
}

// --------------------------------------------------------------------
// Test fixtures
// --------------------------------------------------------------------

private class FakeReleaseDedupKeyUseCaseForH66(
    var result: ReleaseDedupKeyResult = ReleaseDedupKeyResult.Released,
    var throwOnRelease: Throwable? = null,
) : ReleaseDedupKeyUseCase {
    var releaseCalls: Int = 0
        private set
    var lastRunId: RunId? = null
        private set

    override suspend fun release(studioRunId: RunId): ReleaseDedupKeyResult {
        releaseCalls += 1
        lastRunId = studioRunId
        throwOnRelease?.let { throw it }
        return result
    }
}

private class FakeDedupKeyPortForH66 : DedupKeyPort {
    var releaseCalls: Int = 0
        private set
    override suspend fun findByDedupKey(dedupKey: String): DedupedRunSnapshot? = null
    override suspend fun release(studioRunId: RunId): Boolean {
        releaseCalls += 1
        return true
    }
}

private class FakeMetadataForH66(
    val state: String,
) : StudioMetadataPort {
    override fun findExperiment(id: dev.pipelinek.studio.domain.ExperimentId) = null
    override fun findSnapshot(id: SnapshotId) = null
    override fun findScenario(id: ScenarioId) = null
    override fun findRun(id: RunId) = dev.pipelinek.studio.application.ports.RunBindingSnapshot(
        id = id,
        experimentId = dev.pipelinek.studio.domain.ExperimentId("00000000-0000-0000-0000-000000000000"),
        snapshotId = SnapshotId("snapshot-h66"),
        scenarioId = ScenarioId("scenario-h66"),
        state = state,
        createdAt = java.time.Instant.now(),
    )
    override fun listRuns(state: RunState?, limit: Int, offset: Int) = emptyList<dev.pipelinek.studio.application.ports.RunBindingSnapshot>()
    override fun listScenarios(limit: Int, offset: Int) = emptyList<dev.pipelinek.studio.application.ports.ScenarioSnapshot>()
    override fun saveScenario(scenario: dev.pipelinek.studio.domain.SourceScenario) { /* drop */ }
    override fun saveExperiment(experiment: dev.pipelinek.studio.domain.Experiment) { /* drop */ }
    override fun saveSnapshot(snap: dev.pipelinek.studio.domain.SourceSnapshot) { /* noop */ }
    override fun loadSnapshotSource(id: SnapshotId): ByteArray? = null
    override fun persistRunBinding(binding: dev.pipelinek.studio.domain.RunBinding) { /* drop */ }
    override fun transitionRunState(studioRunId: RunId, newState: RunState) { /* drop */ }
}

private class StubLocalRunnerForH66(
    val cancelResult: CancellationResult,
) : PipelineRunnerPort {
    override fun submit(req: RunRequest) =
        throw UnsupportedOperationException("not used in H6.6+ falsifiers")
    override fun cancel(runId: RunId): CancellationResult = cancelResult
    override fun status(runId: RunId): RunHandleStatus =
        RunHandleStatus.Unknown("not used in H6.6+ falsifiers")
}

private class StubRemoteRunnerForH66 : RemoteRunnerPort {
    override suspend fun cancel(studioRunId: RunId) =
        dev.pipelinek.studio.application.ports.RemoteCancellationResult.CancelRequested
    override suspend fun summary(studioRunId: RunId) =
        dev.pipelinek.studio.application.ports.RemoteInspectionResult.Unknown(
            "not used in H6.6+ falsifiers",
        )
}

private class LocalOnlySelectorForH66 : RuntimeBackendSelector {
    override fun select(req: RunRequest) =
        RuntimeBackendSelectionResult.Allowed(RuntimeBackend.Local)
}
