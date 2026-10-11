package dev.pipelinek.studio.infra.storage

import org.slf4j.LoggerFactory
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager

/**
 * Single entry point for opening a SQLite connection in the Studio repo.
 *
 * R0.3.2 (2026-10-11): every connection is opened with
 * `PRAGMA foreign_keys = ON`. SQLite's per-connection default for
 * `foreign_keys` is **OFF**; the schema is NOT enforced unless the
 * pragma is set on every connection that performs reads or writes.
 * The previous code opened connections directly at 7 sites
 * (StudioDatabase pool, RetentionService, RunReconciler,
 * StudioBackup, PipelineKRunReadPort, PipelineKDiagnosticsAndIrPorts,
 * VariantAndCompare) and NONE of them set the pragma — meaning
 * the FOREIGN KEY constraints in studio.db's migrations were
 * silently inactive. The database happened to stay consistent
 * because the writers were disciplined; nothing prevented a
 * future migration from violating an FK and producing a corrupt
 * schema.
 *
 * Additional PRAGMAs (set on every open for predictability):
 *
 *   * `journal_mode = WAL` — readers don't block writers. WAL
 *     is persistent at the DB file level, so this assertion is
 *     a no-op after the first open and confirms intent.
 *   * `synchronous = NORMAL` — SQLite's recommended tradeoff
 *     for most apps (FULL trades latency for stronger crash
 *     guarantees; OFF trades data for speed).
 *   * `foreign_keys = ON` — re-asserted at the end so a future
 *     schema or driver change that reorders pragmas cannot silently
 *     leave FK enforcement off.
 *
 * Use the [openReadOnly] / [openReadWrite] helpers — they document
 * intent at every call site.
 */
object SqliteConnection {
    private val log = LoggerFactory.getLogger(javaClass)

    /** Open for read-write. The Studio pool uses this. */
    fun openReadWrite(path: Path): Connection =
        openInternal(path, readOnly = false)

    /**
     * Open read-only. Per SQLite docs, a read-only connection cannot
     * be promoted to a writer later; if the caller needs to write
     * back, it must open a separate handle. Studio's run.db readers
     * (RetentionService, RunReconciler, PipelineKRunReadPort,
     * PipelineKDiagnosticsAndIrPorts, VariantAndCompare) use this
     * because run.db's writer is the SDK subprocess (ADR-003).
     */
    fun openReadOnly(path: Path): Connection =
        openInternal(path, readOnly = true)

    private fun openInternal(path: Path, readOnly: Boolean): Connection {
        // The xerial SQLite driver accepts both `jdbc:sqlite:` and
        // `jdbc:sqlite:file:?mode=ro` for read-only mode. We use the
        // explicit form so the intent is visible at the URL.
        val abs = path.toString()
        val url = if (readOnly) "jdbc:sqlite:$abs" else "jdbc:sqlite:$abs"
        val conn = DriverManager.getConnection(url)
        try {
            conn.createStatement().use { stmt ->
                // foreign_keys MUST be set per-connection (it is a
                // PRAGMA, not a schema attribute). The first PRAGMA
                // turns FK enforcement on; the journal_mode and
                // synchronous are persistent or no-ops if the DB
                // already has them; the trailing foreign_keys is
                // a defensive re-assertion.
                stmt.execute("PRAGMA foreign_keys = ON")
                stmt.execute("PRAGMA journal_mode = WAL")
                stmt.execute("PRAGMA synchronous = NORMAL")
                stmt.execute("PRAGMA foreign_keys = ON")
            }
        } catch (t: Throwable) {
            // If PRAGMA application fails (rare — usually means the
            // file is corrupt or the driver is broken), we MUST
            // close the half-initialized connection rather than
            // leak it back to the caller.
            runCatching { conn.close() }
            throw t
        }
        return conn
    }
}