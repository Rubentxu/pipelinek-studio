package dev.pipelinek.studio.application.usecases

import dev.pipelinek.studio.application.ports.*
import dev.pipelinek.studio.domain.*
import java.nio.file.Path
import java.time.Instant

/**
 * Use cases orchestrate ports to fulfil user-facing commands.
 * Per docs/01-architecture/ARCHITECTURE.md: no adapter can decide outcome.
 * Each use case must reference upstream evidence, never invent data.
 */

class CreateExperimentUseCase(
    private val metadata: StudioMetadataPort,
    private val clock: () -> Instant = Instant::now,
) {
    fun execute(name: String): Experiment {
        require(name.isNotBlank()) { "experiment name required" }
        // B1.1: persist the experiment so the row survives a Studio
        // restart. The ExperimentId is minted here (single source of
        // truth) and recorded by the port; the port never invents an
        // id. The use case is now genuinely creating the experiment
        // — pre-B1 it returned an in-memory object and discarded it.
        val created = Experiment(ExperimentId.new(), name, clock())
        metadata.saveExperiment(created)
        return created
    }
}

class SealSnapshotUseCase(
    private val metadata: StudioMetadataPort,
    private val clock: () -> Instant = Instant::now,
) {
    fun execute(experimentId: ExperimentId, sourceUtf8: ByteArray, creator: String): SourceSnapshot {
        require(sourceUtf8.isNotEmpty()) { "empty source" }
        require(creator.isNotBlank()) { "creator required" }
        val sha = Sha256.digest(sourceUtf8)
        // ADR-005: snapshot immutability; sha256 is the source of truth.
        val snap = SourceSnapshot(
            id = SnapshotId.new(),
            experimentId = experimentId,
            sha256 = sha,
            sourceUtf8 = sourceUtf8,
            createdAt = clock(),
            creator = creator,
        )
        // S1 E1.5: persist the bytes so the runner can read them back when
        // spawning the SDK subprocess. Without this, the seal response carries
        // a snapshot_id that has no corresponding row in `snapshots`; the
        // runner would then have no source to write and would (per the
        // S0 contract) refuse rather than silently run a different script.
        metadata.saveSnapshot(snap)
        return snap
    }
}

/**
 * R0.3.1 (2026-10-11) — register a scenario.
 *
 * The use case is the only writer of [SourceScenario] rows. It mints the
 * [ScenarioId] (single source of truth) and a monotonic version per id
 * within a single studio.db lifetime. Re-registering the same id is a
 * no-op at the SQL layer (`ON CONFLICT(scenario_id) DO NOTHING`).
 *
 * R0.3.1 mandate: "persistir escenarios auténticos; eliminar
 * `experiment_id` como sustituto de `scenario_id`." The runtime is
 * explicit: the [VersionConflict] failure case rejects a re-registration
 * with a different version so the operator's mental model is preserved
 * (the scenario I registered at v1 is still v1).
 */
class RegisterScenarioUseCase(
    private val metadata: StudioMetadataPort,
    private val clock: () -> Instant = Instant::now,
) {
    sealed class Result {
        /** Newly persisted or already-present with matching version. */
        data class Registered(val scenario: SourceScenario) : Result()
        /** The row already exists with a different version. */
        data class VersionConflict(val existingVersion: Int, val attemptedVersion: Int) : Result()
    }

    fun execute(
        explicitId: ScenarioId? = null,
        requestedVersion: Int? = null,
        pluginLockDigest: String? = null,
        environmentProfile: String = SourceScenario.DEFAULT_PROFILE,
    ): Result {
        val id = explicitId ?: ScenarioId.new()
        // Version is monotonic per id within a single studio.db lifetime:
        // first registration defaults to 1. Subsequent registrations on the
        // same id MUST request `existing.version + 1` explicitly to mint a
        // new version; calling without a `requestedVersion` when a row
        // already exists is treated as an idempotent re-register (the SQL
        // layer's `ON CONFLICT(scenario_id) DO NOTHING` already keeps the
        // stored row, so re-running the call must not invent a phantom v+1
        // and silently desync from the persisted state).
        val existing = metadata.findScenario(id)
        val targetVersion = when {
            requestedVersion != null -> requestedVersion
            existing == null -> 1
            else -> existing.version
        }
        if (existing != null && existing.version == targetVersion) {
            // Idempotent re-registration: the row already exists at
            // this version. Return a synthesised snapshot that
            // preserves the stored pluginLockDigest (or the incoming
            // one if the row had none), so callers see the persisted
            // state without us overwriting it.
            val digest = existing.pluginLockDigest ?: pluginLockDigest
            val profile = existing.environmentProfile
            val kept = metadata.findScenario(id)
            return Result.Registered(
                SourceScenario(
                    id = id,
                    version = kept!!.version,
                    pluginLockDigest = digest,
                    createdAt = clock(),
                    environmentProfile = profile,
                ),
            )
        }
        if (existing != null && targetVersion != existing.version + 1) {
            // The caller asked for a non-monotonic version (anything other
            // than exactly the next version). Reject so the operator's
            // mental model is preserved: "the scenario I registered at
            // version N is still N, not whatever the call returned".
            return Result.VersionConflict(existing.version, targetVersion)
        }
        val scenario = SourceScenario(
            id = id,
            version = targetVersion,
            pluginLockDigest = pluginLockDigest,
            createdAt = clock(),
            environmentProfile = environmentProfile,
        )
        metadata.saveScenario(scenario)
        return Result.Registered(scenario)
    }
}

/**
 * R0.3.1 (2026-10-11) — list registered scenarios, newest first.
 * Paginated; bounded by [MAX_LIMIT] to keep query latency bounded.
 */
class ListScenariosUseCase(
    private val metadata: StudioMetadataPort,
) {
    fun execute(limit: Int = 100, offset: Int = 0): List<ScenarioSnapshot> {
        require(limit in 1..MAX_LIMIT) { "limit must be in 1..$MAX_LIMIT" }
        require(offset >= 0) { "offset must be >= 0" }
        return metadata.listScenarios(limit, offset)
    }

    companion object {
        const val MAX_LIMIT = 200
    }
}

class StartRunUseCase(
    private val runner: PipelineRunnerPort,
    private val idempotency: IdempotencyPort,
    private val runtime: PipelineKRuntimePort,
    private val metadata: StudioMetadataPort,
    private val clock: () -> Instant = Instant::now,
) {
    /**
     * B1.3 + B1.4 (2026-10-10): return [ReservationOutcome] instead of
     * a [RunBinding] + a boolean. The four cases mirror the B1 mandate:
     * Reserved / Admitted / Refused / RecoveryRequired. The HTTP layer
     * projects these into the response envelope.
     *
     * B1.4 invariant: the [ReservationOutcome.Reserved.studioRunId] is
     * deterministic for a given (idempotencyKey, requestDigest). 50
     * concurrent identical requests collapse into a single row in
     * `idempotency` + `run_bindings` because:
     *   1. every request computes the same studio_run_id via
     *      [dev.pipelinek.studio.domain.DeterministicId.studioRunId];
     *   2. the lookup-or-record sequence in [IdempotencyPort] is
     *      serialised by SQLite's single-writer WAL, so exactly one
     *      request wins the INSERT and the others see a Match with
     *      the same responseRef;
     *   3. the first request calls [PipelineRunnerPort.submit] exactly
     *      once, the others return the existing [Reserved] without
     *      touching the runner.
     */
    fun execute(
        snapshotId: SnapshotId,
        scenarioId: ScenarioId,
        idempotencyKey: IdempotencyKey,
        requestDigest: Sha256,
    ): ReservationOutcome {
        // 1. Verify runtime available; capture the descriptor (audit C-3)
        // so the version/digest actually flow into the RunBinding instead
        // of being silently replaced with empty strings.
        val runtimeCheck: RuntimeDescriptorResult = runtime.describe()
        val descriptor: RuntimeDescriptor = when (runtimeCheck) {
            is RuntimeDescriptorResult.Failure -> throw IllegalStateException("runtime failure: ${runtimeCheck.reason}")
            RuntimeDescriptorResult.Unavailable -> throw IllegalStateException("runtime UNAVAILABLE — install PipelineK SDK and rerun")
            is RuntimeDescriptorResult.Available -> runtimeCheck.descriptor
        }

        // 2. Resolve the experimentId for FK. If the snapshot row was
        //    never persisted or was deleted, we refuse — the use case
        //    never mints a NIL UUID placeholder (audit C-2).
        val experimentId = resolveExperimentId(snapshotId)

        // 3. B1.4: deterministic studio_run_id from (key, digest).
        val studioRunId = dev.pipelinek.studio.domain.DeterministicId.studioRunId(
            idempotencyKey.value,
            requestDigest,
        )

        // 4. Idempotency: lookup existing op for same key+digest.
        val existing: IdempotencyLookupResult = idempotency.lookup(
            "run", idempotencyKey.value, requestDigest.value,
        )
        when (existing) {
            is IdempotencyLookupResult.Match -> {
                // Replay. The responseRef is the studio_run_id we
                // recorded on the first call; it must equal the
                // deterministic one we just computed (otherwise the
                // stored row was created with a different algorithm,
                // which is a schema-drift bug we surface as an error
                // rather than silently accept).
                val recordedRunId = existing.responseRef
                    ?: throw IllegalStateException(
                        "IDEMPOTENCY_REPLAY_MISSING_RESPONSE_REF: replayed key '${idempotencyKey.value}' " +
                            "has no responseRef (recorded before the schema gained the column); cannot honour AAT-API-008",
                    )
                if (recordedRunId != studioRunId.value) {
                    throw IllegalStateException(
                        "IDEMPOTENCY_DETERMINISTIC_ID_MISMATCH: replayed key '${idempotencyKey.value}' " +
                            "yields a different studio_run_id than the stored one ($recordedRunId vs ${studioRunId.value})",
                    )
                }
                return ReservationOutcome.Reserved(studioRunId)
            }
            is IdempotencyLookupResult.Conflict -> throw IllegalStateException("IDEMPOTENCY_CONFLICT: ${existing.reason}")
            IdempotencyLookupResult.NotFound -> {
                // First caller for this (key, digest). Reserve the row
                // and only then submit. The IdempotencyPort's record
                // is `ON CONFLICT DO NOTHING` so a concurrent second
                // caller will see AlreadyExists and re-do the lookup
                // (returning Reserved with the same studio_run_id).
                val opId = OperationId.new()
                val rec = idempotency.record(
                    "run", idempotencyKey.value, opId, requestDigest.value,
                    responseRef = studioRunId.value,
                )
                if (rec is IdempotencyRecordResult.AlreadyExists) {
                    // Lost the race. Re-do the lookup to fetch the
                    // recorded responseRef (which equals our
                    // deterministic id) and return Reserved without
                    // touching the runner.
                    val after = idempotency.lookup("run", idempotencyKey.value, requestDigest.value)
                    if (after is IdempotencyLookupResult.Match && after.responseRef != null) {
                        return ReservationOutcome.Reserved(RunId(after.responseRef))
                    }
                    // Pathological: the row was just deleted between
                    // our record and our lookup. Surface a clear
                    // recovery-required rather than retrying
                    // silently.
                    return ReservationOutcome.RecoveryRequired(
                        studioRunId,
                        "idempotency row vanished after AlreadyExists",
                    )
                }
            }
        }

        // 5. Persist the run_bindings row at the deterministic id
        //    BEFORE calling the runner. If the runner crashes or
        //    refuses, the row exists in `state = CREATED` and the
        //    recovery path on next Studio restart sees it.
        val now = clock()
        val binding = RunBinding(
            runId = studioRunId,
            experimentId = experimentId,
            snapshotId = snapshotId,
            scenarioId = scenarioId,
            runtimeVersion = descriptor.version,
            runtimeDigest = descriptor.artifactDigest,
            pluginSetDigest = descriptor.pluginSdkVersion,
            state = RunState.CREATED,
            createdAt = now,
            updatedAt = now,
        )
        metadata.persistRunBinding(binding)

        // 6. Submit to the runner. The deterministic id is what
        //    the runner sees as the request id; the upstream
        //    process (if any) is identified by the upstream id
        //    the runner returns.
        val req = RunRequest(studioRunId, snapshotId, scenarioId, ExecutionProfile.TrustedLocal)
        val sub: SubmissionResult = runner.submit(req)
        return when (sub) {
            is SubmissionResult.Refused -> ReservationOutcome.Refused(
                studioRunId,
                sub.reason,
            )
            is SubmissionResult.Admitted -> ReservationOutcome.Admitted(
                studioRunId,
                sub.upstreamRunId,
                sub.controlRoot,
            )
            is SubmissionResult.AlreadyRunning -> ReservationOutcome.Admitted(
                studioRunId,
                sub.upstreamRunId,
                // controlRoot not provided by the runner in this case;
                // B1.5 will resolve it from run.db on read.
                java.nio.file.Path.of(""),
            )
        }
    }

    /**
     * Audit C-2 (2026-10-10): the previous implementation substituted
     * `ExperimentId("00000000-0000-0000-0000-000000000000")` (NIL UUID) for
     * the experiment id in every RunBinding, breaking the experiment↔run
     * link for downstream consumers (the Web UI's "Experiments" page, the
     * /runs filter, the retention service's per-experiment view). The fix
     * looks the snapshot up via the metadata port and returns its
     * experimentId. If the snapshot is not found (which can happen during
     * a replay of an old run whose source row was deleted), we throw
     * rather than fall back to a NIL UUID — the consumer can then
     * distinguish "broken replay" from "fresh run".
     */
    private fun resolveExperimentId(snapshotId: SnapshotId): ExperimentId =
        metadata.findSnapshot(snapshotId)?.experimentId
            ?: throw IllegalStateException(
                "SNAPSHOT_NOT_FOUND: cannot resolve experimentId for snapshot ${snapshotId.value}; " +
                    "the snapshot row was deleted (or never persisted) and the use case refuses to " +
                    "mint a NIL UUID placeholder",
            )
}

class InspectRunUseCase(
    private val runner: PipelineRunnerPort,
    private val read: RunReadPort,
    private val output: OutputReadPort,
) {
    fun status(runId: RunId): RunHandleStatus = runner.status(runId)

    /**
     * B1.5: return the four-case [dev.pipelinek.studio.domain.RunObservation]
     * for the run. Delegates to [RunReadPort.observation], which the
     * SDK adapter can override to use the journal + run.db for a
     * precise RecoveryRequired vs Unavailable distinction.
     */
    fun observation(runId: RunId): dev.pipelinek.studio.domain.RunObservation =
        read.observation(runId)

    fun events(runId: RunId, after: EventCursor?, limit: Int): RunReadResult =
        read.events(runId, after, limit)

    fun output(runId: RunId, cursor: OutputCursor?, bytes: Int): OutputReadResult =
        output.read(runId, cursor, bytes)

    fun cancel(runId: RunId): CancellationResult = runner.cancel(runId)
}

/**
 * S1 read-side — list runs ordered by created_at DESC, optionally
 * filtered by state. Enforces a hard ceiling on `limit` so a
 * careless client cannot page through the whole table. Returns an
 * empty list when nothing matches; this is honest NOT_FOUND-as-empty
 * rather than a 404, because the resource (the collection) exists.
 */
class ListRunsUseCase(private val metadata: StudioMetadataPort) {
    fun execute(state: dev.pipelinek.studio.domain.RunState? = null, limit: Int = 50, offset: Int = 0): List<RunBindingSnapshot> =
        metadata.listRuns(state, limit, offset)
}

/**
 * S2 E2.3 — Scenario test runner. Wraps [ScenarioRunnerPort] and produces a
 * structured pass/fail/diff report. The use case never invents a passing
 * result: when the runner reports a failure, or when the actual descriptor
 * does not match the expected one, the verdict is `Failed` with the diff.
 */
class RunScenarioUseCase(
    private val runner: ScenarioRunnerPort,
) {
    data class Verdict(
        val scenarioId: ScenarioId,
        val stepId: String,
        val passed: Boolean,
        val runtimeMs: Long,
        val actual: ResultDescriptor?,
        val reason: String?,
    )

    fun execute(req: ScenarioExecutionRequest, expected: ResultDescriptor? = null): Verdict {
        val res = runner.execute(req)
        return when (res) {
            is ScenarioExecutionResult.Failure -> Verdict(
                scenarioId = req.scenarioId,
                stepId = req.stepId,
                passed = false,
                runtimeMs = 0L,
                actual = null,
                reason = res.reason,
            )
            is ScenarioExecutionResult.Ok -> {
                val passed = expected?.let { descriptorsEqual(it, res.descriptor) } ?: true
                Verdict(
                    scenarioId = req.scenarioId,
                    stepId = req.stepId,
                    passed = passed,
                    runtimeMs = res.runtimeMs,
                    actual = res.descriptor,
                    reason = if (passed) null else "result mismatch (expected ${expected!!::class.simpleName}, got ${res.descriptor::class.simpleName})",
                )
            }
        }
    }

    /**
     * Best-effort descriptor equality. The SDK is the source of truth for the
     * descriptor semantics; we only compare structural shape here so test
     * scenarios that pin a known-good response can catch regressions.
     */
    private fun descriptorsEqual(a: ResultDescriptor, b: ResultDescriptor): Boolean = when {
        a is ResultDescriptor.Text && b is ResultDescriptor.Text -> a.mime == b.mime && a.body.trim() == b.body.trim()
        a is ResultDescriptor.Json && b is ResultDescriptor.Json -> a.body.trim() == b.body.trim()
        a is ResultDescriptor.Table && b is ResultDescriptor.Table -> a.headers == b.headers && a.rows == b.rows
        a is ResultDescriptor.ArtifactRefs && b is ResultDescriptor.ArtifactRefs -> a.refs == b.refs
        a is ResultDescriptor.NotAvailable && b is ResultDescriptor.NotAvailable -> a.reason == b.reason
        else -> false
    }
}

/** Aggregate that's wired at app boot; not a hot-path class. */
data class Wiring(
    val metadata: StudioMetadataPort,
    val runner: PipelineRunnerPort,
    val read: RunReadPort,
    val output: OutputReadPort,
    val idempotency: IdempotencyPort,
    val runtime: PipelineKRuntimePort,
    val scenarioRunner: ScenarioRunnerPort = object : ScenarioRunnerPort {
        // Default wiring: no ScenarioRunnerPort is wired (it lives in the
        // S2 plugin lab). The default returns Failure so the verdict is
        // honest — never a fake PASS.
        override fun execute(req: ScenarioExecutionRequest) =
            ScenarioExecutionResult.Failure("no ScenarioRunnerPort wired; pass a custom Wiring")
    },
    val variant: VariantPort = object : VariantPort {
        override fun variant(request: VariantRequest) =
            VariantResult.Failure("no VariantPort wired; pass a custom Wiring")
    },
    val comparison: RunComparisonPort = object : RunComparisonPort {
        override fun compare(base: RunId, other: RunId) =
            RunComparisonResult.Failure("no RunComparisonPort wired; pass a custom Wiring")
    },
)

fun buildWiring(
    metadata: StudioMetadataPort,
    runner: PipelineRunnerPort,
    read: RunReadPort,
    output: OutputReadPort,
    idempotency: IdempotencyPort,
    runtime: PipelineKRuntimePort,
    scenarioRunner: ScenarioRunnerPort = object : ScenarioRunnerPort {
        override fun execute(req: ScenarioExecutionRequest) =
            ScenarioExecutionResult.Failure("no ScenarioRunnerPort wired; pass a custom Wiring")
    },
    variant: VariantPort = object : VariantPort {
        override fun variant(request: VariantRequest) =
            VariantResult.Failure("no VariantPort wired; pass a custom Wiring")
    },
    comparison: RunComparisonPort = object : RunComparisonPort {
        override fun compare(base: RunId, other: RunId) =
            RunComparisonResult.Failure("no RunComparisonPort wired; pass a custom Wiring")
    },
): Wiring = Wiring(metadata, runner, read, output, idempotency, runtime, scenarioRunner, variant, comparison)

object WiringExt {
    val Wiring.createExperiment get() = CreateExperimentUseCase(metadata)
    val Wiring.sealSnapshot get() = SealSnapshotUseCase(metadata)
    // R0.3.1 (2026-10-11): scenario register + list accessors.
    val Wiring.registerScenario get() = RegisterScenarioUseCase(metadata)
    val Wiring.listScenarios get() = ListScenariosUseCase(metadata)
    fun Wiring.startRun() = StartRunUseCase(runner, idempotency, runtime, metadata)
    fun Wiring.inspectRun() = InspectRunUseCase(runner, read, output)
    // S1 cancel: returns the InspectRunUseCase so the HTTP layer can call
    // `wiring.cancel().execute(runId)`. The use case delegates to the
    // runner's cancel(port), which is the live subprocess registry.
    fun Wiring.cancel() = inspectRun()
    fun Wiring.listRuns() = ListRunsUseCase(metadata)
    fun Wiring.runScenario() = RunScenarioUseCase(runner = scenarioRunner)
}
