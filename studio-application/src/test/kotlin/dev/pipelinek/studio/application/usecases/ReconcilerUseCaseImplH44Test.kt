@file:Suppress("TooManyFunctions")

package dev.pipelinek.studio.application.usecases

import dev.pipelinek.studio.application.ports.ReconcileDecision
import dev.pipelinek.studio.application.ports.RemoteReconcileDecision
import dev.pipelinek.studio.application.ports.RemoteReconcilerPort
import dev.pipelinek.studio.domain.RunId
import dev.pipelinek.studio.domain.RunState
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Falsifiers for the SFW-H4.4
 * [ReconcilerUseCaseImpl] — the use case that
 * wraps the H2.2c+3 reconciler as a use case.
 *
 * The seam under test is a hand-built
 * [FakeRemoteReconcilerPort] + the H4.4 use
 * case. The tests pin the 4 arms of
 * [ReconcileDecision] for the use case.
 */
@DisplayName("ReconcilerUseCaseImpl (SFW-H4.4 wrap the H2.2c+3 reconciler as a use case)")
class ReconcilerUseCaseImplH44Test {

    private val studioRunId = RunId("studio-run-h44-1")

    // ------------------------------------------------------------------
    // Falsifier 1: Unchanged -> ReconcileDecision.Unchanged
    // ------------------------------------------------------------------
    @Test
    fun `Unchanged maps to ReconcileDecision Unchanged`() = runBlocking {
        val port = FakeRemoteReconcilerPort().apply {
            nextDecision = RemoteReconcileDecision.Unchanged
        }
        val useCase = ReconcilerUseCaseImpl(port)

        val result = useCase.reconcile(studioRunId, RunState.QUEUED)

        assertEquals(ReconcileDecision.Unchanged, result)
    }

    // ------------------------------------------------------------------
    // Falsifier 2: Transition(newState, reason) -> ReconcileDecision.Transition
    // ------------------------------------------------------------------
    @Test
    fun `Transition maps to ReconcileDecision Transition with newState and reason`() = runBlocking {
        val port = FakeRemoteReconcilerPort().apply {
            nextDecision = RemoteReconcileDecision.Transition(
                newState = RunState.RUNNING,
                reason = "fabric observed the run started; studio had QUEUED",
            )
        }
        val useCase = ReconcilerUseCaseImpl(port)

        val result = useCase.reconcile(studioRunId, RunState.QUEUED)

        val transition = result as? ReconcileDecision.Transition
        assertTrue(transition != null, "expected ReconcileDecision.Transition, got $result")
        assertEquals(RunState.RUNNING, transition!!.newState)
        assertEquals("fabric observed the run started; studio had QUEUED", transition.reason)
    }

    // ------------------------------------------------------------------
    // Falsifier 3: Unknown -> ReconcileDecision.Unknown
    // ------------------------------------------------------------------
    @Test
    fun `Unknown maps to ReconcileDecision Unknown`() = runBlocking {
        val port = FakeRemoteReconcilerPort().apply {
            nextDecision = RemoteReconcileDecision.Unknown("no binding for studioRunId studio-run-h44-1")
        }
        val useCase = ReconcilerUseCaseImpl(port)

        val result = useCase.reconcile(studioRunId, RunState.QUEUED)

        val unknown = result as? ReconcileDecision.Unknown
        assertTrue(unknown != null, "expected ReconcileDecision.Unknown, got $result")
        assertEquals("no binding for studioRunId studio-run-h44-1", unknown!!.reason)
    }

    // ------------------------------------------------------------------
    // Falsifier 4: Refused -> ReconcileDecision.Refused
    // ------------------------------------------------------------------
    @Test
    fun `Refused maps to ReconcileDecision Refused`() = runBlocking {
        val port = FakeRemoteReconcilerPort().apply {
            nextDecision = RemoteReconcileDecision.Refused(
                reason = "transport failure on RunIndex.summaryOf: connection reset",
            )
        }
        val useCase = ReconcilerUseCaseImpl(port)

        val result = useCase.reconcile(studioRunId, RunState.QUEUED)

        val refused = result as? ReconcileDecision.Refused
        assertTrue(refused != null, "expected ReconcileDecision.Refused, got $result")
        assertTrue(
            refused!!.reason.contains("transport", ignoreCase = true),
            "Refused reason should reference transport: ${refused.reason}",
        )
    }

    // ------------------------------------------------------------------
    // Falsifier 5: unexpected exception on the remote port -> Refused
    // ------------------------------------------------------------------
    @Test
    fun `unexpected exception on the remote port maps to Refused`() = runBlocking {
        val port = FakeRemoteReconcilerPort().apply {
            throwOnReconcile = RuntimeException("fabric gRPC stub: channel closed")
        }
        val useCase = ReconcilerUseCaseImpl(port)

        val result = useCase.reconcile(studioRunId, RunState.QUEUED)

        val refused = result as? ReconcileDecision.Refused
        assertTrue(refused != null, "expected ReconcileDecision.Refused on exception, got $result")
        assertTrue(
            refused!!.reason.contains("reconcile failed", ignoreCase = true),
            "Refused reason should reference 'reconcile failed': ${refused.reason}",
        )
    }
}

/**
 * A test-only [RemoteReconcilerPort] that
 * records every call and returns
 * caller-configured answers.
 */
private class FakeRemoteReconcilerPort : RemoteReconcilerPort {
    var nextDecision: RemoteReconcileDecision = RemoteReconcileDecision.Unchanged
    var throwOnReconcile: Throwable? = null
    var reconcileCalls: Int = 0
        private set
    var lastReconcileRunId: RunId? = null
        private set
    var lastReconcileState: RunState? = null
        private set

    override suspend fun reconcile(
        studioRunId: RunId,
        persistedState: RunState,
    ): RemoteReconcileDecision {
        reconcileCalls += 1
        lastReconcileRunId = studioRunId
        lastReconcileState = persistedState
        throwOnReconcile?.let { throw it }
        return nextDecision
    }
}
