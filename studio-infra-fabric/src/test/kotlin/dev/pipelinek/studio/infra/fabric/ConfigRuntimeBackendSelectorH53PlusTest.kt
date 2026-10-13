package dev.pipelinek.studio.infra.fabric

import dev.pipelinek.studio.application.ports.RuntimeBackend
import dev.pipelinek.studio.application.ports.RuntimeBackendSelectionResult
import dev.pipelinek.studio.domain.RunId
import dev.pipelinek.studio.domain.ScenarioId
import dev.pipelinek.studio.domain.SnapshotId
import dev.pipelinek.studio.application.ports.RunRequest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Falsifiers for the SFW-H5.3+ Podman
 * arm on the [ConfigRuntimeBackendSelector].
 *
 * The H5.3 chain-start ships the abstract
 * Podman surface + a stub adapter. The
 * H5.3+ follow-up wires the config-driven
 * selector to recognize `runtime-backend:
 * podman` and route to the Podman arm.
 * The actual `podman run` invocation is
 * a follow-up H5.3+ unit.
 */
@DisplayName("ConfigRuntimeBackendSelector (SFW-H5.3+ Podman arm)")
class ConfigRuntimeBackendSelectorH53PlusTest {

    private val request = RunRequest(
        runId = RunId("studio-h53p-1"),
        snapshotId = SnapshotId("snapshot-h53p"),
        scenarioId = ScenarioId("scenario-h53p"),
        profile = dev.pipelinek.studio.application.ports.ExecutionProfile.TrustedLocal,
    )

    // ------------------------------------------------------------------
    // Falsifier 1: explicit `podman` routes to Podman arm
    // ------------------------------------------------------------------
    @Test
    fun `explicit podman config routes to Podman arm`() {
        val selector = ConfigRuntimeBackendSelector(mapOf("runtime-backend" to "podman"))
        val result = selector.select(request)
        val allowed = result as? RuntimeBackendSelectionResult.Allowed
        assertNotNull(allowed, "expected Allowed, got $result")
        assertEquals(RuntimeBackend.Podman, allowed!!.backend)
    }

    // ------------------------------------------------------------------
    // Falsifier 2: empty config still defaults to Local
    // ------------------------------------------------------------------
    @Test
    fun `empty config still defaults to Local (no silent default change)`() {
        val selector = ConfigRuntimeBackendSelector(emptyMap())
        val result = selector.select(request)
        val allowed = result as? RuntimeBackendSelectionResult.Allowed
        assertNotNull(allowed, "expected Allowed, got $result")
        assertEquals(RuntimeBackend.Local, allowed!!.backend)
    }

    // ------------------------------------------------------------------
    // Falsifier 3: existing `local` + `fabric` still work (no regression)
    // ------------------------------------------------------------------
    @Test
    fun `existing local config still routes to Local`() {
        val selector = ConfigRuntimeBackendSelector(mapOf("runtime-backend" to "local"))
        val result = selector.select(request)
        val allowed = result as? RuntimeBackendSelectionResult.Allowed
        assertNotNull(allowed, "expected Allowed, got $result")
        assertEquals(RuntimeBackend.Local, allowed!!.backend)
    }

    @Test
    fun `existing fabric config still routes to Fabric`() {
        val selector = ConfigRuntimeBackendSelector(mapOf("runtime-backend" to "fabric"))
        val result = selector.select(request)
        val allowed = result as? RuntimeBackendSelectionResult.Allowed
        assertNotNull(allowed, "expected Allowed, got $result")
        assertEquals(RuntimeBackend.Fabric, allowed!!.backend)
    }

    // ------------------------------------------------------------------
    // Falsifier 4: unknown values still refuse (no silent default)
    // ------------------------------------------------------------------
    @Test
    fun `unknown config value still refuses (no silent default to Podman)`() {
        val selector = ConfigRuntimeBackendSelector(mapOf("runtime-backend" to "k8s"))
        val result = selector.select(request)
        val refused = result as? RuntimeBackendSelectionResult.Refused
        assertNotNull(refused, "expected Refused for unknown value, got $result")
        // The reason must mention `podman` as a valid value (so the
        // operator knows about the new arm).
        assertTrue(
            refused!!.reason.contains("podman"),
            "reason should mention 'podman' as an allowed value: ${refused.reason}",
        )
    }

    // ------------------------------------------------------------------
    // Falsifier 5: case-sensitivity preserved
    // ------------------------------------------------------------------
    @Test
    fun `case-sensitive 'Podman' (capital P) refuses (no silent default)`() {
        val selector = ConfigRuntimeBackendSelector(mapOf("runtime-backend" to "Podman"))
        val result = selector.select(request)
        val refused = result as? RuntimeBackendSelectionResult.Refused
        assertNotNull(refused, "expected Refused (case-sensitive), got $result")
    }
}
