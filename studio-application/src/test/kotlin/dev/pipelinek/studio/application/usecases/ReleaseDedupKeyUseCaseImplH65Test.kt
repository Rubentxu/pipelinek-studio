@file:Suppress("TooManyFunctions")

package dev.pipelinek.studio.application.usecases

import dev.pipelinek.studio.application.ports.DedupKeyPort
import dev.pipelinek.studio.application.ports.DedupedRunSnapshot
import dev.pipelinek.studio.application.ports.ReleaseDedupKeyResult
import dev.pipelinek.studio.application.ports.RunBindingSnapshot
import dev.pipelinek.studio.application.ports.StudioMetadataPort
import dev.pipelinek.studio.domain.ExperimentId
import dev.pipelinek.studio.domain.RunId
import dev.pipelinek.studio.domain.ScenarioId
import dev.pipelinek.studio.domain.SnapshotId
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Falsifiers for the SFW-H6.5+ standalone
 * [ReleaseDedupKeyUseCaseImpl]. The H6.5+
 * unit extracts the H6.4 cancel-path hook
 * into a named use case.
 */
@DisplayName("ReleaseDedupKeyUseCaseImpl (SFW-H6.5+ standalone release)")
class ReleaseDedupKeyUseCaseImplH65Test {

    private val studioRunId = RunId("studio-h65-1")

    // ------------------------------------------------------------------
    // Falsifier 1: state=CREATED -> Released
    // ------------------------------------------------------------------
    @Test
    fun `state CREATED releases the dedup key and returns Released`() = runBlocking {
        val port = FakeDedupKeyPortForH65()
        val meta = FakeMetadataForH65(state = "CREATED")
        val useCase = ReleaseDedupKeyUseCaseImpl(meta, port)
        val result = useCase.release(studioRunId)
        assertEquals(ReleaseDedupKeyResult.Released, result)
        assertEquals(1, port.releaseCalls, "release must be called once for CREATED state")
    }

    // ------------------------------------------------------------------
    // Falsifier 2: state=RUNNING -> NotApplicable (no release)
    // ------------------------------------------------------------------
    @Test
    fun `state RUNNING returns NotApplicable and does NOT release`() = runBlocking {
        val port = FakeDedupKeyPortForH65()
        val meta = FakeMetadataForH65(state = "RUNNING")
        val useCase = ReleaseDedupKeyUseCaseImpl(meta, port)
        val result = useCase.release(studioRunId)
        assertEquals(ReleaseDedupKeyResult.NotApplicable, result)
        assertEquals(0, port.releaseCalls, "release must NOT be called for RUNNING state")
    }

    // ------------------------------------------------------------------
    // Falsifier 3: state=TERMINAL -> NotApplicable (no release)
    // ------------------------------------------------------------------
    @Test
    fun `state CANCELLED returns NotApplicable and does NOT release`() = runBlocking {
        val port = FakeDedupKeyPortForH65()
        val meta = FakeMetadataForH65(state = "CANCELLED")
        val useCase = ReleaseDedupKeyUseCaseImpl(meta, port)
        val result = useCase.release(studioRunId)
        assertEquals(ReleaseDedupKeyResult.NotApplicable, result)
        assertEquals(0, port.releaseCalls, "release must NOT be called for terminal state")
    }

    // ------------------------------------------------------------------
    // Falsifier 4: binding not found -> NotApplicable
    // ------------------------------------------------------------------
    @Test
    fun `binding not found returns NotApplicable`() = runBlocking {
        val port = FakeDedupKeyPortForH65()
        val meta = FakeMetadataForH65NotFound()
        val useCase = ReleaseDedupKeyUseCaseImpl(meta, port)
        val result = useCase.release(studioRunId)
        assertEquals(ReleaseDedupKeyResult.NotApplicable, result)
        assertEquals(0, port.releaseCalls, "release must NOT be called when binding not found")
    }

    // ------------------------------------------------------------------
    // Falsifier 5: port failure -> Refused
    // ------------------------------------------------------------------
    @Test
    fun `port failure returns Refused with reason`() = runBlocking {
        val port = FakeDedupKeyPortForH65(throwOnRelease = RuntimeException("SQLite locked"))
        val meta = FakeMetadataForH65(state = "CREATED")
        val useCase = ReleaseDedupKeyUseCaseImpl(meta, port)
        val result = useCase.release(studioRunId)
        val refused = result as? ReleaseDedupKeyResult.Refused
        assertNotNull(refused, "expected Refused, got $result")
        assertTrue(
            refused!!.reason.contains("release failed"),
            "reason should reference release failed: ${refused.reason}",
        )
    }
}

// --------------------------------------------------------------------
// Test fixtures
// --------------------------------------------------------------------

private class FakeDedupKeyPortForH65(
    var throwOnRelease: Throwable? = null,
) : DedupKeyPort {
    var releaseCalls: Int = 0
        private set
    var lastReleaseRunId: RunId? = null
        private set

    override suspend fun findByDedupKey(dedupKey: String): DedupedRunSnapshot? = null
    override suspend fun release(studioRunId: RunId): Boolean {
        releaseCalls += 1
        lastReleaseRunId = studioRunId
        throwOnRelease?.let { throw it }
        return true
    }
}

private class FakeMetadataForH65(
    val state: String,
) : StudioMetadataPort {
    override fun findExperiment(id: ExperimentId) = null
    override fun findSnapshot(id: SnapshotId) = null
    override fun findScenario(id: ScenarioId) = null
    override fun findRun(id: RunId): RunBindingSnapshot? = RunBindingSnapshot(
        id = id,
        experimentId = ExperimentId("00000000-0000-0000-0000-000000000000"),
        snapshotId = SnapshotId("snapshot-h65"),
        scenarioId = ScenarioId("scenario-h65"),
        state = state,
        createdAt = java.time.Instant.now(),
    )
    override fun listRuns(state: dev.pipelinek.studio.domain.RunState?, limit: Int, offset: Int) = emptyList<RunBindingSnapshot>()
    override fun listScenarios(limit: Int, offset: Int) = emptyList<dev.pipelinek.studio.application.ports.ScenarioSnapshot>()
    override fun saveScenario(scenario: dev.pipelinek.studio.domain.SourceScenario) { /* drop */ }
    override fun saveExperiment(experiment: dev.pipelinek.studio.domain.Experiment) { /* drop */ }
    override fun saveSnapshot(snap: dev.pipelinek.studio.domain.SourceSnapshot) { /* noop */ }
    override fun loadSnapshotSource(id: SnapshotId): ByteArray? = null
    override fun persistRunBinding(binding: dev.pipelinek.studio.domain.RunBinding) { /* drop */ }
    override fun transitionRunState(studioRunId: RunId, newState: dev.pipelinek.studio.domain.RunState) { /* drop */ }
}

private class FakeMetadataForH65NotFound : StudioMetadataPort {
    override fun findExperiment(id: ExperimentId) = null
    override fun findSnapshot(id: SnapshotId) = null
    override fun findScenario(id: ScenarioId) = null
    override fun findRun(id: RunId): RunBindingSnapshot? = null
    override fun listRuns(state: dev.pipelinek.studio.domain.RunState?, limit: Int, offset: Int) = emptyList<RunBindingSnapshot>()
    override fun listScenarios(limit: Int, offset: Int) = emptyList<dev.pipelinek.studio.application.ports.ScenarioSnapshot>()
    override fun saveScenario(scenario: dev.pipelinek.studio.domain.SourceScenario) { /* drop */ }
    override fun saveExperiment(experiment: dev.pipelinek.studio.domain.Experiment) { /* drop */ }
    override fun saveSnapshot(snap: dev.pipelinek.studio.domain.SourceSnapshot) { /* noop */ }
    override fun loadSnapshotSource(id: SnapshotId): ByteArray? = null
    override fun persistRunBinding(binding: dev.pipelinek.studio.domain.RunBinding) { /* drop */ }
    override fun transitionRunState(studioRunId: RunId, newState: dev.pipelinek.studio.domain.RunState) { /* drop */ }
}
