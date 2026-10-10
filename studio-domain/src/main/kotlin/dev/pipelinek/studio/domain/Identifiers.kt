package dev.pipelinek.studio.domain

import java.util.UUID

/**
 * Typed identifiers for Studio domain. Each id is a UUID wrapped in a value class so
 * the type system prevents accidental mixing.
 *
 * Per docs/01-architecture/DATA_MODEL_AND_PERSISTENCE.md:
 * - OperationId != RunId != IdempotencyKey. Three distinct identities.
 */
@JvmInline value class ExperimentId(val value: String) {
    init { require(value.isNotBlank()) { "ExperimentId cannot be blank" } }
    companion object { fun new(): ExperimentId = ExperimentId(UUID.randomUUID().toString()) }
}

@JvmInline value class SnapshotId(val value: String) {
    init { require(value.isNotBlank()) { "SnapshotId cannot be blank" } }
    companion object { fun new(): SnapshotId = SnapshotId(UUID.randomUUID().toString()) }
}

@JvmInline value class ScenarioId(val value: String) {
    init { require(value.isNotBlank()) { "ScenarioId cannot be blank" } }
    companion object { fun new(): ScenarioId = ScenarioId(UUID.randomUUID().toString()) }
}

@JvmInline value class RunId(val value: String) {
    init { require(value.isNotBlank()) { "RunId cannot be blank" } }
    companion object { fun new(): RunId = RunId(UUID.randomUUID().toString()) }
}

/**
 * Audit H-25 (2026-10-10): the RunReconciler previously trusted
 * whatever directory name sat under `controlRootBase/<dir>/run.db`
 * and exposed the name verbatim as a RunId. An operator that
 * dropped `../something` or a non-UUID name would either
 * - create a ReconciliationEntry whose runId is a path-like
 *   string (later surfaced via `/api/recovery` and potentially
 *   used as a filesystem argument), or
 * - feed a non-RunId string into a downstream component that
 *   assumed the canonical UUID shape.
 *
 * The shared helper below pins the canonical UUID regex and is
 * the only place the studio validates an "id-like" string. Both
 * [RetentionService] and [RunReconciler] use it to gate which
 * siblings of controlRootBase are considered real per-run
 * directories.
 */
object IdFormat {
    private val UUID_REGEX = Regex(
        "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$",
    )

    /** True if [s] is a canonical UUID string (8-4-4-4-12 hex). Version-agnostic. */
    fun isUuidLike(s: String?): Boolean = s != null && UUID_REGEX.matches(s)
}

@JvmInline value class OperationId(val value: String) {
    init { require(value.isNotBlank()) { "OperationId cannot be blank" } }
    companion object { fun new(): OperationId = OperationId(UUID.randomUUID().toString()) }
}

@JvmInline value class IdempotencyKey(val value: String) {
    init { require(value.isNotBlank()) { "IdempotencyKey cannot be blank" } }
    companion object { fun new(): IdempotencyKey = IdempotencyKey(UUID.randomUUID().toString()) }
}

@JvmInline value class UpstreamRunId(val value: String) {
    init { require(value.isNotBlank()) { "UpstreamRunId cannot be blank" } }
}
