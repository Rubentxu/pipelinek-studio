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
