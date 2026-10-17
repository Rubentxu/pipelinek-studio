// Error boundary: the bindings query and the state-transition write are arbitrary
// storage adapters, so a failure becomes RecoveryResult.Refused or a per-binding
// refused entry; no narrower exception type is provable at this seam.
@file:Suppress("TooManyFunctions", "TooGenericExceptionCaught")

package dev.pipelinek.studio.application.usecases

import dev.pipelinek.studio.application.ports.ReconcileDecision
import dev.pipelinek.studio.application.ports.ReconcilerUseCase
import dev.pipelinek.studio.application.ports.RecoveryBindingFailure
import dev.pipelinek.studio.application.ports.RecoveryBindingSnapshot
import dev.pipelinek.studio.application.ports.RecoveryResult
import dev.pipelinek.studio.application.ports.RecoveryTransition
import dev.pipelinek.studio.application.ports.RecoveryUseCase
import dev.pipelinek.studio.application.ports.RunBindingsQueryPort
import dev.pipelinek.studio.application.ports.StudioMetadataPort

/**
 * SFW-H4.5: the implementation of
 * [RecoveryUseCase] that loops over all
 * bindings and reconciles each one with
 * the H4.4 [ReconcilerUseCase], persisting
 * the resulting
 * [dev.pipelinek.studio.domain.RunState]
 * transitions via
 * [StudioMetadataPort.transitionRunState].
 *
 * ## The dispatch in one paragraph
 *
 * The `recoverAll()` method:
 * 1. enumerates all bindings via the
 *    [RunBindingsQueryPort];
 * 2. for each binding, calls
 *    `reconciler.reconcile(studioRunId,
 *    persistedState)` and classifies the
 *    4-arm [ReconcileDecision]:
 *    - **Unchanged** → increment the
 *      unchanged counter (no persistence);
 *    - **Transition(newState, reason)** →
 *      call
 *      `metadata.transitionRunState(studioRunId,
 *      newState)` and append a
 *      [RecoveryTransition];
 *    - **Unknown(reason)** → append a
 *      [RecoveryBindingFailure] to the
 *      unknown list (no persistence);
 *    - **Refused(reason)** → append a
 *      [RecoveryBindingFailure] to the
 *      refused list (no persistence);
 * 3. wraps unexpected exceptions on the
 *    enumeration in [RecoveryResult.Refused]
 *    (the H4.5 contract: "any unexpected
 *    exception on the recovery path is a
 *    refusal, not an unknown"). The
 *    per-binding reconcile is already
 *    wrapped by [ReconcilerUseCaseImpl].
 *    A persistence failure on a
 *    Transition is wrapped as a refused
 *    binding (the operator sees the
 *    breakdown in the PartiallyRecovered
 *    arm).
 * 4. returns one of the 4 arms of
 *    [RecoveryResult]: [Recovered] when
 *    every binding was Unchanged or
 *    Transition, [PartiallyRecovered]
 *    when some were Unknown/Refused,
 *    [NoBindings] when the store is
 *    empty, [Refused] when the
 *    enumeration itself failed.
 *
 * ## Why 4 arms (and not 3)
 *
 * The `PartiallyRecovered` arm is
 * load-bearing: a Fabric Wiring may have
 * a mix of local-only bindings (the
 * reconciler returns Refused because
 * there's no Fabric record) and Fabric
 * bindings (the reconciler returns
 * Transition or Unchanged). The operator
 * must see the breakdown — a flat
 * "Recovered" would hide refused
 * bindings, a flat "Refused" would hide
 * successful transitions.
 *
 * ## Why a `NoBindings` arm
 *
 * A `Recovered(0, 0, emptyList)` would
 * be ambiguous (the operator can't
 * tell "nothing to do" from "tried but
 * all Unchanged"). The HTTP route
 * renders `NoBindings` as 200 with an
 * empty body, `Recovered` as 200 with
 * a transition list, and
 * `PartiallyRecovered` as 207 (multi-
 * status) so the operator can
 * distinguish them at a glance.
 *
 * @param bindingsQuery the abstract
 *   port that enumerates the bindings
 *   eligible for reconciliation.
 *   Production: the
 *   [dev.pipelinek.studio.infra.storage.RunBindingsQueryAdapter].
 * @param reconciler the H4.4
 *   [ReconcilerUseCase] that returns the
 *   per-binding [ReconcileDecision].
 * @param metadata the [StudioMetadataPort]
 *   the use case delegates to for the
 *   `transitionRunState` write.
 */
class RecoveryUseCaseImpl(
    private val bindingsQuery: RunBindingsQueryPort,
    private val reconciler: ReconcilerUseCase,
    private val metadata: StudioMetadataPort,
) : RecoveryUseCase {

    override suspend fun recoverAll(): RecoveryResult {
        val bindings: List<RecoveryBindingSnapshot> = try {
            bindingsQuery.listAllForRecovery()
        } catch (failure: Exception) {
            // The H4.5 contract: any unexpected exception
            // on the recovery path (the store itself
            // failed) is a refusal, not an unknown. The
            // per-binding reconcile is already wrapped by
            // ReconcilerUseCaseImpl; the persistence
            // failure on a transition is wrapped below.
            return RecoveryResult.Refused(
                reason = "recovery enumeration failed: " +
                    "${failure.javaClass.simpleName}: ${failure.message}",
            )
        }
        if (bindings.isEmpty()) {
            return RecoveryResult.NoBindings
        }
        val transitioned = mutableListOf<RecoveryTransition>()
        val unknown = mutableListOf<RecoveryBindingFailure>()
        val refused = mutableListOf<RecoveryBindingFailure>()
        var unchanged = 0
        for (binding in bindings) {
            when (val decision = reconciler.reconcile(binding.studioRunId, binding.state)) {
                is ReconcileDecision.Unchanged -> {
                    // No-op: the reconciler decided the
                    // binding is already in sync. No
                    // persistence call.
                    unchanged += 1
                }
                is ReconcileDecision.Transition -> {
                    // The reconciler returned a new
                    // state. Persist it BEFORE adding to
                    // the result list, so a persistence
                    // failure surfaces as a refused
                    // binding (the operator sees the
                    // breakdown in the PartiallyRecovered
                    // arm).
                    try {
                        metadata.transitionRunState(binding.studioRunId, decision.newState)
                        transitioned += RecoveryTransition(
                            studioRunId = binding.studioRunId,
                            previousState = binding.state,
                            newState = decision.newState,
                            reason = decision.reason,
                        )
                    } catch (failure: Exception) {
                        refused += RecoveryBindingFailure(
                            studioRunId = binding.studioRunId,
                            reason = "transition persistence failed: " +
                                "${failure.javaClass.simpleName}: ${failure.message}",
                        )
                    }
                }
                is ReconcileDecision.Unknown -> {
                    unknown += RecoveryBindingFailure(
                        studioRunId = binding.studioRunId,
                        reason = decision.reason,
                    )
                }
                is ReconcileDecision.Refused -> {
                    refused += RecoveryBindingFailure(
                        studioRunId = binding.studioRunId,
                        reason = decision.reason,
                    )
                }
            }
        }
        return if (unknown.isEmpty() && refused.isEmpty()) {
            RecoveryResult.Recovered(
                total = bindings.size,
                unchanged = unchanged,
                transitioned = transitioned.toList(),
            )
        } else {
            RecoveryResult.PartiallyRecovered(
                total = bindings.size,
                unchanged = unchanged,
                transitioned = transitioned.toList(),
                unknown = unknown.toList(),
                refused = refused.toList(),
            )
        }
    }
}
