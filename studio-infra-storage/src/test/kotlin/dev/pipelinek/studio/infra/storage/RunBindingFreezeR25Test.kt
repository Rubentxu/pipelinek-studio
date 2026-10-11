package dev.pipelinek.studio.infra.storage

import dev.pipelinek.studio.domain.Experiment
import dev.pipelinek.studio.domain.ExperimentId
import dev.pipelinek.studio.domain.FrozenPluginDigest
import dev.pipelinek.studio.domain.RunBinding
import dev.pipelinek.studio.domain.RunId
import dev.pipelinek.studio.domain.RunState
import dev.pipelinek.studio.domain.ScenarioId
import dev.pipelinek.studio.domain.Sha256
import dev.pipelinek.studio.domain.SnapshotId
import dev.pipelinek.studio.domain.SourceSnapshot
import dev.pipelinek.studio.domain.UpstreamRunId
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * R2.5 (2026-10-11): storage-layer tests for the per-run plugin
 * freeze. The freeze is JSON-encoded in
 * `run_bindings.frozen_plugin_digests`; [findRunBinding] decodes
 * it back into a [List]<[FrozenPluginDigest]>.
 *
 *  1.  Empty freeze persists as `"[]"` and decodes to empty.
 *  2.  Multi-entry freeze round-trips lossless.
 *  3.  Legacy rows (pre-R2.5, NULL column) decode to empty list.
 *  4.  Malformed JSON decodes to empty list (best-effort read).
 *  5.  The freeze is NOT overwritten on re-persist (B1.2).
 *  6.  `findRunBinding` returns null for missing rows.
 */
class RunBindingFreezeR25Test {

    private lateinit var db: StudioDatabase

    @AfterEach
    fun tearDown() {
        if (::db.isInitialized) db.close()
    }

    private fun seedExperimentAndRefs(repo: StudioMetadataRepository): Triple<ExperimentId, SnapshotId, ScenarioId> {
        val exp = Experiment(ExperimentId.new(), "e", Instant.now())
        repo.saveExperiment(exp)
        val snapId = SnapshotId.new()
        repo.saveSnapshot(
            SourceSnapshot(
                id = snapId,
                experimentId = exp.id,
                sha256 = Sha256.digest("x".toByteArray()),
                sourceUtf8 = "x".toByteArray(),
                createdAt = Instant.now(),
                creator = "test",
            ),
        )
        val scId = ScenarioId.new()
        db.open().use { c ->
            c.prepareStatement(
                "INSERT INTO scenarios(scenario_id, version, environment_profile, created_at) VALUES(?,?,?,?)",
            ).use { ps ->
                ps.setString(1, scId.value); ps.setInt(2, 1)
                ps.setString(3, "trusted_local"); ps.setString(4, Instant.now().toString())
                ps.executeUpdate()
            }
        }
        return Triple(exp.id, snapId, scId)
    }

    @Test
    fun `R2_5 empty freeze persists as JSON empty array and decodes to empty`() {
        db = StudioDatabase.openInMemory()
        val repo = StudioMetadataRepository(db)
        val (expId, snapId, scId) = seedExperimentAndRefs(repo)
        val runId = RunId.new()
        val binding = RunBinding(
            runId = runId,
            experimentId = expId,
            snapshotId = snapId,
            scenarioId = scId,
            runtimeVersion = "0.48.0-rc1",
            runtimeDigest = "d",
            pluginSetDigest = "p",
            state = RunState.CREATED,
            createdAt = Instant.parse("2026-10-11T20:30:00Z"),
            updatedAt = Instant.parse("2026-10-11T20:30:00Z"),
            frozenPluginDigests = emptyList(),
        )
        repo.persistRunBinding(binding)
        val read = repo.findRunBinding(runId)
        assertNotNull(read)
        assertEquals(emptyList(), read.frozenPluginDigests, "empty freeze must round-trip to empty list")
    }

    @Test
    fun `R2_5 multi-entry freeze round-trips lossless`() {
        db = StudioDatabase.openInMemory()
        val repo = StudioMetadataRepository(db)
        val (expId, snapId, scId) = seedExperimentAndRefs(repo)
        val runId = RunId.new()
        val frozenAt = Instant.parse("2026-10-11T20:30:00Z")
        val admittedAt1 = Instant.parse("2026-10-01T00:00:00Z")
        val admittedAt2 = Instant.parse("2026-10-05T00:00:00Z")
        val freeze = listOf(
            FrozenPluginDigest(
                jarFilename = "alpha.jar", sha256 = "a".repeat(64),
                admittedBy = "ops:alice", admittedAt = admittedAt1, frozenAt = frozenAt,
            ),
            FrozenPluginDigest(
                jarFilename = "beta.jar", sha256 = "b".repeat(64),
                admittedBy = "ops:bob", admittedAt = admittedAt2, frozenAt = frozenAt,
            ),
        )
        val binding = RunBinding(
            runId = runId,
            experimentId = expId,
            snapshotId = snapId,
            scenarioId = scId,
            runtimeVersion = "0.48.0-rc1",
            runtimeDigest = "d",
            pluginSetDigest = "computed-digest",
            state = RunState.CREATED,
            createdAt = frozenAt,
            updatedAt = frozenAt,
            frozenPluginDigests = freeze,
        )
        repo.persistRunBinding(binding)
        val read = repo.findRunBinding(runId)
        assertNotNull(read)
        assertEquals(2, read.frozenPluginDigests.size, "freeze must round-trip with the same size")
        // Sorted by jarFilename — the use case sorts before calling
        // persist, so the storage layer sees alpha,beta and writes them
        // in that order.
        assertEquals("alpha.jar", read.frozenPluginDigests[0].jarFilename)
        assertEquals("beta.jar", read.frozenPluginDigests[1].jarFilename)
        assertEquals("ops:alice", read.frozenPluginDigests[0].admittedBy)
        assertEquals("ops:bob", read.frozenPluginDigests[1].admittedBy)
        assertEquals(admittedAt1, read.frozenPluginDigests[0].admittedAt)
        assertEquals(admittedAt2, read.frozenPluginDigests[1].admittedAt)
        assertEquals(frozenAt, read.frozenPluginDigests[0].frozenAt)
    }

    @Test
    fun `R2_5 legacy row with NULL freeze decodes to empty list`() {
        db = StudioDatabase.openInMemory()
        val repo = StudioMetadataRepository(db)
        val (expId, snapId, scId) = seedExperimentAndRefs(repo)
        val runId = RunId.new()
        // Persist a row with empty freeze (default), then NULL the
        // column to simulate a pre-R2.5 migration footprint.
        repo.persistRunBinding(
            RunBinding(
                runId = runId,
                experimentId = expId, snapshotId = snapId, scenarioId = scId,
                runtimeVersion = "v", runtimeDigest = "d", pluginSetDigest = "p",
                state = RunState.CREATED,
                createdAt = Instant.parse("2026-10-11T20:30:00Z"),
                updatedAt = Instant.parse("2026-10-11T20:30:00Z"),
            ),
        )
        db.open().use { c ->
            c.prepareStatement("UPDATE run_bindings SET frozen_plugin_digests = NULL WHERE studio_run_id = ?").use { ps ->
                ps.setString(1, runId.value)
                ps.executeUpdate()
            }
        }
        val read = repo.findRunBinding(runId)
        assertNotNull(read)
        assertEquals(emptyList(), read.frozenPluginDigests, "NULL column must decode to empty list (legacy compatibility)")
    }

    @Test
    fun `R2_5 malformed JSON freeze decodes to empty list best-effort`() {
        db = StudioDatabase.openInMemory()
        val repo = StudioMetadataRepository(db)
        val (expId, snapId, scId) = seedExperimentAndRefs(repo)
        val runId = RunId.new()
        repo.persistRunBinding(
            RunBinding(
                runId = runId,
                experimentId = expId, snapshotId = snapId, scenarioId = scId,
                runtimeVersion = "v", runtimeDigest = "d", pluginSetDigest = "p",
                state = RunState.CREATED,
                createdAt = Instant.parse("2026-10-11T20:30:00Z"),
                updatedAt = Instant.parse("2026-10-11T20:30:00Z"),
            ),
        )
        db.open().use { c ->
            c.prepareStatement("UPDATE run_bindings SET frozen_plugin_digests = ? WHERE studio_run_id = ?").use { ps ->
                ps.setString(1, "this is not json at all")
                ps.setString(2, runId.value)
                ps.executeUpdate()
            }
        }
        val read = repo.findRunBinding(runId)
        assertNotNull(read)
        assertEquals(emptyList(), read.frozenPluginDigests, "malformed JSON must decode to empty list (best-effort read)")
    }

    @Test
    fun `R2_5 re-persist does NOT overwrite the freeze (B1_2 immutability)`() {
        db = StudioDatabase.openInMemory()
        val repo = StudioMetadataRepository(db)
        val (expId, snapId, scId) = seedExperimentAndRefs(repo)
        val runId = RunId.new()
        val frozenAt = Instant.parse("2026-10-11T20:30:00Z")
        val originalFreeze = listOf(
            FrozenPluginDigest("alpha.jar", "a".repeat(64), "ops:alice", Instant.parse("2026-10-01T00:00:00Z"), frozenAt),
        )
        repo.persistRunBinding(
            RunBinding(
                runId = runId,
                experimentId = expId, snapshotId = snapId, scenarioId = scId,
                runtimeVersion = "v", runtimeDigest = "d", pluginSetDigest = "p",
                state = RunState.QUEUED,
                createdAt = frozenAt, updatedAt = frozenAt,
                frozenPluginDigests = originalFreeze,
            ),
        )
        // Re-persist with a different state AND a different freeze
        // (the "rogue" writer tries to retroactively edit the freeze).
        val rogueFreeze = listOf(
            FrozenPluginDigest("evil.jar", "0".repeat(64), "ops:mallory", Instant.parse("2026-10-11T20:31:00Z"), Instant.parse("2026-10-11T20:31:00Z")),
        )
        repo.persistRunBinding(
            RunBinding(
                runId = runId,
                experimentId = expId, snapshotId = snapId, scenarioId = scId,
                runtimeVersion = "v", runtimeDigest = "d", pluginSetDigest = "p",
                state = RunState.SUCCEEDED,
                createdAt = frozenAt, updatedAt = Instant.parse("2026-10-11T20:31:00Z"),
                frozenPluginDigests = rogueFreeze,
            ),
        )
        val read = repo.findRunBinding(runId)
        assertNotNull(read)
        assertEquals(1, read.frozenPluginDigests.size)
        assertEquals("alpha.jar", read.frozenPluginDigests.single().jarFilename, "freeze must NOT be overwritten by a re-persist")
        assertEquals("ops:alice", read.frozenPluginDigests.single().admittedBy)
        // The state, however, is also unchanged (B1.2 immutability)
        // — the re-persist is a no-op, period.
        assertEquals(RunState.QUEUED, read.state, "B1.2: re-persist is a no-op for the entire row, including the freeze")
    }

    @Test
    fun `R2_5 findRunBinding returns null for missing rows`() {
        db = StudioDatabase.openInMemory()
        val repo = StudioMetadataRepository(db)
        val read = repo.findRunBinding(RunId.new())
        assertNull(read, "missing binding must return null")
    }

    @Test
    fun `R2_5 encoded JSON contains all 5 fields per entry`() {
        db = StudioDatabase.openInMemory()
        val repo = StudioMetadataRepository(db)
        val (expId, snapId, scId) = seedExperimentAndRefs(repo)
        val runId = RunId.new()
        val frozenAt = Instant.parse("2026-10-11T20:30:00Z")
        repo.persistRunBinding(
            RunBinding(
                runId = runId,
                experimentId = expId, snapshotId = snapId, scenarioId = scId,
                runtimeVersion = "v", runtimeDigest = "d", pluginSetDigest = "p",
                state = RunState.CREATED,
                createdAt = frozenAt, updatedAt = frozenAt,
                frozenPluginDigests = listOf(
                    FrozenPluginDigest("alpha.jar", "abc", "ops:alice", Instant.parse("2026-10-01T00:00:00Z"), frozenAt),
                ),
            ),
        )
        // Read the raw column to verify the encoded shape
        val raw: String? = db.open().use { c ->
            c.prepareStatement("SELECT frozen_plugin_digests FROM run_bindings WHERE studio_run_id = ?").use { ps ->
                ps.setString(1, runId.value)
                ps.executeQuery().use { rs -> if (rs.next()) rs.getString(1) else null }
            }
        }
        assertNotNull(raw)
        assertTrue(raw.contains("\"jarFilename\""), "encoded JSON must include jarFilename, got: $raw")
        assertTrue(raw.contains("\"sha256\""), "encoded JSON must include sha256, got: $raw")
        assertTrue(raw.contains("\"admittedBy\""), "encoded JSON must include admittedBy, got: $raw")
        assertTrue(raw.contains("\"admittedAt\""), "encoded JSON must include admittedAt, got: $raw")
        assertTrue(raw.contains("\"frozenAt\""), "encoded JSON must include frozenAt, got: $raw")
        assertTrue(raw.contains("alpha.jar"), "encoded JSON must include the jar filename, got: $raw")
    }

    @Test
    fun `R2_5 binding round-trip preserves pluginSetDigest`() {
        db = StudioDatabase.openInMemory()
        val repo = StudioMetadataRepository(db)
        val (expId, snapId, scId) = seedExperimentAndRefs(repo)
        val runId = RunId.new()
        val frozenAt = Instant.parse("2026-10-11T20:30:00Z")
        val freeze = listOf(
            FrozenPluginDigest("alpha.jar", "a".repeat(64), "ops:alice", Instant.parse("2026-10-01T00:00:00Z"), frozenAt),
            FrozenPluginDigest("beta.jar", "b".repeat(64), "ops:bob", Instant.parse("2026-10-05T00:00:00Z"), frozenAt),
        )
        // The pluginSetDigest is what the use case computed via digestOf()
        val expectedDigest = "0123456789abcdef".repeat(4)  // 64 hex chars
        repo.persistRunBinding(
            RunBinding(
                runId = runId,
                experimentId = expId, snapshotId = snapId, scenarioId = scId,
                runtimeVersion = "v", runtimeDigest = "d", pluginSetDigest = expectedDigest,
                state = RunState.CREATED,
                createdAt = frozenAt, updatedAt = frozenAt,
                frozenPluginDigests = freeze,
            ),
        )
        val read = repo.findRunBinding(runId)
        assertNotNull(read)
        assertEquals(expectedDigest, read.pluginSetDigest, "pluginSetDigest must survive the round-trip")
        // The other persisted fields are also intact.
        assertEquals("v", read.runtimeVersion)
        assertEquals("d", read.runtimeDigest)
        assertEquals(UpstreamRunId("u1").let { null }, read.upstreamRunId)  // sanity: nullable
    }
}
