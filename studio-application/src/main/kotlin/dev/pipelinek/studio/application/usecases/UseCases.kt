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
        // S0 stores via metadata port. Persistence is in studio-infra-storage.
        val created = Experiment(ExperimentId.new(), name, clock())
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
    private val clock: () -> Instant = Instant::now,
) {
    fun execute(
        snapshotId: SnapshotId,
        scenarioId: ScenarioId,
        runId: RunId,
        idempotencyKey: IdempotencyKey,
        requestDigest: Sha256,
    ): RunBinding {
        // 1. Verify runtime available; refuse if not.
        val runtimeCheck: RuntimeDescriptorResult = runtime.describe()
        when (runtimeCheck) {
            is RuntimeDescriptorResult.Failure -> throw IllegalStateException("runtime failure: ${runtimeCheck.reason}")
            RuntimeDescriptorResult.Unavailable -> throw IllegalStateException("runtime UNAVAILABLE — install PipelineK SDK and rerun")
            is RuntimeDescriptorResult.Available -> Unit
        }

        // 2. Idempotency: lookup existing op for same key+digest.
        val existing: IdempotencyLookupResult = idempotency.lookup("run", idempotencyKey.value, requestDigest.value)
        when (existing) {
            is IdempotencyLookupResult.Match -> {
                // Same op already in flight — caller will reuse.
                val now = clock()
                return RunBinding(
                    runId = runId,
                    experimentId = ExperimentId("00000000-0000-0000-0000-000000000000"),
                    snapshotId = snapshotId,
                    scenarioId = scenarioId,
                    runtimeVersion = "",
                    runtimeDigest = "",
                    pluginSetDigest = "",
                    state = RunState.CREATED,
                    createdAt = now,
                    updatedAt = now,
                )
            }
            is IdempotencyLookupResult.Conflict -> throw IllegalStateException("IDEMPOTENCY_CONFLICT: ${existing.reason}")
            IdempotencyLookupResult.NotFound -> {
                idempotency.record("run", idempotencyKey.value, OperationId.new(), requestDigest.value)
            }
        }

        // 3. Submit to runner.
        val req = RunRequest(runId, snapshotId, scenarioId, ExecutionProfile.TRUSTED_LOCAL)
        val sub: SubmissionResult = runner.submit(req)
        val now = clock()
        return when (sub) {
            is SubmissionResult.Refused -> RunBinding(
                runId = runId,
                experimentId = ExperimentId("00000000-0000-0000-0000-000000000000"),
                snapshotId = snapshotId,
                scenarioId = scenarioId,
                runtimeVersion = "",
                runtimeDigest = "",
                pluginSetDigest = "",
                state = RunState.REFUSED,
                createdAt = now,
                updatedAt = now,
            )
            is SubmissionResult.Admitted -> RunBinding(
                runId = runId,
                experimentId = ExperimentId("00000000-0000-0000-0000-000000000000"),
                snapshotId = snapshotId,
                scenarioId = scenarioId,
                runtimeVersion = "",
                runtimeDigest = "",
                pluginSetDigest = "",
                state = RunState.QUEUED,
                upstreamRunId = UpstreamRunId(sub.upstreamRunId),
                runDbRef = sub.controlRoot.toString(),
                controlRootRef = sub.controlRoot.toString(),
                createdAt = now,
                updatedAt = now,
            )
            is SubmissionResult.AlreadyRunning -> RunBinding(
                runId = runId,
                experimentId = ExperimentId("00000000-0000-0000-0000-000000000000"),
                snapshotId = snapshotId,
                scenarioId = scenarioId,
                runtimeVersion = "",
                runtimeDigest = "",
                pluginSetDigest = "",
                state = RunState.RUNNING,
                upstreamRunId = UpstreamRunId(sub.upstreamRunId),
                createdAt = now,
                updatedAt = now,
            )
        }
    }
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
)

fun buildWiring(
    metadata: StudioMetadataPort,
    runner: PipelineRunnerPort,
    read: RunReadPort,
    output: OutputReadPort,
    idempotency: IdempotencyPort,
    runtime: PipelineKRuntimePort,
): Wiring = Wiring(metadata, runner, read, output, idempotency, runtime)

object WiringExt {
    val Wiring.createExperiment get() = CreateExperimentUseCase(metadata)
    val Wiring.sealSnapshot get() = SealSnapshotUseCase(metadata)
    fun Wiring.startRun() = StartRunUseCase(runner, idempotency, runtime)
    fun Wiring.inspectRun() = InspectRunUseCase(runner, read, output)
    fun Wiring.listRuns() = ListRunsUseCase(metadata)
    fun Wiring.runScenario() = RunScenarioUseCase(
        runner = object : ScenarioRunnerPort {
            // The default wiring has no ScenarioRunnerPort (it lives in S2 plugin lab).
            // Callers who wire a real one should pass it via a custom Wiring.
            // The default returns Failure so the verdict is honest — never a fake PASS.
            override fun execute(req: ScenarioExecutionRequest) =
                ScenarioExecutionResult.Failure("no ScenarioRunnerPort wired; pass a custom Wiring")
        },
    )
}
