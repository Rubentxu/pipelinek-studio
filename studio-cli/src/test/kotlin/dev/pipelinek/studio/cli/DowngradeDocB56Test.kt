package dev.pipelinek.studio.cli

import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertTrue

/**
 * B5.6 (2026-10-10): downgrade path
 * documentation contract.
 *
 * The B5.0+ versions write a
 * `studio_schema_version` row to the
 * SQLite database as part of every Flyway
 * migration. Once the schema is past a
 * version boundary, a downgrade to an
 * older CLI is NOT safe in place —
 * Flyway refuses to roll the schema
 * back, and the older CLI sees a newer
 * schema and refuses to start.
 *
 * The supported downgrade flow is
 * "fresh data dir + restore snapshot
 * from before the upgrade". The
 * procedure is operator-facing and lives
 * in TROUBLESHOOTING.md; the tests
 * here pin the doc is present, has the
 * expected sections, and mentions the
 * four operations an operator must run
 * to execute the downgrade.
 *
 * These are doc-level tests, not
 * functional tests. A functional
 * downgrade test would require a full
 * Flyway run + a snapshot dump + a
 * restore — out of scope for the unit
 * suite. The functional guarantee lives
 * in the B5.4 live certification
 * harness.
 */
class DowngradeDocB56Test {

    private fun projectRoot(): Path {
        var cwd: Path? = Path.of("").toAbsolutePath()
        repeat(6) {
            val candidate = cwd!!.resolve("settings.gradle.kts")
            if (Files.isRegularFile(candidate)) return cwd
            cwd = cwd.parent
        }
        throw AssertionError("settings.gradle.kts not found from cwd=${Path.of("").toAbsolutePath()}")
    }

    private fun troubleshooting(): Path =
        projectRoot().resolve("TROUBLESHOOTING.md")

    @Test
    fun `B5_6 TROUBLESHOOTING md exists and is reachable from the project root`() {
        assertTrue(
            Files.isRegularFile(troubleshooting()),
            "TROUBLESHOOTING.md must live at the project root, missing at ${troubleshooting()}",
        )
    }

    @Test
    fun `B5_6 downgrade section is present and named (B5 6)`() {
        val text = Files.readString(troubleshooting())
        assertTrue(
            "B5.6" in text,
            "TROUBLESHOOTING.md must mention the B5.6 downgrade contract",
        )
        assertTrue(
            Regex("^## .*Downgrade.*\\(B5\\.6\\)", RegexOption.MULTILINE).containsMatchIn(text),
            "TROUBLESHOOTING.md must have a top-level downgrade section tagged B5.6",
        )
    }

    @Test
    fun `B5_6 downgrade doc explains why in-place schema rollback is not supported`() {
        val text = Files.readString(troubleshooting())
        // The honest explanation: Flyway
        // is forward-only; rollback would
        // require `flyway clean` which
        // wipes the DB.
        assertTrue(
            "Flyway" in text && "forward-only" in text,
            "downgrade doc must explain that Flyway is forward-only (no in-place rollback)",
        )
        assertTrue(
            "flyway clean" in text || "wipes the database" in text,
            "downgrade doc must explain the cost of `flyway clean` (wipes the DB)",
        )
    }

    @Test
    fun `B5_6 downgrade doc lists the four required operator actions`() {
        val text = Files.readString(troubleshooting())
        // 1. snapshot the current data dir
        // 2. install the older CLI
        // 3. point it at a fresh data dir
        // 4. restore a pre-upgrade snapshot
        assertTrue(
            "snapshot" in text.lowercase() && "tar" in text,
            "downgrade doc must show how to snapshot the current data dir (tar)",
        )
        assertTrue(
            "downgrade" in text.lowercase() && "install" in text.lowercase(),
            "downgrade doc must show how to install the older CLI (brew/asdf/scoop/standalone)",
        )
        assertTrue(
            "fresh data dir" in text || "fresh" in text.lowercase() && "data dir" in text,
            "downgrade doc must say to use a fresh data dir",
        )
        assertTrue(
            "restore" in text.lowercase() && "snapshot" in text.lowercase(),
            "downgrade doc must show how to restore a pre-upgrade snapshot",
        )
    }

    @Test
    fun `B5_6 downgrade doc says what NOT to do (in-place rollback is not supported)`() {
        val text = Files.readString(troubleshooting())
        // The honest negative section
        // names the cases where the
        // snapshot-restore flow is wrong
        // (mid-migration rollback).
        assertTrue(
            "When NOT to downgrade" in text || "not" in text.lowercase() && "downgrade" in text.lowercase(),
            "downgrade doc must include a 'When NOT to downgrade' section",
        )
    }

    @Test
    fun `B5_6 downgrade doc references the snapshot CLI commands the operator must run`() {
        val text = Files.readString(troubleshooting())
        // The CLI commands the operator
        // must type. The exact names come
        // from the snapshot feature (B1.x).
        assertTrue(
            "studio stop" in text,
            "downgrade doc must say `studio stop` first (release the lock + WAL)",
        )
        assertTrue(
            "studio snapshot" in text,
            "downgrade doc must reference the `studio snapshot ...` subcommands",
        )
    }
}
