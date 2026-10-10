package dev.pipelinek.studio.infra.storage

import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.sql.DriverManager

/**
 * S5 (Product Ready) backup / restore helper for `studio.db` per ROADMAP §"E5.2
 * Contrato versionado OpenAPI y SDK TS ... upgrade DB, backup/restore".
 *
 * SQLite is single-writer. Backup uses the SQLite Online Backup API (via xerial's
 * `NativeDB.backup(destFile, srcDbName, observer)`) which is the safe way to copy a
 * live SQLite DB without holding a write lock. Restore is a simple file replace after
 * running `PRAGMA integrity_check` on the source.
 *
 * Both operations are designed to be tested hermetically without a runner:
 * - Backup writes `<dest>` as a valid SQLite file that can be reopened.
 * - Restore copies `<src>` over `<dest>` after validating integrity_check returns "ok".
 */
object StudioBackup {

    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * Audit H-26 (2026-10-10): a runaway `studio.db` (e.g. retention
     * broken, snapshot BLOB spam) used to make the backup itself
     * unbounded — the SQLite Online Backup API would happily copy
     * every page to the destination, and the operator only learned
     * about it when the disk filled up. The cap below is the
     * defense: `backup()` refuses to start when the source is
     * already over the cap, and verifies the destination after the
     * native API returns so a partial over-cap backup never
     * silently lands on disk.
     */
    const val DEFAULT_MAX_BACKUP_BYTES: Long = 1L shl 30 // 1 GiB

    data class BackupOutcome(val bytesCopied: Long, val sourcePages: Int)

    /**
     * Returns pages copied, or throws on IO error or on the source
     * exceeding [maxBytes] (default [DEFAULT_MAX_BACKUP_BYTES]).
     *
     * @param maxBytes upper bound on the resulting backup file.
     *   When the source is already larger than the cap, the call
     *   fails fast with [BackupTooLargeException] and no partial
     *   file is written. When the source is smaller but the
     *   resulting backup is larger (it shouldn't be, but a custom
     *   page size or vacuum could shift it), the partial file is
     *   deleted and the same exception is thrown.
     */
    fun backup(
        sourceDb: Path,
        destFile: Path,
        maxBytes: Long = DEFAULT_MAX_BACKUP_BYTES,
    ): BackupOutcome {
        require(Files.isRegularFile(sourceDb)) { "not a file: $sourceDb" }
        require(maxBytes > 0) { "maxBytes must be > 0, got $maxBytes" }
        // Pre-flight: cheap early bail-out when the source is
        // already over the cap. The native API would write the
        // cap-crossing pages to disk before we could check; this
        // check refuses the call before any IO happens.
        val sourceSize = Files.size(sourceDb)
        if (sourceSize > maxBytes) {
            throw BackupTooLargeException(sourceSize, maxBytes)
        }
        Files.deleteIfExists(destFile)
        val pagesCopied = try {
            DriverManager.getConnection("jdbc:sqlite:${sourceDb.toAbsolutePath()}").use { src ->
                SQLiteBackupWriter.backupTo(src, destFile)
            }
        } catch (e: Throwable) {
            // On any native-API failure, do not leave a half-written
            // backup file in the destination path.
            try { Files.deleteIfExists(destFile) } catch (_: Exception) { /* best-effort */ }
            throw e
        }
        val bytes = Files.size(destFile)
        if (bytes > maxBytes) {
            try { Files.deleteIfExists(destFile) } catch (_: Exception) { /* best-effort */ }
            throw BackupTooLargeException(bytes, maxBytes)
        }
        log.info("studio.db backup: src={} dst={} pages={} bytes={}", sourceDb, destFile, pagesCopied, bytes)
        return BackupOutcome(bytesCopied = bytes, sourcePages = pagesCopied)
    }

    class BackupTooLargeException(
        val actualBytes: Long,
        val maxBytes: Long,
    ) : RuntimeException("studio.db backup exceeds the cap: $actualBytes bytes > $maxBytes bytes")

    /** Restores a backup to the destination, validating it's a SQLite file first. */
    fun restore(backupFile: Path, destDb: Path) {
        require(Files.isRegularFile(backupFile)) { "backup file missing: $backupFile" }
        // Validate file by opening it read-only and reading PRAGMA integrity_check. A
        // non-SQLite file (or one that fails to open as a database) raises either an
        // SQLiteException (malformed header) or a generic SQLException. We treat any
        // failure to confirm `integrity_check = "ok"` as a refusal to restore.
        val integrity = runCatching {
            DriverManager.getConnection("jdbc:sqlite:${backupFile.toAbsolutePath()}").use { c ->
                c.createStatement().use { st ->
                    st.executeQuery("PRAGMA integrity_check").use { rs ->
                        if (rs.next()) rs.getString(1) else "unknown"
                    }
                }
            }
        }.getOrElse { e ->
            throw IllegalArgumentException("backup is not a readable SQLite file: ${e.message}", e)
        }
        require(integrity == "ok") { "backup integrity check failed: $integrity" }
        Files.createDirectories(destDb.parent ?: Path.of("."))
        Files.copy(backupFile, destDb, StandardCopyOption.REPLACE_EXISTING)
        log.info("studio.db restore: backup={} dst={}", backupFile, destDb)
    }
}

/**
 * Thin adapter around xerial sqlite-jdbc 3.46.x's `NativeDB.backup(...)`.
 *
 * xerial's `JDBC4Connection` does NOT expose a `(String, OutputStream)` backup overload;
 * the lowest-level method is `getDatabase(): DB` returning `NativeDB`, which has
 * `backup(String destFileName, String srcDbName, ProgressObserver): int` and writes the
 * resulting backup to a *file path*, not a stream. We therefore let the native API write
 * to a real file and then we hand the file's bytes back to the caller via a temp file.
 *
 * Avoids taking a compile-time dependency on `org.sqlite` types in [StudioBackup] (kept
 * thin so it can be reused without dragging sqlite-jdbc into upper layers' classpaths).
 */
private object SQLiteBackupWriter {

    fun backupTo(src: java.sql.Connection, destFile: Path): Int {
        val db = src.javaClass.getMethod("getDatabase").invoke(src)
        // xerial 3.46.x nested the observer as `org.sqlite.core.DB$ProgressObserver` (it
        // moved out of the top-level `org.sqlite.core.ProgressObserver` at some point). We
        // resolve the observer by FQN at runtime so this code keeps compiling against
        // java.sql only — no compile-time dep on org.sqlite.*.
        val progressObserverClass = runCatching { Class.forName("org.sqlite.core.DB\$ProgressObserver") }
            .recoverCatching { Class.forName("org.sqlite.core.ProgressObserver") }
            .getOrElse { e ->
                throw UnsupportedOperationException("cannot load ProgressObserver: ${e.message}", e)
            }
        // xerial NativeDB.backup signature is `(String dbName, String destFile, ProgressObserver)`:
        //   native synchronized int backup(byte[] dbNameUtf8, byte[] destFileNameUtf8, ProgressObserver, ...)
        // Param order is (dbName, destFileName) — easy to flip, so we resolve by class types
        // rather than argument indices and pass explicitly.
        val dbNameType = String::class.java
        val fileNameType = String::class.java
        val backup = runCatching {
            db.javaClass.getMethod("backup", dbNameType, fileNameType, progressObserverClass)
        }.getOrElse { e ->
            val methodNames = db.javaClass.methods.filter { it.name == "backup" }.joinToString {
                it.name + "(" + it.parameterTypes.joinToString(",") { p -> p.name } + ")"
            }
            throw UnsupportedOperationException(
                "no backup(String,String,ProgressObserver) on ${db.javaClass.name}: ${e.message}; " +
                    "available: $methodNames",
                e,
            )
        }
        // Invoke with the correct argument order: (dbName="main", destFileName=..., observer=null).
        return backup.invoke(db, "main", destFile.toAbsolutePath().toString(), null) as Int
    }
}
