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
    /**
     * R2.5 (2026-10-11): the frozen plugin set captured at run start.
     * Each entry records the JAR filename, its sha256, the actor that
     * admitted it, and the instant the freeze was captured. The list
     * is sorted by [jarFilename] so the encoded form is deterministic.
     *
     * Default is `emptyList()` so existing call sites that don't wire
     * admission (production boot not yet exposing plugin admission) keep
     * working without modification.
     *
     * Invariant: this list is the *snapshot* of admission at run start.
     * Subsequent changes to the admission lock (revoke, new admit) MUST
     * NOT mutate this field. The freeze is what the run is bound to.
     */
    val frozenPluginDigests: List<FrozenPluginDigest> = emptyList(),
)

/**
 * R2.5 (2026-10-11): one row in the per-run plugin freeze. Mirrors the
 * shape of [dev.pipelinek.studio.application.ports.AdmittedPlugin] so
 * the freeze is a true audit copy of the lock at run start, with
 * [frozenAt] pinned to the moment the run reserved the binding.
 *
 * Not @Serializable here: persistence is in
 * `studio-infra-storage/Repositories.kt`, which encodes via the JSON
 * encoder available there. The domain object stays in pure Kotlin.
 */
data class FrozenPluginDigest(
    val jarFilename: String,
    val sha256: String,
    val admittedBy: String,
    val admittedAt: Instant,
    val frozenAt: Instant,
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
