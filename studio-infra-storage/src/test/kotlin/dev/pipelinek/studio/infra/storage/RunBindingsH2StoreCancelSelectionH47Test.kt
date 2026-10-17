@file:Suppress("TooManyFunctions")

package dev.pipelinek.studio.infra.storage

import dev.pipelinek.studio.domain.ExperimentId
import dev.pipelinek.studio.domain.RunId
import dev.pipelinek.studio.domain.ScenarioId
import dev.pipelinek.studio.domain.SnapshotId
import dev.pipelinek.studio.domain.UpstreamRunId
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.sql.Connection
import java.time.Instant
import javax.sql.DataSource

/**
 * Falsifiers for the SFW-H4.7
 * `cancel_selection` field on `run_bindings`.
 *
 * The seam under test is a real SQLite
 * (V1 + V2 + V3 + V4 migrations applied
 * via Flyway) + the
 * [dev.pipelinek.studio.infra.storage.RunBindingsH2Store]
 * methods that read/write the new column.
 *
 * The tests pin the roundtrip of the
 * `cancelSelection` field:
 * 1. recordAdmission with cancelSelection
 *    = "Local" roundtrips via getBinding;
 * 2. recordAdmission with cancelSelection
 *    = "Fabric" roundtrips via getBinding;
 * 3. legacy row (cancelSelection = null)
 *    still roundtrips without crashing.
 */
@DisplayName("RunBindingsH2Store (SFW-H4.7 cancel_selection field)")
class RunBindingsH2StoreCancelSelectionH47Test {

    @TempDir
    lateinit var tmpDir: Path

    private lateinit var db: StudioDatabase
    private lateinit var store: RunBindingsH2Store

    @BeforeEach
    fun setUp() {
        val dbPath = tmpDir.resolve("h47-test.db")
        db = StudioDatabase(dbPath)  // applies V1 + V2 + V3 + V4
        // A minimal DataSource that delegates to StudioDatabase.open().
        // The H4.7 test only needs the H2.3 store's recordAdmission +
        // getBinding; the parent-row inserts use `db.open()` directly.
        val dataSource: DataSource = object : DataSource {
            override fun getConnection(): Connection = db.open()
            override fun getConnection(username: String?, password: String?): Connection = db.open()
            override fun getLogWriter(): java.io.PrintWriter? = null
            override fun setLogWriter(out: java.io.PrintWriter?) {}
            override fun setLoginTimeout(seconds: Int) {}
            override fun getLoginTimeout(): Int = 0
            override fun getParentLogger(): java.util.logging.Logger =
                java.util.logging.Logger.getLogger("test-h47")
            override fun <T : Any?> unwrap(iface: Class<T>?): T = throw UnsupportedOperationException()
            override fun isWrapperFor(iface: Class<*>?): Boolean = false
        }
        store = RunBindingsH2Store(dataSource)
    }

    @AfterEach
    fun tearDown() {
        db.close()
    }

    // ------------------------------------------------------------------
    // Falsifier 1: recordAdmission(cancelSelection = "Local") roundtrips
    // ------------------------------------------------------------------
    @Test
    fun `recordAdmission with cancelSelection = Local roundtrips via getBinding`() {
        val studioRunId = RunId("studio-h47-local-1")
        insertLocalRow(studioRunId)
        // The H2.3 store's recordAdmission takes a fabric_run_id
        // (the row must have the H2 path taken; see the V3
        // migration's comment). The H4.7 cancelSelection is
        // written alongside the fabric_run_id.
        val updated = store.recordAdmission(
            studioRunId = studioRunId,
            upstreamRunId = UpstreamRunId("fabric-h47-local-1"),
            packageDigest = "sha256:0001",
            cancelSelection = "Local",
        )
        assertEquals(true, updated, "recordAdmission must return true when the row exists")

        val record = store.getBinding(studioRunId)
        assertNotNull(record, "getBinding must return the row that was just admitted")
        assertEquals("Local", record!!.cancelSelection, "cancelSelection must roundtrip as 'Local'")
    }

    // ------------------------------------------------------------------
    // Falsifier 2: recordAdmission(cancelSelection = "Fabric") roundtrips
    // ------------------------------------------------------------------
    @Test
    fun `recordAdmission with cancelSelection = Fabric roundtrips via getBinding`() {
        val studioRunId = RunId("studio-h47-fabric-1")
        insertLocalRow(studioRunId)
        val updated = store.recordAdmission(
            studioRunId = studioRunId,
            upstreamRunId = UpstreamRunId("fabric-h47-fabric-1"),
            packageDigest = "sha256:0002",
            cancelSelection = "Fabric",
        )
        assertEquals(true, updated)

        val record = store.getBinding(studioRunId)
        assertNotNull(record)
        assertEquals("Fabric", record!!.cancelSelection, "cancelSelection must roundtrip as 'Fabric'")
    }

    // ------------------------------------------------------------------
    // Falsifier 3: legacy row (cancelSelection = null) still roundtrips
    // ------------------------------------------------------------------
    @Test
    fun `legacy row with cancelSelection = null still roundtrips without crashing`() {
        val studioRunId = RunId("studio-h47-legacy-1")
        insertLocalRow(studioRunId)
        // The legacy caller does NOT pass cancelSelection
        // (the parameter defaults to null). The V4
        // migration added the column as NULL by default;
        // a legacy row keeps working without migration
        // data.
        val updated = store.recordAdmission(
            studioRunId = studioRunId,
            upstreamRunId = UpstreamRunId("fabric-h47-legacy-1"),
            packageDigest = "sha256:0003",
        )
        assertEquals(true, updated)

        val record = store.getBinding(studioRunId)
        assertNotNull(record)
        assertNull(record!!.cancelSelection, "cancelSelection must be null for legacy rows")
    }

    /**
     * Insert a local-only `run_bindings` row for [id] (no
     * `fabric_run_id`). The H2.3 store is an UPDATE-only
     * store; the local start-run flow creates the row. The
     * `run_bindings` table has FKs to `experiments`,
     * `snapshots`, `scenarios`; the parent rows must be
     * inserted first.
     */
    private fun insertLocalRow(id: RunId) {
        db.open().use { conn ->
            conn.prepareStatement(
                "INSERT INTO experiments (experiment_id, name, created_at) VALUES (?, ?, ?)",
            ).use { ps ->
                ps.setString(1, ExperimentId("exp-h47").value)
                ps.setString(2, "h47 test experiment")
                ps.setString(3, Instant.now().toString())
                ps.executeUpdate()
            }
            conn.prepareStatement(
                "INSERT INTO snapshots (snapshot_id, experiment_id, sha256, source_utf8, creator, created_at) " +
                    "VALUES (?, ?, ?, ?, ?, ?)",
            ).use { ps ->
                ps.setString(1, SnapshotId("snap-h47").value)
                ps.setString(2, ExperimentId("exp-h47").value)
                ps.setString(3, "sha256:" + "0".repeat(64))
                ps.setBytes(4, "// h47 test".toByteArray())
                ps.setString(5, "h47-test")
                ps.setString(6, Instant.now().toString())
                ps.executeUpdate()
            }
            conn.prepareStatement(
                "INSERT INTO scenarios (scenario_id, version, environment_profile, plugin_lock_digest, created_at) " +
                    "VALUES (?, ?, ?, ?, ?)",
            ).use { ps ->
                ps.setString(1, ScenarioId("scen-h47").value)
                ps.setInt(2, 1)
                ps.setString(3, "trusted-local")
                ps.setString(4, "sha256:" + "2".repeat(64))
                ps.setString(5, Instant.now().toString())
                ps.executeUpdate()
            }
            conn.prepareStatement(
                "INSERT INTO run_bindings (studio_run_id, experiment_id, snapshot_id, scenario_id, " +
                    "runtime_version, runtime_digest, plugin_set_digest, state, created_at, updated_at) " +
                    "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            ).use { ps ->
                ps.setString(1, id.value)
                ps.setString(2, ExperimentId("exp-h47").value)
                ps.setString(3, SnapshotId("snap-h47").value)
                ps.setString(4, ScenarioId("scen-h47").value)
                ps.setString(5, "0.56.0")
                ps.setString(6, "sha256:" + "0".repeat(64))
                ps.setString(7, "sha256:" + "1".repeat(64))
                ps.setString(8, "RUNNING")
                ps.setString(9, Instant.now().toString())
                ps.setString(10, Instant.now().toString())
                ps.executeUpdate()
            }
        }
    }
}
