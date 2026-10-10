package dev.pipelinek.studio.infra.storage

import dev.pipelinek.studio.application.ports.*
import dev.pipelinek.studio.domain.*
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class StudioDatabaseTest {
    private lateinit var db: StudioDatabase

    @AfterEach
    fun tearDown() {
        if (::db.isInitialized) db.close()
    }

    @Test
    fun `migration creates expected tables`() {
        db = StudioDatabase.openInMemory()
        db.open().use { c ->
            val rs = c.metaData.getTables(null, null, null, arrayOf("TABLE"))
            val names = mutableSetOf<String>()
            while (rs.next()) names += rs.getString("TABLE_NAME").lowercase()
            assertEquals(true, "experiments" in names)
            assertEquals(true, "snapshots" in names)
            assertEquals(true, "scenarios" in names)
            assertEquals(true, "run_bindings" in names)
            assertEquals(true, "idempotency" in names)
            assertEquals(true, "run_state_history" in names)
            assertEquals(true, "runtime_descriptor" in names)
            assertEquals(true, "studio_schema_version" in names)
        }
    }

    @Test
    fun `metadata repository persists and reads experiment`() {
        db = StudioDatabase.openInMemory()
        val repo = StudioMetadataRepository(db)
        val created = repo.createExperiment("first-experiment")
        val read = repo.findExperiment(created.id)
        assertNotNull(read)
        assertEquals(created.id, read.id)
        assertEquals("first-experiment", read.name)
    }

    @Test
    fun `idempotency lookup matches and conflicts`() {
        db = StudioDatabase.openInMemory()
        val idem = IdempotencyRepository(db)
        val key = IdempotencyKey.new()
        val digest = Sha256.digest("a".toByteArray())
        val first = idem.lookup("run", key.value, digest.value)
        assertEquals(IdempotencyLookupResult.NotFound, first)
        val opId = OperationId.new()
        val rec = idem.record("run", key.value, opId, digest.value)
        assertEquals(IdempotencyRecordResult.Recorded, rec)
        val second = idem.lookup("run", key.value, digest.value)
        assertEquals(IdempotencyLookupResult.Match(opId, responseRef = null), second)
        val conflict = idem.lookup("run", key.value, Sha256.digest("b".toByteArray()).value)
        assertEquals(true, conflict is IdempotencyLookupResult.Conflict)
    }

    @Test
    fun `run binding persists state transitions`() {
        db = StudioDatabase.openInMemory()
        val repo = StudioMetadataRepository(db)
        val exp = repo.createExperiment("e")
        // create dummy snapshot + scenario rows to satisfy FK
        db.open().use { c ->
            c.prepareStatement(
                "INSERT INTO snapshots(snapshot_id, experiment_id, sha256, source_utf8, creator, created_at) VALUES(?,?,?,?,?,?)",
            ).use { ps ->
                ps.setString(1, SnapshotId.new().value)
                ps.setString(2, exp.id.value)
                ps.setString(3, Sha256.digest("x".toByteArray()).value)
                ps.setBytes(4, "x".toByteArray())
                ps.setString(5, "test")
                ps.setString(6, java.time.Instant.now().toString())
                ps.executeUpdate()
            }
            c.prepareStatement(
                "INSERT INTO scenarios(scenario_id, version, environment_profile, created_at) VALUES(?,?,?,?)",
            ).use { ps ->
                ps.setString(1, ScenarioId.new().value)
                ps.setInt(2, 1)
                ps.setString(3, "trusted_local")
                ps.setString(4, java.time.Instant.now().toString())
                ps.executeUpdate()
            }
        }
        val snapSnap = repo.findSnapshot(SnapshotId.findAllFirst(db))
        val scSnap = repo.findScenario(ScenarioId.findAllFirst(db))
        assertNotNull(snapSnap)
        assertNotNull(scSnap)
        val binding = RunBinding(
            runId = RunId.new(),
            experimentId = exp.id,
            snapshotId = snapSnap.id,
            scenarioId = scSnap.id,
            runtimeVersion = "0.48.0-rc1",
            runtimeDigest = "test-digest",
            pluginSetDigest = "test-plugins",
            state = RunState.QUEUED,
            upstreamRunId = UpstreamRunId("upstream-1"),
            runDbRef = "/tmp/ctrl/run.db",
            controlRootRef = "/tmp/ctrl",
            createdAt = java.time.Instant.now(),
            updatedAt = java.time.Instant.now(),
        )
        repo.persistRunBinding(binding)
        val read = repo.findRun(binding.runId)
        assertNotNull(read)
        assertEquals("QUEUED", read.state)
    }
}

private fun SnapshotId.Companion.findAllFirst(db: StudioDatabase): SnapshotId {
    db.open().use { c ->
        c.createStatement().use { st ->
            st.executeQuery("SELECT snapshot_id FROM snapshots LIMIT 1").use { rs ->
                if (rs.next()) return SnapshotId(rs.getString(1))
            }
        }
    }
    throw IllegalStateException("no snapshot found")
}

private fun ScenarioId.Companion.findAllFirst(db: StudioDatabase): ScenarioId {
    db.open().use { c ->
        c.createStatement().use { st ->
            st.executeQuery("SELECT scenario_id FROM scenarios LIMIT 1").use { rs ->
                if (rs.next()) return ScenarioId(rs.getString(1))
            }
        }
    }
    throw IllegalStateException("no scenario found")
}
