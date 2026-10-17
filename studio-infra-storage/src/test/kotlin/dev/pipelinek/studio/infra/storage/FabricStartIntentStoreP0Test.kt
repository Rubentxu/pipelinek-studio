@file:Suppress("TooManyFunctions")

package dev.pipelinek.studio.infra.storage

import dev.pipelinek.studio.application.ports.FabricStartIntentReservation
import dev.pipelinek.studio.application.ports.FabricStartIntentStore
import dev.pipelinek.studio.domain.ExperimentId
import dev.pipelinek.studio.domain.IdFormat
import dev.pipelinek.studio.domain.RunId
import dev.pipelinek.studio.domain.ScenarioId
import dev.pipelinek.studio.domain.SnapshotId
import dev.pipelinek.studio.domain.UpstreamRunId
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.util.Comparator
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.sql.DataSource

/**
 * Falsifiers for [SqliteFabricStartIntentStore] — the Studio P0-03
 * durable start-intent (command identity) reservation.
 *
 * The seam under test is a real SQLite (V1..V7 migrations applied via
 * [StudioDatabase]) behind the pure
 * [FabricStartIntentStore] application port. The tests pin, through
 * the public port only:
 *
 *  - the command identity is minted once and persisted BEFORE any
 *    remote call could happen;
 *  - a retried identical request (same store, recreated store, and a
 *    reopened database = process restart) returns the identical
 *    command identity;
 *  - contradictory request content for the same Studio run is
 *    rejected and leaves the stored identity + the `run_bindings`
 *    mapping untouched;
 *  - a missing `run_bindings` row is refused without writing an
 *    intent (no invented identity to dispatch later);
 *  - concurrent identical reservations converge on one identity;
 *  - distinct deliberate Studio runs get distinct identities;
 *  - a malformed fingerprint is refused, never stored.
 *
 * Scratch state lives in the ignored `build/p0-storage` tree (not the
 * host temp dir) and is removed in [tearDown].
 */
@DisplayName("SqliteFabricStartIntentStore (Studio P0-03 durable start intent)")
class FabricStartIntentStoreP0Test {

    private lateinit var scratchDir: Path
    private lateinit var dbPath: Path
    private lateinit var db: StudioDatabase
    private lateinit var store: FabricStartIntentStore

    private val studioRunId = RunId("studio-p0-intent-1")
    private val otherStudioRunId = RunId("studio-p0-intent-2")
    private val upstreamRunId = UpstreamRunId("fabric-p0-intent-1")
    private val packageDigest = "sha256:" + "a".repeat(64)

    @BeforeEach
    fun setUp() {
        scratchDir = Files.createDirectories(
            Path.of("build", "p0-storage", "intent-" + UUID.randomUUID()),
        )
        dbPath = scratchDir.resolve("studio-test.db")
        db = StudioDatabase(dbPath, poolSize = 8)  // applies V1..V7
        store = newStore()
    }

    @AfterEach
    fun tearDown() {
        db.close()
        deleteScratchTree(scratchDir)
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    /**
     * The DataSource used by the store. Production wiring passes the
     * Studio-wide DataSource over the same [StudioDatabase]; the test
     * uses the same thin adapter the sibling storage tests use.
     */
    private fun newStore(): FabricStartIntentStore = SqliteFabricStartIntentStore(dataSource())

    private fun dataSource(): DataSource {
        val database = db
        return object : DataSource {
            override fun getConnection(): Connection = database.open()
            override fun getConnection(username: String?, password: String?): Connection =
                database.open()
            override fun getLogWriter() = null
            override fun setLogWriter(out: java.io.PrintWriter?) {}
            override fun setLoginTimeout(seconds: Int) {}
            override fun getLoginTimeout(): Int = 0
            override fun getParentLogger(): java.util.logging.Logger =
                java.util.logging.Logger.getLogger("test")
            override fun <T : Any?> unwrap(iface: Class<T>?): T =
                throw UnsupportedOperationException()
            override fun isWrapperFor(iface: Class<*>?): Boolean = false
        }
    }

    private fun deleteScratchTree(dir: Path) {
        if (!Files.exists(dir)) return
        Files.walk(dir).use { paths ->
            paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
        }
    }

    /**
     * Insert the parent rows + the local-only `run_bindings` row the
     * intent reservation requires (the `fabric_start_intents` row has
     * an FK to `run_bindings`). The V7 migration is additive; nothing
     * here is changed by the intent table.
     */
    private fun insertLocalRow(id: RunId = studioRunId) {
        // Parent ids are derived from the Studio run id so two runs can
        // coexist in one database (the FKs must be satisfied per run).
        val suffix = id.value
        val experimentId = ExperimentId("exp-$suffix")
        val snapshotId = SnapshotId("snap-$suffix")
        val scenarioId = ScenarioId("scen-$suffix")
        db.open().use { conn ->
            conn.prepareStatement(
                "INSERT INTO experiments (experiment_id, name, created_at) VALUES (?, ?, ?)",
            ).use { ps ->
                ps.setString(1, experimentId.value)
                ps.setString(2, "p0 intent experiment")
                ps.setString(3, "2026-10-16T00:00:00Z")
                ps.executeUpdate()
            }
            conn.prepareStatement(
                "INSERT INTO snapshots (snapshot_id, experiment_id, sha256, source_utf8, creator, created_at) " +
                    "VALUES (?, ?, ?, ?, ?, ?)",
            ).use { ps ->
                ps.setString(1, snapshotId.value)
                ps.setString(2, experimentId.value)
                ps.setString(3, "sha256:" + "0".repeat(64))
                ps.setBytes(4, "// p0".toByteArray())
                ps.setString(5, "test")
                ps.setString(6, "2026-10-16T00:00:00Z")
                ps.executeUpdate()
            }
            conn.prepareStatement(
                "INSERT INTO scenarios (scenario_id, version, environment_profile, plugin_lock_digest, created_at) " +
                    "VALUES (?, ?, ?, ?, ?)",
            ).use { ps ->
                ps.setString(1, scenarioId.value)
                ps.setInt(2, 1)
                ps.setString(3, "trusted-local")
                ps.setString(4, "sha256:" + "2".repeat(64))
                ps.setString(5, "2026-10-16T00:00:00Z")
                ps.executeUpdate()
            }
            conn.prepareStatement(
                "INSERT INTO run_bindings (studio_run_id, experiment_id, snapshot_id, scenario_id, " +
                    "runtime_version, runtime_digest, plugin_set_digest, state, created_at, updated_at) " +
                    "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            ).use { ps ->
                ps.setString(1, id.value)
                ps.setString(2, experimentId.value)
                ps.setString(3, snapshotId.value)
                ps.setString(4, scenarioId.value)
                ps.setString(5, "0.56.0")
                ps.setString(6, "sha256:" + "0".repeat(64))
                ps.setString(7, "sha256:" + "1".repeat(64))
                ps.setString(8, "RUNNING")
                ps.setString(9, "2026-10-16T00:00:00Z")
                ps.setString(10, "2026-10-16T00:00:00Z")
                ps.executeUpdate()
            }
        }
    }

    private fun fingerprintHex(seed: String): String =
        java.security.MessageDigest.getInstance("SHA-256")
            .digest(seed.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    private fun reservedCommandId(result: FabricStartIntentReservation) =
        assertInstanceOf(FabricStartIntentReservation.Reserved::class.java, result).commandId

    // ------------------------------------------------------------------
    // rule 1: identity is minted once and persisted before any dispatch
    // ------------------------------------------------------------------

    @Test
    fun `reserve persists a canonical command identity for a bound Studio run`() {
        insertLocalRow()

        val result = store.reserve(studioRunId, "sha256:" + fingerprintHex("req-1"))

        val commandId = reservedCommandId(result)
        assertTrue(
            IdFormat.isUuidLike(commandId.value),
            "command identity must be a canonical UUID, was ${commandId.value}",
        )
        val persisted = store.lookup(studioRunId)
        assertEquals(commandId, persisted!!.commandId)
        assertEquals("sha256:" + fingerprintHex("req-1"), persisted.requestFingerprint)
    }

    @Test
    fun `same request retried on the same store returns the identical command identity`() {
        insertLocalRow()
        val first = reservedCommandId(store.reserve(studioRunId, "sha256:" + fingerprintHex("req-1")))

        val retried = reservedCommandId(store.reserve(studioRunId, "sha256:" + fingerprintHex("req-1")))

        assertEquals(first, retried)
    }

    @Test
    fun `recreated store over a reopened database returns the identical command identity`() {
        insertLocalRow()
        val first = reservedCommandId(store.reserve(studioRunId, "sha256:" + fingerprintHex("req-1")))

        // Process restart: close the pool, reopen the same studio.db,
        // build a fresh store. Nothing identity-related may be cached
        // in memory.
        db.close()
        db = StudioDatabase(dbPath, poolSize = 8)
        store = newStore()

        val afterRestart =
            reservedCommandId(store.reserve(studioRunId, "sha256:" + fingerprintHex("req-1")))
        assertEquals(first, afterRestart)
    }

    // ------------------------------------------------------------------
    // rule 2: contradictory content is rejected, nothing is replaced
    // ------------------------------------------------------------------

    @Test
    fun `conflicting request content is rejected and leaves identity and binding mapping unchanged`() {
        insertLocalRow()
        val bindings = RunBindingsH2Store(dataSource())
        assertTrue(bindings.recordAdmission(studioRunId, upstreamRunId, packageDigest))
        val original =
            reservedCommandId(store.reserve(studioRunId, "sha256:" + fingerprintHex("req-1")))

        val conflict = store.reserve(studioRunId, "sha256:" + fingerprintHex("req-2"))

        assertInstanceOf(FabricStartIntentReservation.Conflict::class.java, conflict)
        val persisted = store.lookup(studioRunId)
        assertEquals(original, persisted!!.commandId)
        assertEquals("sha256:" + fingerprintHex("req-1"), persisted.requestFingerprint)
        // The rejected content never reached the binding mapping.
        val binding = bindings.getBinding(studioRunId)!!
        assertEquals(upstreamRunId, binding.upstreamRunId)
        assertEquals(packageDigest, binding.packageDigest)
        // And a later retry of the ORIGINAL request still gets the
        // original identity.
        assertEquals(
            original,
            reservedCommandId(store.reserve(studioRunId, "sha256:" + fingerprintHex("req-1"))),
        )
    }

    @Test
    fun `lookup returns null for a run whose intent was never reserved`() {
        insertLocalRow()
        assertNull(store.lookup(studioRunId))
    }

    // ------------------------------------------------------------------
    // rule 3: an unpersisted/missing binding cannot reserve (and so
    // cannot dispatch a remote effect later)
    // ------------------------------------------------------------------

    @Test
    fun `missing run binding is refused without writing an intent`() {
        // No run_bindings row: the local start-run flow never created
        // it. The reservation must fail closed and write nothing.
        val result = store.reserve(studioRunId, "sha256:" + fingerprintHex("req-1"))

        assertInstanceOf(FabricStartIntentReservation.MissingBinding::class.java, result)
        assertNull(store.lookup(studioRunId))
        // A repeated attempt is refused the same way: no row was
        // invented by the first refusal.
        assertInstanceOf(
            FabricStartIntentReservation.MissingBinding::class.java,
            store.reserve(studioRunId, "sha256:" + fingerprintHex("req-1")),
        )
    }

    // ------------------------------------------------------------------
    // rule 4: concurrent identical reservations converge
    // ------------------------------------------------------------------

    @Test
    fun `concurrent identical reservations converge on one command identity`() {
        insertLocalRow()
        val fingerprint = "sha256:" + fingerprintHex("req-concurrent")
        val threads = 8
        val barrier = CyclicBarrier(threads)
        val pool = Executors.newFixedThreadPool(threads)
        try {
            val tasks = (1..threads).map {
                Callable { barrier.await(10, TimeUnit.SECONDS); store.reserve(studioRunId, fingerprint) }
            }
            val results = pool.invokeAll(tasks).map { it.get(30, TimeUnit.SECONDS) }
            val commandIds = results.map { reservedCommandId(it) }.toSet()
            assertEquals(
                1,
                commandIds.size,
                "every concurrent identical reservation must observe the same identity, got $commandIds",
            )
            assertEquals(commandIds.single(), store.lookup(studioRunId)!!.commandId)
        } finally {
            pool.shutdownNow()
        }
    }

    // ------------------------------------------------------------------
    // rule 5: distinct deliberate Studio runs stay distinct
    // ------------------------------------------------------------------

    @Test
    fun `distinct Studio runs with identical content get distinct command identities`() {
        insertLocalRow(studioRunId)
        insertLocalRow(otherStudioRunId)
        val fingerprint = "sha256:" + fingerprintHex("req-shared")

        val first = reservedCommandId(store.reserve(studioRunId, fingerprint))
        val second = reservedCommandId(store.reserve(otherStudioRunId, fingerprint))

        assertNotEquals(first, second)
        assertEquals(first, store.lookup(studioRunId)!!.commandId)
        assertEquals(second, store.lookup(otherStudioRunId)!!.commandId)
    }

    // ------------------------------------------------------------------
    // rule 6: a malformed fingerprint cannot be persisted
    // ------------------------------------------------------------------

    @Test
    fun `blank or malformed fingerprint is refused without writing an intent`() {
        insertLocalRow()

        val blank = store.reserve(studioRunId, "   ")
        val short = store.reserve(studioRunId, "sha256:abc")
        val notHex = store.reserve(studioRunId, "sha256:" + "z".repeat(64))

        assertInstanceOf(FabricStartIntentReservation.Failure::class.java, blank)
        assertInstanceOf(FabricStartIntentReservation.Failure::class.java, short)
        assertInstanceOf(FabricStartIntentReservation.Failure::class.java, notHex)
        assertNull(store.lookup(studioRunId))
    }
}
