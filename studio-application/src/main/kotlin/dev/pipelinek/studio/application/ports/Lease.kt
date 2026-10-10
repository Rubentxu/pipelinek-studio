package dev.pipelinek.studio.application.ports

import java.time.Instant

/**
 * AAT-SEC-010 — Workspace lease for root-destructive operations.
 *
 * Per docs/03-adrs/ADR-007-permissions-sandbox.md and
 * docs/01-architecture/DATA_MODEL_AND_PERSISTENCE.md:
 *   "Destructive ops over the workspace are protected by a
 *    lease; refusal has no destructive effect."
 *
 * The contract: a destructive operation (e.g. retention sweep,
 * future DELETE endpoints) must be authorised by a recently-
 * acquired lease. Without a valid lease, the operation is
 * refused with a typed error and no files are touched.
 *
 * Leases are short-lived (default 60 s) and bound to a
 * specific op-kind (e.g. "retention-sweep"). A lease for
 * one op-kind cannot be used to authorise a different kind.
 *
 * The registry is in-memory; on studio restart, all leases
 * are invalidated. A future persistence-backed registry can
 * implement the same interface.
 */
data class Lease(
    val token: String,
    val opKind: String,
    val issuedAt: Instant,
    val expiresAt: Instant,
) {
    fun isValid(now: Instant): Boolean = now.isBefore(expiresAt)
}

interface LeasePort {
    /**
     * Acquire a fresh lease for [opKind]. The lease is valid
     * for [ttlSeconds] from the moment of acquisition.
     */
    fun acquire(opKind: String, ttlSeconds: Long = 60): Lease

    /**
     * Validate [token] for [opKind]. Returns the lease if
     * the token is recognised, matches opKind, and has not
     * expired. Returns null otherwise — no exception.
     */
    fun validate(token: String, opKind: String): Lease?

    /**
     * Release [token]. Idempotent. Returns true if the token
     * was found and removed.
     */
    fun release(token: String): Boolean
}
