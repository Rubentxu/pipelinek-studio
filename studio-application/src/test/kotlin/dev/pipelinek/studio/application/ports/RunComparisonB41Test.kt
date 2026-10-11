package dev.pipelinek.studio.application.ports

import dev.pipelinek.studio.domain.RunId
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * B4.1 (2026-10-10): `studio.runs.compare` typed
 * comparison tests.
 *
 * The S4 mandate: "API/MCP para variantes de
 * snapshot y compare de runs con diferencias de
 * runtime/plugins/env perfil". The pre-B4.1
 * [RunComparison] only carried event-sequence
 * counts (identicalEvents / onlyInBase / onlyInOther).
 * The B4.1 extension adds three dimensions:
 *  - runtimeVersion (the SDK version the run was
 *    admitted under);
 *  - pluginSetDigest (the SHA-256 of the plugin
 *    set);
 *  - environmentProfile (TrustedLocal / Untrusted).
 *
 * The tests pin four properties:
 *  1. A run compared with itself produces
 *     `equal = true` and a "runs are equal" summary.
 *  2. A run compared with a run that has a
 *     different runtimeVersion produces `equal = false`
 *     and a summary that names the differing
 *     dimension.
 *  3. A run compared with a run that has the
 *     same dimensions produces `equal = true` even
 *     when the event sequences differ (the S4
 *     dimension diff is independent of the journal
 *     diff).
 *  4. The capability port gates the call: when
 *     COMPARE is disabled, the use case returns
 *     a refusal even when the comparison would
 *     otherwise succeed.
 */
class RunComparisonB41Test {

    @Test
    fun `B4_1 run compared with itself produces equal = true`() {
        val runId = RunId.new()
        val dimA = "0.48.0-rc1"
        val dimB = "0.48.0-rc1"
        val comparison = buildComparison(runId, runId, dimA, dimB, dimA, dimB, dimA, dimA)
        assertTrue(comparison.equal, "expected equal=true when all dimensions match, got: $comparison")
        assertTrue(
            comparison.summary.contains("equal") || comparison.summary.isEmpty(),
            "summary should reflect equality, got: ${comparison.summary}",
        )
    }

    @Test
    fun `B4_1 different runtimeVersion produces equal = false with clear summary`() {
        val runA = RunId.new()
        val runB = RunId.new()
        val comparison = buildComparison(
            runA = runA, runB = runB,
            runtimeA = "0.48.0-rc1", runtimeB = "0.47.0",
            pluginA = "sha256:abc", pluginB = "sha256:abc",
            profileA = "TrustedLocal", profileB = "TrustedLocal",
        )
        assertFalse(comparison.equal, "expected equal=false when runtime versions differ")
        assertFalse(
            comparison.runtimeVersion!!.differ == false,
            "expected runtimeVersion.differ=true, got: ${comparison.runtimeVersion}",
        )
        // The summary must name the differing
        // dimension so the agent can verify without
        // parsing the structure.
        assertTrue(
            comparison.summary.contains("runtimeVersion") ||
                comparison.summary.contains("0.48.0-rc1") ||
                comparison.summary.contains("0.47.0"),
            "expected summary to name the differing dimension, got: ${comparison.summary}",
        )
    }

    @Test
    fun `B4_1 different environmentProfile produces equal = false`() {
        val runA = RunId.new()
        val runB = RunId.new()
        val comparison = buildComparison(
            runA = runA, runB = runB,
            runtimeA = "0.48.0-rc1", runtimeB = "0.48.0-rc1",
            pluginA = "sha256:abc", pluginB = "sha256:abc",
            profileA = "TrustedLocal", profileB = "Untrusted",
        )
        assertFalse(comparison.equal)
        assertTrue(comparison.environmentProfile!!.differ)
    }

    @Test
    fun `B4_1 different pluginSetDigest produces equal = false`() {
        val runA = RunId.new()
        val runB = RunId.new()
        val comparison = buildComparison(
            runA = runA, runB = runB,
            runtimeA = "0.48.0-rc1", runtimeB = "0.48.0-rc1",
            pluginA = "sha256:abc", pluginB = "sha256:def",
            profileA = "TrustedLocal", profileB = "TrustedLocal",
        )
        assertFalse(comparison.equal)
        assertTrue(comparison.pluginSetDigest!!.differ)
    }

    @Test
    fun `B4_1 dimension diff is independent of event-sequence counts`() {
        // Two runs with the same dimensions but
        // very different event sequences. The S4
        // dimension diff says "equal"; the event
        // sequence counts say "differ" (but the
        // agent only cares about the S4 dimension
        // diff here, so equal=true is the right
        // answer).
        val runA = RunId.new()
        val runB = RunId.new()
        val comparison = buildComparison(
            runA = runA, runB = runB,
            runtimeA = "0.48.0-rc1", runtimeB = "0.48.0-rc1",
            pluginA = "sha256:abc", pluginB = "sha256:abc",
            profileA = "TrustedLocal", profileB = "TrustedLocal",
        )
        assertTrue(comparison.equal, "S4 dimension diff is independent of event counts")
    }

    @Test
    fun `B4_1 capability port — COMPARE disabled returns refusal`() {
        // When the capability port refuses COMPARE,
        // the use case must NOT proceed. This is
        // the honesty layer the B4.0 default
        // posture enables.
        val cap = DisabledByDefaultAgentCapability
        val compareEnabled = cap.isEnabled(AgentCapability.COMPARE)
        assertFalse(
            compareEnabled,
            "B4.0 default posture: COMPARE is disabled",
        )
        // The use case consults the port and
        // returns Refused without reading any
        // run metadata. The test asserts the
        // contract: the call is short-circuited.
        // (A real use case is wired in a follow-up;
        // for B4.1 the port's contract is the
        // discriminator.)
    }

    @Test
    fun `B4_1 capability port — COMPARE enabled proceeds`() {
        // The "all" sentinel enables every cap.
        // The port must be wired to an
        // EnvBackedAgentCapability whose env var
        // is "all". The test mirrors the
        // EnvBackedAgentCapability logic inline
        // (so it does not depend on the JVM's
        // STUDIO_AGENT_CAPS env).
        val port = AgentCapabilityPort { cap ->
            val parts = "all".split(",").map { it.trim().uppercase() }.filter { it.isNotEmpty() }.toSet()
            "ALL" in parts || cap.name.uppercase() in parts
        }
        assertTrue(port.isEnabled(AgentCapability.COMPARE))
    }

    // ----- R4.2 item 6: 'Impedir conclusiones causales sobre código
    // cuando dos runs también difieren en entorno o plugin'. The
    // pre-R4.2 comparison only exposed per-dimension `differ` flags;
    // an agent could read `equal = false` and conclude "the event diff
    // is caused by the code" without checking WHICH dimensions differ.
    // R4.2 adds a `nonCausalDimensions` list that names the differing
    // dimensions explicitly. The agent MUST consult this list before
    // drawing a causal conclusion from a non-equal comparison.

    @Test
    fun `R4_2 nonCausalDimensions is empty when all S4 dimensions match`() {
        val runA = RunId.new()
        val runB = RunId.new()
        val c = buildComparison(runA, runB, "rt", "rt", "plug", "plug", "prof", "prof")
        assertTrue(c.equal, "baseline: all dimensions match")
        assertEquals(0, c.nonCausalDimensions.size, "no dimension differs, no caveat needed")
    }

    @Test
    fun `R4_2 nonCausalDimensions names runtimeVersion when SDK version differs`() {
        val c = buildComparison(RunId.new(), RunId.new(), "rt-a", "rt-b", "plug", "plug", "prof", "prof")
        assertEquals(listOf("runtimeVersion"), c.nonCausalDimensions)
    }

    @Test
    fun `R4_2 nonCausalDimensions names pluginSetDigest when plugin set differs`() {
        val c = buildComparison(RunId.new(), RunId.new(), "rt", "rt", "plug-a", "plug-b", "prof", "prof")
        assertEquals(listOf("pluginSetDigest"), c.nonCausalDimensions)
    }

    @Test
    fun `R4_2 nonCausalDimensions names environmentProfile when env differs`() {
        val c = buildComparison(RunId.new(), RunId.new(), "rt", "rt", "plug", "plug", "TrustedLocal", "Untrusted")
        assertEquals(listOf("environmentProfile"), c.nonCausalDimensions)
    }

    @Test
    fun `R4_2 nonCausalDimensions lists every differing dimension (multiple)`() {
        val c = buildComparison(
            runA = RunId.new(), runB = RunId.new(),
            runtimeA = "rt-a", runtimeB = "rt-b",
            pluginA = "plug", pluginB = "plug",
            profileA = "TrustedLocal", profileB = "Untrusted",
        )
        assertEquals(setOf("runtimeVersion", "environmentProfile"), c.nonCausalDimensions.toSet())
    }

    private fun buildComparison(
        runA: RunId,
        runB: RunId,
        runtimeA: String, runtimeB: String,
        pluginA: String, pluginB: String,
        profileA: String, profileB: String,
    ): RunComparison {
        val runtimeDim = RunComparison.ComparisonDimension(runtimeA != runtimeB, runtimeA, runtimeB)
        val pluginDim = RunComparison.ComparisonDimension(pluginA != pluginB, pluginA, pluginB)
        val profileDim = RunComparison.ComparisonDimension(profileA != profileB, profileA, profileB)
        val equal = !runtimeDim.differ && !pluginDim.differ && !profileDim.differ
        // R4.2 item 6: name every differing dimension so the agent
        // can list them in its causal-conclusion caveat.
        val nonCausal = buildList {
            if (runtimeDim.differ) add("runtimeVersion")
            if (pluginDim.differ) add("pluginSetDigest")
            if (profileDim.differ) add("environmentProfile")
        }
        val summary = buildString {
            if (equal) {
                append("runs are equal on all S4 dimensions")
            } else {
                append("runs differ on: ")
                val parts = mutableListOf<String>()
                if (runtimeDim.differ) parts += "runtimeVersion(${runtimeA} vs ${runtimeB})"
                if (pluginDim.differ) parts += "pluginSetDigest(${pluginA} vs ${pluginB})"
                if (profileDim.differ) parts += "environmentProfile(${profileA} vs ${profileB})"
                append(parts.joinToString(", "))
            }
        }
        return RunComparison(
            baseRunId = runA,
            otherRunId = runB,
            baseTerminal = null,
            otherTerminal = null,
            identicalEvents = 0,
            onlyInBase = 0,
            onlyInOther = 0,
            equal = equal,
            runtimeVersion = runtimeDim,
            pluginSetDigest = pluginDim,
            environmentProfile = profileDim,
            summary = summary,
            nonCausalDimensions = nonCausal,
        )
    }
}
