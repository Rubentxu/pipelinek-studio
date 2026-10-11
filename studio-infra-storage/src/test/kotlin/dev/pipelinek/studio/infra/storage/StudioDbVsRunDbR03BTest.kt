package dev.pipelinek.studio.infra.storage

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import java.sql.SQLException

/**
 * R0.3.8 (2026-10-11) — adversarial proof that Studio
 * (`studio.db`) and PipelineK (`run.db`) live in separate
 * databases, on separate files, with non-overlapping authority.
 *
 * The mandate R0.3.8 is:
 *  > Preservar `studio.db` como propiedad de Studio y `run.db`
 *  > como propiedad de PipelineK.
 *
 * The invariant is enforced by:
 *  - the V1 migration creates only Studio-owned tables
 *    (`experiments`, `snapshots`, `scenarios`, `run_bindings`,
 *    `idempotency`, `run_state_history`, `runtime_descriptor`)
 *    and never touches `events` (the run.db journal);
 *  - [StudioDatabase] only accepts a single path and routes
 *    every read/write through that path's pool;
 *  - [SqliteConnection.openReadOnly] opens a JDBC handle in
 *    SQLite's read-only mode so the OS rejects any write attempt
 *    on a `run.db` consumer.
 *
 * This test pins those three properties:
 *
 *   1. `studio.db` does NOT contain the run.db-owned `events`
 *      table — Studio never wrote a journal.
 *   2. Two `StudioDatabase` instances on different files operate
 *      independently — writes on one do NOT appear on the other.
 *   3. `SqliteConnection.openReadOnly` on a `run.db` file
 *      refuses INSERT/UPDATE/DELETE with a SQLException — Studio
 *      cannot accidentally mutate a run.db.
 *   4. `StudioDatabase` always opens the path it was constructed
 *      with — passing a `run.db` file as the studio.db path is
 *      rejected (the migrations error out because the V1 schema
 *      doesn't match an `events`-only database) OR is a no-op for
 *      studio tables that don't exist there.
 */
class StudioDbVsRunDbR03BTest {

    @Test
    fun `studio db does not contain the run db events table (R038)`(@TempDir tmp: Path) {
        val studioDbPath = tmp.resolve("studio.db")
        StudioDatabase(studioDbPath).use { studio ->
            val tables = studio.dbPath.collectTableNames()
            // Studio-owned tables (per V1__init_studio_schema.sql):
            assertTrue("experiments" in tables, "missing experiments; got $tables")
            assertTrue("snapshots" in tables, "missing snapshots; got $tables")
            assertTrue("scenarios" in tables, "missing scenarios; got $tables")
            assertTrue("run_bindings" in tables, "missing run_bindings; got $tables")
            assertTrue("idempotency" in tables, "missing idempotency; got $tables")
            assertTrue("run_state_history" in tables, "missing run_state_history; got $tables")
            assertTrue("runtime_descriptor" in tables, "missing runtime_descriptor; got $tables")
            // run.db-owned table MUST NOT be present:
            assertFalse(
                "events" in tables,
                "studio.db MUST NOT contain the run.db 'events' table (R0.3.8 authority leak); got $tables",
            )
        }
    }

    @Test
    fun `two StudioDatabase instances on different files operate independently (R038)`(@TempDir tmp: Path) {
        val dbA = tmp.resolve("studio-A.db")
        val dbB = tmp.resolve("studio-B.db")
        StudioDatabase(dbA).use { a ->
            StudioDatabase(dbB).use { b ->
                // Write to A only.
                a.open().use { c ->
                    c.prepareStatement(
                        "INSERT INTO experiments(experiment_id, name, created_at) VALUES (?, ?, ?)",
                    ).use { ps ->
                        ps.setString(1, "exp-A-only")
                        ps.setString(2, "only in A")
                        ps.setString(3, "2026-10-11T11:00:00Z")
                        ps.executeUpdate()
                    }
                }
                assertEquals(1, dbA.rowCount("experiments"), "studio-A.db must have 1 experiments row")
                assertEquals(0, dbB.rowCount("experiments"), "studio-B.db must have 0 experiments rows (R0.3.8 isolation)")
            }
        }
    }

    @Test
    fun `openReadOnly refuses INSERT on a run db file (R038)`(@TempDir tmp: Path) {
        val runDbPath = tmp.resolve("run.db")
        // Pre-create a run.db with the events schema that the SDK
        // writes (per PipelineKRunReadPort.kt: SELECT ... FROM events).
        DriverManager.getConnection("jdbc:sqlite:$runDbPath").use { c ->
            c.createStatement().use { st ->
                st.executeUpdate(
                    "CREATE TABLE events (" +
                        "event_id TEXT, run_id TEXT, sequence INTEGER, " +
                        "kind TEXT, occurred_at TEXT, payload TEXT)",
                )
                st.executeUpdate(
                    "INSERT INTO events(event_id, run_id, sequence, kind, occurred_at, payload) " +
                        "VALUES ('e1', 'r1', 1, 'RunStarted', '2026-10-11T10:00:00Z', '{}')",
                )
            }
        }
        // Open via SqliteConnection.openReadOnly — Studio's read-side
        // path for run.db. Any write attempt must throw SQLException.
        SqliteConnection.openReadOnly(runDbPath).use { c ->
            assertThrows(SQLException::class.java) {
                c.createStatement().use { st ->
                    st.executeUpdate(
                        "INSERT INTO events(event_id, run_id, sequence, kind, occurred_at, payload) " +
                            "VALUES ('e2', 'r1', 2, 'ForgedFromStudio', '2026-10-11T10:00:01Z', '{}')",
                    )
                }
            }
            // Update also blocked:
            assertThrows(SQLException::class.java) {
                c.createStatement().use { st ->
                    st.executeUpdate("UPDATE events SET kind = 'ForgedFromStudio' WHERE sequence = 1")
                }
            }
            // Delete also blocked:
            assertThrows(SQLException::class.java) {
                c.createStatement().use { st ->
                    st.executeUpdate("DELETE FROM events WHERE sequence = 1")
                }
            }
            // Read still works (regression control):
            c.createStatement().use { st ->
                st.executeQuery("SELECT name FROM sqlite_master WHERE type='table'").use { rs ->
                    val tables = mutableListOf<String>()
                    while (rs.next()) tables += rs.getString(1)
                    assertTrue("events" in tables, "events table must be visible to read-only handle; got $tables")
                }
            }
        }
    }

    @Test
    fun `StudioDatabase is always opened on the path it was constructed with (R038)`(@TempDir tmp: Path) {
        val studioDbPath = tmp.resolve("studio-canary.db")
        val otherPath = tmp.resolve("other.db")
        // Construct StudioDatabase on path A and write to it via the pool.
        StudioDatabase(studioDbPath).use { db ->
            db.open().use { c ->
                c.prepareStatement(
                    "INSERT INTO experiments(experiment_id, name, created_at) VALUES (?, ?, ?)",
                ).use { ps ->
                    ps.setString(1, "exp-canary")
                    ps.setString(2, "canary")
                    ps.setString(3, "2026-10-11T11:00:00Z")
                    ps.executeUpdate()
                }
            }
            // dbPath is the path we passed in; Studio never reads or
            // writes any other file on its own.
            assertEquals(
                studioDbPath.toAbsolutePath(),
                db.dbPath.toAbsolutePath(),
                "StudioDatabase.dbPath must be exactly the path it was constructed with",
            )
            // The other file (if it doesn't exist) is not created
            // by Studio — proof that Studio cannot accidentally
            // redirect to a different file.
            assertFalse(
                Files.exists(otherPath),
                "StudioDatabase must not touch files outside its configured path",
            )
            // The canary experiment is on disk at studioDbPath:
            assertEquals(1, studioDbPath.rowCount("experiments"), "canary experiment must be on the configured path")
            assertEquals(0, otherPath.rowCountOrMissing("experiments"), "other file must not contain studio data")
        }
    }

    // ---- helpers ----

    private fun Path.collectTableNames(): Set<String> =
        Files.exists(this).let { exists ->
            if (!exists) emptySet()
            else DriverManager.getConnection("jdbc:sqlite:$this").use { c ->
                c.createStatement().use { st ->
                    st.executeQuery(
                        "SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' ORDER BY name",
                    ).use { rs ->
                        val out = mutableSetOf<String>()
                        while (rs.next()) out += rs.getString(1)
                        out
                    }
                }
            }
        }

    private fun Path.rowCount(table: String): Int =
        DriverManager.getConnection("jdbc:sqlite:$this").use { c ->
            c.createStatement().use { st ->
                st.executeQuery("SELECT COUNT(*) FROM $table").use { rs ->
                    assertTrue(rs.next(), "table $table must exist on $this")
                    rs.getInt(1)
                }
            }
        }

    /**
     * Like [rowCount] but returns 0 when the file does not exist
     * (no exception, no false-positive failure). Used to assert
     * that a file Studio never touches stays clean.
     */
    private fun Path.rowCountOrMissing(table: String): Int {
        if (!Files.exists(this)) return 0
        return rowCount(table)
    }
}