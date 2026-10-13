package dev.pipelinek.studio.infra.storage

import dev.pipelinek.studio.application.ports.PlacementAuditPort
import dev.pipelinek.studio.domain.RunId

/**
 * SFW-H5.6: the SQLite-backed
 * [PlacementAuditPort] that the H5.1
 * [dev.pipelinek.studio.application.usecases.ScheduleRunUseCaseImpl]
 * uses to persist the `Placed(workerId,
 * region)` decision.
 *
 * ## What it does
 *
 * Delegates to the H5.5
 * [dev.pipelinek.studio.infra.storage.RunBindingsH2Store.recordPlacement]
 * method. The adapter is a thin wrapper that
 * keeps the use case layer (above the
 * hexagonal boundary) decoupled from the
 * storage layer.
 *
 * ## Why a separate port (not reusing
 * [dev.pipelinek.studio.application.ports.CancelSelectionLookup])
 *
 * The H4.8 [dev.pipelinek.studio.application.ports.CancelSelectionLookup]
 * is a read-side port (the use case reads
 * the cancelSelection). The H5.6
 * [PlacementAuditPort] is a write-side port
 * (the use case writes the placement). A
 * new port keeps the seams narrow.
 */
class PlacementAuditAdapter(
    private val bindingsStore: RunBindingsH2Store,
) : PlacementAuditPort {

    override suspend fun recordPlacement(
        studioRunId: RunId,
        workerId: String,
        region: String,
    ) {
        bindingsStore.recordPlacement(
            studioRunId = studioRunId,
            workerId = workerId,
            region = region,
        )
    }
}
