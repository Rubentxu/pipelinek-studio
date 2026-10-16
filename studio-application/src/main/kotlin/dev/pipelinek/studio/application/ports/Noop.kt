package dev.pipelinek.studio.application.ports

import dev.pipelinek.studio.domain.Experiment
import dev.pipelinek.studio.domain.ExperimentId
import dev.pipelinek.studio.domain.OperationId
import dev.pipelinek.studio.domain.RunId
import dev.pipelinek.studio.domain.RunState
import dev.pipelinek.studio.domain.ScenarioId
import dev.pipelinek.studio.domain.SnapshotId

/** Noop metadata for in-memory wiring without SQLite (used in CLI smoke tests). */
open class StudionMetadataNoop : StudioMetadataPort {
    override fun findExperiment(id: ExperimentId) = null
    override fun findSnapshot(id: SnapshotId) = null
    override fun findScenario(id: ScenarioId) = null
    override fun findRun(id: RunId) = null
    override fun listRuns(state: RunState?, limit: Int, offset: Int): List<RunBindingSnapshot> = emptyList()
    // R0.3.1 (2026-10-11): list scenarios — same noop-drop semantics as
    // listRuns. Production wires the real SQLite-backed repository.
    override fun listScenarios(limit: Int, offset: Int): List<ScenarioSnapshot> = emptyList()
    // B1.1: the noop port silently drops saveExperiment. The CLI smoke test
    // never queries the DB after create, so this is the right behaviour for
    // that surface. Production code (studio-app) wires the real repo.
    override fun saveExperiment(experiment: Experiment) { /* drop */ }
    // S1 E1.5: the noop port silently drops saves + returns null on read.
    // The CLI smoke test never reaches submit, so this is the right behaviour
    // for that surface. Production code (studio-app) wires the real repo.
    override fun saveSnapshot(snap: dev.pipelinek.studio.domain.SourceSnapshot) { /* drop */ }
    override fun loadSnapshotSource(id: SnapshotId): ByteArray? = null
    // B1.3 + B1.4: same noop-drop semantics for the run binding persist.
    // The CLI smoke harness never reaches this path; production wires
    // the real repo.
    override fun persistRunBinding(binding: dev.pipelinek.studio.domain.RunBinding) { /* drop */ }
    // R0.3.1 (2026-10-11): scenario register — silent drop in the noop
    // variant. Production wires the real SQLite-backed repository.
    override fun saveScenario(scenario: dev.pipelinek.studio.domain.SourceScenario) { /* drop */ }
    // SFW-H4.5: the noop port silently drops state transitions.
    // The CLI smoke harness never reaches the recovery path;
    // production wires the real SQLite-backed repository.
    override fun transitionRunState(studioRunId: RunId, newState: RunState) { /* drop */ }
}

/** Noop idempotency for in-memory wiring. */
object IdempotencyNoop : IdempotencyPort {
    override fun lookup(scope: String, key: String, requestDigestSha256: String) =
        IdempotencyLookupResult.NotFound
    override fun record(
        scope: String,
        key: String,
        operationId: OperationId,
        requestDigestSha256: String,
        responseRef: String?,
    ) = IdempotencyRecordResult.Recorded
}
