package dev.pipelinek.studio.infra.fabric

import dev.pipelinek.studio.application.ports.FabricStartCommandId
import dev.pipelinek.studio.application.ports.FabricStartIntent
import dev.pipelinek.studio.application.ports.FabricStartIntentFingerprint
import dev.pipelinek.studio.application.ports.FabricStartIntentReservation
import dev.pipelinek.studio.application.ports.FabricStartIntentStore
import dev.pipelinek.studio.domain.RunId
import java.util.UUID

/**
 * A **test-only** [FabricStartIntentStore] that models the durable
 * journal's observable behaviour: one command identity per Studio run,
 * bound to one content fingerprint, minted once and never replaced.
 *
 * ## Why this exists and what it must never become
 *
 * The production adapter takes the journal as an injected port; a unit
 * falsifier needs an implementation that (a) remembers what it stored
 * and (b) refuses contradictions and unbound runs exactly like the
 * SQLite implementation does, without a database file per test. This
 * class is that double — and it is **never** a production default:
 * [FabricRunnerAdapter]'s `startIntentStore` parameter defaults to
 * `null`, which is fail-closed, precisely so an in-memory map can
 * never become the journal (an in-memory journal answers "no record"
 * after a restart, which is the defect P0-03 exists to remove).
 *
 * The real persistence semantics — atomic insert-if-absent, the
 * `run_bindings` precondition, `sha256:<hex>` storage — are pinned by
 * the SQLite falsifiers in
 * [dev.pipelinek.studio.infra.storage.FabricStartIntentStoreP0Test]
 * and by the real-database cases in
 * [RunBindingsH2StoreIntegrationTest]; this double only has to be
 * faithful enough to drive the adapter's own branches.
 *
 * @param boundStudioRunIds the runs that already have a persisted
 *   `run_bindings` row. `null` (the default) means "every run in this
 *   test is bound", so a test that is not about the missing-binding
 *   precondition does not have to declare it; a test that *is* about it
 *   passes `emptySet()` or a one-element set.
 * @param newCommandId the command-id minter, injectable so a test can
 *   prove the adapter sends the persisted value and not a fresh one.
 */
class FakeStartIntentStoreP0(
    private val boundStudioRunIds: Set<RunId>? = null,
    private val newCommandId: () -> String = { UUID.randomUUID().toString() },
) : FabricStartIntentStore {

    /** The journal: what a real `SELECT` would read back, in insertion order. */
    private val journal = LinkedHashMap<RunId, FabricStartIntent>()

    /** Every `reserve` argument pair, in call order. */
    val reserveCalls: MutableList<Pair<RunId, String>> = mutableListOf()

    /** If non-null, `reserve` throws this instead of answering. */
    var reserveThrows: Throwable? = null

    override fun reserve(
        studioRunId: RunId,
        requestFingerprint: String,
    ): FabricStartIntentReservation {
        reserveCalls.add(studioRunId to requestFingerprint)
        reserveThrows?.let { throw it }
        val canonical = FabricStartIntentFingerprint.canonicalize(requestFingerprint)
            ?: return FabricStartIntentReservation.Failure(
                "request fingerprint is not a sha256 digest: '$requestFingerprint'",
            )
        if (boundStudioRunIds != null && studioRunId !in boundStudioRunIds) {
            return FabricStartIntentReservation.MissingBinding(studioRunId)
        }
        val stored = journal[studioRunId]
        if (stored != null) {
            return if (stored.requestFingerprint == canonical) {
                FabricStartIntentReservation.Reserved(stored.commandId)
            } else {
                // The stored identity is NOT touched: a contradiction
                // must leave the journal byte-for-byte what it was.
                FabricStartIntentReservation.Conflict(
                    "studio run ${studioRunId.value} already reserved command " +
                        "${stored.commandId.value} for a different request fingerprint",
                )
            }
        }
        val commandId = FabricStartCommandId(newCommandId())
        journal[studioRunId] = FabricStartIntent(
            studioRunId = studioRunId,
            commandId = commandId,
            requestFingerprint = canonical,
        )
        return FabricStartIntentReservation.Reserved(commandId)
    }

    override fun lookup(studioRunId: RunId): FabricStartIntent? = journal[studioRunId]

    /** How many identities the journal holds (one per reserved Studio run). */
    val size: Int get() = journal.size
}
