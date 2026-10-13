package dev.pipelinek.studio.application.ports

import dev.pipelinek.studio.domain.Experiment
import dev.pipelinek.studio.domain.ExperimentId
import dev.pipelinek.studio.domain.OperationId
import dev.pipelinek.studio.domain.RunId
import dev.pipelinek.studio.domain.ScenarioId
import dev.pipelinek.studio.domain.SnapshotId
import dev.pipelinek.studio.domain.SourceSnapshot
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
    /**
     * R5.4 (2026-10-12): how the version string was obtained.
     * `Manifest` is the strict path: the jar's `MANIFEST.MF`
     * carried an `Implementation-Version`. `LockfileBySha` is
     * the R5.4 fallback: the manifest had no version, so the
     * studio matched the installed jar's SHA-256 against
     * `sdk.lock.json` and adopted the pinned version. The cert
     * asserts `Manifest` for the strict surface and accepts
     * `LockfileBySha` as a documented exception; any other
     * value (e.g. `Unknown`) is a hard FAIL.
     */
    val versionSource: VersionSource = VersionSource.Manifest,
)

/**
 * R5.4 (2026-10-12): how the [RuntimeDescriptor.version] was
 * resolved. The studio is honest: if neither the manifest nor
 * the lockfile-by-SHA lookup can identify the SDK, the
 * probe returns [RuntimeDescriptorResult.Failure] and the
 * `versionSource` field is never emitted. The two sources
 * below are the only values the studio emits; certs and UI
 * code can pattern-match them safely.
 */
enum class VersionSource {
    /** The version was read straight from the jar's MANIFEST.MF. */
    Manifest,
    /**
     * The jar's manifest had no version, but the SHA-256
     * matched an entry in `sdk.lock.json` and the pinned
     * version was adopted.
     */
    LockfileBySha,
}

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

/**
 * B2.4 (2026-10-10): the execution profile is now a sealed
 * class, not an enum with one case. The new shape lets us
 * represent the seam for an UNTRUSTED profile (a future
 * non-trusted isolation profile that requires an OS-level
 * sandbox) without committing to one. The B2 mandate is
 * explicit: 'Si se necesita un perfil no confiable, admitirlo
 * solo cuando haya un mecanismo OS concreto y un test de
 * aislamiento superado. En otro caso, denegarlo.' The single
 * case [TrustedLocal] is JVM-isolated only — 'aislamiento
 * JVM no significa sandbox de sistema operativo' (mandate
 * § 6.5). [Untrusted] is the seam; the runner denies it
 * until an OS-level sandbox + isolation test exist.
 */
sealed interface ExecutionProfile {
    /**
     * JVM-isolated but no OS sandbox certified (D-005). The
     * only profile currently accepted by the runner.
     */
    data object TrustedLocal : ExecutionProfile

    /**
     * Reserved for a future non-trusted profile that requires
     * an OS-level sandbox. The runner MUST reject this with
     * a clear refusal until an isolation test demonstrates
     * the boundary holds. The class is here so the request
     * envelope stays sealed, not to imply the runner accepts
     * it.
     */
    data object Untrusted : ExecutionProfile
}

/** A handle to a running pipeline execution. Implementations must be idempotent on
 *  cancellation queries: asking twice yields the same outcome. */
interface PipelineRunnerPort {
    fun submit(req: RunRequest): SubmissionResult
    fun cancel(runId: RunId): CancellationResult
    fun status(runId: RunId): RunHandleStatus
}

sealed interface SubmissionResult {
    // R6.1.2 (2026-10-12): upstreamRunId is nullable. The
    // runner used to block the submit handler on
    // readUpstreamIdFromJournal (~5-10s for the subprocess
    // to compile and emit RunStarted). The handler now
    // admits as soon as the subprocess is spawned and reads
    // the upstreamId asynchronously. /api/version can
    // surface "upstream id pending" when the value is
    // null, then update when the async reader populates
    // it. The cert's cancel path uses studioRunId (the URL
    // key) and does not need the upstreamId.
    data class Admitted(val upstreamRunId: String?, val controlRoot: Path) : SubmissionResult
    data class Refused(val reason: String) : SubmissionResult
    data class AlreadyRunning(val upstreamRunId: String?) : SubmissionResult
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
    /**
     * B1.5: return the current observation of the run. The default
     * implementation derives [RunObservation] from [state] (the
     * legacy three-case enum) and the journal; adapters with
     * access to the run.db can override to surface
     * RecoveryRequired vs Unavailable precisely. The mandate
     * requires the four-case ADT; the three-case state() is
     * kept for back-compat but every consumer should prefer
     * observation() in new code.
     */
    fun observation(runId: RunId): dev.pipelinek.studio.domain.RunObservation {
        return when (val s = state(runId)) {
            is RunReadResult.Ok -> dev.pipelinek.studio.domain.RunObservation.Terminal(
                dev.pipelinek.studio.domain.VerifiedOutcome.DeclaredPending,
            )
            is RunReadResult.Unknown -> dev.pipelinek.studio.domain.RunObservation.Unavailable(s.reason)
        }
    }
}

/** Idempotency storage — survives MCP disconnection per DATA_MODEL_AND_PERSISTENCE.md §"MCP desconectado". */
interface IdempotencyPort {
    fun lookup(scope: String, key: String, requestDigestSha256: String): IdempotencyLookupResult
    fun record(
        scope: String,
        key: String,
        operationId: OperationId,
        requestDigestSha256: String,
        responseRef: String? = null,
    ): IdempotencyRecordResult
}

sealed interface IdempotencyLookupResult {
    /**
     * A replay: the same key + same digest has been seen before. The
     * [operationId] is the original operation's id, and [responseRef]
     * is the durable pointer the original caller recorded (typically
     * the studio_run_id, so the HTTP layer can re-emit the same
     * response). Without [responseRef] we cannot honour AAT-API-008
     * (Idempotency-Key => same run_id); the field is null only when
     * the row was recorded before the schema gained it.
     */
    data class Match(val operationId: OperationId, val responseRef: String?) : IdempotencyLookupResult
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
    /**
     * List runs ordered by created_at DESC. The [state] filter is optional;
     * when null, all states are returned. The [limit] is bounded by the
     * caller — the use case layer enforces a hard ceiling (default 200).
     * Returns an empty list when the table is empty.
     */
    fun listRuns(state: dev.pipelinek.studio.domain.RunState?, limit: Int, offset: Int): List<RunBindingSnapshot>

    /**
     * R0.3.1 (2026-10-11): list scenarios ordered by created_at DESC, paginated.
     * Returns an empty list when the table is empty.
     */
    fun listScenarios(limit: Int, offset: Int): List<ScenarioSnapshot>

    /**
     * B1.1: persist a freshly-created experiment. The use case owns the
     * ExperimentId (minted once at construction) and the createdAt timestamp
     * (from the injected clock); the port never invents an id. Re-persisting
     * the same ExperimentId is a no-op (`INSERT ... ON CONFLICT DO NOTHING`)
     * because experiments are immutable once sealed.
     */
    fun saveExperiment(experiment: Experiment)

    /**
     * S1 E1.5: persist a freshly-sealed snapshot's metadata + source bytes.
     * The bytes are stored as a BLOB in `snapshots.source_utf8`; the runner
     * reads them back via [loadSnapshotSource] when spawning the SDK subprocess.
     */
    fun saveSnapshot(snap: SourceSnapshot)

    /**
     * R0.3.1 (2026-10-11): persist a freshly-registered scenario. The use case
     * owns the [ScenarioId] (minted once at register time) and the version
     * (monotonically increasing per scenario). The plugin lock digest is
     * recorded so a run that pins that scenario can verify the plugin
     * registry has not changed since the scenario was sealed.
     *
     * Re-registering `(id, version)` is a no-op (`INSERT ... ON CONFLICT
     * DO NOTHING`) — scenarios are immutable once their version is sealed.
     */
    fun saveScenario(scenario: dev.pipelinek.studio.domain.SourceScenario)

    /**
     * S1 E1.5: read the original sealed source bytes for a snapshot. The
     * runner uses this to write the `snapshot.pipeline.kts` it spawns the
     * SDK subprocess against. Returns null when the snapshot is missing.
     * Callers MUST treat null as a hard error (no fallback to placeholder),
     * because falling back would silently run a different script than the
     * user sealed.
     */
    fun loadSnapshotSource(id: SnapshotId): ByteArray?

    /**
     * B1.3 + B1.4 (2026-10-10): persist a freshly-reserved [RunBinding]
     * at its deterministic [RunId]. The use case calls this BEFORE
     * the runner.submit() so a crash between the two leaves a row
     * the recovery path can reconcile. The underlying SQL is
     * `INSERT ... ON CONFLICT(studio_run_id) DO NOTHING` (B1.2): a
     * re-persist of the same id is a no-op.
     */
    fun persistRunBinding(binding: dev.pipelinek.studio.domain.RunBinding)
}

/**
 * B2.5 (2026-10-10): port the [RunReconciler] uses to persist
 * the reconciled state of an in-flight run. The contract is
 * "the studio will not invent FAILED, but it WILL persist
 * RECOVERY_REQUIRED / SUCCEEDED / FAILED / CANCELLED transitions
 * for runs whose journal records a terminal event, and
 * RECOVERY_REQUIRED for runs whose journal is non-terminal and
 * whose subprocess is gone." The port is intentionally narrow:
 * the reconciler never deletes a row, never spawns a new
 * subprocess, and never invents a transition that the journal
 * does not support.
 */
interface ReconcileOutcomePort {
    /**
     * Persist the reconciled [state] for the run, recording the
     * transition in `run_state_history` with [observedAt] (the
     * journal's last event timestamp when available, or the
     * wall-clock time the reconciliation ran). [reason] is the
     * human-readable explanation the reconciler derived from the
     * journal. Implementations must be idempotent: reconciling
     * the same run twice does not produce duplicate history
     * rows.
     */
    fun recordReconciled(
        runId: dev.pipelinek.studio.domain.RunId,
        state: dev.pipelinek.studio.domain.RunState,
        reason: String,
        observedAt: java.time.Instant?,
    )
}

data class ExperimentSnapshot(val id: ExperimentId, val name: String, val createdAt: java.time.Instant)
data class SnapshotSnapshot(
    val id: SnapshotId,
    val experimentId: ExperimentId,
    val sha256: String,
    val createdAt: java.time.Instant,
)
/**
 * R0.3.9 (2026-10-11): the read-side scenario projection now
 * carries [environmentProfile] alongside the snapshot identity.
 * Without this field the read side could not answer "what profile
 * was this scenario sealed with?", which is one of the four
 * identity tuples the R0.3.9 mandate requires to survive a
 * round-trip through the SQLite layer.
 */
data class ScenarioSnapshot(
    val id: ScenarioId,
    val version: Int,
    val pluginLockDigest: String?,
    val environmentProfile: String,
)
data class RunBindingSnapshot(
    val id: RunId,
    val experimentId: ExperimentId,
    val snapshotId: SnapshotId,
    val scenarioId: ScenarioId,
    val state: String,
    val createdAt: java.time.Instant,
)

/**
 * SFW-H2.4 (2026-10-13): which runtime backend a [RunRequest]
 * should dispatch to.
 *
 * A closed sum so the use case can exhaustively decide
 * which [dev.pipelinek.studio.infra.sdk.PipelineKRunner] (or
 * future backend) to call. The H2 path is
 * [Fabric] (the H2.2c write port +
 * [dev.pipelinek.studio.infra.storage.RunBindingsH2Store]);
 * the pre-H2 path is [Local] (the SDK subprocess). A
 * future work-unit may add a Podman or remote-Jenkins
 * arm without changing this surface — the [RuntimeBackendSelector]
 * is the one place that grows.
 */
sealed interface RuntimeBackend {
    /** The pre-H2 S0 SDK subprocess. The default. */
    data object Local : RuntimeBackend

    /**
     * The H2 path through the Fabric client. A Studio
     * that opts in here must have a working
     * [dev.pipelinek.studio.infra.storage.RunBindingsH2Store]
     * and a [dev.pipelinek.studio.infra.fabric.FabricRunnerAdapter]
     * wired in the use case.
     */
    data object Fabric : RuntimeBackend
}

/**
 * SFW-H2.4 (2026-10-13): the decision of which [RuntimeBackend]
 * a [RunRequest] should use.
 *
 * Three arms:
 * - [Allowed] carries the chosen backend; the use case
 *   dispatches accordingly.
 * - [Refused] is **not a silent fallback to [RuntimeBackend.Local]**.
 *   It is a misconfiguration the operator must fix
 *   (a typo in the config, a missing value, a value
 *   with the wrong case). The use case renders it as
 *   a 4xx; the H2.4 mandate is "no silent fallback".
 */
sealed interface RuntimeBackendSelectionResult {
    data class Allowed(val backend: RuntimeBackend) : RuntimeBackendSelectionResult
    data class Refused(val reason: String) : RuntimeBackendSelectionResult
}

/**
 * SFW-H2.4 (2026-10-13): the port the use case calls to
 * decide which [RuntimeBackend] a [RunRequest] should use.
 *
 * The port is one method (`select`) returning a
 * [RuntimeBackendSelectionResult]. The use case never
 * picks a default; it always asks the selector. The
 * default is the selector's responsibility (the
 * [dev.pipelinek.studio.infra.fabric.ConfigRuntimeBackendSelector]
 * maps an empty config to [RuntimeBackendSelectionResult.Allowed]
 * with [RuntimeBackend.Local]).
 */
interface RuntimeBackendSelector {
    fun select(req: RunRequest): RuntimeBackendSelectionResult
}
