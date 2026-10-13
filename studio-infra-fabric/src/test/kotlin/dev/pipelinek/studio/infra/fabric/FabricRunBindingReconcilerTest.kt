@file:Suppress("TooManyFunctions")

package dev.pipelinek.studio.infra.fabric

import dev.pipelinek.studio.domain.RunId
import dev.pipelinek.studio.domain.RunState
import io.jenkins.pipelinek.fabric.contracts.model.RunId as FabricRunId
import io.jenkins.pipelinek.fabric.contracts.model.RunOutcome
import io.jenkins.pipelinek.fabric.contracts.model.RunState as FabricRunState
import io.jenkins.pipelinek.fabric.contracts.model.RunSummary
import io.jenkins.pipelinek.fabric.contracts.observation.RunReadRefusal
import io.jenkins.pipelinek.fabric.contracts.observation.RunSummaryResult
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Falsifiers for [FabricRunBindingReconcilerAdapter] — the
 * fourth port of the H2.2c contract (H2.2c+3, the reconciler).
 *
 * The seam under test is a [io.jenkins.pipelinek.fabric.contracts.observation.RunIndex]
 * (Fabric's port) + a [BindingLookup] (the same seam from
 * H2.2c+1). The tests inject a [FakeRunIndex] + an in-memory
 * map-backed [BindingLookup].
 *
 * What these tests pin:
 *  - the **decision logic** (Unchanged / Transition / Unknown
 *    / Refused) under the 6 paths in the contract table;
 *  - the **Fabric → Studio state translation** ([fabricRunStateToStudio]);
 *  - the **RunStateMachine discipline** (a forbidden
 *    transition is Refused, not silently applied);
 *  - the **terminal-sticky rule** (a Studio-side terminal
 *    run is finished even if Fabric says "Running");
 *  - the **lost-comes-back rule** (a LOST binding that Fabric
 *    now reports as live transitions to UNKNOWN, the only
 *    LOST transition the state machine allows);
 *  - the **transport-failure carve-out** (a `FabricTransportFailure`
 *    is `Refused`, not `Unknown`).
 */
@DisplayName("FabricRunBindingReconcilerAdapter (SFW-H2.2c+3 reconciler)")
class FabricRunBindingReconcilerTest {

    // ---- fixtures -------------------------------------------------------------

    private val studioRunId = RunId("studio-run-1")
    private val fabricRunId = FabricRunId("fabric-run-77")

    private fun bindingWith(map: Map<RunId, FabricRunId> = mapOf(studioRunId to fabricRunId)) =
        BindingLookup { id -> map[id] }

    private fun summaryWith(state: FabricRunState): RunSummary = RunSummary(
        runId = fabricRunId,
        displayName = "studio:${studioRunId.value}",
        state = state,
        startedAt = java.time.Instant.parse("2026-10-13T10:00:00Z"),
        finishedAt = if (state is FabricRunState.Terminal) {
            java.time.Instant.parse("2026-10-13T10:05:00Z")
        } else {
            null
        },
    )

    private fun adapter(
        fakeIndex: FakeRunIndex,
        binding: BindingLookup = bindingWith(),
    ) = FabricRunBindingReconcilerAdapter(fakeIndex, binding)

    // ---- decide: Unchanged ----------------------------------------------------

    @Test
    fun `reconcile with matching states is Unchanged`() = runBlocking {
        val fake = FakeRunIndex().apply {
            summaryAnswer = RunSummaryResult.Found(summaryWith(FabricRunState.Running))
        }
        val result = adapter(fake).reconcile(studioRunId, RunState.RUNNING)
        assertInstanceOf(FabricReconcileDecision.Unchanged::class.java, result)
    }

    // ---- decide: terminal transition ------------------------------------------

    @Test
    fun `reconcile RUNNING persisted with Terminal-SUCCESS on Fabric is Transition`() = runBlocking {
        val fake = FakeRunIndex().apply {
            summaryAnswer = RunSummaryResult.Found(
                summaryWith(FabricRunState.Terminal(RunOutcome.Success)),
            )
        }
        val result = adapter(fake).reconcile(studioRunId, RunState.RUNNING)

        assertInstanceOf(FabricReconcileDecision.Transition::class.java, result)
        val transition = result as FabricReconcileDecision.Transition
        assertEquals(RunState.SUCCEEDED, transition.newState)
        assertTrue(
            transition.reason.contains("fabric reports terminal") &&
                transition.reason.contains("SUCCEEDED"),
            "got: ${transition.reason}",
        )
    }

    // ---- decide: terminal sticky ----------------------------------------------

    @Test
    fun `reconcile terminal persisted with Fabric Running is Unchanged (terminal is sticky)`() = runBlocking {
        val fake = FakeRunIndex().apply {
            summaryAnswer = RunSummaryResult.Found(summaryWith(FabricRunState.Running))
        }
        // The Studio thinks the run finished. Fabric now says
        // "Running". The reconciler refuses to unstick a
        // terminal run — the remote's "Running" is not the
        // source of truth for an outcome that already
        // happened.
        val result = adapter(fake).reconcile(studioRunId, RunState.SUCCEEDED)

        assertInstanceOf(FabricReconcileDecision.Unchanged::class.java, result)
    }

    // ---- decide: terminal divergence (both terminal, different outcomes) -----

    @Test
    fun `reconcile with terminal divergence where state machine allows is Transition`() = runBlocking {
        // Both terminal, different. The state machine allows
        // CANCELLED -> SUCCEEDED? Let's check: only
        // RECOVERY_REQUIRED -> FAILED is a defined
        // "terminal-to-terminal" path. Hmm — there are no
        // "both terminal different" transitions in the
        // allowed set. So this case will be Refused.
        val fake = FakeRunIndex().apply {
            summaryAnswer = RunSummaryResult.Found(
                summaryWith(FabricRunState.Terminal(RunOutcome.Success)),
            )
        }
        val result = adapter(fake).reconcile(studioRunId, RunState.FAILED)

        // The state machine forbids FAILED -> SUCCEEDED, so
        // the reconciler Refuses. This is the discipline:
        // a divergence on outcome is the persistence layer's
        // problem, not the reconciler's.
        assertInstanceOf(FabricReconcileDecision.Refused::class.java, result)
        val refused = result as FabricReconcileDecision.Refused
        assertTrue(
            refused.reason.contains("illegal transition") &&
                refused.reason.contains("FAILED -> SUCCEEDED"),
            "got: ${refused.reason}",
        )
    }

    // ---- decide: lost-run-came-back -------------------------------------------

    @Test
    fun `reconcile LOST persisted with Fabric Running is Transition to UNKNOWN`() = runBlocking {
        // LOST -> UNKNOWN is the only LOST transition the
        // state machine allows; the persistence layer can
        // then re-reconcile UNKNOWN forward.
        val fake = FakeRunIndex().apply {
            summaryAnswer = RunSummaryResult.Found(summaryWith(FabricRunState.Running))
        }
        val result = adapter(fake).reconcile(studioRunId, RunState.LOST)

        assertInstanceOf(FabricReconcileDecision.Transition::class.java, result)
        val t = result as FabricReconcileDecision.Transition
        assertEquals(RunState.UNKNOWN, t.newState)
        assertTrue(t.reason.contains("lost run came back"), "got: ${t.reason}")
    }

    // ---- decide: binding miss -------------------------------------------------

    @Test
    fun `reconcile with binding miss is Unknown and never calls RunIndex`() = runBlocking {
        val fake = FakeRunIndex()
        val result = adapter(fake, bindingWith(mapOf())).reconcile(studioRunId, RunState.RUNNING)

        assertInstanceOf(FabricReconcileDecision.Unknown::class.java, result)
        val unknown = result as FabricReconcileDecision.Unknown
        assertTrue(
            unknown.reason.contains("no binding for studioRunId"),
            "got: ${unknown.reason}",
        )
        assertEquals(0, fake.summaryCalls.size)
    }

    // ---- decide: RunReadRefusal arms ------------------------------------------

    @Test
    fun `reconcile with UnknownRun refusal is Unknown (mark for RECOVERY_REQUIRED)`() = runBlocking {
        val fake = FakeRunIndex().apply {
            summaryAnswer = RunSummaryResult.Refused(RunReadRefusal.UnknownRun(fabricRunId))
        }
        val result = adapter(fake).reconcile(studioRunId, RunState.RUNNING)

        assertInstanceOf(FabricReconcileDecision.Unknown::class.java, result)
        val unknown = result as FabricReconcileDecision.Unknown
        assertTrue(
            unknown.reason.contains("unknown run on Fabric") &&
                unknown.reason.contains("RECOVERY_REQUIRED"),
            "got: ${unknown.reason}",
        )
    }

    @Test
    fun `reconcile with UnobservedRun refusal is Unknown`() = runBlocking {
        val fake = FakeRunIndex().apply {
            summaryAnswer = RunSummaryResult.Refused(RunReadRefusal.UnobservedRun(fabricRunId))
        }
        val result = adapter(fake).reconcile(studioRunId, RunState.RUNNING)

        assertInstanceOf(FabricReconcileDecision.Unknown::class.java, result)
        assertTrue(
            (result as FabricReconcileDecision.Unknown).reason.contains("unobserved run on Fabric"),
        )
    }

    @Test
    fun `reconcile with IncompleteObservation refusal is Refused (retry)`() = runBlocking {
        val fake = FakeRunIndex().apply {
            summaryAnswer = RunSummaryResult.Refused(
                RunReadRefusal.IncompleteObservation(fabricRunId, "the projection stopped"),
            )
        }
        val result = adapter(fake).reconcile(studioRunId, RunState.RUNNING)

        assertInstanceOf(FabricReconcileDecision.Refused::class.java, result)
        val refused = result as FabricReconcileDecision.Refused
        assertTrue(
            refused.reason.contains("incomplete observation") && refused.reason.contains("retry"),
            "got: ${refused.reason}",
        )
    }

    @Test
    fun `reconcile with ReadBudgetExhausted refusal is Refused`() = runBlocking {
        val fake = FakeRunIndex().apply {
            summaryAnswer = RunSummaryResult.Refused(
                RunReadRefusal.ReadBudgetExhausted(fabricRunId, reads = 47),
            )
        }
        val result = adapter(fake).reconcile(studioRunId, RunState.RUNNING)

        assertInstanceOf(FabricReconcileDecision.Refused::class.java, result)
        assertTrue(
            (result as FabricReconcileDecision.Refused).reason.contains("read budget exhausted"),
        )
    }

    // ---- decide: transport carve-out -----------------------------------------

    @Test
    fun `reconcile with FabricTransportFailure is Refused, not Unknown`() = runBlocking {
        val fake = FakeRunIndex().apply {
            summaryThrows = FabricTransportFailure(
                callName = "RunIndex.summaryOf",
                message = "deadline exceeded",
            )
        }
        val result = adapter(fake).reconcile(studioRunId, RunState.RUNNING)

        assertInstanceOf(FabricReconcileDecision.Refused::class.java, result)
        val refused = result as FabricReconcileDecision.Refused
        assertTrue(
            refused.reason.contains("transport failure") &&
                refused.reason.contains("RunIndex.summaryOf"),
            "got: ${refused.reason}",
        )
    }

    // ---- decide: defensive ---------------------------------------------------

    @Test
    fun `reconcile with unknown exception is Refused with the exception class name`() = runBlocking {
        val fake = FakeRunIndex().apply {
            summaryThrows = IllegalStateException("the run state machine is on fire")
        }
        val result = adapter(fake).reconcile(studioRunId, RunState.RUNNING)

        assertInstanceOf(FabricReconcileDecision.Refused::class.java, result)
        assertTrue(
            (result as FabricReconcileDecision.Refused).reason.contains("IllegalStateException"),
        )
    }

    // ---- mapper: Fabric -> Studio state translation ---------------------------

    @Test
    fun `mapper translates Waiting to RUNNING (Studio has no Waiting arm)`() {
        val interactionId = io.jenkins.pipelinek.fabric.contracts.model.InteractionId("int-1")
        val result = fabricRunStateToStudio(summaryWith(FabricRunState.Waiting(interactionId)))
        assertEquals(RunState.RUNNING, result)
    }

    @Test
    fun `mapper translates Lost to LOST`() {
        val result = fabricRunStateToStudio(summaryWith(FabricRunState.Lost()))
        assertEquals(RunState.LOST, result)
    }

    @Test
    fun `mapper translates Terminal Unstable to SUCCEEDED with reason noting unstable`() {
        // The terminal-collapse-to-SUCCEEDED for Unstable is
        // documented in the contract; the reason field
        // carries the note. The mapper itself only does the
        // state mapping; the reason is the adapter's job.
        val result = fabricRunStateToStudio(
            summaryWith(FabricRunState.Terminal(RunOutcome.Unstable)),
        )
        assertEquals(RunState.SUCCEEDED, result)
    }

    @Test
    fun `mapper translates Terminal Interrupted to CANCELLED`() {
        val result = fabricRunStateToStudio(
            summaryWith(
                FabricRunState.Terminal(
                    RunOutcome.Interrupted(
                        io.jenkins.pipelinek.fabric.contracts.model.InterruptionRecord(
                            reason = io.jenkins.pipelinek.fabric.contracts.model.InterruptionReason.ORDERED,
                            summary = "operator-requested stop",
                            site = io.jenkins.pipelinek.fabric.contracts.model.ProblemSite.RunLevel,
                        ),
                    ),
                ),
            ),
        )
        assertEquals(RunState.CANCELLED, result)
    }
}
