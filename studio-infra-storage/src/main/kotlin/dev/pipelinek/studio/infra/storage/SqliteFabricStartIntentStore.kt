package dev.pipelinek.studio.infra.storage

import dev.pipelinek.studio.application.ports.FabricStartCommandId
import dev.pipelinek.studio.application.ports.FabricStartIntent
import dev.pipelinek.studio.application.ports.FabricStartIntentFingerprint
import dev.pipelinek.studio.application.ports.FabricStartIntentReservation
import dev.pipelinek.studio.application.ports.FabricStartIntentStore
import dev.pipelinek.studio.domain.IdFormat
import dev.pipelinek.studio.domain.RunId
import java.sql.Connection
import java.sql.SQLException
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

/**
 * Studio P0-03: the SQLite-backed [FabricStartIntentStore].
 *
 * ## What it backs
 *
 * `studio.db.fabric_start_intents` (V7 migration): one row per Studio
 * run, holding the Fabric `commandId` that Studio committed to use and
 * the immutable fingerprint of the request content it committed to.
 * The row is written **before** the remote `StartRun`, so a retry
 * across a crash, a recreated store, or a process restart reuses the
 * same command identity.
 *
 * ## Why the reservation is an insert-if-absent plus a read
 *
 * The race that matters is two simultaneous submissions of the same
 * Studio run. The store never does "read, then decide, then write":
 *
 *  1. the `run_bindings` row is checked on the same connection (an
 *     unbound run cannot mint an identity);
 *  2. `INSERT OR IGNORE` writes a freshly minted command id only if no
 *     row exists yet (the `studio_run_id` primary key makes the loser
 *     of the race a no-op);
 *  3. the row is read back **on the same connection** and its
 *     fingerprint is compared with the requested one.
 *
 * So the identity returned is always the persisted one — never the
 * discarded candidate — and concurrent identical reservations observe
 * the same value. A concurrent different-content reservation wins or
 * loses the insert whole: it either stores its own identity or reports
 * [FabricStartIntentReservation.Conflict]; it never half-writes.
 *
 * Each statement autocommits, so no explicit transaction is needed and
 * no lock is held across the mint. SQLite serialises the writes
 * itself; the driver's `busy_timeout` bounds a concurrent writer's
 * wait and a failure surfaces as [FabricStartIntentReservation.Failure]
 * — there is no in-memory fallback and no exception-as-success path.
 *
 * ## What it does NOT do
 *
 * It does not create the `run_bindings` row (the local start-run flow
 * owns that, as with [RunBindingsH2Store]), it does not compute the
 * request fingerprint (the submit path does), and it does not call
 * Fabric.
 *
 * @param dataSource the studio-wide DataSource (in production the same
 *   one the rest of the storage layer uses over `StudioDatabase`).
 * @param newCommandId the command-id minter. Injectable so the tests can
 *   prove the value is minted once; production uses `UUID.randomUUID()`.
 */
class SqliteFabricStartIntentStore(
    private val dataSource: DataSource,
    private val newCommandId: () -> String = { UUID.randomUUID().toString() },
) : FabricStartIntentStore {

    override fun reserve(
        studioRunId: RunId,
        requestFingerprint: String,
    ): FabricStartIntentReservation {
        val canonical = FabricStartIntentFingerprint.canonicalize(requestFingerprint)
            ?: return FabricStartIntentReservation.Failure(
                "request fingerprint is not a sha256 digest: '$requestFingerprint'",
            )
        return try {
            dataSource.connection.use { conn -> reserveOn(conn, studioRunId, canonical) }
        } catch (failure: SQLException) {
            // A storage failure is a refusal to dispatch, never a
            // success. No retry loop, no in-memory journal.
            FabricStartIntentReservation.Failure(
                "start intent reservation failed: " +
                    "${failure.javaClass.simpleName}: ${failure.message}",
            )
        }
    }

    /**
     * One reservation attempt on [conn]: refuse an unbound run, mint a
     * candidate identity, insert-if-absent, then read the surviving row back
     * on the SAME connection so the answer is always the persisted identity
     * and never the discarded candidate. Every arm fails closed: no path here
     * hands back a command id that is not stored.
     */
    private fun reserveOn(
        conn: Connection,
        studioRunId: RunId,
        canonicalFingerprint: String,
    ): FabricStartIntentReservation {
        if (!bindingExists(conn, studioRunId)) {
            return FabricStartIntentReservation.MissingBinding(studioRunId)
        }
        val candidate = newCommandId()
        if (!IdFormat.isUuidLike(candidate)) {
            return FabricStartIntentReservation.Failure(
                "minted command id is not a canonical UUID: '$candidate'",
            )
        }
        insertIfAbsent(conn, studioRunId, candidate, canonicalFingerprint)
        val stored = readRow(conn, studioRunId)
            ?: return FabricStartIntentReservation.Failure(
                "intent row for ${studioRunId.value} is missing right after the insert",
            )
        return reservationOf(stored, studioRunId, canonicalFingerprint)
    }

    /**
     * Maps the persisted row to the typed reservation. Same content: the
     * stored identity is the dispatchable answer. Different content: a
     * [FabricStartIntentReservation.Conflict] that leaves the row untouched.
     * A stored id that is not a canonical UUID is a
     * [FabricStartIntentReservation.Failure] — never a dispatchable id.
     */
    private fun reservationOf(
        stored: StoredIntent,
        studioRunId: RunId,
        canonicalFingerprint: String,
    ): FabricStartIntentReservation {
        if (stored.requestFingerprint != canonicalFingerprint) {
            return FabricStartIntentReservation.Conflict(
                "studio run ${studioRunId.value} already reserved command " +
                    "${stored.commandId} for a different request fingerprint",
            )
        }
        val commandId = runCatching { FabricStartCommandId(stored.commandId) }.getOrNull()
            ?: return FabricStartIntentReservation.Failure(
                "stored command id is not a canonical UUID: '${stored.commandId}'",
            )
        return FabricStartIntentReservation.Reserved(commandId)
    }

    override fun lookup(studioRunId: RunId): FabricStartIntent? =
        dataSource.connection.use { conn ->
            val stored = readRow(conn, studioRunId) ?: return null
            FabricStartIntent(
                studioRunId = studioRunId,
                commandId = FabricStartCommandId(stored.commandId),
                requestFingerprint = stored.requestFingerprint,
            )
        }

    // ----------------------------------------------------------------
    // SQL
    // ----------------------------------------------------------------

    /**
     * True iff the local start-run flow already created the
     * `run_bindings` row for [studioRunId]. An unbound run must not get
     * an identity, so this is an explicit refusal rather than a
     * foreign-key error.
     */
    private fun bindingExists(conn: Connection, studioRunId: RunId): Boolean =
        conn.prepareStatement(
            "SELECT 1 FROM run_bindings WHERE studio_run_id = ?",
        ).use { ps ->
            ps.setString(1, studioRunId.value)
            ps.executeQuery().use { rs -> rs.next() }
        }

    /**
     * Atomic insert-if-absent: the `studio_run_id` primary key decides
     * the winner, the loser writes nothing, and the caller reads the
     * surviving row back on this same connection.
     */
    private fun insertIfAbsent(
        conn: Connection,
        studioRunId: RunId,
        commandId: String,
        canonicalFingerprint: String,
    ) {
        conn.prepareStatement(
            "INSERT OR IGNORE INTO fabric_start_intents " +
                "(studio_run_id, command_id, request_fingerprint, created_at) " +
                "VALUES (?, ?, ?, ?)",
        ).use { ps ->
            ps.setString(1, studioRunId.value)
            ps.setString(2, commandId)
            ps.setString(3, canonicalFingerprint)
            ps.setString(4, Instant.now().toString())
            ps.executeUpdate()
        }
    }

    private fun readRow(conn: Connection, studioRunId: RunId): StoredIntent? =
        conn.prepareStatement(
            "SELECT command_id, request_fingerprint FROM fabric_start_intents " +
                "WHERE studio_run_id = ?",
        ).use { ps ->
            ps.setString(1, studioRunId.value)
            ps.executeQuery().use { rs ->
                if (rs.next()) {
                    StoredIntent(
                        commandId = rs.getString(1),
                        requestFingerprint = rs.getString(2),
                    )
                } else {
                    null
                }
            }
        }

    /** The raw column values, validated only at the typed boundary. */
    private data class StoredIntent(
        val commandId: String,
        val requestFingerprint: String,
    )
}
