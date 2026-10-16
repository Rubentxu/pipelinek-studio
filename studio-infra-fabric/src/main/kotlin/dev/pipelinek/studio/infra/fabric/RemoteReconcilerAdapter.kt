@file:Suppress("TooManyFunctions")

package dev.pipelinek.studio.infra.fabric

import dev.pipelinek.studio.application.ports.RemoteReconcileDecision
import dev.pipelinek.studio.application.ports.RemoteReconcilerPort
import dev.pipelinek.studio.domain.RunId
import dev.pipelinek.studio.domain.RunState

/**
 * SFW-H4.4: the [RemoteReconcilerPort]
 * implementation that adapts the H2.2c+3
 * [FabricRunBindingReconcilerPort] to the
 * use-case layer's abstract port. The adapter
 * delegates `reconcile` to the H2.2c+3 adapter
 * and maps the 4-arm
 * [FabricReconcileDecision] to the 4-arm
 * [RemoteReconcileDecision] (identity mapping;
 * the `Transition.newState` is already a
 * Studio [RunState] — the H2.2c+3 adapter
 * does the Fabric-to-Studio translation via
 * `fabricRunStateToStudio`).
 */
class RemoteReconcilerAdapter(
    private val fabricReconciler: FabricRunBindingReconcilerPort,
) : RemoteReconcilerPort {

    override suspend fun reconcile(
        studioRunId: RunId,
        persistedState: RunState,
    ): RemoteReconcileDecision =
        when (val r = fabricReconciler.reconcile(studioRunId, persistedState)) {
            is FabricReconcileDecision.Unchanged -> RemoteReconcileDecision.Unchanged
            is FabricReconcileDecision.Transition ->
                RemoteReconcileDecision.Transition(
                    newState = r.newState,
                    reason = r.reason,
                )
            is FabricReconcileDecision.Unknown -> RemoteReconcileDecision.Unknown(r.reason)
            is FabricReconcileDecision.Refused -> RemoteReconcileDecision.Refused(r.reason)
        }
}
