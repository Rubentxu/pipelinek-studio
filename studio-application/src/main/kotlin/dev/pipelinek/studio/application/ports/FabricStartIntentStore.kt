package dev.pipelinek.studio.application.ports

import dev.pipelinek.studio.domain.IdFormat
import dev.pipelinek.studio.domain.RunId

/**
 * Studio P0-03 (Fabric integration spec §3): the durable start-intent
 * reservation port.
 *
 * ## The invariant it protects
 *
 * A Fabric `StartRun` must never be sent with a command identity that
 * was not already persisted for this Studio run. The identity (the
 * Fabric `commandId`) and the immutable fingerprint of the request
 * content are written to `studio.db` **before** the remote call, so:
 *
 *  - a retry of the same request — same process, recreated store, or a
 *    Studio restart — reuses the stored command identity instead of
 *    minting a new one;
 *  - contradictory content for the same Studio run is rejected before
 *    anything is sent;
 *  - a Studio run without a persisted binding cannot obtain an
 *    identity, so it cannot dispatch a remote effect.
 *
 * ## Purity
 *
 * This file is the application-layer contract only: typed values, an
 * interface, and a pure fingerprint canonicaliser. No JDBC, no Fabric
 * types (the Fabric `StartRunRequest` field is a plain string at the
 * adapter boundary). The SQLite implementation lives in
 * `studio-infra-storage` (`SqliteFabricStartIntentStore`).
 *
 * ## Adapter integration (production transport remains unwired)
 *
 * The Fabric submit path calls [FabricStartIntentStore.reserve] before
 * `RunControl.start` and puts
 * [FabricStartIntentReservation.Reserved.commandId] into the Fabric
 * request. Any other arm means "do not call Fabric":
 * [FabricStartIntentReservation.Conflict] and
 * [FabricStartIntentReservation.MissingBinding] are refusals of this
 * request, [FabricStartIntentReservation.Failure] is a storage failure
 * (never treated as success). There is deliberately no in-memory
 * fallback.
 */
interface FabricStartIntentStore {
    /**
     * Reserve the durable command identity for [studioRunId] against
     * the canonical [requestFingerprint] of the request content.
     *
     * Contract:
     *  - first call for a bound run: mint one command identity, persist
     *    it, return [FabricStartIntentReservation.Reserved];
     *  - retry with the same fingerprint: return the stored identity,
     *    never a new one;
     *  - retry with a different fingerprint: return
     *    [FabricStartIntentReservation.Conflict] and leave the stored
     *    identity untouched;
     *  - no `run_bindings` row for [studioRunId]: return
     *    [FabricStartIntentReservation.MissingBinding] and persist
     *    nothing;
     *  - malformed fingerprint: return [FabricStartIntentReservation.Failure]
     *    without writing; a storage failure also returns Failure, but may
     *    happen after a reservation was committed. A later retry must reuse
     *    any such reservation rather than replace it.
     *
     * Implementations must make the insert-if-absent and the read used
     * to answer atomic for concurrent callers, so two simultaneous
     * identical reservations observe one identity.
     */
    fun reserve(studioRunId: RunId, requestFingerprint: String): FabricStartIntentReservation

    /**
     * The persisted intent for [studioRunId], or `null` when no
     * identity has been reserved. A read-only diagnostic used by the
     * storage tests and by reconciliation; the submit path uses
     * [reserve] so the reservation stays atomic.
     *
     * A row whose stored command id is not a canonical UUID (a
     * corruption no writer in this repo can produce) throws
     * [IllegalArgumentException] instead of being reported as absent.
     */
    fun lookup(studioRunId: RunId): FabricStartIntent?
}

/**
 * The command identity Studio records before dispatching a Fabric
 * start. It is stored as the Fabric `commandId` string, but it is not
 * a Fabric type: the adapter converts it at the boundary.
 *
 * Validation reuses the studio's single id-shape rule,
 * [IdFormat.isUuidLike], so a stored identity is always a canonical
 * 8-4-4-4-12 UUID string (never a Studio run id, never a path, never a
 * truncated id).
 */
@JvmInline
value class FabricStartCommandId(val value: String) {
    init {
        require(IdFormat.isUuidLike(value)) {
            "FabricStartCommandId must be a canonical UUID (8-4-4-4-12 hex): $value"
        }
    }
}

/**
 * The persisted start intent: which command identity was reserved for
 * which Studio run, against which immutable content fingerprint.
 */
data class FabricStartIntent(
    val studioRunId: RunId,
    val commandId: FabricStartCommandId,
    val requestFingerprint: String,
)

/**
 * The typed result of [FabricStartIntentStore.reserve].
 *
 * Exactly one arm is returned; there is no "success by exception"
 * path. A caller that does not get [Reserved] must not call Fabric.
 */
sealed interface FabricStartIntentReservation {
    /**
     * The durable identity for this Studio run and this content. Safe
     * to send, and the same value on every retry.
     */
    data class Reserved(val commandId: FabricStartCommandId) : FabricStartIntentReservation

    /**
     * The Studio run already has a different command identity bound to
     * different request content. The stored identity is unchanged and
     * must not be replaced.
     */
    data class Conflict(val reason: String) : FabricStartIntentReservation

    /**
     * There is no persisted Studio run binding for the request, so no
     * identity may be minted (nor any remote effect dispatched).
     */
    data class MissingBinding(val studioRunId: RunId) : FabricStartIntentReservation

    /**
     * The reservation itself failed (invalid fingerprint, storage
     * error). The caller must not dispatch. A previously committed
     * reservation is retained and must be reused on a later retry.
     */
    data class Failure(val reason: String) : FabricStartIntentReservation
}

/**
 * Pure canonicalisation of the request content fingerprint.
 *
 * The fingerprint is the SHA-256 of the canonical request bytes (the
 * caller computes it; this port only validates and normalises the
 * shape). Accepted inputs, both case-sensitive lowercase hex:
 *
 *  - 64 lowercase hex characters (the `DedupRunUseCaseImpl`
 *    convention);
 *  - `sha256:` followed by 64 lowercase hex characters (the
 *    `package_digest` convention).
 *
 * The canonical stored form is always `sha256:<hex>`, so a caller that
 * switches spelling between retries still matches the same identity
 * instead of spuriously conflicting. Anything else is rejected.
 */
object FabricStartIntentFingerprint {
    private const val PREFIX = "sha256:"
    private val HEX = Regex("^[0-9a-f]{64}$")

    /** The canonical `sha256:<hex>` form of [raw], or `null` if invalid. */
    fun canonicalize(raw: String): String? {
        val trimmed = raw.trim()
        val hex = if (trimmed.startsWith(PREFIX)) trimmed.removePrefix(PREFIX) else trimmed
        if (!HEX.matches(hex)) return null
        return PREFIX + hex
    }
}
