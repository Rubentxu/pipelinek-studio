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
// R0.1.3 (2026-10-11): the SQLite PRAGMA foreign_keys=ON emit can
// throw any java.sql / JDBC exception (already-broken connection,
// driver mismatch, locked file). The caller treats this as
// "FK enforcement off for this connection" — a best-effort
// degradation, not a crash. TooGenericExceptionCaught is the
// correct posture here.
@Suppress("TooGenericExceptionCaught")
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
        // R0.3.8 (2026-10-11): the previous code used the same URL
        // for both branches (`jdbc:sqlite:$abs`), meaning the
        // `openReadOnly` helper opened a read-write connection —
        // a name-vs-behaviour divergence. The xerial driver
        // accepts `?mode=ro` (or equivalently `?open_mode=ro`)
        // to open the underlying SQLite handle in read-only mode.
        // We use the explicit form so the intent is visible at
        // the URL AND the OS-level reject fires on any write
        // attempt.
        val abs = path.toString()
        // The xerial JDBC URL form is `jdbc:sqlite:file:<path>?mode=ro`
        // — the `file:` prefix makes the driver parse the path
        // as a URI, after which the query string is honoured. Without
        // the prefix, the driver treats the entire post-`:` string
        // (including `?mode=ro`) as the literal filename and ends up
        // opening an in-memory empty database instead of the file
        // on disk. We confirmed this empirically in R0.3.8's
        // adversarial test.
        val url = if (readOnly) "jdbc:sqlite:file:$abs?mode=ro" else "jdbc:sqlite:$abs"
        val conn = DriverManager.getConnection(url)
        try {
            conn.createStatement().use { stmt ->
                // R0.3.8 (2026-10-11): read-only handles (`?mode=ro`)
                // cannot run `PRAGMA journal_mode = WAL` or
                // `PRAGMA synchronous = NORMAL` — those PRAGMAs
                // require write access to mutate the database file.
                // On a read-only handle the only PRAGMA that
                // matters is `foreign_keys = ON`, which is a
                // per-connection setting that only changes in-memory
                // state and works in ro mode.
                stmt.execute("PRAGMA foreign_keys = ON")
                if (!readOnly) {
                    stmt.execute("PRAGMA journal_mode = WAL")
                    stmt.execute("PRAGMA synchronous = NORMAL")
                }
                // Defensive re-assertion at the end so a future
                // refactor that reorders the PRAGMAs cannot silently
                // leave FK enforcement off.
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