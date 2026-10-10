package dev.pipelinek.studio.infra.storage

import java.sql.Connection
import java.sql.SQLException

/**
 * Audit H-22 (2026-10-10): a Connection wrapper that intercepts
 * `close()` to return the connection to the [StudioDatabase] pool
 * instead of closing it. The wrapper is [AutoCloseable] in the
 * standard sense — calling `close()` from a `use { }` block
 * releases the connection back to the pool. If the caller
 * forgets to close, the connection stays out of the pool until
 * the database is shut down, which is observable as a
 * "pool exhausted" error.
 *
 * The wrapper forwards every other JDBC call to the underlying
 * connection. A connection that produced an error during its
 * borrow period is marked invalid and not returned to the pool
 * (a fresh one is created on the next borrow).
 */
internal class PooledConnection(
    private val db: StudioDatabase,
    private val delegate: Connection,
) : Connection by delegate {

    @Volatile private var closed = false
    @Volatile private var errored = false

    override fun close() {
        if (closed) return
        closed = true
        db.release(delegate, valid = !errored)
    }

    /**
     * Mark the connection as errored so it is NOT returned to the
     * pool. Callers should call this in a catch block when the
     * borrow period saw a SQLException, so the pool replaces the
     * bad connection instead of recycling a half-broken one.
     */
    fun markErrored() { errored = true }

    override fun commit() = try { delegate.commit() } catch (e: SQLException) { errored = true; throw e }
    override fun rollback() = try { delegate.rollback() } catch (e: SQLException) { errored = true; throw e }
    override fun setAutoCommit(autoCommit: Boolean) = try {
        delegate.autoCommit = autoCommit
    } catch (e: SQLException) { errored = true; throw e }
}
