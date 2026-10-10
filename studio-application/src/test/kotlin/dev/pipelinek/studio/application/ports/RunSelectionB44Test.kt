package dev.pipelinek.studio.application.ports

import dev.pipelinek.studio.domain.OperationId
import dev.pipelinek.studio.domain.RunId
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * B4.4 (2026-10-10): `studio.runs.select` tests.
 *
 * The S4 mandate is "Run Selection (Step
 * contextualized)": the agent can ask the
 * studio to reconstruct a pipeline that runs
 * only one Step of a run, with the Step's
 * declared inputs pre-supplied. The
 * reconstruction is read-only and best-effort.
 *
 * The tests pin four properties of
 * [RunSelectionPort.select]:
 *  1. The common case: a Step with a known
 *     fingerprint, a known line range, and a
 *     declared input schema produces an Ok
 *     result whose reconstructed source is a
 *     valid pipeline that runs ONLY the
 *     selected Step.
 *  2. The reconstructed source includes the
 *     selected Step's source lines verbatim
 *     (a future agent can verify the line
 *     range by reading the original source).
 *  3. A Step whose body references
 *     `prevResult.` cannot be reconstructed
 *     offline — the port returns Unknown with
 *     a reason that names the obstacle.
 *  4. A missing run or step returns Failure
 *     (not exception, not empty reconstruction).
 */
class RunSelectionB44Test {

    @Test
    fun `B4_4 common case — selected Step is the only one reconstructed`() {
        val runId = RunId.new()
        val opId = OperationId("op-1")
        val source = """
            |import kotlinx.coroutines.flow.Flow
            |import kotlinx.coroutines.flow.flowOf
            |
            |val pipeline = pipeline {
            |    step("alpha") { flowOf("a") }
            |    step("beta") { flowOf("b") }
            |    step("gamma") { flowOf("c") }
            |}
            |
            |pipeline.run()
        """.trimMargin()
        val port = LineRangeReconstructingRunSelection(
            sourceLoader = { _ -> source.toByteArray() },
            stepReader = { _, _ -> StepRecord(
                opId = opId,
                fingerprint = "studio.steps.Beta",
                input = "Flow<String>",
                // The beta step is on line 5
                // (0-indexed) of the source.
                lineRange = 5..5,
            ) },
        )
        val result = port.select(RunSelectionRequest(runId, opId))
        require(result is RunSelectionResult.Ok) { "expected Ok, got $result" }
        // The reconstructed source must
        // include the original step body
        // VERBATIM. The agent can verify by
        // comparing line ranges against the
        // original.
        val text = result.reconstructedSource.toString(Charsets.UTF_8)
        assertTrue(
            text.contains("""step("beta") { flowOf("b") }"""),
            "reconstructed source must include the selected Step body, got:\n$text",
        )
        // The reconstructed source must NOT
        // include the other steps' bodies.
        // (This is the "selected Step is the
        // only one" property from the ODD
        // acceptance.)
        assertTrue(
            !text.contains("""step("alpha")"""),
            "reconstructed source must NOT include alpha, got:\n$text",
        )
        assertTrue(
            !text.contains("""step("gamma")"""),
            "reconstructed source must NOT include gamma, got:\n$text",
        )
        // The line range is surfaced for
        // auditing.
        assertEquals(5..5, result.lineRange)
        assertEquals("studio.steps.Beta", result.fingerprint)
    }

    @Test
    fun `B4_4 reconstructed source includes the selected lines verbatim`() {
        val runId = RunId.new()
        val opId = OperationId("op-verbatim")
        val source = """
            |import kotlinx.coroutines.flow.flowOf
            |
            |val alpha = "first"
            |
            |fun betaImpl(): String {
            |    val x = 1
            |    val y = 2
            |    return (x + y).toString()
            |}
            |
            |val gamma = "third"
        """.trimMargin()
        val port = LineRangeReconstructingRunSelection(
            sourceLoader = { _ -> source.toByteArray() },
            stepReader = { _, _ -> StepRecord(
                opId = opId,
                fingerprint = "studio.steps.BetaImpl",
                input = "Unit",
                // The beta function body is
                // lines 4-7 (0-indexed).
                lineRange = 4..7,
            ) },
        )
        val result = port.select(RunSelectionRequest(runId, opId))
        require(result is RunSelectionResult.Ok) { "expected Ok, got $result" }
        val text = result.reconstructedSource.toString(Charsets.UTF_8)
        // Every line of the original step body
        // must be present in the reconstructed
        // source, byte-for-byte (modulo
        // trimming).
        val originalLines = source.split('\n').subList(4, 8)
        for (line in originalLines) {
            assertTrue(
                text.contains(line.trim()),
                "reconstructed source must include line '$line', got:\n$text",
            )
        }
    }

    @Test
    fun `B4_4 step with prevResult reference returns Unknown (cannot reconstruct offline)`() {
        val runId = RunId.new()
        val opId = OperationId("op-runtime")
        // The Step body uses `prevResult.foo`.
        // This is a runtime-evaluated value
        // that the offline reconstruction
        // cannot supply. The port must return
        // Unknown, NOT a fabricated pipeline.
        val source = """
            |import kotlinx.coroutines.flow.flowOf
            |
            |val step1 = flowOf("a")
            |
            |fun step2Impl(): String {
            |    val fromPrev = prevResult.value
            |    return fromPrev
            |}
        """.trimMargin()
        val port = LineRangeReconstructingRunSelection(
            sourceLoader = { _ -> source.toByteArray() },
            stepReader = { _, _ -> StepRecord(
                opId = opId,
                fingerprint = "studio.steps.Step2",
                input = "Unit",
                lineRange = 4..6,
            ) },
        )
        val result = port.select(RunSelectionRequest(runId, opId))
        require(result is RunSelectionResult.Unknown) { "expected Unknown, got $result" }
        // The reason must name the obstacle
        // (the prevResult reference) so the
        // agent can decide what to do.
        assertTrue(
            result.reason.contains("prevResult") || result.reason.contains("runtime"),
            "Unknown must name the obstacle, got: ${result.reason}",
        )
    }

    @Test
    fun `B4_4 missing run returns Failure not exception`() {
        val port = LineRangeReconstructingRunSelection(
            sourceLoader = { _ -> null },
            stepReader = { _, _ -> null },
        )
        val result = port.select(RunSelectionRequest(RunId.new(), OperationId("op-missing")))
        require(result is RunSelectionResult.Failure) { "expected Failure, got $result" }
        assertTrue(
            result.reason.contains("not found"),
            "Failure must say 'not found', got: ${result.reason}",
        )
    }

    @Test
    fun `B4_4 missing step in present run returns Failure`() {
        val runId = RunId.new()
        val port = LineRangeReconstructingRunSelection(
            sourceLoader = { _ -> "src".toByteArray() },
            stepReader = { _, _ -> null },
        )
        val result = port.select(RunSelectionRequest(runId, OperationId("op-missing-step")))
        require(result is RunSelectionResult.Failure) { "expected Failure, got $result" }
        assertTrue(
            result.reason.contains("op-missing-step") || result.reason.contains("not in run"),
            "Failure must name the missing op, got: ${result.reason}",
        )
    }

    @Test
    fun `B4_4 line range out of source bounds returns Failure`() {
        val runId = RunId.new()
        val opId = OperationId("op-oob")
        // The step claims to be on lines
        // 100-200 but the source has only
        // 5 lines. The port must reject this
        // — fabricating a reconstruction
        // would be a synthetic block.
        val port = LineRangeReconstructingRunSelection(
            sourceLoader = { _ -> "a\nb\nc\nd\ne".toByteArray() },
            stepReader = { _, _ -> StepRecord(
                opId = opId,
                fingerprint = "studio.steps.OutOfBounds",
                input = "Unit",
                lineRange = 100..200,
            ) },
        )
        val result = port.select(RunSelectionRequest(runId, opId))
        require(result is RunSelectionResult.Failure) { "expected Failure, got $result" }
        assertTrue(
            result.reason.contains("out of") || result.reason.contains("bounds"),
            "Failure must name the bounds issue, got: ${result.reason}",
        )
    }

    @Test
    fun `B4_4 reconstructed source is a single-step pipeline (only the selected step ran)`() {
        // Stronger form of the "only the
        // selected step ran" property: the
        // reconstructed source is a
        // runBlocking { ... } block that
        // wraps ONLY the selected step's
        // body. Other steps' bodies are not
        // in the source.
        val runId = RunId.new()
        val opId = OperationId("op-only")
        val source = """
            |fun alpha() { println("a") }
            |fun beta() { println("b") }
            |fun gamma() { println("c") }
        """.trimMargin()
        val port = LineRangeReconstructingRunSelection(
            sourceLoader = { _ -> source.toByteArray() },
            stepReader = { _, _ -> StepRecord(
                opId = opId,
                fingerprint = "studio.steps.Beta",
                input = "Unit",
                lineRange = 1..1,
            ) },
        )
        val result = port.select(RunSelectionRequest(runId, opId))
        require(result is RunSelectionResult.Ok) { "expected Ok, got $result" }
        val text = result.reconstructedSource.toString(Charsets.UTF_8)
        assertTrue(
            text.contains("""fun beta() { println("b") }"""),
            "reconstructed source must include beta, got:\n$text",
        )
        assertTrue(
            !text.contains("""fun alpha()"""),
            "reconstructed source must NOT include alpha, got:\n$text",
        )
        assertTrue(
            !text.contains("""fun gamma()"""),
            "reconstructed source must NOT include gamma, got:\n$text",
        )
        // The preamble must reference the
        // run + op for audit purposes.
        assertTrue(
            text.contains(runId.value) && text.contains(opId.value),
            "reconstructed source must name the run and op, got:\n$text",
        )
        // The fingerprint must be in the
        // preamble.
        assertNotNull(result.fingerprint)
        assertTrue(
            text.contains(result.fingerprint),
            "reconstructed source must name the fingerprint, got:\n$text",
        )
    }
}
