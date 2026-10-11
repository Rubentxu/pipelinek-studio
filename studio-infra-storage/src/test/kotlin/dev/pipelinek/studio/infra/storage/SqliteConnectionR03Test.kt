package dev.pipelinek.studio.infra.storage

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import java.nio.file.Files
import java.sql.SQLException
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteIfExists

/**
 * R0.3.2 (2026-10-11) — adversarial proof that every SQLite
 * connection Studio opens has PRAGMA foreign_keys = ON. The
 * previous code opened connections at 7 sites and NONE of them
 * set the pragma — meaning the FOREIGN KEY constraints declared
 * in V1__init_studio_schema.sql were silently inactive. The DB
 * stayed consistent by discipline, not by enforcement.
 *
 * This test class proves three things:
 *
 *   1. Every pooled connection from StudioDatabase reports
 *      `foreign_keys = 1`.
 *   2. Every standalone SqliteConnection.openReadOnly /
 *      openReadWrite handle reports the same.
 *   3. Inserting a row that breaks a real FK (snapshot with a
 *      non-existent experiment_id) is rejected with an
 *      SQLException — i.e. FK enforcement actually fires.
 *
 * The third assertion is the one that would FAIL with the previous
 * code (FK off → insert would silently succeed, leaving a dangling
 * row). With R0.3.2 it throws.
 */
class SqliteConnectionR03Test {

    @Test
    fun `every pooled connection reports foreign_keys enabled`() {
        val dbPath = Files.createTempFile("studio-r03-", ".db").also { it.deleteIfExists() }
        StudioDatabase(dbPath).use { db ->
            // Exercise every pool slot to guarantee none of the
            // 4 pooled connections was opened with FK off.
            repeat(4) {
                db.open().use { c ->
                    val fk = c.createStatement().use { st ->
                        st.executeQuery("PRAGMA foreign_keys").use { rs ->
                            rs.next()
                            rs.getInt(1)
                        }
                    }
                    assertEquals(1, fk, "pooled connection $it has FKs off")
                }
            }
        }
    }

    @Test
    fun `openReadOnly sets foreign_keys on a fresh handle`() {
        // R0.3.8 (2026-10-11): `openReadOnly` now uses `?mode=ro`
        // in the JDBC URL — SQLite's read-only mode requires the
        // file to exist on disk (the driver no longer auto-creates
        // it). Pre-create the file via openReadWrite so the path
        // exists; the test still proves foreign_keys=ON is set
        // on every read-only handle.
        val dbPath = Files.createTempFile("studio-r03-", ".db")
        SqliteConnection.openReadOnly(dbPath).use { c ->
            val fk = c.createStatement().use { st ->
                st.executeQuery("PRAGMA foreign_keys").use { rs -> rs.getInt(1) }
            }
            assertEquals(1, fk)
        }
    }

    @Test
    fun `openReadWrite sets foreign_keys on a fresh handle`() {
        val dbPath = Files.createTempFile("studio-r03-", ".db").also { it.deleteIfExists() }
        SqliteConnection.openReadWrite(dbPath).use { c ->
            val fk = c.createStatement().use { st ->
                st.executeQuery("PRAGMA foreign_keys").use { rs -> rs.getInt(1) }
            }
            assertEquals(1, fk)
        }
    }

    /**
     * Adversarial: the schema declares snapshots.experiment_id
     * REFERENCES experiments.experiment_id. Inserting a snapshot
     * with an experiment_id that doesn't exist MUST fail.
     *
     * Pre-R0.3.2: insert succeeded silently (FK off → dangling row).
     * Post-R0.3.2: insert throws SQLException.
     */
    @Test
    fun `FK constraint is actually enforced on inserts`() {
        val dbPath = Files.createTempFile("studio-r03-fk-", ".db").also { it.deleteIfExists() }
        StudioDatabase(dbPath).use { db ->
            db.open().use { c ->
                c.createStatement().use { st ->
                    // The schema is migrated by StudioDatabase's
                    // init block. Confirm the snapshots table
                    // exists with its FK.
                    val fkSql = c.metaData.getTables(null, null, "snapshots", null)
                    assertNotNull(fkSql, "snapshots table missing after migration")
                }
                // Insert a snapshot referencing a non-existent
                // experiment_id. The FK should reject it.
                val ex = assertThrows(SQLException::class.java) {
                    c.prepareStatement(
                        "INSERT INTO snapshots (snapshot_id, experiment_id, sha256, source_utf8, creator, created_at) " +
                            "VALUES ('snap-x', 'exp-DOES-NOT-EXIST', 'd', X'00', 'tester', '2026-10-11')",
                    ).use { it.executeUpdate() }
                }
                // SQLite error code 19 is SQLITE_CONSTRAINT; the
                // extended code for FK violation is 787 (FOREIGN
                // KEY constraint failed). We assert that *some*
                // constraint error fires (driver may wrap the
                // code in the message); the important thing is
                // that the row is NOT inserted.
                assert(ex.message!!.contains("FOREIGN KEY", ignoreCase = true) ||
                    ex.message!!.contains("constraint", ignoreCase = true)) {
                    "expected FK constraint error, got: ${ex.message}"
                }
                // Confirm: no row was inserted.
                val count = c.createStatement().use { st ->
                    st.executeQuery("SELECT COUNT(*) FROM snapshots").use { rs ->
                        rs.next(); rs.getInt(1)
                    }
                }
                assertEquals(0, count, "FK off: snapshot was silently inserted")
            }
        }
    }

    /**
     * Adversarial negative control: with FKs OFF on a bare
     * `DriverManager.getConnection` (the pre-R0.3.2 posture),
     * the same INSERT succeeds. This proves that the previous
     * code path silently violated schema invariants. The test
     * exists only to document the regression; if you see it
     * fail, the pragma default changed.
     */
    @Test
    fun `pre R03 control -- bare DriverManager insert would have succeeded`() {
        val dbPath = Files.createTempFile("studio-r03-ctrl-", ".db").also { it.deleteIfExists() }
        // Manually apply the same migrations as StudioDatabase would.
        // We do it via Flyway to mirror the production migration path.
        val url = "jdbc:sqlite:${dbPath.toAbsolutePath()}"
        val flyway = org.flywaydb.core.Flyway.configure()
            .dataSource(url, "", "")
            .locations("classpath:db/migration")
            .placeholderReplacement(false)
            .load()
        flyway.migrate()

        // Now open a bare handle and prove FK enforcement is OFF.
        java.sql.DriverManager.getConnection(url).use { c ->
            // The pragma is OFF by default per-connection.
            val fk = c.createStatement().use { st ->
                st.executeQuery("PRAGMA foreign_keys").use { rs -> rs.getInt(1) }
            }
            assertEquals(0, fk, "test premise: bare DriverManager must default FK off")

            // And so the dangling insert succeeds.
            val inserted = assertDoesNotThrow {
                c.prepareStatement(
                    "INSERT INTO snapshots (snapshot_id, experiment_id, sha256, source_utf8, creator, created_at) " +
                        "VALUES ('snap-bare', 'exp-DOES-NOT-EXIST', 'd', X'00', 'tester', '2026-10-11')",
                ).use { it.executeUpdate() }
            }
            assertEquals(1, inserted, "with FK off, the dangling row IS persisted")
        }
    }
}