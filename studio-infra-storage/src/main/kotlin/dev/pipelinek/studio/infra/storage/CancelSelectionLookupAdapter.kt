package dev.pipelinek.studio.infra.storage

import dev.pipelinek.studio.application.ports.CancelSelectionLookup
import dev.pipelinek.studio.domain.RunId

/**
 * SFW-H4.8: the SQLite-backed
 * [CancelSelectionLookup] that the H4.1
 * [dev.pipelinek.studio.application.usecases.CancelRunUseCase]
 * uses to read the H4.7
 * `run_bindings.cancel_selection` value.
 *
 * ## What it returns
 *
 * The [dev.pipelinek.studio.infra.storage.RunBindingsH2Store.RunBindingRecord.cancelSelection]
 * value (the H2.4 [dev.pipelinek.studio.application.ports.RuntimeBackend]
 * enum's `name`, e.g. `"Local"` or `"Fabric"`,
 * or `null` for a pre-H4.7 row or a missing
 * binding). When the value is `null`, the
 * H4.1 use case falls back to the H2.4
 * selector (the H4.1 honest default).
 *
 * ## Why a separate port (not reusing
 * [dev.pipelinek.studio.application.ports.RunBindingsQueryPort])
 *
 * The H4.5 ([sfw-h4-5-recovery-use-case])
 * `RunBindingsQueryPort` is a list-all port
 * (returns all bindings for reconciliation).
 * A single-binding lookup is a different
 * access pattern; a new port keeps the
 * seams narrow. The production impl is a
 * thin adapter that calls
 * [dev.pipelinek.studio.infra.storage.RunBindingsH2Store.getBinding]
 * (the same backing store as the H4.7
 * [dev.pipelinek.studio.infra.storage.RunBindingsH2Store]).
 *
 * ## Why `suspend` (not `fun`)
 *
 * The use case layer is a `suspend fun
 * cancel(...)`; a sync port would force the
 * use case to wrap the call in a `runBlocking`.
 * The `suspend` signature keeps the use case
 * flat. The SQLite read is sync under the
 * hood; the `suspend` wrapper is a no-op
 * but keeps the port type-aligned with the
 * other [dev.pipelinek.studio.application.ports]
 * ports that may be `suspend` (the
 * [dev.pipelinek.studio.application.ports.ReconcilerUseCase]
 * + [dev.pipelinek.studio.application.ports.RemoteReconcilerPort]
 * are `suspend`).
 */
class CancelSelectionLookupAdapter(
    private val bindingsStore: RunBindingsH2Store,
) : CancelSelectionLookup {

    override suspend fun cancelSelectionFor(studioRunId: RunId): String? =
        bindingsStore.getBinding(studioRunId)?.cancelSelection
}
