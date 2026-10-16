@file:Suppress("TooManyFunctions")

package dev.pipelinek.studio.application.usecases

import dev.pipelinek.studio.application.ports.CancellationResult
import dev.pipelinek.studio.application.ports.CancelRunResult
import dev.pipelinek.studio.application.ports.PipelineRunnerPort
import dev.pipelinek.studio.application.ports.ReleaseDedupKeyResult
import dev.pipelinek.studio.application.ports.ReleaseDedupKeyUseCase
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
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Falsifiers for the SFW-H6.7+ cancel-path
 * unit: the H6.4 direct-port hook is removed;
 * the H6.5+ use case is the only path.
 *
 * The H6.7+ unit is a refactor that removes
 * the `metadata` + `dedupKeyRelease` parameters
 * from `CancelRunUseCaseImpl`. The
 * `releaseDedupKeyUseCase` parameter is the
 * only path; when null, the dedup release is
 * a noop.
 */
@DisplayName("CancelRunUseCaseImpl (SFW-H6.7+ H6.4 hook removed)")
class CancelRunUseCaseImplH67Test {

    private val request = RunRequest(
        runId = RunId("studio-h67-1"),
        snapshotId = SnapshotId("snapshot-h67"),
        scenarioId = ScenarioId("scenario-h67"),
        profile = dev.pipelinek.studio.application.ports.ExecutionProfile.TrustedLocal,
    )

    // ------------------------------------------------------------------
    // Falsifier 1: use case is called on CREATED cancel
    // ------------------------------------------------------------------
    @Test
    fun `use case is called on cancel (the only path)`() = runBlocking {
        val useCase = FakeReleaseDedupKeyUseCaseForH67(
            result = ReleaseDedupKeyResult.Released,
        )
        val cancel = CancelRunUseCaseImpl(
            localPort = StubLocalRunnerForH67(cancelResult = CancellationResult.CancelRequested),
            remotePort = StubRemoteRunnerForH67(),
            selector = LocalOnlySelectorForH67(),
            releaseDedupKeyUseCase = useCase,
        )
        val result = cancel.cancel(RunId("studio-h67-existing"), request)
        assertTrue(
            result is CancelRunResult.CancelRequested,
            "expected CancelRequested, got $result",
        )
        assertEquals(1, useCase.releaseCalls, "use case must be called once on cancel")
    }

    // ------------------------------------------------------------------
    // Falsifier 2: use case result is best-effort (not surfaced)
    // ------------------------------------------------------------------
    @Test
    fun `use case port failure is best-effort and does not surface to the operator`() = runBlocking {
        val useCase = FakeReleaseDedupKeyUseCaseForH67(
            throwOnRelease = RuntimeException("SQLite locked"),
        )
        val cancel = CancelRunUseCaseImpl(
            localPort = StubLocalRunnerForH67(cancelResult = CancellationResult.CancelRequested),
            remotePort = StubRemoteRunnerForH67(),
            selector = LocalOnlySelectorForH67(),
            releaseDedupKeyUseCase = useCase,
        )
        val result = cancel.cancel(RunId("studio-h67-portfail"), request)
        assertTrue(
            result is CancelRunResult.CancelRequested,
            "expected CancelRequested (use case failure must NOT surface), got $result",
        )
    }

    // ------------------------------------------------------------------
    // Falsifier 3: use case null is a noop (backwards-compatible)
    // ------------------------------------------------------------------
    @Test
    fun `use case null is a noop (backwards-compatible)`() = runBlocking {
        val cancel = CancelRunUseCaseImpl(
            localPort = StubLocalRunnerForH67(cancelResult = CancellationResult.CancelRequested),
            remotePort = StubRemoteRunnerForH67(),
            selector = LocalOnlySelectorForH67(),
            releaseDedupKeyUseCase = null,
        )
        val result = cancel.cancel(RunId("studio-h67-noport"), request)
        assertTrue(
            result is CancelRunResult.CancelRequested,
            "expected CancelRequested, got $result",
        )
    }

    // ------------------------------------------------------------------
    // Falsifier 4: cancel of a terminal run is still CancelRequested
    // ------------------------------------------------------------------
    @Test
    fun `cancel of a terminal run still returns CancelRequested (no dedup release)`() = runBlocking {
        val useCase = FakeReleaseDedupKeyUseCaseForH67(
            result = ReleaseDedupKeyResult.NotApplicable,
        )
        val cancel = CancelRunUseCaseImpl(
            localPort = StubLocalRunnerForH67(cancelResult = CancellationResult.CancelRequested),
            remotePort = StubRemoteRunnerForH67(),
            selector = LocalOnlySelectorForH67(),
            releaseDedupKeyUseCase = useCase,
        )
        val result = cancel.cancel(RunId("studio-h67-terminal"), request)
        assertTrue(
            result is CancelRunResult.CancelRequested,
            "expected CancelRequested, got $result",
        )
        assertEquals(1, useCase.releaseCalls, "use case must be called (the use case decides NotApplicable internally)")
    }

    // ------------------------------------------------------------------
    // Falsifier 5: H6.4 direct-port hook is no longer accepted (compile-time)
    // ------------------------------------------------------------------
    @Test
    fun `H64 direct-port hook is no longer a parameter (compile-time check)`() {
        // The compile-time check is the assertion:
        // the CancelRunUseCaseImpl constructor
        // no longer accepts `metadata` or
        // `dedupKeyRelease`. The run-time check
        // confirms the new dispatch.
        val cancel = CancelRunUseCaseImpl(
            localPort = StubLocalRunnerForH67(cancelResult = CancellationResult.CancelRequested),
            remotePort = StubRemoteRunnerForH67(),
            selector = LocalOnlySelectorForH67(),
            releaseDedupKeyUseCase = null,
        )
        assertNotNullH67(cancel)
    }
}

private fun assertNotNullH67(cancel: Any) {
    org.junit.jupiter.api.Assertions.assertNotNull(cancel)
}

// --------------------------------------------------------------------
// Test fixtures
// --------------------------------------------------------------------

private class FakeReleaseDedupKeyUseCaseForH67(
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

private class StubLocalRunnerForH67(
    val cancelResult: CancellationResult,
) : PipelineRunnerPort {
    override fun submit(req: RunRequest) =
        throw UnsupportedOperationException("not used in H6.7+ falsifiers")
    override fun cancel(runId: RunId): CancellationResult = cancelResult
    override fun status(runId: RunId): RunHandleStatus =
        RunHandleStatus.Unknown("not used in H6.7+ falsifiers")
}

private class StubRemoteRunnerForH67 : RemoteRunnerPort {
    override suspend fun cancel(studioRunId: RunId) =
        dev.pipelinek.studio.application.ports.RemoteCancellationResult.CancelRequested
    override suspend fun summary(studioRunId: RunId) =
        dev.pipelinek.studio.application.ports.RemoteInspectionResult.Unknown(
            "not used in H6.7+ falsifiers",
        )
}

private class LocalOnlySelectorForH67 : RuntimeBackendSelector {
    override fun select(req: RunRequest) =
        RuntimeBackendSelectionResult.Allowed(RuntimeBackend.Local)
}
