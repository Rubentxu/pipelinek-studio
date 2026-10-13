@file:Suppress("TooManyFunctions")

package dev.pipelinek.studio.application.usecases

import dev.pipelinek.studio.application.ports.ReconcileDecision
import dev.pipelinek.studio.application.ports.ReconcilerUseCase
import dev.pipelinek.studio.application.ports.RemoteReconcileDecision
import dev.pipelinek.studio.application.ports.RemoteReconcilerPort
import dev.pipelinek.studio.domain.RunId
import dev.pipelinek.studio.domain.RunState

/**
 * SFW-H4.4: the implementation of
 * [ReconcilerUseCase] that wraps the H2.2c+3
 * [dev.pipelinek.studio.infra.fabric.FabricRunBindingReconcilerPort]
 * (adapted to the use-case layer's abstract
 * [RemoteReconcilerPort]) as a use case.
 *
 * ## The dispatch in one paragraph
 *
 * The `reconcile(studioRunId, persistedState)`
 * method delegates to the remote
 * [RemoteReconcilerPort.reconcile] (the H2.2c+3
 * reconciler adapted to the abstract port);
 * maps the 4-arm [RemoteReconcileDecision] to
 * the 4-arm [ReconcileDecision] (identity
 * mapping; the shape is the same by design);
 * wraps unexpected exceptions in
 * [ReconcileDecision.Refused] (the H4.4
 * contract: "any unexpected exception on the
 * reconcile path is a refusal, not an
 * unknown").
 *
 * ## Why a 4-arm [ReconcileDecision] (and not 3)
 *
 * The H2.2c+3 `Refused` arm is a load-bearing
 * distinction: a transport failure is a
 * refusal, not an unknown. Unifying to 4 arms
 * means the future H4.5 use case and the HTTP
 * transport do not have to special-case the
 * two backends. The local path's "unexpected
 * exception" is now an explicit
 * [ReconcileDecision.Refused] (wrapped in a
 * try/catch).
 *
 * @param remotePort the H2.2c+3 reconciler
 *   port adapted to the use-case layer's
 *   abstract [RemoteReconcilerPort].
 *   Production: the
 *   [dev.pipelinek.studio.infra.fabric.RemoteReconcilerAdapter].
 */
class ReconcilerUseCaseImpl(
    private val remotePort: RemoteReconcilerPort,
) : ReconcilerUseCase {

    override suspend fun reconcile(
        studioRunId: RunId,
        persistedState: RunState,
    ): ReconcileDecision = try {
        when (val r = remotePort.reconcile(studioRunId, persistedState)) {
            is RemoteReconcileDecision.Unchanged -> ReconcileDecision.Unchanged
            is RemoteReconcileDecision.Transition ->
                ReconcileDecision.Transition(
                    newState = r.newState,
                    reason = r.reason,
                )
            is RemoteReconcileDecision.Unknown -> ReconcileDecision.Unknown(r.reason)
            is RemoteReconcileDecision.Refused -> ReconcileDecision.Refused(r.reason)
        }
    } catch (failure: Exception) {
        // The H4.4 contract: any unexpected
        // exception on the reconcile path is a
        // refusal, not an unknown. The future
        // H4.5 HTTP route renders this as
        // `503 RECONCILE_REFUSED`.
        ReconcileDecision.Refused(
            reason = "reconcile failed: ${failure.javaClass.simpleName}: ${failure.message}",
        )
    }
}
