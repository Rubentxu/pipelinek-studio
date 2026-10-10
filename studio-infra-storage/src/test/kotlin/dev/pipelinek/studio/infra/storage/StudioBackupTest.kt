package dev.pipelinek.studio.infra.storage

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class StudioBackupTest {

    @Test
    fun `backup produces a valid SQLite copy that contains the same tables`(@TempDir tmp: Path) {
        val db = tmp.resolve("studio.db")
        val studio = StudioDatabase(db)
        // exercise a schema migration by opening so flyway initialises
        studio.close()

        val backup = tmp.resolve("studio.backup.db")
        val outcome = StudioBackup.backup(db, backup)
        assertTrue(outcome.bytesCopied > 0, "backup should have bytes, got $outcome")
        assertTrue(Files.isRegularFile(backup), "backup file not created")

        // Verify the backup is openable as a SQLite DB with the same schema.
        val tables = mutableSetOf<String>()
        java.sql.DriverManager.getConnection("jdbc:sqlite:${backup.toAbsolutePath()}").use { c ->
            c.metaData.getTables(null, null, null, arrayOf("TABLE")).use { rs ->
                while (rs.next()) tables += rs.getString("TABLE_NAME").lowercase()
            }
        }
        assertTrue("experiments" in tables, "expected 'experiments' table in backup; got $tables")
        assertTrue("snapshots" in tables, "expected 'snapshots' table in backup")
        assertTrue("studio_schema_version" in tables, "expected schema version table")
    }

    @Test
    fun `restore validates integrity_check before writing destination`(@TempDir tmp: Path) {
        val goodSrc = tmp.resolve("good.db")
        java.sql.DriverManager.getConnection("jdbc:sqlite:$goodSrc").use { c ->
            c.createStatement().use { it.executeUpdate("CREATE TABLE foo(id INTEGER)") }
        }
        val backup = tmp.resolve("backup.db")
        StudioBackup.backup(goodSrc, backup)

        // Stand-in source bytes that are NOT a SQLite file
        val badBackup = tmp.resolve("bad.db")
        Files.writeString(badBackup, "this is not sqlite")

        val dest = tmp.resolve("dest.db")
        try {
            StudioBackup.restore(badBackup, dest)
            error("should have thrown")
        } catch (e: IllegalArgumentException) {
            val msg = e.message.orEmpty()
            val acceptable = msg.contains("integrity check failed") ||
                msg.contains("not a readable SQLite file") ||
                msg.contains("not a database")
            assertTrue(acceptable, "expected integrity failure message, got: $msg")
        }
    }

    @Test
    fun `restore writes bytes to destination when backup is valid`(@TempDir tmp: Path) {
        val goodSrc = tmp.resolve("good.db")
        java.sql.DriverManager.getConnection("jdbc:sqlite:$goodSrc").use { c ->
            c.createStatement().use { it.executeUpdate("CREATE TABLE foo(id INTEGER)") }
        }
        val backup = tmp.resolve("backup.db")
        StudioBackup.backup(goodSrc, backup)

        val dest = tmp.resolve("dest.db")
        StudioBackup.restore(backup, dest)
        assertTrue(Files.isRegularFile(dest))
        assertEquals(Files.size(backup), Files.size(dest), "restored should match backup size")
    }

    /**
     * Audit H-26 (2026-10-10): a runaway studio.db (retention
     * broken, snapshot BLOB spam) used to make the backup itself
     * unbounded. backup() now fails fast when the source is already
     * over the cap, and the partial file (if any) is deleted.
     */
    @Test
    fun `backup with a tiny cap fails fast and leaves no partial file`(@TempDir tmp: Path) {
        val src = tmp.resolve("src.db")
        java.sql.DriverManager.getConnection("jdbc:sqlite:$src").use { c ->
            c.createStatement().use { it.executeUpdate("CREATE TABLE foo(id INTEGER)") }
        }
        val dst = tmp.resolve("cap.backup.db")
        // Set a cap smaller than the page-aligned minimum so the
        // pre-flight check refuses before any IO. SQLite's smallest
        // page is 512 bytes; the header is 100 bytes, so a 1 KiB
        // DB already crosses a 10-byte cap.
        val thrown = runCatching { StudioBackup.backup(src, dst, maxBytes = 10) }.exceptionOrNull()
        assertTrue(thrown is StudioBackup.BackupTooLargeException,
            "expected BackupTooLargeException, got $thrown")
        assertEquals(10L, (thrown as StudioBackup.BackupTooLargeException).maxBytes)
        assertTrue(Files.size(src) > 10L, "test pre-condition: source must exceed the cap")
        assertTrue(!Files.exists(dst), "no partial file should remain, found $dst")
    }

    @Test
    fun `backup honors the default 1 GiB cap for normal studios`(@TempDir tmp: Path) {
        // The default cap is 1 GiB; a freshly-created tiny studio
        // must back up fine under it. This test pins the default so
        // a future "let's bump it to 10 GiB" change shows up in CI.
        val src = tmp.resolve("src.db")
        java.sql.DriverManager.getConnection("jdbc:sqlite:$src").use { c ->
            c.createStatement().use { it.executeUpdate("CREATE TABLE foo(id INTEGER)") }
        }
        val dst = tmp.resolve("default.backup.db")
        val outcome = StudioBackup.backup(src, dst)
        assertTrue(outcome.bytesCopied > 0)
        assertTrue(outcome.bytesCopied <= StudioBackup.DEFAULT_MAX_BACKUP_BYTES)
    }
}
