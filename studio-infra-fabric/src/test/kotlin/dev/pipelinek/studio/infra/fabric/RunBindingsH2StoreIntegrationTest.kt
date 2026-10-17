@file:Suppress("TooManyFunctions")

package dev.pipelinek.studio.infra.fabric

import dev.pipelinek.studio.application.ports.ExecutionProfile
import dev.pipelinek.studio.application.ports.RunRequest
import dev.pipelinek.studio.domain.ExperimentId
import dev.pipelinek.studio.domain.RunId
import dev.pipelinek.studio.domain.ScenarioId
import dev.pipelinek.studio.domain.SnapshotId
import dev.pipelinek.studio.infra.storage.RunBindingsH2Store
import dev.pipelinek.studio.infra.storage.StudioDatabase
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.sql.Connection
import javax.sql.DataSource

/**
 * SFW-H2.3 end-to-end integration test: a real
 * [RunBindingsH2Store] (SQLite) + the [FabricRunnerAdapter]
 * (H2.2c) + a [FakeRunControl] (the test seam from H2.2c).
 *
 * This is the falsifier that proves the H2.2c write port
 * and the H2.3 store can be wired end-to-end without a test
 * fixture for the BindingLookup seam: a real
 * `recordAdmission` call after a successful `submit`
 * populates the row, and a subsequent `BindingLookup` call
 * returns the recorded `fabric_run_id`.
 *
 * The H2.3 contract's "1 end-to-end falsifier" lives here
 * (not in `studio-infra-storage`) because the test needs
 * the [FakeRunControl] test fixture that lives in
 * `studio-infra-fabric/src/test/`. The storage-layer
 * falsifiers (8 of them) live in
 * [dev.pipelinek.studio.infra.storage.RunBindingsH2StoreTest].
 */
@DisplayName("RunBindingsH2Store integration (SFW-H2.3 end-to-end)")
class RunBindingsH2StoreIntegrationTest {

    @TempDir
    lateinit var tmpDir: Path
    private lateinit var db: StudioDatabase
    private lateinit var store: RunBindingsH2Store

    private val studioRunId = RunId("studio-run-1")
    private val packageDigest = "sha256:" + "a".repeat(64)

    @BeforeEach
    fun setUp() {
        val dbPath = tmpDir.resolve("studio-test.db")
        db = StudioDatabase(dbPath)  // applies V1+V2+V3
        val dataSource = object : DataSource {
            override fun getConnection(): Connection = db.open()
            override fun getConnection(username: String?, password: String?): Connection = db.open()
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
        insertLocalRow()
    }

    @AfterEach
    fun tearDown() {
        db.close()
    }

    /**
     * Insert a local-only `run_bindings` row + the parent
     * `experiments` / `snapshots` / `scenarios` rows. The
     * H2.3 store is an UPDATE-only store; the local
     * start-run flow creates these rows.
     */
    private fun insertLocalRow() {
        db.open().use { conn ->
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
            conn.prepareStatement(
                "INSERT INTO run_bindings (studio_run_id, experiment_id, snapshot_id, scenario_id, " +
                    "runtime_version, runtime_digest, plugin_set_digest, state, created_at, updated_at) " +
                    "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            ).use { ps ->
                ps.setString(1, studioRunId.value)
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

    @Test
    fun `submit then recordAdmission is queryable by BindingLookup`() = runBlocking {
        // Wire: a real H2.3 store + a fake RunControl +
        // the H2.2c adapter. The use-case layer (H2.4) is
        // what calls recordAdmission after a successful
        // submit; this test does it inline.
        val fakeRunControl = FakeRunControl().apply {
            startAnswer = io.jenkins.pipelinek.fabric.contracts.model.CommandResult.Accepted(
                io.jenkins.pipelinek.fabric.contracts.model.RunId("fabric-run-77"),
            )
        }
        val registry = PackageRegistry { _ -> packageDigest }
        val adapter = FabricRunnerAdapter(
            runControl = fakeRunControl,
            packageRegistry = registry,
            // P0-04: the real store *is* the BindingLookup seam.
            bindingLookup = store,
        )

        val result = adapter.submit(
            RunRequest(
                runId = studioRunId,
                snapshotId = SnapshotId("snap-1"),
                scenarioId = ScenarioId("scen-1"),
                profile = ExecutionProfile.TrustedLocal,
            ),
        )

        assertInstanceOf(FabricSubmissionResult.Admitted::class.java, result)
        val admitted = result as FabricSubmissionResult.Admitted
        assertEquals(io.jenkins.pipelinek.fabric.contracts.model.RunId("fabric-run-77"), admitted.fabricRunId)

        // Use-case call: the real seam.
        val ok = store.recordAdmission(studioRunId, admitted.fabricRunId, packageDigest)
        assertEquals(true, ok)

        // And now the read ports can answer.
        val looked = store.fabricRunIdFor(studioRunId)
        assertEquals(admitted.fabricRunId, looked)

        // The full record carries the 3rd identity (still null
        // — populated when a fact stream surfaces the
        // executor's id) and the package digest.
        val binding = store.getBinding(studioRunId)
        assertNotNull(binding)
        assertEquals(admitted.fabricRunId, binding!!.fabricRunId)
        assertEquals(null, binding.pipelinekRunId)
        assertEquals(packageDigest, binding.packageDigest)
    }

    /**
     * P0-04 / S5 end-to-end: after a real [RunBindingsH2Store.recordAdmission],
     * [FabricRunnerAdapter.cancel] resolves the persisted Fabric identity
     * from the store and puts **that** id on the wire — never the Studio id.
     */
    @Test
    fun `cancel forwards the persisted fabricRunId from the real store`() = runBlocking {
        val fakeRunControl = FakeRunControl().apply {
            startAnswer = io.jenkins.pipelinek.fabric.contracts.model.CommandResult.Accepted(
                io.jenkins.pipelinek.fabric.contracts.model.RunId("fabric-run-77"),
            )
        }
        val adapter = FabricRunnerAdapter(
            runControl = fakeRunControl,
            packageRegistry = PackageRegistry { _ -> packageDigest },
            bindingLookup = store,
        )

        val submitted = adapter.submit(
            RunRequest(
                runId = studioRunId,
                snapshotId = SnapshotId("snap-1"),
                scenarioId = ScenarioId("scen-1"),
                profile = ExecutionProfile.TrustedLocal,
            ),
        )
        assertInstanceOf(FabricSubmissionResult.Admitted::class.java, submitted)
        val admitted = submitted as FabricSubmissionResult.Admitted
        assertEquals(true, store.recordAdmission(studioRunId, admitted.fabricRunId, packageDigest))
        val bindingBefore = store.getBinding(studioRunId)

        val result = adapter.cancel(studioRunId)

        assertInstanceOf(FabricCancellationResult.CancelRequested::class.java, result)
        assertEquals(1, fakeRunControl.cancelCalls.size)
        val sent = fakeRunControl.cancelCalls.single()
        assertEquals("fabric-run-77", sent.runId.value)
        org.junit.jupiter.api.Assertions.assertNotEquals(studioRunId.value, sent.runId.value)
        // The cancel path only reads the binding: the persisted row is
        // byte-for-byte what it was before the call.
        assertEquals(bindingBefore, store.getBinding(studioRunId))
    }
}
