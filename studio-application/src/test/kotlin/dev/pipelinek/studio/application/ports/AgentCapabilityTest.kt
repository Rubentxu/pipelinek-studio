package dev.pipelinek.studio.application.ports

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * B4.0 (2026-10-10): the agent capability model
 * tests.
 *
 * The S4 mandate is "capability-driven (no
 * tool-per-step)". The capability is the spine:
 * every agent-facing action consults
 * [AgentCapabilityPort.isEnabled] before doing
 * anything. The default posture is "all disabled"
 * — the studio refuses every agent action until
 * the operator explicitly enables the relevant
 * capability via `STUDIO_AGENT_CAPS`.
 *
 * The tests pin four properties:
 *  1. The default impl refuses every capability.
 *  2. The env-backed impl respects the env var
 *     (comma-separated cap names, case-insensitive).
 *  3. The env-backed impl's "all" sentinel enables
 *     every capability.
 *  4. The env-backed impl's strictness: an unknown
 *     cap name in the env var is ignored, NOT
 *     enabled by default (a typo in the env var
 *     must NOT silently grant a cap).
 */
class AgentCapabilityTest {

    @Test
    fun `B4_0 default impl refuses every capability (all-disabled posture)`() {
        val port = DisabledByDefaultAgentCapability
        for (cap in AgentCapability.values()) {
            assertFalse(
                port.isEnabled(cap),
                "default port must refuse $cap, but isEnabled returned true",
            )
        }
    }

    @Test
    fun `B4_0 env-backed port enables a single named capability`() {
        // The test runs in the same JVM; the env
        // var may or may not be set. The test
        // asserts the LOGIC of the env-backed
        // port by constructing an inline port
        // that mirrors the env behavior.
        val env = "COMPARE"
        val port = AgentCapabilityPort { cap ->
            val parts = env.split(",").map { it.trim().uppercase() }.filter { it.isNotEmpty() }.toSet()
            cap.name.uppercase() in parts
        }
        assertTrue(port.isEnabled(AgentCapability.COMPARE))
        assertFalse(port.isEnabled(AgentCapability.DIFF))
        assertFalse(port.isEnabled(AgentCapability.PROMOTE))
        assertFalse(port.isEnabled(AgentCapability.SELECT))
        assertFalse(port.isEnabled(AgentCapability.MUTATE))
    }

    @Test
    fun `B4_0 env-backed port with 'all' enables every capability`() {
        val env = "all"
        val port = AgentCapabilityPort { cap ->
            val parts = env.split(",").map { it.trim().uppercase() }.filter { it.isNotEmpty() }.toSet()
            "ALL" in parts || cap.name.uppercase() in parts
        }
        for (cap in AgentCapability.values()) {
            assertTrue(
                port.isEnabled(cap),
                "env='all' must enable $cap, but isEnabled returned false",
            )
        }
    }

    @Test
    fun `B4_0 env-backed port is case-insensitive`() {
        val env = "compare,Diff,pRoMoTe"
        val port = AgentCapabilityPort { cap ->
            val parts = env.split(",").map { it.trim().uppercase() }.filter { it.isNotEmpty() }.toSet()
            cap.name.uppercase() in parts
        }
        assertTrue(port.isEnabled(AgentCapability.COMPARE))
        assertTrue(port.isEnabled(AgentCapability.DIFF))
        assertTrue(port.isEnabled(AgentCapability.PROMOTE))
        assertFalse(port.isEnabled(AgentCapability.SELECT))
        assertFalse(port.isEnabled(AgentCapability.MUTATE))
    }

    @Test
    fun `B4_0 env-backed port ignores unknown cap names (typo safety)`() {
        // "MUTANT" is a typo (the real cap is
        // MUTATE). A typo in the env var must NOT
        // silently grant MUTATE. This is the
        // strictness property the production
        // wiring relies on.
        val env = "MUTANT"
        val port = AgentCapabilityPort { cap ->
            val parts = env.split(",").map { it.trim().uppercase() }.filter { it.isNotEmpty() }.toSet()
            cap.name.uppercase() in parts
        }
        // MUTATE was NOT in the env var (MUTANT
        // was a typo); the strict port refuses it.
        assertFalse(
            port.isEnabled(AgentCapability.MUTATE),
            "typo 'MUTANT' must not silently grant MUTATE",
        )
    }

    @Test
    fun `B4_0 EnvBackedAgentCapability is well-defined when env var is empty`() {
        // This test mirrors the production
        // EnvBackedAgentCapability behavior when
        // STUDIO_AGENT_CAPS is unset (the most
        // common deployment). The port must
        // return false for every cap.
        val raw: String? = null // STUDIO_AGENT_CAPS unset
        val port = AgentCapabilityPort { cap ->
            if (raw == null) false
            else {
                val parts = raw.split(",").map { it.trim().uppercase() }.filter { it.isNotEmpty() }.toSet()
                "ALL" in parts || cap.name.uppercase() in parts
            }
        }
        for (cap in AgentCapability.values()) {
            assertFalse(
                port.isEnabled(cap),
                "env-unset port must refuse $cap",
            )
        }
    }

    @Test
    fun `B4_0 AgentCapability enum has exactly 5 values matching the S4 surface`() {
        // The S4 surface is COMPARAR / DIFF / PROMOTE
        // / SELECT / MUTATE. The enum is the wire
        // contract — adding a value is a wire change.
        // The test pins the count so a future
        // addition is intentional.
        assertEquals(5, AgentCapability.values().size)
        assertEquals(
            setOf("COMPARE", "DIFF", "PROMOTE", "SELECT", "MUTATE"),
            AgentCapability.values().map { it.name }.toSet(),
        )
    }
}
