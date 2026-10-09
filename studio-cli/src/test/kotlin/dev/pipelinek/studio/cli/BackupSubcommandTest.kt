package dev.pipelinek.studio.cli

import dev.pipelinek.studio.infra.storage.StudioDatabase
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * CLI S5 E5.2 backup / restore subcommands. Asserts the dispatcher wires the right
 * StudioBackup entrypoints and emits both human and JSON output shapes.
 */
class BackupSubcommandTest {

    private fun captureStdout(block: () -> Unit): String {
        val orig = System.out
        val baos = ByteArrayOutputStream()
        try {
            System.setOut(PrintStream(baos, true, "UTF-8"))
            block()
        } finally {
            System.setOut(orig)
        }
        return baos.toString("UTF-8")
    }

    @Test
    fun `backup subcommand writes dest file and prints summary`(@TempDir tmp: Path) {
        val db = tmp.resolve("studio.db")
        StudioDatabase(db).close()
        val dest = tmp.resolve("studio.backup.db")

        val out = captureStdout { main(arrayOf("backup", "--db", db.toString(), "--dest", dest.toString())) }
        assertTrue(Files.isRegularFile(dest), "backup should produce a file")
        assertTrue(out.contains("backup:"), "expected human summary, got: $out")
        assertTrue(out.contains("pages="), "expected pages= in summary")
        assertTrue(out.contains("bytes="), "expected bytes= in summary")
    }

    @Test
    fun `backup subcommand with --json emits JSON shape`(@TempDir tmp: Path) {
        val db = tmp.resolve("studio.db")
        StudioDatabase(db).close()
        val dest = tmp.resolve("studio.backup.db")

        val out = captureStdout {
            main(arrayOf("backup", "--db", db.toString(), "--dest", dest.toString(), "--json"))
        }
        // Lightweight JSON check — we don't pull in a JSON parser for a single test.
        assertTrue(out.contains("\"bytesCopied\""), "expected bytesCopied, got: $out")
        assertTrue(out.contains("\"sourcePages\""), "expected sourcePages, got: $out")
        assertTrue(out.contains("\"db\""), "expected db, got: $out")
        assertTrue(out.contains("\"dest\""), "expected dest, got: $out")
    }

    @Test
    fun `restore subcommand writes valid backup to destination`(@TempDir tmp: Path) {
        val db = tmp.resolve("studio.db")
        StudioDatabase(db).close()
        val backup = tmp.resolve("backup.db")
        StudioDatabase(backup).close()
        StudioBackupOnCli(tmp, db, backup)
        val dest = tmp.resolve("restored.db")

        val out = captureStdout { main(arrayOf("restore", "--from", backup.toString(), "--to", dest.toString())) }
        assertTrue(Files.isRegularFile(dest))
        assertEquals(Files.size(backup), Files.size(dest))
        assertTrue(out.contains("restore:"), "got: $out")
    }

    @Test
    fun `restore subcommand refuses non-sqlite backup`(@TempDir tmp: Path) {
        val badBackup = tmp.resolve("bad.db")
        Files.writeString(badBackup, "this is not a sqlite file")
        val dest = tmp.resolve("restored.db")
        // The dispatcher's `restore` branch throws IllegalArgumentException when the
        // integrity check fails; this propagates as a test failure. We assert the
        // observable invariant: dest must not exist, AND the throw must be a known type.
        val orig = System.err
        val errBaos = ByteArrayOutputStream()
        val thrown: Throwable? = try {
            System.setErr(PrintStream(errBaos, true, "UTF-8"))
            captureStdout { main(arrayOf("restore", "--from", badBackup.toString(), "--to", dest.toString())) }
            null
        } catch (t: Throwable) {
            t
        } finally {
            System.setErr(orig)
        }
        assertTrue(!Files.isRegularFile(dest), "restore should not have written a non-sqlite file")
        assertTrue(thrown is IllegalArgumentException, "expected IllegalArgumentException, got $thrown")
    }

    /** Calls StudioBackup.backup() directly to produce a valid backup file for the restore test. */
    private fun StudioBackupOnCli(@Suppress("UNUSED_PARAMETER") tmp: Path, db: Path, backup: Path) {
        // We don't import StudioBackup here to keep the test pure-CLI; instead re-use the
        // same dispatch by invoking the backup subcommand and capturing stdout.
        val out = captureStdout { main(arrayOf("backup", "--db", db.toString(), "--dest", backup.toString())) }
        assertTrue(out.contains("backup:") || out.contains("bytesCopied"))
    }
}
