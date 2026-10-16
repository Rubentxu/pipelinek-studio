@file:Suppress("TooGenericExceptionCaught")

package dev.pipelinek.studio.infra.fabric

import dev.pipelinek.studio.domain.RunId
import dev.pipelinek.studio.domain.RunState
import dev.pipelinek.studio.domain.RunStateMachine
import io.jenkins.pipelinek.fabric.contracts.model.RunOutcome
import io.jenkins.pipelinek.fabric.contracts.model.RunState as FabricRunState
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
 * ## What the reconciler is, structurally
 *
 * A write port is a command: caller decides what to do.
 * Read ports are queries: caller asks, port answers. The
 * reconciler is a **comparator**: caller has a piece of
 * state, port asks the remote for the truth, and returns a
 * decision. The decision is
 * [FabricReconcileDecision.Unchanged] /
 * [FabricReconcileDecision.Transition] /
 * [FabricReconcileDecision.Unknown] /
 * [FabricReconcileDecision.Refused].
 *
 * ## Why a per-binding port, not a bulk one
 *
 * H2.2c+3 reconciles one binding at a time. A `reconcileAll()`
 * is a H2.3 concern (it depends on the `BindingLookup` being
 * a list, not a function). The use case layer is the one that
 * loops; the port is the unit of work.
 *
 * ## Why the decision is a Studio-side type
 *
 * The reconciler takes a [RunState] (Studio enum) and returns
 * a [FabricReconcileDecision] (Studio sealed family). The
 * Fabric types are mapped at the seam and do not leak: a
 * Studio caller that asks "reconcile this binding" gets back
 * Studio-shaped answers, not Fabric-shaped ones.
 */
interface FabricRunBindingReconcilerPort {

    /**
     * Compare the binding's [persistedState] to what Fabric
     * reports and return a decision.
     *
     * @return [FabricReconcileDecision.Unchanged] when Fabric
     *   agrees; [FabricReconcileDecision.Transition] with the
     *   new state and a reason when Fabric reports a different
     *   state the Studio's state machine accepts;
     *   [FabricReconcileDecision.Unknown] when the binding is
     *   not in `BindingLookup` OR Fabric has no record of the
     *   run; [FabricReconcileDecision.Refused] for any other
     *   refusal (the carve-out: transport failure is a refusal,
     *   not Unknown).
     */
    suspend fun reconcile(
        studioRunId: RunId,
        persistedState: RunState,
    ): FabricReconcileDecision
}

/**
 * The result of [FabricRunBindingReconcilerPort.reconcile],
 * Studio-shaped.
 *
 * Four arms:
 * - [Unchanged] — Fabric agrees; the persistence layer
 *   does nothing.
 * - [Transition] — Fabric reports a different state, the
 *   transition is allowed by [RunStateMachine.canTransition],
 *   and the persistence layer should write the new state
 *   with the [reason].
 * - [Unknown] — the binding-lookup missed (Studio-side gap)
 *   OR Fabric has no record of the run (run was lost on the
 *   remote, or was never observed). The persistence layer
 *   should mark the binding for [RunState.RECOVERY_REQUIRED].
 * - [Refused] — the reconciler could not decide (transport
 *   failure, Fabric's `IncompleteObservation` /
 *   `ReadBudgetExhausted`, the Studio's state machine refused
 *   the proposed transition, or an unknown exception). The
 *   persistence layer leaves the binding alone.
 */
sealed interface FabricReconcileDecision {
    data object Unchanged : FabricReconcileDecision
    data class Transition(
        val newState: RunState,
        val reason: String,
    ) : FabricReconcileDecision
    data class Unknown(val reason: String) : FabricReconcileDecision
    data class Refused(val reason: String) : FabricReconcileDecision
}
