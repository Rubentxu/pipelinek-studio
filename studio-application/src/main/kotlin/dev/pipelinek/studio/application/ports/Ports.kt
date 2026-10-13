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

/**
 * SFW-H4.1: the unified cancellation result the
 * [CancelRunUseCase] returns. The H4.1 use case
 * consolidates the local 3-arm
 * [CancellationResult] (in this file) and the
 * remote 4-arm
 * [dev.pipelinek.studio.infra.fabric.FabricCancellationResult]
 * (in `studio-infra-fabric`) into a single
 * 4-arm surface. The `Refused` arm is the H4.1
 * addition: the local path's "unexpected
 * exception" is now an explicit refusal (the
 * H2.2c `Refused` arm); the remote path's
 * `Refused` arm maps to the same shape.
 *
 * Why a 4-arm `CancelRunResult` (and not 3):
 * the H2.2c `Refused` arm is a load-bearing
 * distinction (a transport failure is a refusal,
 * not an unknown). Unifying to 4 arms means
 * the HTTP route and the future UATs do not
 * have to special-case the two backends.
 */
sealed interface CancelRunResult {
    /** The cancel was accepted by the backend. */
    data object CancelRequested : CancelRunResult

    /** The run is already in a terminal state. */
    data class AlreadyTerminal(val finalState: String) : CancelRunResult

    /** The backend does not know about this run
     *  (the run was admitted but the binding-lookup
     *  missed, OR the run was admitted to a
     *  different server). */
    data class Unknown(val reason: String) : CancelRunResult

    /** The cancel was refused for any other reason
     *  (transport failure, unmapped event, version
     *  too new, etc.). The HTTP route renders
     *  this as `503 CANCEL_REFUSED`. */
    data class Refused(val reason: String) : CancelRunResult
}

/**
 * SFW-H4.1: the use case that consolidates
 * cancellation across the local SDK subprocess
 * (the [PipelineRunnerPort]) and a remote
 * Fabric server (the [RemoteRunnerPort]).
 *
 * The use case asks the
 * [RuntimeBackendSelector] which backend the
 * submit would have landed on (the selector's
 * decision is deterministic on the [RunRequest]'s
 * shape) and delegates to the matching port.
 *
 * @param studioRunId the studio-side identity
 *   the run was admitted as (the URL key for
 *   `POST /runs/{runId}/cancel`).
 * @param request the synthetic [RunRequest] the
 *   selector uses to pick the backend. In
 *   practice, the HTTP route constructs a
 *   minimal request with the `studioRunId` and
 *   placeholder snapshot/scenario/profile (the
 *   selector's policy is config-based, not
 *   shape-based). A future H4 unit may add a
 *   `RunBindingsH2Store.cancelSelection` field
 *   that remembers the original submit
 *   decision; H4.1 ships the simple
 *   selector-based decision.
 */
interface CancelRunUseCase {
    suspend fun cancel(studioRunId: RunId, request: RunRequest): CancelRunResult
}

/**
 * SFW-H4.1: the abstract remote runner port the
 * [CancelRunUseCase] delegates to for Fabric
 * runs. The shape mirrors the H2.2c
 * [dev.pipelinek.studio.infra.fabric.FabricRunnerPort.cancel]
 * (the cancel-only subset); the production
 * implementation is the existing
 * [dev.pipelinek.studio.infra.fabric.FabricRunnerAdapter].
 *
 * Why an abstract port here (and not just
 * import the Fabric type): the
 * [CancelRunUseCase] lives in `studio-application`,
 * which is the use-case layer; it must not
 * depend on `studio-infra-fabric` (the
 * infra layer). The [RemoteRunnerPort] is the
 * hexagonal abstraction; the infra module
 * implements it.
 */
interface RemoteRunnerPort {
    suspend fun cancel(studioRunId: RunId): RemoteCancellationResult

    /**
     * SFW-H4.2: the inspection port. Returns the
     * run's current state on the remote backend
     * (Fabric). The H4.2 use case maps this to
     * the unified [RunInspectionResult] (the
     * `Found(RunSummary)` arm splits into `Live`
     * and `Terminal` based on the
     * `RunSummary.state`).
     */
    suspend fun summary(studioRunId: RunId): RemoteInspectionResult
}

/**
 * SFW-H4.1: the result of
 * [RemoteRunnerPort.cancel]. Mirrors the H2.2c
 * [dev.pipelinek.studio.infra.fabric.FabricCancellationResult]
 * shape (4 arms); the [CancelRunUseCase] maps
 * this 1:1 to its own 4-arm [CancelRunResult].
 */
sealed interface RemoteCancellationResult {
    data object CancelRequested : RemoteCancellationResult
    data class AlreadyTerminal(val finalState: String) : RemoteCancellationResult
    data class Unknown(val reason: String) : RemoteCancellationResult
    data class Refused(val reason: String) : RemoteCancellationResult
}

/**
 * SFW-H4.2: a use-case-layer [RunSummary] shape.
 * The use case cannot depend on Fabric's
 * `io.jenkins.pipelinek.fabric.contracts.model.RunSummary`
 * (the layering: `studio-application` does not
 * depend on `studio-infra-fabric`); the
 * [dev.pipelinek.studio.infra.fabric.RemoteRunnerAdapter]
 * adapts the Fabric `RunSummary` to this shape
 * (a near-identity mapping).
 *
 * The [finalState] is the variant class name
 * (e.g. `"Running"`, `"Waiting"`, `"Terminal"`).
 * The [isTerminal] flag is the load-bearing
 * distinction the H4.2 use case reads to split
 * `Found` into [RunInspectionResult.Live] or
 * [RunInspectionResult.Terminal].
 */
data class RunSummary(
    val upstreamRunId: String,
    val displayName: String,
    val finalState: String,
    val isTerminal: Boolean,
    val startedAt: java.time.Instant?,
    val finishedAt: java.time.Instant?,
)

/**
 * SFW-H4.2: the result of
 * [RemoteRunnerPort.summary]. Mirrors the H2.2c+1
 * [dev.pipelinek.studio.infra.fabric.FabricRunSummaryResult]
 * shape (3 arms: `Found(RunSummary)`, `Unknown(reason)`,
 * `Refused(reason)`); the [RunStateInspectionUseCase]
 * maps this 1:1 (or splits `Found` into
 * [RunInspectionResult.Live] / [RunInspectionResult.Terminal]
 * based on the `RunSummary.isTerminal` flag).
 */
sealed interface RemoteInspectionResult {
    data class Found(val summary: RunSummary) : RemoteInspectionResult
    data class Unknown(val reason: String) : RemoteInspectionResult
    data class Refused(val reason: String) : RemoteInspectionResult
}

/**
 * SFW-H4.2: the unified inspection result the
 * [RunStateInspectionUseCase] returns. 4 arms:
 *  - [Live] — the run is in flight (the
 *    upstream id is the Fabric-side or
 *    SDK-subprocess id; `finalState` is the
 *    Fabric `RunState` name if available, or
 *    null for the local path which only has
 *    `Live(upstreamRunId, pid)`);
 *  - [Terminal] — the run has terminated
 *    (`finalState` is the Fabric `RunState.Terminal.outcome`
 *    string or the local `finalState` string;
 *    `completedAt` is the Fabric
 *    `RunSummary.finishedAt` or null for the
 *    local path);
 *  - [Unknown] — the backend does not know
 *    about this run (binding missed; run not
 *    observed; unobserved);
 *  - [Refused] — the backend refused the
 *    inspection (transport failure; budget
 *    exhausted; etc.). The H4.2 new arm: the
 *    H2.2c+1 `Refused` arm is now explicit at
 *    the use-case layer.
 */
sealed interface RunInspectionResult {
    data class Live(
        val upstreamRunId: String?,
        val finalState: String?,
    ) : RunInspectionResult

    data class Terminal(
        val finalState: String,
        val completedAt: java.time.Instant?,
    ) : RunInspectionResult

    data class Unknown(val reason: String) : RunInspectionResult
    data class Refused(val reason: String) : RunInspectionResult
}

/**
 * SFW-H4.2: the use case that consolidates run
 * state inspection across the local SDK
 * subprocess (the [PipelineRunnerPort.status])
 * and a remote Fabric server (the
 * [RemoteRunnerPort.summary]).
 *
 * The use case asks the
 * [RuntimeBackendSelector] which backend the
 * submit would have landed on and delegates to
 * the matching port; maps the local 3-arm
 * [RunHandleStatus] or the remote 3-arm
 * [RemoteInspectionResult] to the unified 4-arm
 * [RunInspectionResult]; returns to the caller.
 */
interface RunStateInspectionUseCase {
    suspend fun inspect(studioRunId: RunId, request: RunRequest): RunInspectionResult
}

/**
 * SFW-H4.4: the result of
 * [RemoteReconcilerPort.reconcile]. Mirrors the
 * H2.2c+3 [FabricReconcileDecision] shape (4
 * arms: `Unchanged`, `Transition(newState,
 * reason)`, `Unknown(reason)`, `Refused(reason)`);
 * the [ReconcilerUseCase] maps this 1:1.
 *
 * The [Transition.newState] is the Studio
 * [dev.pipelinek.studio.domain.RunState] enum
 * value; the
 * [dev.pipelinek.studio.infra.fabric.RemoteReconcilerAdapter]
 * is responsible for the Fabric-to-Studio
 * translation (via the H2.2c+3
 * `fabricRunStateToStudio` mapping).
 */
sealed interface RemoteReconcileDecision {
    data object Unchanged : RemoteReconcileDecision
    data class Transition(
        val newState: dev.pipelinek.studio.domain.RunState,
        val reason: String,
    ) : RemoteReconcileDecision
    data class Unknown(val reason: String) : RemoteReconcileDecision
    data class Refused(val reason: String) : RemoteReconcileDecision
}

/**
 * SFW-H4.4: the use-case-layer
 * [ReconcileDecision] shape. 4 arms mirroring
 * [RemoteReconcileDecision] (the shape is the
 * same by design; H4.4 ships the wrapper for
 * the use-case layer's perspective).
 */
sealed interface ReconcileDecision {
    data object Unchanged : ReconcileDecision
    data class Transition(
        val newState: dev.pipelinek.studio.domain.RunState,
        val reason: String,
    ) : ReconcileDecision
    data class Unknown(val reason: String) : ReconcileDecision
    data class Refused(val reason: String) : ReconcileDecision
}

/**
 * SFW-H4.4: the abstract remote reconciler
 * port the [ReconcilerUseCase] delegates to.
 * Mirrors the H2.2c+3
 * [dev.pipelinek.studio.infra.fabric.FabricRunBindingReconcilerPort]
 * shape (the cancel-only subset); the
 * production impl is the new
 * [dev.pipelinek.studio.infra.fabric.RemoteReconcilerAdapter]
 * which adapts the H2.2c+3 port to the
 * abstract port.
 */
interface RemoteReconcilerPort {
    suspend fun reconcile(
        studioRunId: RunId,
        persistedState: dev.pipelinek.studio.domain.RunState,
    ): RemoteReconcileDecision
}

/**
 * SFW-H4.4: the use case that wraps the
 * H2.2c+3 reconciler as a use case. The
 * reconciler is Fabric-only (a local Studio
 * run does not need reconciliation; the local
 * SDK self-reports via the journal + run.db);
 * the use case delegates to the
 * [RemoteReconcilerPort].
 *
 * @param studioRunId the studio-side identity
 *   the run was admitted as.
 * @param persistedState the Studio
 *   [dev.pipelinek.studio.domain.RunState]
 *   the persistence layer has on record
 *   (the binding's persisted state).
 * @return [ReconcileDecision] — the use-case
 *   layer's 4-arm shape (mirrors
 *   [RemoteReconcileDecision]).
 */
interface ReconcilerUseCase {
    suspend fun reconcile(
        studioRunId: RunId,
        persistedState: dev.pipelinek.studio.domain.RunState,
    ): ReconcileDecision
}

/**
 * SFW-H4.5: the use case that loops over all
 * bindings and reconciles each one with the
 * H4.4 [ReconcilerUseCase], persisting the
 * resulting [dev.pipelinek.studio.domain.RunState]
 * transitions. This is the user-facing surface
 * for "I have N bindings, what does Fabric think
 * about them?".
 *
 * The recovery use case is the only consumer of
 * the per-binding [ReconcilerUseCase]; it scans
 * the bindings store via the [RunBindingsQueryPort]
 * and persists the new state via
 * [StudioMetadataPort.transitionRunState].
 *
 * The use case is honest about partial success:
 * a binding the reconciler returns [ReconcileDecision.Unknown]
 * or [ReconcileDecision.Refused] for is NOT a
 * transition (the use case never invents one);
 * the operator sees the breakdown in the
 * [RecoveryResult].
 */
sealed interface RecoveryResult {
    /**
     * All bindings were reconciled without any
     * failures. The [transitioned] list may be
     * empty (every binding was Unchanged).
     */
    data class Recovered(
        val total: Int,
        val unchanged: Int,
        val transitioned: List<RecoveryTransition>,
    ) : RecoveryResult

    /**
     * Some bindings reconciled cleanly and
     * some did not. The operator must inspect
     * [unknown] and [refused] to decide
     * whether to retry.
     */
    data class PartiallyRecovered(
        val total: Int,
        val unchanged: Int,
        val transitioned: List<RecoveryTransition>,
        val unknown: List<RecoveryBindingFailure>,
        val refused: List<RecoveryBindingFailure>,
    ) : RecoveryResult

    /**
     * The bindings store is empty. The
     * recovery use case is a no-op in this
     * case (the operator has nothing to
     * reconcile). This is a separate arm
     * from [Recovered] so the HTTP route
     * can render it as 200 with an empty
     * body (the operator may want to
     * distinguish "nothing to do" from
     * "tried but all bindings were
     * Unchanged").
     */
    data object NoBindings : RecoveryResult

    /**
     * The recovery use case could not
     * enumerate the bindings (the store
     * itself failed). The future H4.5
     * HTTP route renders this as 503.
     */
    data class Refused(val reason: String) : RecoveryResult
}

/**
 * SFW-H4.5: a single state transition
 * applied by the [RecoveryUseCase]. The
 * [previousState] is what Studio had on
 * record; the [newState] is what the
 * reconciler returned; the [reason] is
 * the reconciler's explanation.
 */
data class RecoveryTransition(
    val studioRunId: RunId,
    val previousState: dev.pipelinek.studio.domain.RunState,
    val newState: dev.pipelinek.studio.domain.RunState,
    val reason: String,
)

/**
 * SFW-H4.5: a binding the reconciler
 * could not decide on (Unknown) or
 * refused (Refused). The operator
 * sees the [reason] in the
 * [RecoveryResult.PartiallyRecovered].
 */
data class RecoveryBindingFailure(
    val studioRunId: RunId,
    val reason: String,
)

/**
 * SFW-H4.5: the abstract port the
 * [RecoveryUseCase] uses to enumerate
 * the bindings that are eligible for
 * reconciliation. The use case layer
 * does NOT depend on the persistence
 * module directly; the production
 * implementation is in
 * `studio-infra-storage` (the
 * `RunBindingsQueryAdapter`).
 *
 * The semantics of "eligible for
 * reconciliation" is implementation-
 * defined; the contract is "return
 * every binding the operator might
 * want to inspect". The simplest
 * implementation returns all rows
 * (`SELECT ... FROM run_bindings`)
 * and lets the reconciler decide
 * (a terminal binding will return
 * Unchanged or Refused, not a
 * transition).
 */
interface RunBindingsQueryPort {
    fun listAllForRecovery(): List<RecoveryBindingSnapshot>
}

/**
 * SFW-H4.5: the narrow projection the
 * [RunBindingsQueryPort] returns. The
 * use case only needs (studioRunId,
 * state); a wider projection
 * (experimentId, snapshotId, scenarioId,
 * fabricRunId) is the read-side
 * [RunBindingSnapshot] and is not
 * needed by the recovery use case.
 *
 * The `fabricRunId` field is intentionally
 * OMITTED — the use-case layer is
 * Fabric-agnostic (the hexagonal boundary
 * keeps `studio-application` from depending
 * on the Fabric module). The reconciler is
 * the one with the intelligence: a
 * local-only binding (no `fabricRunId`)
 * returns [ReconcileDecision.Refused] (no
 * Fabric record to compare against), and
 * the operator sees the breakdown in the
 * [RecoveryResult.PartiallyRecovered.refused]
 * list.
 */
data class RecoveryBindingSnapshot(
    val studioRunId: RunId,
    val state: dev.pipelinek.studio.domain.RunState,
)

/**
 * SFW-H4.5: the use case that loops over
 * all bindings and reconciles each one
 * with the H4.4 [ReconcilerUseCase],
 * persisting the resulting transitions.
 * Returns a [RecoveryResult] that is
 * honest about partial success.
 */
interface RecoveryUseCase {
    suspend fun recoverAll(): RecoveryResult
}

/**
 * SFW-H4.3: the result of
 * [RemoteSubmitPort.submit]. Mirrors the
 * H2.2c [FabricSubmissionResult] shape (3
 * arms: `Admitted`, `Refused`, `AlreadyRunning`).
 * The H4.3 use case maps this to the unified
 * [RetryRunResult] (the 3-arm
 * `RemoteSubmissionResult` plus the local
 * `SubmissionResult` shape — the
 * [RemoteSubmitAdapter] is identity-mapped to
 * the local `SubmissionResult.Admitted`
 * shape so the persistence layer can record
 * the `controlRoot`).
 */
sealed interface RemoteSubmissionResult {
    data class Admitted(
        val upstreamRunId: String?,
        val controlRoot: Path,
    ) : RemoteSubmissionResult

    data class Refused(val reason: String) : RemoteSubmissionResult
    data class AlreadyRunning(val upstreamRunId: String?) : RemoteSubmissionResult
}

/**
 * SFW-H4.3: the abstract remote submit port
 * the [RetryRunUseCase] delegates to. Mirrors
 * the H2.2c [FabricRunnerPort.submit] (the
 * submit-only subset); the production impl is
 * the new
 * [dev.pipelinek.studio.infra.fabric.RemoteSubmitAdapter]
 * which adapts the H2.2c `FabricRunnerAdapter.submit`
 * to the abstract port.
 */
interface RemoteSubmitPort {
    suspend fun submit(req: RunRequest): RemoteSubmissionResult
}

/**
 * SFW-H4.3: the result of
 * [RetryRunUseCase.retry]. 4 arms:
 *  - [Retried] — the retry succeeded; the new
 *    run is admitted (the original run is
 *    cancelled; the new run has a fresh
 *    `studioRunId`);
 *  - [CancelRefused] — the cancel failed; the
 *    new run was NOT submitted;
 *  - [SubmitRefused] — the cancel succeeded
 *    but the new submit was refused (the
 *    original run is cancelled; the new run
 *    is not admitted; the user may retry
 *    again);
 *  - [Unknown] — the original run is not in
 *    the binding; the retry cannot dispatch.
 */
sealed interface RetryRunResult {
    data class Retried(
        val newStudioRunId: RunId,
        val newUpstreamRunId: String?,
        val newControlRoot: Path,
    ) : RetryRunResult

    data class CancelRefused(val reason: String) : RetryRunResult
    data class SubmitRefused(val reason: String) : RetryRunResult
    data class Unknown(val reason: String) : RetryRunResult
}

/**
 * SFW-H4.3: the use case that chains the
 * H4.1 cancel with the submit path. The
 * retry is a workflow:
 *  1. **cancel phase**: ask the H2.4 selector
 *     which backend the original run is on;
 *     delegate to the matching cancel port
 *     (the H4.1 use case does this);
 *  2. **submit phase**: ask the H2.4 selector
 *     which backend the new request lands on;
 *     delegate to the local submit port (for
 *     `Local`) or the remote submit port (for
 *     `Fabric`).
 *
 * @param originalRunId the studio-side identity
 *   of the run to cancel.
 * @param newRequest the new [RunRequest] (the
 *   caller mints a fresh `studioRunId` for the
 *   retry; the retry is a new run, not a
 *   re-submission of the same one).
 */
interface RetryRunUseCase {
    suspend fun retry(originalRunId: RunId, newRequest: RunRequest): RetryRunResult
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

    /**
     * SFW-H4.5: transition a run binding's
     * [dev.pipelinek.studio.domain.RunState] to
     * [newState]. Called by the [RecoveryUseCase]
     * when the H4.4 [ReconcilerUseCase] returns a
     * [ReconcileDecision.Transition]. The port
     * is intentionally narrow: the recovery use
     * case never invents a new state (only the
     * reconciler can), and the port never reads
     * the binding back (the recovery use case
     * already has the previous state from the
     * [RunBindingsQueryPort] snapshot). The
     * underlying SQL is an UPDATE on the
     * `state` + `updated_at` columns of the
     * existing row.
     */
    fun transitionRunState(
        studioRunId: RunId,
        newState: dev.pipelinek.studio.domain.RunState,
    )
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
 * SFW-H3.1 (2026-10-13): a port that streams live events for
 * a run with strict cursor monotonicity. The
 * load-bearing primitive for the H3 surfaces (timeline,
 * console-incremental, SSE-for-web, replay).
 *
 * The implementation MUST guarantee that the resulting
 * [kotlinx.coroutines.flow.Flow] emits events with
 * strictly-increasing `sequence` and ends exactly once
 * with the source's `complete: true` signal. A consumer
 * that receives an empty poll MUST NOT conclude the run
 * has terminated; a consumer that receives an `End`
 * MUST conclude the run has ended (and only then).
 */
interface LiveEventStream {
    fun subscribe(
        studioRunId: dev.pipelinek.studio.domain.RunId,
        fromCursor: dev.pipelinek.studio.application.ports.EventCursor,
    ): kotlinx.coroutines.flow.Flow<LiveEvent>
}

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
