@file:Suppress("TooManyFunctions")

package dev.pipelinek.studio.infra.fabric

import dev.pipelinek.studio.application.ports.ExecutionProfile
import dev.pipelinek.studio.application.ports.RuntimeBackend
import dev.pipelinek.studio.application.ports.RuntimeBackendSelectionResult
import dev.pipelinek.studio.application.ports.RunRequest
import dev.pipelinek.studio.domain.RunId
import dev.pipelinek.studio.domain.ScenarioId
import dev.pipelinek.studio.domain.SnapshotId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Falsifiers for [ConfigRuntimeBackendSelector] — the
 * SFW-H2.4 controlled-transition selector.
 *
 * The seam under test is a `Map<String, String>` config
 * (the smallest production-shape surface that lets the
 * test drive the selector without booting a config
 * loader).
 *
 * What these tests pin:
 *  - the **default** is `Local` (an empty config is the
 *    only silent default; documented in the contract);
 *  - the **explicit opt-in** to `Fabric`;
 *  - the **typo / wrong case** paths return `Refused`,
 *    NOT a silent `Local` (the H2.4 mandate: no silent
 *    fallback);
 *  - the `Refused` reason names the unknown value + the
 *    allowed values, so the operator sees the answer in
 *    the 4xx.
 */
@DisplayName("ConfigRuntimeBackendSelector (SFW-H2.4 controlled transition)")
class ConfigRuntimeBackendSelectorTest {

    private val runId = RunId("studio-run-1")
    private val req = RunRequest(
        runId = runId,
        snapshotId = SnapshotId("snap-1"),
        scenarioId = ScenarioId("scen-1"),
        profile = ExecutionProfile.TrustedLocal,
    )

    // ---- default -----------------------------------------------------------

    @Test
    fun `empty config is Allowed Local (the pre-H2 default)`() {
        val selector = ConfigRuntimeBackendSelector(emptyMap())
        val result = selector.select(req)

        assertInstanceOf(RuntimeBackendSelectionResult.Allowed::class.java, result)
        assertEquals(RuntimeBackend.Local, (result as RuntimeBackendSelectionResult.Allowed).backend)
    }

    // ---- explicit local ----------------------------------------------------

    @Test
    fun `runtime-backend local is Allowed Local`() {
        val selector = ConfigRuntimeBackendSelector(mapOf("runtime-backend" to "local"))
        val result = selector.select(req)

        assertInstanceOf(RuntimeBackendSelectionResult.Allowed::class.java, result)
        assertEquals(RuntimeBackend.Local, (result as RuntimeBackendSelectionResult.Allowed).backend)
    }

    @Test
    fun `runtime-backend local with surrounding whitespace is Allowed Local`() {
        // The selector trims; a config-map value with a
        // trailing newline (e.g. a `ConfigMap` env var
        // serialised by a shell) still works.
        val selector = ConfigRuntimeBackendSelector(mapOf("runtime-backend" to "  local  "))
        val result = selector.select(req)

        assertInstanceOf(RuntimeBackendSelectionResult.Allowed::class.java, result)
        assertEquals(RuntimeBackend.Local, (result as RuntimeBackendSelectionResult.Allowed).backend)
    }

    // ---- explicit fabric ----------------------------------------------------

    @Test
    fun `runtime-backend fabric is Allowed Fabric (the opt-in)`() {
        val selector = ConfigRuntimeBackendSelector(mapOf("runtime-backend" to "fabric"))
        val result = selector.select(req)

        assertInstanceOf(RuntimeBackendSelectionResult.Allowed::class.java, result)
        assertEquals(RuntimeBackend.Fabric, (result as RuntimeBackendSelectionResult.Allowed).backend)
    }

    // ---- typos and wrong case: refused, NOT a silent Local ------------------

    @Test
    fun `unknown value is Refused the controlled-transition mandate refuses it`() {
        val selector = ConfigRuntimeBackendSelector(mapOf("runtime-backend" to "fabric-server"))
        val result = selector.select(req)

        assertInstanceOf(RuntimeBackendSelectionResult.Refused::class.java, result)
        val refused = result as RuntimeBackendSelectionResult.Refused
        assertTrue(
            refused.reason.contains("fabric-server") &&
                refused.reason.contains("allowed") &&
                refused.reason.contains("local") &&
                refused.reason.contains("fabric"),
            "got: ${refused.reason}",
        )
    }

    @Test
    fun `uppercase LOCAL is Refused (the values are case-sensitive)`() {
        // The values are case-sensitive on purpose: a
        // case-tolerance would mean a typo in either
        // direction is silently accepted. The H2.4
        // discipline is to refuse.
        val selector = ConfigRuntimeBackendSelector(mapOf("runtime-backend" to "LOCAL"))
        val result = selector.select(req)

        assertInstanceOf(RuntimeBackendSelectionResult.Refused::class.java, result)
        assertTrue(
            (result as RuntimeBackendSelectionResult.Refused).reason.contains("LOCAL"),
        )
    }

    @Test
    fun `mixed-case Fabric is Refused (the values are case-sensitive)`() {
        val selector = ConfigRuntimeBackendSelector(mapOf("runtime-backend" to "Fabric"))
        val result = selector.select(req)

        assertInstanceOf(RuntimeBackendSelectionResult.Refused::class.java, result)
        val refused = result as RuntimeBackendSelectionResult.Refused
        assertTrue(
            refused.reason.contains("Fabric"),
            "got: ${refused.reason}",
        )
    }
}
