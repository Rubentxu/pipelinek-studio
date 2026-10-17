package dev.pipelinek.studio.infra.storage

import dev.pipelinek.studio.domain.Experiment
import dev.pipelinek.studio.domain.ExperimentId
import dev.pipelinek.studio.domain.Sha256
import dev.pipelinek.studio.domain.SourceSnapshot
import dev.pipelinek.studio.domain.SnapshotId
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * R5 EVO-C.T3 — collection reads backing `GET /experiments` and
 * `GET /experiments/{experimentId}/snapshots`.
 *
 * The SQL is additive: ordering (created_at DESC) and pagination
 * (LIMIT/OFFSET) mirror [StudioMetadataRepository.listScenarios]; a
 * re-save of an immutable row must not duplicate it in the collection.
 */
class MetadataCollectionReadR5Test {
    private lateinit var db: StudioDatabase

    @AfterEach
    fun tearDown() {
        if (::db.isInitialized) db.close()
    }

    private fun repo(): StudioMetadataRepository {
        db = StudioDatabase.openInMemory()
        return StudioMetadataRepository(db)
    }

    private fun experiment(name: String, createdAt: String): Experiment =
        Experiment(ExperimentId.new(), name, Instant.parse(createdAt))

    private fun snapshot(experimentId: ExperimentId, digest: String, createdAt: String): SourceSnapshot =
        SourceSnapshot(
            id = SnapshotId.new(),
            experimentId = experimentId,
            sha256 = Sha256(digest),
            sourceUtf8 = "fun main() {}".toByteArray(),
            creator = "test",
            createdAt = Instant.parse(createdAt),
        )

    @Test
    fun `listExperiments returns newest first and paginates`() {
        val repository = repo()
        val older = experiment("older", "2026-02-01T00:00:00Z")
        val newer = experiment("newer", "2026-03-01T00:00:00Z")
        repository.saveExperiment(older)
        repository.saveExperiment(newer)

        val all = repository.listExperiments(10, 0)
        assertEquals(listOf(newer.id, older.id), all.map { it.id })

        val firstPage = repository.listExperiments(1, 0)
        assertEquals(listOf(newer.id), firstPage.map { it.id })
        val secondPage = repository.listExperiments(1, 1)
        assertEquals(listOf(older.id), secondPage.map { it.id })
        assertEquals(emptyList(), repository.listExperiments(10, 5))
    }

    @Test
    fun `listExperiments never duplicates an immutable row`() {
        val repository = repo()
        val e = experiment("once", "2026-02-01T00:00:00Z")
        repository.saveExperiment(e)
        repository.saveExperiment(e)
        val items = repository.listExperiments(10, 0)
        assertEquals(1, items.size)
        assertEquals("once", items.single().name)
    }

    @Test
    fun `listSnapshots filters by experiment and orders newest first`() {
        val repository = repo()
        val a = experiment("a", "2026-02-01T00:00:00Z")
        val b = experiment("b", "2026-02-02T00:00:00Z")
        repository.saveExperiment(a)
        repository.saveExperiment(b)
        val aOld = snapshot(a.id, "1".repeat(64), "2026-02-03T00:00:00Z")
        val aNew = snapshot(a.id, "2".repeat(64), "2026-02-04T00:00:00Z")
        val bOnly = snapshot(b.id, "3".repeat(64), "2026-02-05T00:00:00Z")
        repository.saveSnapshot(aOld)
        repository.saveSnapshot(aNew)
        repository.saveSnapshot(bOnly)

        val forA = repository.listSnapshots(a.id, 10, 0)
        assertEquals(listOf(aNew.id, aOld.id), forA.map { it.id })
        assertTrue(forA.all { it.experimentId == a.id })
        assertEquals(listOf(bOnly.id), repository.listSnapshots(b.id, 10, 0).map { it.id })
        assertEquals(emptyList(), repository.listSnapshots(ExperimentId.new(), 10, 0))
    }

    @Test
    fun `collection reads reject out-of-contract bounds`() {
        val repository = repo()
        assertFailsWith<IllegalArgumentException> { repository.listExperiments(0, 0) }
        assertFailsWith<IllegalArgumentException> { repository.listExperiments(10, -1) }
        assertFailsWith<IllegalArgumentException> { repository.listSnapshots(ExperimentId.new(), 0, 0) }
    }
}
