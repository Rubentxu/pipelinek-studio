package dev.pipelinek.studio.infra.storage

import dev.pipelinek.studio.application.ports.Lease
import dev.pipelinek.studio.application.ports.LeasePort
import java.time.Clock
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * AAT-SEC-010 — in-memory lease registry.
 *
 * The studio's lease state is held in a ConcurrentHashMap keyed
 * by token. The registry is process-local; a studio restart
 * invalidates all outstanding leases. This is the conservative
 * default: a destructive op that needs to survive a restart
 * must acquire a new lease.
 *
 * The Clock is injected so tests can advance time without
 * sleeping. The default clock is system UTC.
 */
class InMemoryLeaseRegistry(
    private val clock: Clock = Clock.systemUTC(),
) : LeasePort {
    private val leases = ConcurrentHashMap<String, Lease>()

    override fun acquire(opKind: String, ttlSeconds: Long): Lease {
        val now = clock.instant()
        val lease = Lease(
            token = UUID.randomUUID().toString(),
            opKind = opKind,
            issuedAt = now,
            expiresAt = now.plusSeconds(ttlSeconds),
        )
        leases[lease.token] = lease
        return lease
    }

    override fun validate(token: String, opKind: String): Lease? {
        val lease = leases[token] ?: return null
        // Lazy expiry: leases past their expiresAt are not
        // returned; the next acquire may overwrite the slot.
        if (!lease.isValid(clock.instant())) {
            leases.remove(token, lease)
            return null
        }
        // A lease for one op-kind does not authorise another.
        if (lease.opKind != opKind) return null
        return lease
    }

    override fun release(token: String): Boolean {
        val lease = leases.remove(token) ?: return false
        return true.also { /* no extra work */ }
    }
}
