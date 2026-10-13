package dev.pipelinek.studio.infra.storage

import dev.pipelinek.studio.application.ports.DedupKeyPort
import dev.pipelinek.studio.application.ports.DedupedRunSnapshot

/**
 * SFW-H6.1: the SQLite-backed
 * [DedupKeyPort] that the H6.1
 * [dev.pipelinek.studio.application.usecases.DedupRunUseCase]
 * uses to check whether a run with the given
 * dedup key already exists.
 *
 * ## What it does
 *
 * Delegates to the H6.1
 * [dev.pipelinek.studio.infra.storage.RunBindingsH2Store.findByDedupKey]
 * method. The adapter is a thin wrapper that
 * keeps the use case layer (above the
 * hexagonal boundary) decoupled from the
 * storage layer.
 *
 * ## Why a separate adapter (not reusing an existing one)
 *
 * The H5.6
 * [dev.pipelinek.studio.infra.storage.PlacementAuditAdapter]
 * is a write-side adapter (the use case
 * writes the placement). The H6.1
 * [DedupKeyStore] is a read-side adapter
 * (the use case reads the dedup_key). A new
 * adapter keeps the seams narrow.
 */
class DedupKeyStore(
    private val bindingsStore: RunBindingsH2Store,
) : DedupKeyPort {

    override suspend fun findByDedupKey(dedupKey: String): DedupedRunSnapshot? =
        bindingsStore.findByDedupKey(dedupKey)
}
