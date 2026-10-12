package dev.pipelinek.studio.cli

import org.junit.jupiter.api.Test
import kotlin.test.assertTrue
import kotlin.test.assertEquals

/**
 * S5 E5.3 — SDK TS generator tests. The output is pure TypeScript text; we assert
 * structural properties without a TS parser:
 *   - Header comment + VERSION export
 *   - One StudioClient class
 *   - Every documented HTTP path produces a wrapper function
 *   - Path params become required function args
 *   - POSTs accept an optional body arg
 *   - Function names are camelCased
 */
class GenerateSdkTest {

    private val sdk: String by lazy { GenerateSdk.generate() }

    @Test
    fun `sdk starts with auto-generated header and exports VERSION`() {
        assertTrue(sdk.startsWith("// Auto-generated from /openapi.json"), "missing header; got: ${sdk.take(80)}")
        assertTrue(sdk.contains("export const VERSION = \"1.0.0-rc.14\""), "missing VERSION export")
    }

    @Test
    fun `sdk contains exactly one StudioClient class`() {
        val count = Regex("export class StudioClient").findAll(sdk).count()
        assertEquals(1, count, "expected exactly one StudioClient; got $count")
    }

    @Test
    fun `sdk emits a function for every documented path`() {
        val expectedFns = listOf(
            "getHealth",
            "getApiVersion",
            "getOpenapiJson",
            "postExperiments",
            "postExperimentsExperimentIdSnapshots",
            "postRuns",
            "getRunsRunId",
            "getRunsRunIdEvents",
            "getRunsRunIdStream",
            "getRunsRunIdOutput",
            "getRunsRunIdDiagnostics",
            "getRunsRunIdIr",
            "getSteps",
            "getStepsStepId",
        )
        for (fn in expectedFns) {
            assertTrue(sdk.contains("async $fn("), "missing function $fn")
        }
    }

    @Test
    fun `path params are required positional args on the wrapper functions`() {
        // experimentId in /experiments/{experimentId}/snapshots
        val snapFn = Regex("async postExperimentsExperimentIdSnapshots\\(([^)]*)\\)").find(sdk)?.groupValues?.get(1)
        assertTrue(snapFn != null && snapFn.contains("experimentId: string"), "got: $snapFn")
        // runId in /runs/{runId}/...
        val runFn = Regex("async getRunsRunId\\(([^)]*)\\)").find(sdk)?.groupValues?.get(1)
        assertTrue(runFn != null && runFn.contains("runId: string"), "got: $runFn")
    }

    @Test
    fun `POST functions accept an optional body arg`() {
        assertTrue(Regex("async postExperiments\\([^)]*body\\?\\s*:\\s*unknown").containsMatchIn(sdk),
            "postExperiments should accept body?")
        assertTrue(Regex("async postRuns\\([^)]*body\\?\\s*:\\s*unknown").containsMatchIn(sdk),
            "postRuns should accept body?")
    }

    @Test
    fun `path interpolation uses dollar-sign-brace syntax, not broken string templates`() {
        // The wrapper for /runs/{runId} should interpolate `${runId}` not ${1} or $1.
        // We assert against the literal source: `/${'$'}{runId}` (escaped in the Kotlin
        // string so the $ doesn't get interpreted).
        assertTrue(sdk.contains("`/runs/\${runId}`"), "expected \${runId} interpolation in path template; sdk excerpt: ${sdk.lineSequence().filter { "getRunsRunId" in it }.joinToString()}")
    }

    @Test
    fun `request helper honors 202 accepted as a non-throw status`() {
        // Per the Studio contract, 202 is the upstream "unknown" status (not a hard error).
        assertTrue(sdk.contains("res.status !== 202"), "expected 202 to NOT throw in the request helper")
    }
}
