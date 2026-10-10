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

class StartRunUseCase(
    private val runner: PipelineRunnerPort,
    private val idempotency: IdempotencyPort,
    private val runtime: PipelineKRuntimePort,
    private val metadata: StudioMetadataPort,
    private val clock: () -> Instant = Instant::now,
) {
    fun execute(
        snapshotId: SnapshotId,
        scenarioId: ScenarioId,
        runId: RunId,
        idempotencyKey: IdempotencyKey,
        requestDigest: Sha256,
    ): RunBinding {
        // 1. Verify runtime available; capture the descriptor (audit C-3)
        // so the version/digest actually flow into the RunBinding instead
        // of being silently replaced with empty strings.
        val runtimeCheck: RuntimeDescriptorResult = runtime.describe()
        val descriptor: RuntimeDescriptor = when (runtimeCheck) {
            is RuntimeDescriptorResult.Failure -> throw IllegalStateException("runtime failure: ${runtimeCheck.reason}")
            RuntimeDescriptorResult.Unavailable -> throw IllegalStateException("runtime UNAVAILABLE — install PipelineK SDK and rerun")
            is RuntimeDescriptorResult.Available -> runtimeCheck.descriptor
        }

        // 2. Idempotency: lookup existing op for same key+digest.
        val existing: IdempotencyLookupResult = idempotency.lookup("run", idempotencyKey.value, requestDigest.value)
        when (existing) {
            is IdempotencyLookupResult.Match -> {
                // AAT-API-008: replay returns the original run_id, not a
                // freshly minted one. The IdempotencyPort's responseRef
                // is the studio_run_id we recorded on the first call.
                // If the row pre-dates the schema change that added
                // response_ref, we still have the operationId but no
                // way to recover the run_id; in that case we surface
                // a clear "stale idempotency row" error instead of
                // inventing a new run.
                val originalRunId = existing.responseRef
                    ?: throw IllegalStateException(
                        "IDEMPOTENCY_REPLAY_MISSING_RESPONSE_REF: replayed key '${idempotencyKey.value}' " +
                            "has no responseRef (recorded before the schema gained the column); cannot honour AAT-API-008",
                    )
                val now = clock()
                return RunBinding(
                    runId = RunId(originalRunId),
                    experimentId = resolveExperimentId(snapshotId),
                    snapshotId = snapshotId,
                    scenarioId = scenarioId,
                    runtimeVersion = descriptor.version,
                    runtimeDigest = descriptor.artifactDigest,
                    pluginSetDigest = descriptor.pluginSdkVersion,
                    state = RunState.CREATED,
                    createdAt = now,
                    updatedAt = now,
                )
            }
            is IdempotencyLookupResult.Conflict -> throw IllegalStateException("IDEMPOTENCY_CONFLICT: ${existing.reason}")
            IdempotencyLookupResult.NotFound -> {
                idempotency.record("run", idempotencyKey.value, OperationId.new(), requestDigest.value, responseRef = runId.value)
            }
        }

        // 3. Submit to runner.
        val req = RunRequest(runId, snapshotId, scenarioId, ExecutionProfile.TRUSTED_LOCAL)
        val sub: SubmissionResult = runner.submit(req)
        val now = clock()
        val experimentId = resolveExperimentId(snapshotId)
        return when (sub) {
            is SubmissionResult.Refused -> RunBinding(
                runId = runId,
                experimentId = experimentId,
                snapshotId = snapshotId,
                scenarioId = scenarioId,
                runtimeVersion = descriptor.version,
                runtimeDigest = descriptor.artifactDigest,
                pluginSetDigest = descriptor.pluginSdkVersion,
                state = RunState.REFUSED,
                createdAt = now,
                updatedAt = now,
            )
            is SubmissionResult.Admitted -> RunBinding(
                runId = runId,
                experimentId = experimentId,
                snapshotId = snapshotId,
                scenarioId = scenarioId,
                runtimeVersion = descriptor.version,
                runtimeDigest = descriptor.artifactDigest,
                pluginSetDigest = descriptor.pluginSdkVersion,
                state = RunState.QUEUED,
                upstreamRunId = UpstreamRunId(sub.upstreamRunId),
                runDbRef = sub.controlRoot.toString(),
                controlRootRef = sub.controlRoot.toString(),
                createdAt = now,
                updatedAt = now,
            )
            is SubmissionResult.AlreadyRunning -> RunBinding(
                runId = runId,
                experimentId = experimentId,
                snapshotId = snapshotId,
                scenarioId = scenarioId,
                runtimeVersion = descriptor.version,
                runtimeDigest = descriptor.artifactDigest,
                pluginSetDigest = descriptor.pluginSdkVersion,
                state = RunState.RUNNING,
                upstreamRunId = UpstreamRunId(sub.upstreamRunId),
                createdAt = now,
                updatedAt = now,
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
    fun Wiring.startRun() = StartRunUseCase(runner, idempotency, runtime, metadata)
    fun Wiring.inspectRun() = InspectRunUseCase(runner, read, output)
    // S1 cancel: returns the InspectRunUseCase so the HTTP layer can call
    // `wiring.cancel().execute(runId)`. The use case delegates to the
    // runner's cancel(port), which is the live subprocess registry.
    fun Wiring.cancel() = inspectRun()
    fun Wiring.listRuns() = ListRunsUseCase(metadata)
    fun Wiring.runScenario() = RunScenarioUseCase(runner = scenarioRunner)
}
