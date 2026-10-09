package dev.pipelinek.studio.application.ports

import dev.pipelinek.studio.domain.ExperimentId
import dev.pipelinek.studio.domain.OperationId
import dev.pipelinek.studio.domain.RunId
import dev.pipelinek.studio.domain.ScenarioId
import dev.pipelinek.studio.domain.SnapshotId
import java.nio.file.Path

/**
 * Hexagonal ports per docs/01-architecture/ARCHITECTURE.md §"Contratos públicos upstream".
 *
 * These are **Studio-side** abstractions. Implementations live in
 * studio-infra-*. Names are proposals, not upstream-declared APIs (per ARCHITECTURE.md).
 *
 * Critical invariant: Studio NEVER invents outcomes — every read port returns upstream
 * evidence or a refusal (`NOT_AVAILABLE`).
 */

/** Identity / metadata about the installed PipelineK distribution. */
data class RuntimeDescriptor(
    val version: String,
    val artifactDigest: String,
    val pluginSdkVersion: String,
    val capabilities: Set<String>,
)

interface PipelineKRuntimePort {
    /** Returns the descriptor for the installed PipelineK, or `RuntimeUnavailable`. */
    fun describe(): RuntimeDescriptorResult
}

sealed interface RuntimeDescriptorResult {
    data class Available(val descriptor: RuntimeDescriptor) : RuntimeDescriptorResult
    object Unavailable : RuntimeDescriptorResult
    data class Failure(val reason: String) : RuntimeDescriptorResult
}

/** Submit a run to the supervised runner. */
data class RunRequest(
    val runId: RunId,
    val snapshotId: SnapshotId,
    val scenarioId: ScenarioId,
    val profile: ExecutionProfile,
)

enum class ExecutionProfile {
    /** JVM-isolated but no OS sandbox certified (D-005). Default in S0. */
    TRUSTED_LOCAL,
}

/** A handle to a running pipeline execution. Implementations must be idempotent on
 *  cancellation queries: asking twice yields the same outcome. */
interface PipelineRunnerPort {
    fun submit(req: RunRequest): SubmissionResult
    fun cancel(runId: RunId): CancellationResult
    fun status(runId: RunId): RunHandleStatus
}

sealed interface SubmissionResult {
    data class Admitted(val upstreamRunId: String, val controlRoot: Path) : SubmissionResult
    data class Refused(val reason: String) : SubmissionResult
    data class AlreadyRunning(val upstreamRunId: String) : SubmissionResult
}

sealed interface CancellationResult {
    object CancelRequested : CancellationResult
    data class AlreadyTerminal(val finalState: String) : CancellationResult
    data class Unknown(val reason: String) : CancellationResult
}

sealed interface RunHandleStatus {
    data class Live(val upstreamRunId: String, val pid: Long?) : RunHandleStatus
    data class Terminal(val finalState: String) : RunHandleStatus
    data class Unknown(val reason: String) : RunHandleStatus
}

/** Read ports separate Event/Output planes per DATA_MODEL_AND_PERSISTENCE.md §"Output Plane". */

data class OutputCursor(val cursor: String, val streamId: String)

data class OutputChunk(
    val streamId: String,
    val bytes: ByteArray,
    val cursor: OutputCursor,
    val eof: Boolean,
) {
    override fun equals(other: Any?): Boolean =
        other is OutputChunk && other.streamId == streamId && other.cursor == cursor
    override fun hashCode(): Int = streamId.hashCode() * 31 + cursor.hashCode()
}

interface OutputReadPort {
    fun read(runId: RunId, cursor: OutputCursor?, byteBudget: Int): OutputReadResult
}

sealed interface OutputReadResult {
    data class Ok(val chunks: List<OutputChunk>, val nextCursor: OutputCursor) : OutputReadResult
    data class UnknownStream(val reason: String) : OutputReadResult
    data class Refused(val reason: String) : OutputReadResult
    data class RecoveryRequired(val reason: String) : OutputReadResult
}

/** State and event journal — Event Plane is authoritative, NEVER the source of bytes. */

data class EventCursor(val sequence: Long)

data class RunEvent(
    val sequence: Long,
    val kind: String,
    val occurredAt: java.time.Instant,
    val payload: Map<String, String>,
)

sealed interface RunReadResult {
    data class Ok(val events: List<RunEvent>, val nextSequence: EventCursor?) : RunReadResult
    data class Unknown(val reason: String) : RunReadResult
}

interface RunReadPort {
    fun events(runId: RunId, after: EventCursor?, limit: Int): RunReadResult
    fun state(runId: RunId): RunReadResult
}

/** Idempotency storage — survives MCP disconnection per DATA_MODEL_AND_PERSISTENCE.md §"MCP desconectado". */
interface IdempotencyPort {
    fun lookup(scope: String, key: String, requestDigestSha256: String): IdempotencyLookupResult
    fun record(scope: String, key: String, operationId: OperationId, requestDigestSha256: String): IdempotencyRecordResult
}

sealed interface IdempotencyLookupResult {
    data class Match(val operationId: OperationId) : IdempotencyLookupResult
    data class Conflict(val reason: String) : IdempotencyLookupResult
    object NotFound : IdempotencyLookupResult
}

sealed interface IdempotencyRecordResult {
    object Recorded : IdempotencyRecordResult
    data class AlreadyExists(val operationId: OperationId) : IdempotencyRecordResult
    data class Failure(val reason: String) : IdempotencyRecordResult
}

/** Persist experiment / snapshot / scenario aggregations. */
interface StudioMetadataPort {
    fun findExperiment(id: ExperimentId): ExperimentSnapshot?
    fun findSnapshot(id: SnapshotId): SnapshotSnapshot?
    fun findScenario(id: ScenarioId): ScenarioSnapshot?
    fun findRun(id: RunId): RunBindingSnapshot?
}

data class ExperimentSnapshot(val id: ExperimentId, val name: String, val createdAt: java.time.Instant)
data class SnapshotSnapshot(
    val id: SnapshotId,
    val experimentId: ExperimentId,
    val sha256: String,
    val createdAt: java.time.Instant,
)
data class ScenarioSnapshot(val id: ScenarioId, val version: Int, val pluginLockDigest: String?)
data class RunBindingSnapshot(
    val id: RunId,
    val experimentId: ExperimentId,
    val snapshotId: SnapshotId,
    val scenarioId: ScenarioId,
    val state: String,
    val createdAt: java.time.Instant,
)
