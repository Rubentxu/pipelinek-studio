@file:Suppress("TooManyFunctions")

package dev.pipelinek.studio.application.usecases

import dev.pipelinek.studio.application.ports.ReconcileDecision
import dev.pipelinek.studio.application.ports.ReconcilerUseCase
import dev.pipelinek.studio.application.ports.RemoteReconcileDecision
import dev.pipelinek.studio.application.ports.RemoteReconcilerPort
import dev.pipelinek.studio.application.ports.RecoveryBindingSnapshot
import dev.pipelinek.studio.application.ports.RecoveryResult
import dev.pipelinek.studio.application.ports.RunBindingsQueryPort
import dev.pipelinek.studio.application.ports.StudionMetadataNoop
import dev.pipelinek.studio.domain.RunId
import dev.pipelinek.studio.domain.RunState
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Falsifiers for the SFW-H4.5
 * [RecoveryUseCaseImpl] — the use case
 * that loops over all bindings and
 * reconciles each one with the H4.4
 * [ReconcilerUseCase], persisting the
 * resulting [RunState] transitions.
 */
@DisplayName("RecoveryUseCaseImpl (SFW-H4.5 loop over all bindings + reconcile)")
class RecoveryUseCaseImplH45Test {

    // ------------------------------------------------------------------
    // Falsifier 1: Recovered (3 bindings, all Unchanged)
    // ------------------------------------------------------------------
    @Test
    fun `3 bindings all Unchanged maps to Recovered with total=3 unchanged=3 transitioned=0`() = runBlocking {
        val bindings = listOf(
            binding("h45-1", RunState.QUEUED),
            binding("h45-2", RunState.RUNNING),
            binding("h45-3", RunState.SUCCEEDED),
        )
        val query = FakeRunBindingsQueryPort(bindings)
        val reconciler = FakeReconcilerForH45().apply { decisions = bindings.map { ReconcileDecision.Unchanged } }
        val metadata = FakeStudioMetadataPortForH45()
        val useCase = RecoveryUseCaseImpl(query, reconciler, metadata)

        val result = useCase.recoverAll()

        val recovered = result as? RecoveryResult.Recovered
        assertTrue(recovered != null, "expected RecoveryResult.Recovered, got $result")
        assertEquals(3, recovered!!.total)
        assertEquals(3, recovered.unchanged)
        assertEquals(emptyList<Any>(), recovered.transitioned)
        assertEquals(0, metadata.transitionCalls, "Unchanged must not trigger a persistence call")
    }

    // ------------------------------------------------------------------
    // Falsifier 2: Recovered (3 bindings, 1 Unchanged + 2 Transition)
    // ------------------------------------------------------------------
    @Test
    fun `3 bindings with 2 Transition maps to Recovered with transitioned=2`() = runBlocking {
        val bindings = listOf(
            binding("h45-4", RunState.QUEUED),
            binding("h45-5", RunState.QUEUED),
            binding("h45-6", RunState.RUNNING),
        )
        val query = FakeRunBindingsQueryPort(bindings)
        val reconciler = FakeReconcilerForH45().apply {
            decisions = listOf(
                ReconcileDecision.Transition(newState = RunState.RUNNING, reason = "fabric observed started"),
                ReconcileDecision.Transition(newState = RunState.RUNNING, reason = "fabric observed started"),
                ReconcileDecision.Unchanged,
            )
        }
        val metadata = FakeStudioMetadataPortForH45()
        val useCase = RecoveryUseCaseImpl(query, reconciler, metadata)

        val result = useCase.recoverAll()

        val recovered = result as? RecoveryResult.Recovered
        assertTrue(recovered != null, "expected RecoveryResult.Recovered, got $result")
        assertEquals(3, recovered!!.total)
        assertEquals(1, recovered.unchanged)
        assertEquals(2, recovered.transitioned.size)
        assertEquals(2, metadata.transitionCalls, "Transition must trigger a persistence call per binding")
        assertEquals(RunState.RUNNING, metadata.lastTransitionState)
    }

    // ------------------------------------------------------------------
    // Falsifier 3: PartiallyRecovered (1 Transition + 1 Unknown + 1 Refused)
    // ------------------------------------------------------------------
    @Test
    fun `1 Transition + 1 Unknown + 1 Refused maps to PartiallyRecovered`() = runBlocking {
        val bindings = listOf(
            binding("h45-7", RunState.QUEUED),
            binding("h45-8", RunState.QUEUED),
            binding("h45-9", RunState.QUEUED),
        )
        val query = FakeRunBindingsQueryPort(bindings)
        val reconciler = FakeReconcilerForH45().apply {
            decisions = listOf(
                ReconcileDecision.Transition(newState = RunState.RUNNING, reason = "fabric observed started"),
                ReconcileDecision.Unknown("fabric has no binding for h45-8"),
                ReconcileDecision.Refused("transport failure on h45-9: connection reset"),
            )
        }
        val metadata = FakeStudioMetadataPortForH45()
        val useCase = RecoveryUseCaseImpl(query, reconciler, metadata)

        val result = useCase.recoverAll()

        val partial = result as? RecoveryResult.PartiallyRecovered
        assertTrue(partial != null, "expected RecoveryResult.PartiallyRecovered, got $result")
        assertEquals(3, partial!!.total)
        assertEquals(0, partial.unchanged)
        assertEquals(1, partial.transitioned.size)
        assertEquals(1, partial.unknown.size)
        assertEquals(1, partial.refused.size)
        assertEquals(RunId("h45-8"), partial.unknown[0].studioRunId)
        assertEquals(RunId("h45-9"), partial.refused[0].studioRunId)
        assertEquals(1, metadata.transitionCalls, "only the Transition must trigger a persistence call")
    }

    // ------------------------------------------------------------------
    // Falsifier 4: NoBindings (empty store)
    // ------------------------------------------------------------------
    @Test
    fun `empty bindings store maps to NoBindings`() = runBlocking {
        val query = FakeRunBindingsQueryPort(emptyList())
        val reconciler = FakeReconcilerForH45()
        val metadata = FakeStudioMetadataPortForH45()
        val useCase = RecoveryUseCaseImpl(query, reconciler, metadata)

        val result = useCase.recoverAll()

        assertEquals(RecoveryResult.NoBindings, result)
        assertEquals(0, reconciler.reconcileCalls, "reconciler must not be called when there are no bindings")
        assertEquals(0, metadata.transitionCalls)
    }

    // ------------------------------------------------------------------
    // Falsifier 5: Refused (enumeration itself failed)
    // ------------------------------------------------------------------
    @Test
    fun `enumeration failure maps to Refused without calling reconciler`() = runBlocking {
        val query = FakeRunBindingsQueryPort(emptyList()).apply {
            throwOnList = RuntimeException("SQLite database is locked")
        }
        val reconciler = FakeReconcilerForH45()
        val metadata = FakeStudioMetadataPortForH45()
        val useCase = RecoveryUseCaseImpl(query, reconciler, metadata)

        val result = useCase.recoverAll()

        val refused = result as? RecoveryResult.Refused
        assertTrue(refused != null, "expected RecoveryResult.Refused, got $result")
        assertTrue(
            refused!!.reason.contains("enumeration", ignoreCase = true),
            "Refused reason should reference enumeration: ${refused.reason}",
        )
        assertEquals(0, reconciler.reconcileCalls, "reconciler must not be called when enumeration fails")
        assertEquals(0, metadata.transitionCalls)
    }

    // ------------------------------------------------------------------
    // Falsifier 6: Transition persistence failure maps to refused
    // ------------------------------------------------------------------
    @Test
    fun `transition persistence failure maps to refused (not transition)`() = runBlocking {
        val bindings = listOf(binding("h45-10", RunState.QUEUED))
        val query = FakeRunBindingsQueryPort(bindings)
        val reconciler = FakeReconcilerForH45().apply {
            decisions = listOf(
                ReconcileDecision.Transition(newState = RunState.RUNNING, reason = "fabric observed started"),
            )
        }
        val metadata = FakeStudioMetadataPortForH45().apply {
            throwOnTransition = RuntimeException("SQLite write conflict")
        }
        val useCase = RecoveryUseCaseImpl(query, reconciler, metadata)

        val result = useCase.recoverAll()

        val partial = result as? RecoveryResult.PartiallyRecovered
        assertTrue(partial != null, "expected RecoveryResult.PartiallyRecovered, got $result")
        assertEquals(0, partial!!.transitioned.size, "a persistence failure is NOT a successful transition")
        assertEquals(1, partial.refused.size, "the persistence failure shows up in the refused list")
        assertEquals(RunId("h45-10"), partial.refused[0].studioRunId)
        assertTrue(
            partial.refused[0].reason.contains("persistence", ignoreCase = true),
            "refused reason should reference persistence: ${partial.refused[0].reason}",
        )
    }

    // ------------------------------------------------------------------
    // Falsifier 7: Reconciler.UseCase layer is used (not the remote port)
    // The RecoveryUseCaseImpl takes ReconcilerUseCase, not RemoteReconcilerPort
    // ------------------------------------------------------------------
    @Test
    fun `use case uses ReconcilerUseCase (not RemoteReconcilerPort) -- Fabric-only discipline is preserved`() = runBlocking {
        // This is a type-level falsifier: RecoveryUseCaseImpl's
        // constructor takes ReconcilerUseCase, not
        // RemoteReconcilerPort. The compilation itself is the
        // assertion. We exercise it with a simple Recovered path
        // so the test is not vacuous.
        val bindings = listOf(binding("h45-11", RunState.QUEUED))
        val query = FakeRunBindingsQueryPort(bindings)
        val reconciler: ReconcilerUseCase = ReconcilerUseCaseImpl(
            remotePort = object : RemoteReconcilerPort {
                override suspend fun reconcile(
                    studioRunId: RunId,
                    persistedState: RunState,
                ): RemoteReconcileDecision = RemoteReconcileDecision.Unchanged
            },
        )
        val metadata = FakeStudioMetadataPortForH45()
        val useCase = RecoveryUseCaseImpl(query, reconciler, metadata)

        val result = useCase.recoverAll()

        assertEquals(
            RecoveryResult.Recovered(total = 1, unchanged = 1, transitioned = emptyList()),
            result,
        )
    }
}

// --------------------------------------------------------------------
// Test fixtures
// --------------------------------------------------------------------

private fun binding(id: String, state: RunState): RecoveryBindingSnapshot =
    RecoveryBindingSnapshot(
        studioRunId = RunId(id),
        state = state,
    )

private class FakeRunBindingsQueryPort(
    private val bindings: List<RecoveryBindingSnapshot>,
) : RunBindingsQueryPort {
    var throwOnList: Throwable? = null
    var listCalls: Int = 0
        private set

    override fun listAllForRecovery(): List<RecoveryBindingSnapshot> {
        listCalls += 1
        throwOnList?.let { throw it }
        return bindings
    }
}

private class FakeReconcilerForH45 : ReconcilerUseCase {
    var decisions: List<ReconcileDecision> = emptyList()
    var reconcileCalls: Int = 0
        private set
    var lastRunId: RunId? = null
        private set
    var lastState: RunState? = null
        private set
    private var queue: ArrayDeque<ReconcileDecision> = ArrayDeque()

    override suspend fun reconcile(
        studioRunId: RunId,
        persistedState: RunState,
    ): ReconcileDecision {
        reconcileCalls += 1
        lastRunId = studioRunId
        lastState = persistedState
        if (queue.isEmpty()) {
            queue = ArrayDeque(decisions)
        }
        return queue.removeFirst()
    }
}

/**
 * A [StudionMetadataNoop] that records every
 * `transitionRunState` call. The other
 * `StudioMetadataPort` methods keep the
 * noop-drop semantics (we don't need them
 * for the H4.5 recovery tests).
 */
private class FakeStudioMetadataPortForH45 : StudionMetadataNoop() {
    var transitionCalls: Int = 0
        private set
    var lastTransitionRunId: RunId? = null
        private set
    var lastTransitionState: RunState? = null
        private set
    var throwOnTransition: Throwable? = null

    override fun transitionRunState(studioRunId: RunId, newState: RunState) {
        transitionCalls += 1
        lastTransitionRunId = studioRunId
        lastTransitionState = newState
        throwOnTransition?.let { throw it }
    }
}
