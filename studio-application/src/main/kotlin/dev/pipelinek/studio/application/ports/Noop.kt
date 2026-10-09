package dev.pipelinek.studio.application.ports

import dev.pipelinek.studio.domain.ExperimentId
import dev.pipelinek.studio.domain.OperationId
import dev.pipelinek.studio.domain.RunId
import dev.pipelinek.studio.domain.RunState
import dev.pipelinek.studio.domain.ScenarioId
import dev.pipelinek.studio.domain.SnapshotId

/** Noop metadata for in-memory wiring without SQLite (used in CLI smoke tests). */
object StudionMetadataNoop : StudioMetadataPort {
    override fun findExperiment(id: ExperimentId) = null
    override fun findSnapshot(id: SnapshotId) = null
    override fun findScenario(id: ScenarioId) = null
    override fun findRun(id: RunId) = null
    override fun listRuns(state: RunState?, limit: Int, offset: Int): List<RunBindingSnapshot> = emptyList()
    // S1 E1.5: the noop port silently drops saves + returns null on read.
    // The CLI smoke test never reaches submit, so this is the right behaviour
    // for that surface. Production code (studio-app) wires the real repo.
    override fun saveSnapshot(snap: dev.pipelinek.studio.domain.SourceSnapshot) { /* drop */ }
    override fun loadSnapshotSource(id: SnapshotId): ByteArray? = null
}

/** Noop idempotency for in-memory wiring. */
object IdempotencyNoop : IdempotencyPort {
    override fun lookup(scope: String, key: String, requestDigestSha256: String) =
        IdempotencyLookupResult.NotFound
    override fun record(scope: String, key: String, operationId: OperationId, requestDigestSha256: String) =
        IdempotencyRecordResult.Recorded
}
