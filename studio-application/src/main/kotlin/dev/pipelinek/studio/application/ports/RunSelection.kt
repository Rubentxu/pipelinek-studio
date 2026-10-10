package dev.pipelinek.studio.application.ports

import dev.pipelinek.studio.domain.OperationId
import dev.pipelinek.studio.domain.RunId

/**
 * B4.4 (2026-10-10): `studio.runs.select` per
 * ROADMAP §"S4 API/MCP para variantes de
 * snapshot y compare de runs...".
 *
 * Run Selection takes a RunId and a step's
 * OperationId and reconstructs a pipeline that
 * runs ONLY the selected Step, with the Step's
 * declared inputs as `val` declarations. The
 * reconstruction is read-only: the original
 * source is never mutated. The agent can then
 * run the reconstructed pipeline in isolation
 * to debug a single Step.
 *
 * The reconstruction is best-effort. A Step
 * whose body depends on a runtime-evaluated
 * value (e.g. `prevResult.foo`) cannot be
 * reconstructed offline because the runtime
 * value is gone. The port returns
 * [RunSelectionResult.Unknown] with a clear
 * reason. A missing run or step returns
 * [RunSelectionResult.Failure].
 *
 * The reconstruction works in three steps:
 *  1. Read the run's source snapshot bytes.
 *  2. Find the Step's line range in the source
 *     (the [stepReader] provides the range).
 *  3. If the lines are clean (no
 *     runtime-evaluated references), wrap them
 *     in a preamble that declares the Step's
 *     input as a `val` and return the bytes.
 *     Otherwise return Unknown.
 */
data class RunSelectionRequest(
    val runId: RunId,
    val stepOpId: OperationId,
)

/**
 * The pre-B4.4 reconstruction contract:
 *  - [reconstructedSource]: the source bytes of
 *    the reconstructed pipeline. The bytes are
 *    UTF-8 and form a valid pipeline.kts that
 *    runs only the selected Step.
 *  - [lineRange]: the original source's line
 *    range that was extracted. Surfaced so the
 *    agent can verify the selection.
 *  - [fingerprint]: the Step's class name from
 *    the operation_journal. Surfaced for
 *    auditing.
 */
sealed interface RunSelectionResult {
    data class Ok(
        val reconstructedSource: ByteArray,
        val lineRange: IntRange,
        val fingerprint: String,
    ) : RunSelectionResult

    /**
     * The Step exists but cannot be reconstructed
     * offline. [reason] names the obstacle (e.g.
     * "runtime-evaluated value: prevResult.foo").
     */
    data class Unknown(val reason: String) : RunSelectionResult

    data class Failure(val reason: String) : RunSelectionResult
}

interface RunSelectionPort {
    fun select(request: RunSelectionRequest): RunSelectionResult
}

/**
 * B4.4 (2026-10-10): the default line-range
 * reconstruction impl. The port takes two
 * functional dependencies so the application
 * layer stays decoupled from the SDK adapter:
 *  - [sourceLoader]: read the run's source
 *    bytes.
 *  - [stepReader]: read the Step's metadata
 *    (fingerprint, input schema, line range in
 *    the source). The SDK adapter is
 *    responsible for resolving the line range
 *    from the operation_journal and the
 *    original snapshot's source.
 *
 * The reconstruction is best-effort: a Step
 * whose source lines contain a
 * `prevResult.` reference is rejected with
 * Unknown because the runtime value is gone.
 */
class LineRangeReconstructingRunSelection(
    private val sourceLoader: (RunId) -> ByteArray?,
    private val stepReader: (RunId, OperationId) -> StepRecord?,
) : RunSelectionPort {

    override fun select(request: RunSelectionRequest): RunSelectionResult {
        val source = sourceLoader(request.runId)
            ?: return RunSelectionResult.Failure("run ${request.runId.value} not found")
        val step = stepReader(request.runId, request.stepOpId)
            ?: return RunSelectionResult.Failure("step ${request.stepOpId.value} not in run ${request.runId.value}")
        val lines = source.toString(Charsets.UTF_8).split('\n')
        val range = step.lineRange
        if (range.first < 0 || range.last >= lines.size) {
            return RunSelectionResult.Failure(
                "step ${request.stepOpId.value} line range $range out of source bounds [0, ${lines.size})",
            )
        }
        val selected = lines.subList(range.first, range.last + 1)
        // Heuristic: a runtime-evaluated value
        // is referenced as `prevResult.`. The
        // Step's body cannot be reconstructed
        // offline because the value is gone.
        val runtimeRef = selected.firstOrNull { it.contains("prevResult.") }
        if (runtimeRef != null) {
            return RunSelectionResult.Unknown(
                "step ${request.stepOpId.value} cannot be reconstructed offline: " +
                    "runtime-evaluated value 'prevResult.' reference at line " +
                    "${selected.indexOf(runtimeRef) + range.first + 1}",
            )
        }
        val preamble = buildString {
            appendLine("// Reconstructed by studio.runs.select from run ${request.runId.value}, op ${request.stepOpId.value}.")
            appendLine("// Original source lines ${range.first + 1}-${range.last + 1} of the run's snapshot.")
            appendLine("// Fingerprint: ${step.fingerprint}")
            if (step.input.isNotBlank()) {
                appendLine("// Input schema: ${step.input}")
            }
            appendLine("import kotlinx.coroutines.runBlocking")
            appendLine("runBlocking {")
        }
        val epilogue = "}\n"
        val body = selected.joinToString("\n")
        val reconstructed = (preamble + body + "\n" + epilogue).toByteArray(Charsets.UTF_8)
        return RunSelectionResult.Ok(
            reconstructedSource = reconstructed,
            lineRange = range,
            fingerprint = step.fingerprint,
        )
    }
}

/**
 * The Step metadata the [stepReader] returns.
 * [lineRange] is INCLUSIVE on both ends, 0-indexed
 * (matching [List.subList]).
 */
data class StepRecord(
    val opId: OperationId,
    val fingerprint: String,
    val input: String,
    val lineRange: IntRange,
)
