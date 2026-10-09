package dev.pipelinek.studio.domain

import java.time.Instant

/**
 * A run binds an immutable snapshot + scenario + runtime version + plugin digests +
 * per-run ownership. It is the studio-side projection of an upstream PipelineK execution.
 *
 * Per DATA_MODEL_AND_PERSISTENCE.md: an upstream run id is recorded for cross-ref but the
 * studio-run-id is the public identity. run.db lives under controlRootRef managed by studio;
 * one run.db per run to avoid lease/sequence collisions.
 */
data class RunBinding(
    val runId: RunId,
    val experimentId: ExperimentId,
    val snapshotId: SnapshotId,
    val scenarioId: ScenarioId,
    val runtimeVersion: String,
    val runtimeDigest: String,
    val pluginSetDigest: String,
    val state: RunState,
    val upstreamRunId: UpstreamRunId? = null,
    val runDbRef: String? = null,
    val controlRootRef: String? = null,
    val createdAt: Instant,
    val updatedAt: Instant,
)

data class Scenario(
    val id: ScenarioId,
    val version: Int,
    val inputRefs: List<String>,
    val expectedOutcomes: List<String>,
    val environmentProfile: String,
    val pluginLockDigest: String?,
)

data class Experiment(
    val id: ExperimentId,
    val name: String,
    val createdAt: Instant,
)

/** Captured operation for idempotency replay per RELEASE_PROTOCOL §"MCP desconectado". */
data class Operation(
    val id: OperationId,
    val idempotencyKey: IdempotencyKey,
    val requestDigest: Sha256,
    val responseRef: String? = null,
    val createdAt: Instant,
)

/** Optional cancel request signal — distinct from state, see [RunState.CANCELLED]. */
data class RunCancelRequested(val runId: RunId, val at: Instant)
