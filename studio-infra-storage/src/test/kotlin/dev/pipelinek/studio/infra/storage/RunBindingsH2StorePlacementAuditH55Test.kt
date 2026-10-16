@file:Suppress("TooManyFunctions")

package dev.pipelinek.studio.infra.storage

import dev.pipelinek.studio.domain.ExperimentId
import dev.pipelinek.studio.domain.RunId
import dev.pipelinek.studio.domain.ScenarioId
import dev.pipelinek.studio.domain.SnapshotId
import io.jenkins.pipelinek.fabric.contracts.model.RunId as FabricRunId
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
 * Falsifiers for the SFW-H5.5
 * `placement_audit` field on `run_bindings`.
 *
 * The seam under test is a real SQLite
 * (V1..V5 migrations applied via Flyway)
 * + the
 * [dev.pipelinek.studio.infra.storage.RunBindingsH2Store]
 * methods that read/write the new column.
 *
 * The tests pin the roundtrip of the
 * `placementAudit` field:
 * 1. recordPlacement(Local worker) roundtrips
 *    via getBinding;
 * 2. recordPlacement(Fabric worker) roundtrips
 *    via getBinding;
 * 3. legacy row (placementAudit = null)
 *    still roundtrips without crashing.
 */
@DisplayName("RunBindingsH2Store (SFW-H5.5 placement_audit field)")
class RunBindingsH2StorePlacementAuditH55Test {

    @TempDir
    lateinit var tmpDir: Path

    private lateinit var db: StudioDatabase
    private lateinit var store: RunBindingsH2Store

    @BeforeEach
    fun setUp() {
        val dbPath = tmpDir.resolve("h55-test.db")
        db = StudioDatabase(dbPath)  // applies V1 + V2 + V3 + V4 + V5
        val dataSource: DataSource = object : DataSource {
            override fun getConnection(): Connection = db.open()
            override fun getConnection(username: String?, password: String?): Connection = db.open()
            override fun getLogWriter(): java.io.PrintWriter? = null
            override fun setLogWriter(out: java.io.PrintWriter?) {}
            override fun setLoginTimeout(seconds: Int) {}
            override fun getLoginTimeout(): Int = 0
            override fun getParentLogger(): java.util.logging.Logger =
                java.util.logging.Logger.getLogger("test-h55")
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
    // Falsifier 1: recordPlacement(Local worker) roundtrips
    // ------------------------------------------------------------------
    @Test
    fun `recordPlacement with Local worker roundtrips via getBinding`() {
        val studioRunId = RunId("studio-h55-local-1")
        insertLocalRow(studioRunId)
        // The H5.5 store needs a fabric_run_id (the row
        // must have the H2 path taken; see the V3
        // migration's comment). The H5.5 placementAudit
        // is written alongside the fabric_run_id via
        // recordPlacement.
        store.recordAdmission(
            studioRunId = studioRunId,
            fabricRunId = FabricRunId("fabric-h55-local-1"),
            packageDigest = "sha256:0001",
        )
        val placedAt = Instant.parse("2026-10-13T16:00:00Z")
        val updated = store.recordPlacement(
            studioRunId = studioRunId,
            workerId = "worker-local-1",
            region = "us-west-2",
            placedAt = placedAt,
        )
        assertEquals(true, updated, "recordPlacement must return true when the row exists")

        val record = store.getBinding(studioRunId)
        assertNotNull(record, "getBinding must return the row that was just placed")
        val audit = record!!.placementAudit
        assertNotNull(audit, "placementAudit must be non-null after recordPlacement")
        assertEquals("worker-local-1", audit!!.workerId)
        assertEquals("us-west-2", audit.region)
        assertEquals(placedAt, audit.placedAt)
    }

    // ------------------------------------------------------------------
    // Falsifier 2: recordPlacement(Fabric worker) roundtrips
    // ------------------------------------------------------------------
    @Test
    fun `recordPlacement with Fabric worker roundtrips via getBinding`() {
        val studioRunId = RunId("studio-h55-fabric-1")
        insertLocalRow(studioRunId)
        store.recordAdmission(
            studioRunId = studioRunId,
            fabricRunId = FabricRunId("fabric-h55-fabric-1"),
            packageDigest = "sha256:0002",
        )
        val placedAt = Instant.parse("2026-10-13T16:01:00Z")
        val updated = store.recordPlacement(
            studioRunId = studioRunId,
            workerId = "worker-fabric-1",
            region = "eu-central-1",
            placedAt = placedAt,
        )
        assertEquals(true, updated)

        val record = store.getBinding(studioRunId)
        assertNotNull(record)
        val audit = record!!.placementAudit
        assertNotNull(audit)
        assertEquals("worker-fabric-1", audit!!.workerId)
        assertEquals("eu-central-1", audit.region)
    }

    // ------------------------------------------------------------------
    // Falsifier 3: legacy row (placementAudit = null) still roundtrips
    // ------------------------------------------------------------------
    @Test
    fun `legacy row with placementAudit = null still roundtrips without crashing`() {
        val studioRunId = RunId("studio-h55-legacy-1")
        insertLocalRow(studioRunId)
        // The legacy caller admits the binding but
        // does NOT call recordPlacement (the H5.6+
        // use case change is a follow-up). The V5
        // migration added the column as NULL by
        // default; a legacy row keeps working
        // without migration data.
        store.recordAdmission(
            studioRunId = studioRunId,
            fabricRunId = FabricRunId("fabric-h55-legacy-1"),
            packageDigest = "sha256:0003",
        )
        val record = store.getBinding(studioRunId)
        assertNotNull(record)
        assertNull(record!!.placementAudit, "placementAudit must be null for legacy rows")
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
                ps.setString(1, ExperimentId("exp-h55").value)
                ps.setString(2, "h55 test experiment")
                ps.setString(3, Instant.now().toString())
                ps.executeUpdate()
            }
            conn.prepareStatement(
                "INSERT INTO snapshots (snapshot_id, experiment_id, sha256, source_utf8, creator, created_at) " +
                    "VALUES (?, ?, ?, ?, ?, ?)",
            ).use { ps ->
                ps.setString(1, SnapshotId("snap-h55").value)
                ps.setString(2, ExperimentId("exp-h55").value)
                ps.setString(3, "sha256:" + "0".repeat(64))
                ps.setBytes(4, "// h55 test".toByteArray())
                ps.setString(5, "h55-test")
                ps.setString(6, Instant.now().toString())
                ps.executeUpdate()
            }
            conn.prepareStatement(
                "INSERT INTO scenarios (scenario_id, version, environment_profile, plugin_lock_digest, created_at) " +
                    "VALUES (?, ?, ?, ?, ?)",
            ).use { ps ->
                ps.setString(1, ScenarioId("scen-h55").value)
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
                ps.setString(2, ExperimentId("exp-h55").value)
                ps.setString(3, SnapshotId("snap-h55").value)
                ps.setString(4, ScenarioId("scen-h55").value)
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
