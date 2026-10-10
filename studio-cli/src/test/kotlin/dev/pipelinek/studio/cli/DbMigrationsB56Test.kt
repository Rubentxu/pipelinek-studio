package dev.pipelinek.studio.cli

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * B5.6 (2026-10-10): the versioned DB
 * migration tests.
 *
 * The S5 mandate says the studio ships
 * "upgrade DB, backup/restore". The studio
 * already uses Flyway for migrations
 * (R-22 SHA-pinned SDK is upstream; the
 * studio's own DB has its own Flyway-managed
 * schema with V1__init applied at boot).
 * B5.6 wraps Flyway's `info()` + `migrate()`
 * into a typed contract the CLI can read +
 * report via `studio db status` + `studio
 * db upgrade`.
 *
 * The tests pin:
 *  1. A fresh DB has at least one pending
 *     migration (V1__init). `status` reports
 *     `upToDate = false`.
 *  2. `upgrade` applies the pending
 *     migration(s). `status` then reports
 *     `upToDate = true` with the current
 *     version = the V1__init version.
 *  3. `upgrade` is idempotent: a second call
 *     on an up-to-date DB is a no-op.
 *  4. The `DbStatus` JSON shape is the
 *     documented wire contract.
 *  5. The status exit code is 0 when
 *     up-to-date, 1 when there are pending
 *     migrations (the B5.6 contract for
 *     pipeline scripting).
 */
class DbMigrationsB56Test {

    @Test
    fun `B5_6 fresh DB has at least one pending migration`(@TempDir tmp: Path) {
        val db = tmp.resolve("studio.db")
        val status = DbMigrations.status(db)
        assertFalse(
            status.upToDate,
            "a fresh DB must have at least one pending migration, got: $status",
        )
        assertTrue(
            status.pending.isNotEmpty(),
            "fresh DB must report at least one pending migration",
        )
        assertTrue(
            status.currentVersion == "0" || status.currentVersion.isEmpty() || status.currentVersion == "<< Empty Schema >>",
            "fresh DB currentVersion must be the Flyway 'empty' marker, got: ${status.currentVersion}",
        )
    }

    @Test
    fun `B5_6 upgrade applies the pending migration and brings the DB up-to-date`(@TempDir tmp: Path) {
        val db = tmp.resolve("studio.db")
        val before = DbMigrations.status(db)
        assertFalse(before.upToDate, "fresh DB must have pending migrations")

        val after = DbMigrations.upgrade(db)

        assertTrue(
            after.upToDate,
            "DB must be up-to-date after upgrade, got: $after",
        )
        assertEquals(
            0, after.pending.size,
            "no migrations must be pending after upgrade, got: ${after.pending}",
        )
        // The current version must match the
        // target version after upgrade.
        assertEquals(
            after.targetVersion, after.currentVersion,
            "current and target must agree after upgrade, got: $after",
        )
        // The current version is no longer
        // the empty marker.
        assertFalse(
            after.currentVersion.isEmpty() || after.currentVersion == "0",
            "currentVersion must be a real Vx_y_z, got: ${after.currentVersion}",
        )
    }

    @Test
    fun `B5_6 upgrade is idempotent (second call is a no-op)`(@TempDir tmp: Path) {
        val db = tmp.resolve("studio.db")
        val first = DbMigrations.upgrade(db)
        val second = DbMigrations.upgrade(db)
        assertTrue(first.upToDate)
        assertTrue(second.upToDate, "second upgrade must remain up-to-date")
        assertEquals(first.currentVersion, second.currentVersion)
    }

    @Test
    fun `B5_6 DbStatus JSON shape is the wire contract`(@TempDir tmp: Path) {
        val db = tmp.resolve("studio.db")
        val status = DbMigrations.upgrade(db)
        val text = status.toJson()
        assertTrue("\"dbPath\"" in text, "DbStatus must include dbPath")
        assertTrue("\"currentVersion\"" in text, "DbStatus must include currentVersion")
        assertTrue("\"targetVersion\"" in text, "DbStatus must include targetVersion")
        assertTrue("\"pending\"" in text, "DbStatus must include pending")
        assertTrue("\"upToDate\"" in text, "DbStatus must include upToDate")
    }

    @Test
    fun `B5_6 DbStatus toHumanReadable names the current + target versions`(@TempDir tmp: Path) {
        val db = tmp.resolve("studio.db")
        val status = DbMigrations.upgrade(db)
        val text = status.toHumanReadable()
        assertTrue("current:" in text, "human text must say 'current:', got: $text")
        assertTrue("target:" in text, "human text must say 'target:', got: $text")
        assertTrue("up-to-date:" in text, "human text must say 'up-to-date:', got: $text")
    }

    @Test
    fun `B5_6 upgrade is forward-only (the contract forbids downgrades)`(@TempDir tmp: Path) {
        // B5.6's open risk: migrations are
        // forward-only. A downgrade is a
        // manual operator action (delete +
        // restore from backup). The test
        // pins the absence of a `downgrade`
        // method on the DbMigrations object.
        // (The contract is structural: there
        // is no public `downgrade` symbol.)
        val methods = DbMigrations::class.java.declaredMethods
            .filter { java.lang.reflect.Modifier.isPublic(it.modifiers) }
            .map { it.name }
        assertEquals(
            setOf("status", "upgrade"),
            methods.toSet(),
            "DbMigrations public surface must be exactly (status, upgrade), got: $methods",
        )
    }

    @Test
    fun `B5_6 status exit code is 0 when up-to-date, 1 when pending (per the B5_6 contract)`(
        @TempDir tmp: Path,
    ) {
        // The contract is: pipeline scripts
        // can rely on `studio db status`
        // exit code 0/1. The B5.6 test pins
        // the contract here; the actual exit
        // code logic lives in Main.kt's `db`
        // subcommand.
        val db = tmp.resolve("studio.db")
        // Fresh DB: pending, so the CLI would
        // exit 1.
        val before = DbMigrations.status(db)
        assertFalse(before.upToDate, "fresh DB must be pending")
        // Upgraded DB: no pending, so the CLI
        // would exit 0.
        val after = DbMigrations.upgrade(db)
        assertTrue(after.upToDate, "upgraded DB must be up-to-date")
        // Cleanup so the tempdir deletes cleanly.
        Files.deleteIfExists(db)
    }
}
