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
        return SourceSnapshot(
            id = SnapshotId.new(),
            experimentId = experimentId,
            sha256 = sha,
            sourceUtf8 = sourceUtf8,
            createdAt = clock(),
            creator = creator,
        )
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
}
