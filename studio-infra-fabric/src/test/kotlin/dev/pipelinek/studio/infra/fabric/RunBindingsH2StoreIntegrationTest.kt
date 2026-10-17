@file:Suppress("TooManyFunctions")

package dev.pipelinek.studio.infra.fabric

import dev.pipelinek.studio.application.ports.ExecutionProfile
import dev.pipelinek.studio.application.ports.RunRequest
import dev.pipelinek.studio.domain.ExperimentId
import dev.pipelinek.studio.domain.RunId
import dev.pipelinek.studio.domain.ScenarioId
import dev.pipelinek.studio.domain.SnapshotId
import dev.pipelinek.studio.domain.UpstreamRunId
import dev.pipelinek.studio.infra.storage.RunBindingsH2Store
import dev.pipelinek.studio.infra.storage.SqliteFabricStartIntentStore
import dev.pipelinek.studio.infra.storage.StudioDatabase
import io.jenkins.pipelinek.fabric.contracts.model.CommandResult
import io.jenkins.pipelinek.fabric.contracts.model.RunId as FabricRunId
import io.jenkins.pipelinek.fabric.contracts.runcontrol.CancelRunCommand
import io.jenkins.pipelinek.fabric.contracts.runcontrol.RunControl
import io.jenkins.pipelinek.fabric.contracts.runcontrol.StartRunRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.util.Collections
import java.util.UUID
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
 * Audit P1-04: the store answers the application-layer
 * `UpstreamRunIdLookup` port, so the adapter is wired through
 * `BindingLookup.from(store)` — the Fabric `RunId` is minted at
 * that boundary and never inside `studio-infra-storage`.
 *
 * The H2.3 contract's "1 end-to-end falsifier" lives here
 * (not in `studio-infra-storage`) because the test needs
 * the [FakeRunControl] test fixture that lives in
 * `studio-infra-fabric/src/test/`. The storage-layer
 * falsifiers (8 of them) live in
 * [dev.pipelinek.studio.infra.storage.RunBindingsH2StoreTest].
 *
 * ## P0-03 (S4): the real SQLite start-intent journal
 *
 * The submit path now refuses to dispatch unless the command identity
 * was **reserved in a durable journal** first. The journal tests below
 * run against the real [SqliteFabricStartIntentStore] over a real
 * `studio.db` file: a recreated store (a stand-in for a process
 * restart, since it re-reads the same file), contradictory content, an
 * unbound Studio run and concurrent submits all have to behave the way
 * the in-memory double promises. A fake journal would prove nothing
 * here — durability is the property under test.
 *
 * The database file lives under the module's ignored `build/p0-submit`
 * tree and is deleted in [tearDown]; no test in this class touches the
 * production database, a shared temp directory or the repository root.
 */
@DisplayName("RunBindingsH2Store integration (SFW-H2.3 end-to-end)")
class RunBindingsH2StoreIntegrationTest {

    private lateinit var db: StudioDatabase
    private lateinit var store: RunBindingsH2Store
    private lateinit var dataSource: DataSource
    private lateinit var intentStore: SqliteFabricStartIntentStore
    private lateinit var dbPath: Path

    private val studioRunId = RunId("studio-run-1")
    private val otherStudioRunId = RunId("studio-run-2")
    private val packageDigest = "sha256:" + "a".repeat(64)

    @BeforeEach
    fun setUp() {
        val scratchDir = Path.of("build", "p0-submit")
        Files.createDirectories(scratchDir)
        dbPath = scratchDir.resolve("studio-${UUID.randomUUID()}.db")
        db = StudioDatabase(dbPath)  // applies V1..V7
        dataSource = object : DataSource {
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
        intentStore = SqliteFabricStartIntentStore(dataSource)
        insertLocalRow()
    }

    @AfterEach
    fun tearDown() {
        db.close()
        // The scratch database (plus SQLite's WAL side files) is inside
        // the worktree, so the test that created it removes it.
        listOf("", "-wal", "-shm").forEach { suffix ->
            Files.deleteIfExists(Path.of(dbPath.toString() + suffix))
        }
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

    /**
     * A second local-only `run_bindings` row, reusing the parent rows
     * [insertLocalRow] created. The H2.3 store is UPDATE-only and the
     * start-intent journal requires the row to exist (P0-03: a run
     * without a persisted binding cannot obtain an identity).
     */
    private fun insertBinding(runId: RunId) {
        db.open().use { conn ->
            conn.prepareStatement(
                "INSERT INTO run_bindings (studio_run_id, experiment_id, snapshot_id, scenario_id, " +
                    "runtime_version, runtime_digest, plugin_set_digest, state, created_at, updated_at) " +
                    "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            ).use { ps ->
                ps.setString(1, runId.value)
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

    private fun localRequest(
        runId: RunId = studioRunId,
        scenarioId: ScenarioId = ScenarioId("scen-1"),
    ) = RunRequest(
        runId = runId,
        snapshotId = SnapshotId("snap-1"),
        scenarioId = scenarioId,
        profile = ExecutionProfile.TrustedLocal,
    )

    private fun adapterOver(
        control: RunControl,
        journal: SqliteFabricStartIntentStore = intentStore,
    ) = FabricRunnerAdapter(
        runControl = control,
        packageRegistry = PackageRegistry { _ -> packageDigest },
        bindingLookup = BindingLookup.from(store),
        startIntentStore = journal,
    )

    /** Rows in `fabric_start_intents` (the durable journal the adapter commits to). */
    private fun intentRowCount(): Int = db.open().use { conn ->
        conn.prepareStatement("SELECT COUNT(*) FROM fabric_start_intents").use { ps ->
            ps.executeQuery().use { rs ->
                rs.next()
                rs.getInt(1)
            }
        }
    }

    // ---- P0-03 (S4): the real SQLite journal ---------------------------------

    private class RecordingControl : RunControl {
        val startCalls: MutableList<StartRunRequest> = Collections.synchronizedList(mutableListOf())

        override suspend fun start(request: StartRunRequest): CommandResult<FabricRunId> {
            startCalls.add(request)
            return CommandResult.Accepted(FabricRunId("fabric-run-77"))
        }

        override suspend fun cancel(command: CancelRunCommand): CommandResult<FabricRunId> =
            CommandResult.Accepted(FabricRunId("fabric-run-77"))
    }

    @Test
    fun `submit persists the command identity in the real journal before the wire call`() = runBlocking {
        val control = FakeRunControl()
        val adapter = adapterOver(control)

        val result = adapter.submit(localRequest())

        assertInstanceOf(FabricSubmissionResult.Admitted::class.java, result)
        val sentId = control.startCalls.single().commandId.value
        val persisted = intentStore.lookup(studioRunId)
        assertNotNull(persisted)
        assertEquals(sentId, persisted!!.commandId.value, "the wire id must be the persisted id")
        assertTrue(
            persisted.requestFingerprint.startsWith("sha256:"),
            "got: ${persisted.requestFingerprint}",
        )
        assertEquals(1, intentRowCount())
    }

    @Test
    fun `the same request reuses the commandId across a recreated store and adapter`() = runBlocking {
        // A transport timeout is the case S4 exists for: the identity
        // must already be durable, and the retry `must` not mint a new one.
        val timingOut = FakeRunControl().apply {
            startThrows = FabricTransportFailure(
                callName = "RunControl.start",
                message = "deadline exceeded",
            )
        }
        val first = adapterOver(timingOut).submit(localRequest())
        assertInstanceOf(FabricSubmissionResult.Refused::class.java, first)
        assertEquals(1, timingOut.startCalls.size, "no automatic retry loop")
        val afterTimeout = intentStore.lookup(studioRunId)
        assertNotNull(afterTimeout, "the identity must survive the transport failure")

        // A restart: a brand-new store and adapter over the same file.
        val recreatedJournal = SqliteFabricStartIntentStore(dataSource)
        val retrying = FakeRunControl()
        val second = adapterOver(retrying, journal = recreatedJournal).submit(localRequest())

        assertInstanceOf(FabricSubmissionResult.Admitted::class.java, second)
        assertEquals(
            timingOut.startCalls.single().commandId.value,
            retrying.startCalls.single().commandId.value,
            "a recreated store must answer with the same commandId",
        )
        assertEquals(afterTimeout, recreatedJournal.lookup(studioRunId))
        assertEquals(1, intentRowCount())
    }

    @Test
    fun `a contradictory request is refused and the current row is kept`() = runBlocking {
        val control = FakeRunControl()
        val adapter = adapterOver(control)
        assertInstanceOf(FabricSubmissionResult.Admitted::class.java, adapter.submit(localRequest()))
        val original = intentStore.lookup(studioRunId)
        assertNotNull(original)

        val result = adapter.submit(localRequest(scenarioId = ScenarioId("scen-2")))

        assertInstanceOf(FabricSubmissionResult.Refused::class.java, result)
        val refused = result as FabricSubmissionResult.Refused
        assertTrue(
            refused.reason.startsWith("start intent conflict: "),
            "got: ${refused.reason}",
        )
        assertEquals(1, control.startCalls.size, "a contradiction must not reach Fabric")
        assertEquals(original, intentStore.lookup(studioRunId), "the stored row must be unchanged")
        assertEquals(1, intentRowCount())
    }

    @Test
    fun `a run with no run_bindings row gets no identity and no dispatch`() = runBlocking {
        val unbound = RunId("studio-run-unbound")
        val control = FakeRunControl()

        val result = adapterOver(control).submit(localRequest(runId = unbound))

        assertInstanceOf(FabricSubmissionResult.Refused::class.java, result)
        val refused = result as FabricSubmissionResult.Refused
        assertTrue(
            refused.reason.contains("no persisted run binding for studio run ${unbound.value}"),
            "got: ${refused.reason}",
        )
        assertEquals(0, control.startCalls.size)
        assertNull(intentStore.lookup(unbound))
        assertEquals(0, intentRowCount())
        // The unbound run must not have been recorded as a binding either.
        assertNull(store.getBinding(unbound))
    }

    @Test
    fun `distinct Studio runs keep distinct commandIds in the real journal`() = runBlocking {
        insertBinding(otherStudioRunId)
        val control = FakeRunControl()
        val adapter = adapterOver(control)

        adapter.submit(localRequest(runId = studioRunId))
        adapter.submit(localRequest(runId = otherStudioRunId))

        assertEquals(2, control.startCalls.size)
        val ids = control.startCalls.map { it.commandId.value }
        assertEquals(2, ids.toSet().size, "two deliberate Studio runs are two runs")
        assertEquals(2, intentRowCount())
        assertEquals(ids[0], intentStore.lookup(studioRunId)!!.commandId.value)
        assertEquals(ids[1], intentStore.lookup(otherStudioRunId)!!.commandId.value)
    }

    @Test
    fun `concurrent submits of one request never send two identities`() = runBlocking {
        // The store's insert-if-absent is what makes this hold. A loser
        // of the race may be refused by SQLite (a storage failure is a
        // refusal, never a mint), so the invariant asserted is: every
        // send that happened carried the SAME identity, and a later
        // submit still uses it.
        val control = RecordingControl()
        val adapter = adapterOver(control)

        val results = (1..4).map {
            async(Dispatchers.IO) { adapter.submit(localRequest()) }
        }.awaitAll()

        assertTrue(
            results.any { it is FabricSubmissionResult.Admitted },
            "at least one concurrent submit must be admitted: $results",
        )
        val sentIds = control.startCalls.toList().map { it.commandId.value }
        assertTrue(sentIds.isNotEmpty())
        assertEquals(
            1,
            sentIds.toSet().size,
            "concurrent submits sent more than one commandId: $sentIds",
        )
        assertEquals(1, intentRowCount())

        val afterwards = FakeRunControl()
        val replay = adapterOver(afterwards).submit(localRequest())
        assertInstanceOf(FabricSubmissionResult.Admitted::class.java, replay)
        assertEquals(sentIds.first(), afterwards.startCalls.single().commandId.value)
    }

    @Test
    fun `submit then recordAdmission is queryable through the adapted BindingLookup`() = runBlocking {
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
            // P0-04: the real store answers the upstream-identity
            // port; the Fabric seam is adapted from it here.
            bindingLookup = BindingLookup.from(store),
            // P0-03: the real SQLite journal is the only source of the
            // command identity; without it the adapter refuses to send.
            startIntentStore = intentStore,
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
        val ok = store.recordAdmission(
            studioRunId,
            UpstreamRunId(admitted.fabricRunId.value),
            packageDigest,
        )
        assertEquals(true, ok)

        // And now the read ports can answer.
        val looked = store.upstreamRunIdFor(studioRunId)
        assertEquals(UpstreamRunId(admitted.fabricRunId.value), looked)

        // The full record carries the 3rd identity (still null
        // — populated when a fact stream surfaces the
        // executor's id) and the package digest.
        val binding = store.getBinding(studioRunId)
        assertNotNull(binding)
        assertEquals(UpstreamRunId(admitted.fabricRunId.value), binding!!.upstreamRunId)
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
            bindingLookup = BindingLookup.from(store),
            startIntentStore = intentStore,
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
        assertEquals(
            true,
            store.recordAdmission(studioRunId, UpstreamRunId(admitted.fabricRunId.value), packageDigest),
        )
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
