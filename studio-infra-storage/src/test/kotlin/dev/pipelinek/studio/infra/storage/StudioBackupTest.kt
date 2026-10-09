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
}
