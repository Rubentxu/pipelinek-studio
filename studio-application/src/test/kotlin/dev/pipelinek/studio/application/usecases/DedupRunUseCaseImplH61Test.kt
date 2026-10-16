@file:Suppress("TooManyFunctions")

package dev.pipelinek.studio.application.usecases

import dev.pipelinek.studio.application.ports.DedupKeyPort
import dev.pipelinek.studio.application.ports.DedupRunResult
import dev.pipelinek.studio.application.ports.DedupRunUseCase
import dev.pipelinek.studio.application.ports.DedupedRunSnapshot
import dev.pipelinek.studio.application.ports.RunRequest
import dev.pipelinek.studio.domain.RunId
import dev.pipelinek.studio.domain.RunState
import dev.pipelinek.studio.domain.ScenarioId
import dev.pipelinek.studio.domain.SnapshotId
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Falsifiers for the SFW-H6.1
 * [DedupRunUseCaseImpl] — the use case that
 * checks whether a run with the same dedup
 * key already exists.
 */
@DisplayName("DedupRunUseCaseImpl (SFW-H6.1 dedup-key surface)")
class DedupRunUseCaseImplH61Test {

    private val request = RunRequest(
        runId = RunId("studio-h61-1"),
        snapshotId = SnapshotId("snapshot-h61"),
        scenarioId = ScenarioId("scenario-h61"),
        profile = dev.pipelinek.studio.application.ports.ExecutionProfile.TrustedLocal,
    )

    // ------------------------------------------------------------------
    // Falsifier 1: port returns DedupedRun -> use case returns DedupedRun
    // ------------------------------------------------------------------
    @Test
    fun `port returns DedupedRun maps to DedupRunResult DedupedRun`() = runBlocking {
        val port = FakeDedupKeyPortForH61().apply {
            nextFound = DedupedRunSnapshot(
                studioRunId = RunId("studio-h61-existing"),
                state = RunState.RUNNING,
            )
        }
        val useCase = DedupRunUseCaseImpl(port)

        val result = useCase.checkDuplicate(request)

        val deduped = result as? DedupRunResult.DedupedRun
        assertTrue(deduped != null, "expected DedupRunResult.DedupedRun, got $result")
        assertEquals(RunId("studio-h61-existing"), deduped!!.studioRunId)
        assertEquals(RunState.RUNNING, deduped.state)
    }

    // ------------------------------------------------------------------
    // Falsifier 2: port returns null -> use case returns NewRun
    // ------------------------------------------------------------------
    @Test
    fun `port returns null maps to DedupRunResult NewRun`() = runBlocking {
        val port = FakeDedupKeyPortForH61().apply {
            nextFound = null
        }
        val useCase = DedupRunUseCaseImpl(port)

        val result = useCase.checkDuplicate(request)

        assertEquals(DedupRunResult.NewRun, result)
    }

    // ------------------------------------------------------------------
    // Falsifier 3: two requests with same content -> same dedup key
    // ------------------------------------------------------------------
    @Test
    fun `two requests with same content produce same dedup key`() {
        val r1 = RunRequest(
            runId = RunId("studio-h61-r1"),
            snapshotId = SnapshotId("snapshot-shared"),
            scenarioId = ScenarioId("scenario-shared"),
            profile = dev.pipelinek.studio.application.ports.ExecutionProfile.TrustedLocal,
        )
        val r2 = RunRequest(
            runId = RunId("studio-h61-r2"),
            snapshotId = SnapshotId("snapshot-shared"),
            scenarioId = ScenarioId("scenario-shared"),
            profile = dev.pipelinek.studio.application.ports.ExecutionProfile.TrustedLocal,
        )
        val k1 = DedupRunUseCaseImpl.computeDedupKey(r1)
        val k2 = DedupRunUseCaseImpl.computeDedupKey(r2)
        assertEquals(k1, k2, "requests with same content must produce same dedup key")
    }

    // ------------------------------------------------------------------
    // Falsifier 4: two requests with different content -> different dedup keys
    // ------------------------------------------------------------------
    @Test
    fun `two requests with different content produce different dedup keys`() {
        val r1 = RunRequest(
            runId = RunId("studio-h61-r1"),
            snapshotId = SnapshotId("snapshot-1"),
            scenarioId = ScenarioId("scenario-1"),
            profile = dev.pipelinek.studio.application.ports.ExecutionProfile.TrustedLocal,
        )
        val r2 = RunRequest(
            runId = RunId("studio-h61-r2"),
            snapshotId = SnapshotId("snapshot-2"),
            scenarioId = ScenarioId("scenario-1"),
            profile = dev.pipelinek.studio.application.ports.ExecutionProfile.TrustedLocal,
        )
        val k1 = DedupRunUseCaseImpl.computeDedupKey(r1)
        val k2 = DedupRunUseCaseImpl.computeDedupKey(r2)
        assertTrue(k1 != k2, "requests with different content must produce different dedup keys")
    }

    // ------------------------------------------------------------------
    // Falsifier 5: unexpected exception on the port -> Refused
    // ------------------------------------------------------------------
    @Test
    fun `unexpected exception on the port maps to Refused (not Unknown)`() = runBlocking {
        val port = FakeDedupKeyPortForH61().apply {
            throwOnFind = RuntimeException("SQLite database is locked")
        }
        val useCase = DedupRunUseCaseImpl(port)

        val result = useCase.checkDuplicate(request)

        val refused = result as? DedupRunResult.Refused
        assertTrue(refused != null, "expected DedupRunResult.Refused, got $result")
        assertTrue(
            refused!!.reason.contains("dedup check failed"),
            "reason should reference dedup check failed: ${refused.reason}",
        )
    }
}

// --------------------------------------------------------------------
// Test fixtures
// --------------------------------------------------------------------

private class FakeDedupKeyPortForH61 : DedupKeyPort {
    var nextFound: DedupedRunSnapshot? = null
    var throwOnFind: Throwable? = null
    var findCalls: Int = 0
        private set
    var lastDedupKey: String? = null
        private set

    override suspend fun findByDedupKey(dedupKey: String): DedupedRunSnapshot? {
        findCalls += 1
        lastDedupKey = dedupKey
        throwOnFind?.let { throw it }
        return nextFound
    }

    // SFW-H6.4: the release method is part of
    // the port surface; the H6.1 test fake
    // implements it as a noop.
    override suspend fun release(studioRunId: RunId): Boolean = true
}
