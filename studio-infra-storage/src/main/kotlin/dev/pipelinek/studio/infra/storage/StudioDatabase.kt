package dev.pipelinek.studio.infra.storage

import org.flywaydb.core.Flyway
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Studio metadata SQLite owner.
 *
 * Per docs/01-architecture/DATA_MODEL_AND_PERSISTENCE.md §"Dos bases de datos SQLite":
 * - Studio owns `studio.db`.
 * - run.db is owned by PipelineK under controlRoot.
 * - This class NEVER opens run.db in writer mode.
 *
 * Audit H-22 (2026-10-10): the previous implementation opened a
 * new DriverManager.getConnection() per query, which costs
 * ~20-40 ms (classloader lookup, sqlite file open + lock
 * acquisition, native handle setup). Under load this dominates
 * the request latency. The fix: a small fixed-size connection
 * pool. SQLite supports concurrent reads via WAL; concurrent
 * writes are serialized by SQLite's own file lock, which the
 * JDK's xerial driver honors. A pool of 4 connections is more
 * than enough for the studio's single-tenant loopback use case
 * and cuts query latency by an order of magnitude.
 */
class StudioDatabase(
    val dbPath: Path,
    poolSize: Int = 4,
) : AutoCloseable {
    private val log = LoggerFactory.getLogger(javaClass)
    private val closed = AtomicBoolean(false)
    private val pool: ArrayBlockingQueue<Connection>

    init {
        runMigrations()
        val initial = (0 until poolSize).map { newConnection() }
        pool = ArrayBlockingQueue(poolSize, false, initial)
    }

    private fun newConnection(): Connection =
        DriverManager.getConnection("jdbc:sqlite:${dbPath.toAbsolutePath()}")

    private fun runMigrations() {
        val url = "jdbc:sqlite:${dbPath.toAbsolutePath()}"
        log.info("Migrating Studio SQLite at {}", dbPath.toAbsolutePath())
        val flyway = Flyway.configure()
            .dataSource(url, "", "")
            .locations("classpath:db/migration")
            .baselineOnMigrate(true)
            // S5 polish: our V1__init_studio_schema.sql has no SQL placeholders,
            // and Flyway 10 introduced a "environment" + "resolvers" mechanism
            // that errors out trying to resolve the legacy default `user`
            // placeholder. Disable placeholder replacement to opt out cleanly
            // — we only carry schema SQL, not templated migrations.
            .placeholderReplacement(false)
            .load()
        val result = flyway.migrate()
        log.info("Studio migrations applied: {} migrations, target={}", result.migrationsExecuted, result.targetSchemaVersion)
    }

    /**
     * Borrow a connection from the pool. The caller MUST close
     * the returned [Connection.use] block so it returns to the
     * pool. A connection that errored out is discarded (not
     * returned) and replaced with a fresh one on the next borrow.
     *
     * The returned [Connection] is actually a [PooledConnection]
     * wrapper; calling `close()` on it returns the underlying
     * connection to the pool instead of physically closing it.
     * The wrapper is transparent — every other JDBC call is
     * forwarded to the underlying connection.
     */
    fun open(): Connection {
        if (closed.get()) error("StudioDatabase is closed")
        val conn = pool.poll(5, TimeUnit.SECONDS)
            ?: error("StudioDatabase connection pool exhausted (5s timeout)")
        if (!conn.isValid(1)) {
            // Stale connection (e.g. after a long pause + WAL
            // rotation). Replace it instead of returning to the
            // pool.
            runCatching { conn.close() }
            return newConnection()
        }
        return PooledConnection(this, conn)
    }

    /**
     * Return a connection to the pool. Called from
     * [Connection.use] blocks via the [PooledConnection] wrapper.
     * Connections that errored out are NOT returned; a fresh one
     * is created on the next [open] call.
     */
    internal fun release(conn: Connection, valid: Boolean) {
        if (closed.get()) {
            runCatching { conn.close() }
            return
        }
        if (!valid) {
            runCatching { conn.close() }
            // Replace the bad connection so the pool stays full.
            runCatching { pool.offer(newConnection(), 1, TimeUnit.SECONDS) }
            return
        }
        // If the pool is full (which it should never be — borrow
        // is symmetric with release), close the extra connection
        // to avoid a leak.
        if (!pool.offer(conn)) {
            runCatching { conn.close() }
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        var conn: Connection?
        while (true) {
            conn = pool.poll() ?: break
            runCatching { conn.close() }
        }
    }

    companion object {
        fun openInMemory(): StudioDatabase {
            // Used in unit tests; Flyway supports the in-memory JDBC URL with shared cache.
            val tmp = Files.createTempFile("studio-test-", ".db")
            tmp.toFile().deleteOnExit()
            return StudioDatabase(tmp)
        }
    }
}
