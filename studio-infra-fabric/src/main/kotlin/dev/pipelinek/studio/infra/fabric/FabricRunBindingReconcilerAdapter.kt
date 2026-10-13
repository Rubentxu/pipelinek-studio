@file:Suppress("TooGenericExceptionCaught")

package dev.pipelinek.studio.infra.fabric

import dev.pipelinek.studio.domain.RunId
import dev.pipelinek.studio.domain.RunState
import dev.pipelinek.studio.domain.RunStateMachine
import io.jenkins.pipelinek.fabric.contracts.model.RunOutcome
import io.jenkins.pipelinek.fabric.contracts.model.RunSummary
import io.jenkins.pipelinek.fabric.contracts.observation.RunIndex
import io.jenkins.pipelinek.fabric.contracts.observation.RunReadRefusal
import io.jenkins.pipelinek.fabric.contracts.observation.RunSummaryResult

/**
 * The fourth port of the H2.2c contract: a reconciler that
 * compares a binding's persisted [RunState] to what Fabric
 * reports and returns a decision the persistence layer can
 * apply.
 *
 * ## What the adapter does, in one paragraph
 *
 * A Studio caller hands the adapter a [RunId] and a
 * [persistedState]. The adapter
 *
 *  1. resolves the [RunId] to a Fabric-side id via the
 *     injected [BindingLookup] (a `null` answer is the
 *     adapter's own [FabricReconcileDecision.Unknown] and
 *     no Fabric call is made);
 *  2. calls [RunIndex.summaryOf] and maps the Fabric
 *     `RunState` to a Studio [RunState] (the translation
 *     table lives in [fabricRunStateToStudio]);
 *  3. compares the mapped state to [persistedState] using
 *     the decision logic in [decide];
 *  4. checks [RunStateMachine.canTransition] and refuses
 *     illegal transitions (the discipline: the reconciler
 *     proposes; the state machine accepts or refuses);
 *  5. catches [FabricTransportFailure] and renders it as
 *     `Refused` (the carve-out, applied to the reconciler).
 *
 * @param runIndex Fabric's [RunIndex] (a
 *   [io.jenkins.pipelinek.fabric.control.client.FabricClient] in
 *   production, a fake in unit tests). Required.
 * @param bindingLookup the [RunId] → fabricRunId resolver.
 *   Required — a null return is the adapter's own `Unknown`.
 */
class FabricRunBindingReconcilerAdapter(
    private val runIndex: RunIndex,
    private val bindingLookup: BindingLookup,
) : FabricRunBindingReconcilerPort {

    override suspend fun reconcile(
        studioRunId: RunId,
        persistedState: RunState,
    ): FabricReconcileDecision {
        val fabricRunId = bindingLookup.fabricRunIdFor(studioRunId)
            ?: return FabricReconcileDecision.Unknown(
                "no binding for studioRunId ${studioRunId.value} (the run was never submitted, " +
                    "or it was submitted to a different Fabric server)",
            )
        return try {
            when (val reply = runIndex.summaryOf(fabricRunId)) {
                is RunSummaryResult.Found -> {
                    val fabricState = fabricRunStateToStudio(reply.summary)
                    decide(persistedState, fabricState, reply.summary)
                }
                is RunSummaryResult.Refused -> when (val r = reply.refusal) {
                    is RunReadRefusal.UnknownRun ->
                        FabricReconcileDecision.Unknown(
                            "unknown run on Fabric: ${r.runId.value} (the run was lost; " +
                                "mark for RECOVERY_REQUIRED)",
                        )
                    is RunReadRefusal.UnobservedRun ->
                        FabricReconcileDecision.Unknown(
                            "unobserved run on Fabric: ${r.runId.value} (admitted but not yet " +
                                "observed; the binding should not yet be RUNNING)",
                        )
                    is RunReadRefusal.IncompleteObservation ->
                        FabricReconcileDecision.Refused(
                            "incomplete observation: ${r.because} (cannot reconcile; retry)",
                        )
                    is RunReadRefusal.ReadBudgetExhausted ->
                        FabricReconcileDecision.Refused(
                            "read budget exhausted (${r.reads} reads); cannot reconcile now",
                        )
                    is RunReadRefusal.NonPositivePageSize ->
                        FabricReconcileDecision.Refused("non-positive page size: ${r.pageSize}")
                    is RunReadRefusal.UnknownCursor ->
                        FabricReconcileDecision.Refused(
                            "unknown cursor: ${r.cursor.lastRunId.value}@${r.cursor.offset}",
                        )
                    is RunReadRefusal.CursorScopeMismatch ->
                        FabricReconcileDecision.Refused("cursor scope mismatch")
                    is RunReadRefusal.CursorOutOfScope ->
                        FabricReconcileDecision.Refused(
                            "cursor out of scope: ${r.cursor.lastRunId.value} in scope " +
                                "pipelineKey=${r.scope.pipelineKey}",
                        )
                }
            }
        } catch (failure: FabricTransportFailure) {
            FabricReconcileDecision.Refused(
                "transport failure on RunIndex.summaryOf: ${failure.message}",
            )
        } catch (failure: Exception) {
            FabricReconcileDecision.Refused(
                "unexpected error on RunIndex.summaryOf: " +
                    "${failure.javaClass.simpleName}: ${failure.message}",
            )
        }
    }

    /**
     * The decision logic. Given the persisted state and the
     * Fabric state (already mapped to Studio's enum), return
     * one of the four [FabricReconcileDecision] arms.
     *
     * See the contract doc for the full table.
     */
    private fun decide(
        persisted: RunState,
        fabric: RunState,
        fabricSummary: RunSummary,
    ): FabricReconcileDecision = when {
        // 1. Same state: nothing to do.
        fabric == persisted -> FabricReconcileDecision.Unchanged

        // 2. Terminal is sticky: a Studio-side terminal run
        //    is finished even if Fabric says "Running" (a
        //    finished run that Fabric does not know about is
        //    finished, not lost — the remote's view is not the
        //    source of truth for an outcome that already
        //    happened).
        persisted.isTerminal && !fabric.isTerminal ->
            FabricReconcileDecision.Unchanged

        // 3. Lost run came back: a Studio-side LOST that Fabric
        //    now reports as live is a real transition. The
        //    state machine allows LOST -> UNKNOWN (which the
        //    persistence layer would then re-reconcile into
        //    RUNNING via UNKNOWN -> STARTING -> RUNNING) but
        //    not directly LOST -> RUNNING.
        persisted == RunState.LOST && fabric != RunState.LOST -> {
            // Try LOST -> UNKNOWN first (the only LOST
            // transition the state machine allows). If the
            // state machine refuses, return Refused.
            if (RunStateMachine.canTransition(persisted, RunState.UNKNOWN)) {
                FabricReconcileDecision.Transition(
                    newState = RunState.UNKNOWN,
                    reason = "lost run came back: fabric reports $fabric " +
                        "(state=${fabricSummary.state})",
                )
            } else {
                FabricReconcileDecision.Refused(
                    "illegal transition: $persisted -> UNKNOWN (state machine refuses)",
                )
            }
        }

        // 4. Fabric reports a new terminal outcome.
        !persisted.isTerminal && fabric.isTerminal -> {
            if (RunStateMachine.canTransition(persisted, fabric)) {
                FabricReconcileDecision.Transition(
                    newState = fabric,
                    reason = "fabric reports terminal: $fabric (state=${fabricSummary.state})",
                )
            } else {
                FabricReconcileDecision.Refused(
                    "illegal transition: $persisted -> $fabric (state machine refuses)",
                )
            }
        }

        // 5. Both terminal, different outcomes: outcome
        //    divergence. The state machine does not allow
        //    SUCCEEDED -> FAILED (and vice versa) directly;
        //    the persistence layer would have to re-record
        //    with reason. For the H2.2c+3 surface, the
        //    reconciler proposes a transition and refuses if
        //    the state machine rejects.
        persisted.isTerminal && fabric.isTerminal && persisted != fabric -> {
            if (RunStateMachine.canTransition(persisted, fabric)) {
                FabricReconcileDecision.Transition(
                    newState = fabric,
                    reason = "outcome divergence: persisted=$persisted, fabric=$fabric " +
                        "(state=${fabricSummary.state})",
                )
            } else {
                FabricReconcileDecision.Refused(
                    "illegal transition: $persisted -> $fabric (terminal divergence; " +
                        "state machine refuses)",
                )
            }
        }

        // 6. Non-terminal divergence (e.g. RUNNING -> STARTING
        //    or STARTING -> QUEUED). Allowed by the state
        //    machine only in one direction (forward in the
        //    admit chain).
        else -> {
            if (RunStateMachine.canTransition(persisted, fabric)) {
                FabricReconcileDecision.Transition(
                    newState = fabric,
                    reason = "fabric reports $fabric (state=${fabricSummary.state})",
                )
            } else {
                FabricReconcileDecision.Refused(
                    "illegal transition: $persisted -> $fabric (state machine refuses)",
                )
            }
        }
    }
}

/**
 * Map a Fabric-side [io.jenkins.pipelinek.fabric.contracts.model.RunState]
 * to a Studio-side [RunState]. The translation table is part of
 * the H2.2c+3 contract; see the contract doc for the rationale
 * on each arm.
 *
 * The mapper is exposed at file-level (not as a method on the
 * adapter) so a unit test can pin the translation table
 * directly.
 */
internal fun fabricRunStateToStudio(summary: RunSummary): RunState =
    when (val s = summary.state) {
        is io.jenkins.pipelinek.fabric.contracts.model.RunState.Queued -> RunState.QUEUED
        is io.jenkins.pipelinek.fabric.contracts.model.RunState.Starting -> RunState.STARTING
        is io.jenkins.pipelinek.fabric.contracts.model.RunState.Running -> RunState.RUNNING
        is io.jenkins.pipelinek.fabric.contracts.model.RunState.Waiting -> RunState.RUNNING
        is io.jenkins.pipelinek.fabric.contracts.model.RunState.Lost -> RunState.LOST
        is io.jenkins.pipelinek.fabric.contracts.model.RunState.Terminal -> when (val o = s.outcome) {
            is RunOutcome.Success -> RunState.SUCCEEDED
            is RunOutcome.Unstable -> RunState.SUCCEEDED
            is RunOutcome.Failure -> RunState.FAILED
            is RunOutcome.Interrupted -> RunState.CANCELLED
        }
    }
