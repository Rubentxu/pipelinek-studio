@file:Suppress("TooManyFunctions")

package dev.pipelinek.studio.infra.storage

import dev.pipelinek.studio.domain.ExperimentId
import dev.pipelinek.studio.domain.RunId
import dev.pipelinek.studio.domain.ScenarioId
import dev.pipelinek.studio.domain.SnapshotId
import dev.pipelinek.studio.domain.UpstreamRunId
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.util.Comparator
import java.util.UUID
import javax.sql.DataSource

/**
 * Falsifiers for [RunBindingsH2Store] — the SFW-H2.3 SQLite-backed
 * implementation of [dev.pipelinek.studio.application.ports.UpstreamRunIdLookup].
 *
 * The seam under test is a real SQLite (V1 + V2 + V3 migrations
 * applied via [StudioDatabase]) + the store. The tests pin:
 *  - the **UpstreamRunIdLookup impl** (read `fabric_run_id` for a
 *    given `studio_run_id`);
 *  - the **recordAdmission** write (UPDATE; idempotent on
 *    retry);
 *  - the **recordPipelinekRunId** write;
 *  - the **getBinding** read (all 3 identity fields + package
 *    digest);
 *  - the **false-on-missing-row** contract (the store does
 *    not insert; the local start-run flow is responsible for
 *    creating the row);
 *  - the **P0-04 admission integrity** contract: a
 *    contradictory established `fabric_run_id` / `package_digest`
 *    is rejected by the UPDATE's WHERE guard (not by a
 *    read-before-write check), so an identity already used for
 *    cancellation is never replaced.
 *
 * Scratch state lives in the ignored `build/p0-storage` tree (not
 * the host temp dir) and is removed in [tearDown].
 */
@DisplayName("RunBindingsH2Store (SFW-H2.3 3-identities persistence)")
class RunBindingsH2StoreTest {

    private lateinit var scratchDir: Path
    private lateinit var db: StudioDatabase
    private lateinit var store: RunBindingsH2Store

    private val studioRunId = RunId("studio-run-1")
    private val upstreamRunId = UpstreamRunId("fabric-run-77")
    private val packageDigest = "sha256:" + "a".repeat(64)

    @BeforeEach
    fun setUp() {
        scratchDir = Files.createDirectories(
            Path.of("build", "p0-storage", "bindings-" + UUID.randomUUID()),
        )
        val dbPath = scratchDir.resolve("studio-test.db")
        db = StudioDatabase(dbPath)  // applies V1..V7
        // A minimal DataSource that delegates to StudioDatabase.open().
        // Production wiring uses the same DataSource the rest of the
        // studio uses; for tests, the simplest is a thin adapter.
        val dataSource = object : DataSource {
            override fun getConnection(): Connection = db.open()
            override fun getConnection(username: String?, password: String?): Connection =
                db.open()
            override fun getLogWriter() = null
            override fun setLogWriter(out: java.io.PrintWriter?) {}
            override fun setLoginTimeout(seconds: Int) {}
            override fun getLoginTimeout(): Int = 0
            override fun getParentLogger(): java.util.logging.Logger =
                java.util.logging.Logger.getLogger("test")
            override fun <T : Any?> unwrap(iface: Class<T>?): T = throw UnsupportedOperationException()
            override fun isWrapperFor(iface: Class<*>?): Boolean = false
        }
        store = RunBindingsH2Store(dataSource)
    }

    @AfterEach
    fun tearDown() {
        db.close()
        Files.walk(scratchDir).use { paths ->
            paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
        }
    }

    /**
     * Insert a local-only `run_bindings` row for [id] (no
     * `fabric_run_id`). The H2.3 store is an UPDATE-only
     * store; the local start-run flow creates the row.
     *
     * The `run_bindings` table has FKs to `experiments`,
     * `snapshots`, `scenarios`; the parent rows must be
     * inserted first.
     */
    private fun insertLocalRow(id: RunId = studioRunId) {
        db.open().use { conn ->
            // Parent rows
            conn.prepareStatement(
                "INSERT INTO experiments (experiment_id, name, created_at) VALUES (?, ?, ?)",
            ).use { ps ->
                ps.setString(1, ExperimentId("exp-1").value)
                ps.setString(2, "test experiment")
                ps.setString(3, java.time.Instant.now().toString())
                ps.executeUpdate()
            }
            conn.prepareStatement(
                "INSERT INTO snapshots (snapshot_id, experiment_id, sha256, source_utf8, creator, created_at) " +
                    "VALUES (?, ?, ?, ?, ?, ?)",
            ).use { ps ->
                ps.setString(1, SnapshotId("snap-1").value)
                ps.setString(2, ExperimentId("exp-1").value)
                ps.setString(3, "sha256:" + "0".repeat(64))
                ps.setBytes(4, "// test".toByteArray())
                ps.setString(5, "test")
                ps.setString(6, java.time.Instant.now().toString())
                ps.executeUpdate()
            }
            conn.prepareStatement(
                "INSERT INTO scenarios (scenario_id, version, environment_profile, plugin_lock_digest, created_at) " +
                    "VALUES (?, ?, ?, ?, ?)",
            ).use { ps ->
                ps.setString(1, ScenarioId("scen-1").value)
                ps.setInt(2, 1)
                ps.setString(3, "trusted-local")
                ps.setString(4, "sha256:" + "2".repeat(64))
                ps.setString(5, java.time.Instant.now().toString())
                ps.executeUpdate()
            }
            // The run_bindings row
            conn.prepareStatement(
                "INSERT INTO run_bindings (studio_run_id, experiment_id, snapshot_id, scenario_id, " +
                    "runtime_version, runtime_digest, plugin_set_digest, state, created_at, updated_at) " +
                    "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            ).use { ps ->
                ps.setString(1, id.value)
                ps.setString(2, ExperimentId("exp-1").value)
                ps.setString(3, SnapshotId("snap-1").value)
                ps.setString(4, ScenarioId("scen-1").value)
                ps.setString(5, "0.56.0")
                ps.setString(6, "sha256:" + "0".repeat(64))
                ps.setString(7, "sha256:" + "1".repeat(64))
                ps.setString(8, "RUNNING")
                ps.setString(9, java.time.Instant.now().toString())
                ps.setString(10, java.time.Instant.now().toString())
                ps.executeUpdate()
            }
        }
    }

    // ---- UpstreamRunIdLookup (H2.2c+1 seam backed) ---------------------------------

    @Test
    fun `UpstreamRunIdLookup returns the fabric_run_id for a row that has been admitted`() {
        insertLocalRow()
        store.recordAdmission(studioRunId, upstreamRunId, packageDigest)

        val result = store.upstreamRunIdFor(studioRunId)

        assertEquals(upstreamRunId, result)
    }

    @Test
    fun `UpstreamRunIdLookup returns null for a row with no fabric_run_id (local-only run)`() {
        insertLocalRow()
        // No recordAdmission call: the row is local-only.
        val result = store.upstreamRunIdFor(studioRunId)
        assertNull(result)
    }

    @Test
    fun `UpstreamRunIdLookup returns null for a non-existent row`() {
        // No insert; the row does not exist.
        val result = store.upstreamRunIdFor(RunId("nonexistent"))
        assertNull(result)
    }

    /**
     * Audit P1-04 (durable): the seam answers a blank persisted
     * `fabric_run_id` as a **miss**, never as a blank identity.
     *
     * A blank column value cannot address an upstream run, so the
     * populated-with-nothing row must be indistinguishable from a row
     * that has no upstream identity at all. That is what keeps a
     * corrupt or legacy row from becoming a cancellation call: the
     * Fabric-side consumer already refuses `null` (and, before this
     * seam was typed, refused a blank id) without ever reaching the
     * wire.
     */
    @Test
    fun `UpstreamRunIdLookup answers a blank persisted fabric_run_id as a miss, never a blank identity`() {
        insertLocalRow()
        db.open().use { conn ->
            conn.prepareStatement(
                "UPDATE run_bindings SET fabric_run_id = '' WHERE studio_run_id = ?",
            ).use { ps ->
                ps.setString(1, studioRunId.value)
                assertEquals(1, ps.executeUpdate(), "the blank value must be persisted for the test")
            }
        }

        assertNull(store.upstreamRunIdFor(studioRunId))
        assertNull(store.getBinding(studioRunId))
    }

    // ---- recordAdmission ------------------------------------------------------

    @Test
    fun `recordAdmission updates the row and returns true`() {
        insertLocalRow()
        val ok = store.recordAdmission(studioRunId, upstreamRunId, packageDigest)
        assertTrue(ok)
        val binding = store.getBinding(studioRunId)
        assertNotNull(binding)
        assertEquals(upstreamRunId, binding!!.upstreamRunId)
        assertEquals(packageDigest, binding.packageDigest)
    }

    @Test
    fun `recordAdmission on a non-existent row returns false (does not insert)`() {
        val ok = store.recordAdmission(RunId("nonexistent"), upstreamRunId, packageDigest)
        assertFalse(ok)
        // The row is not there; getBinding returns null.
        assertNull(store.getBinding(RunId("nonexistent")))
    }

    @Test
    fun `recordAdmission is idempotent on retry (same (studioRunId, upstreamRunId))`() {
        insertLocalRow()
        assertTrue(store.recordAdmission(studioRunId, upstreamRunId, packageDigest))
        // A retry that reaches here twice: the second call
        // is a no-op (the value is already set to the same
        // thing). UPDATE returns 1 in both cases because the
        // row exists.
        assertTrue(store.recordAdmission(studioRunId, upstreamRunId, packageDigest))
        val binding = store.getBinding(studioRunId)
        assertEquals(upstreamRunId, binding!!.upstreamRunId)
    }

    // ---- P0-04 admission integrity -------------------------------------------

    @Test
    fun `recordAdmission rejects a contradictory established fabric_run_id without replacing it`() {
        insertLocalRow()
        assertTrue(store.recordAdmission(studioRunId, upstreamRunId, packageDigest))
        val contradicting = UpstreamRunId("fabric-run-other")

        val updated = store.recordAdmission(studioRunId, contradicting, packageDigest)

        assertFalse(updated, "a contradictory admission must not update the binding row")
        val binding = store.getBinding(studioRunId)
        assertEquals(upstreamRunId, binding!!.upstreamRunId)
        assertEquals(upstreamRunId, store.upstreamRunIdFor(studioRunId))
    }

    @Test
    fun `recordAdmission rejects a contradictory established package_digest without replacing it`() {
        insertLocalRow()
        assertTrue(store.recordAdmission(studioRunId, upstreamRunId, packageDigest))
        val otherDigest = "sha256:" + "b".repeat(64)

        val updated = store.recordAdmission(studioRunId, upstreamRunId, otherDigest)

        assertFalse(updated, "a contradictory package digest must not update the binding row")
        assertEquals(packageDigest, store.getBinding(studioRunId)!!.packageDigest)
    }

    @Test
    fun `recordAdmission on a row with no established identity still writes (local-only row)`() {
        insertLocalRow()

        val updated = store.recordAdmission(studioRunId, upstreamRunId, packageDigest)

        assertTrue(updated)
        assertEquals(upstreamRunId, store.getBinding(studioRunId)!!.upstreamRunId)
    }

    @Test
    fun `other metadata update paths still work after a rejected admission`() {
        insertLocalRow()
        assertTrue(store.recordAdmission(studioRunId, upstreamRunId, packageDigest))
        assertFalse(
            store.recordAdmission(studioRunId, UpstreamRunId("fabric-run-other"), packageDigest),
        )

        val pipelinekRunId = UpstreamRunId("pipelinek-run-after-reject")
        assertTrue(store.recordPipelinekRunId(studioRunId, pipelinekRunId))
        assertTrue(store.clearDedupKey(studioRunId))

        val binding = store.getBinding(studioRunId)!!
        assertEquals(upstreamRunId, binding.upstreamRunId)
        assertEquals(pipelinekRunId, binding.pipelinekRunId)
        assertNull(binding.dedupKey)
    }

    // ---- recordPipelinekRunId ------------------------------------------------

    @Test
    fun `recordPipelinekRunId updates the row and returns true`() {
        insertLocalRow()
        store.recordAdmission(studioRunId, upstreamRunId, packageDigest)

        val pipelinekRunId = UpstreamRunId("pipelinek-run-99")
        val ok = store.recordPipelinekRunId(studioRunId, pipelinekRunId)
        assertTrue(ok)

        val binding = store.getBinding(studioRunId)
        assertNotNull(binding)
        assertEquals(upstreamRunId, binding!!.upstreamRunId)
        assertEquals(pipelinekRunId, binding.pipelinekRunId)
    }

    @Test
    fun `recordPipelinekRunId on a non-existent row returns false`() {
        val ok = store.recordPipelinekRunId(
            RunId("nonexistent"),
            UpstreamRunId("pipelinek-run-99"),
        )
        assertFalse(ok)
    }
}
