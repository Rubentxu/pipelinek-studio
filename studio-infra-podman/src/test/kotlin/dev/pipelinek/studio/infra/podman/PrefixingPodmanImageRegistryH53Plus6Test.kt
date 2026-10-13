package dev.pipelinek.studio.infra.podman

import dev.pipelinek.studio.application.ports.RunRequest
import dev.pipelinek.studio.domain.RunId
import dev.pipelinek.studio.domain.ScenarioId
import dev.pipelinek.studio.domain.SnapshotId
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Falsifiers for the SFW-H5.3+ sixth
 * follow-up: the
 * [PrefixingPodmanImageRegistry] production
 * impl of the [PodmanImageRegistry] seam.
 *
 * The H5.3+ fifth follow-up shipped the
 * seam; the 6th follow-up ships a
 * production impl that prefixes short
 * image names with a configured prefix
 * (useful for private registries).
 */
@DisplayName("PrefixingPodmanImageRegistry (SFW-H5.3+ image prefix)")
class PrefixingPodmanImageRegistryH53Plus6Test {

    private val request = RunRequest(
        runId = RunId("studio-h53p6-1"),
        snapshotId = SnapshotId("snapshot-h53p6"),
        scenarioId = ScenarioId("scenario-h53p6"),
        profile = dev.pipelinek.studio.application.ports.ExecutionProfile.TrustedLocal,
    )

    // ------------------------------------------------------------------
    // Falsifier 1: short name 'studio:latest' is prefixed
    // ------------------------------------------------------------------
    @Test
    fun `short name is prefixed with the configured prefix`() = runBlocking {
        val registry = PrefixingPodmanImageRegistry(imagePrefix = "registry.local")
        val resolved = registry.resolve(request, "studio:latest")
        assertEquals("registry.local/studio:latest", resolved)
    }

    // ------------------------------------------------------------------
    // Falsifier 2: already-namespaced image is returned as-is
    // ------------------------------------------------------------------
    @Test
    fun `already-namespaced image (contains slash) is returned as-is`() = runBlocking {
        val registry = PrefixingPodmanImageRegistry(imagePrefix = "registry.local")
        val resolved = registry.resolve(request, "quay.io/podman/hello")
        assertEquals("quay.io/podman/hello", resolved)
    }

    // ------------------------------------------------------------------
    // Falsifier 3: already-prefixed image (registry.local/studio) is returned as-is
    // ------------------------------------------------------------------
    @Test
    fun `already-prefixed image (matches the configured prefix) is returned as-is`() = runBlocking {
        val registry = PrefixingPodmanImageRegistry(imagePrefix = "registry.local")
        val resolved = registry.resolve(request, "registry.local/studio:0.48.0-rc1")
        assertEquals("registry.local/studio:0.48.0-rc1", resolved)
    }

    // ------------------------------------------------------------------
    // Falsifier 4: empty imagePrefix throws IllegalArgumentException
    // ------------------------------------------------------------------
    @Test
    fun `empty imagePrefix throws IllegalArgumentException`() {
        assertThrows(IllegalArgumentException::class.java) {
            PrefixingPodmanImageRegistry(imagePrefix = "")
        }
    }

    // ------------------------------------------------------------------
    // Falsifier 5: trailing slash in imagePrefix throws IllegalArgumentException
    // ------------------------------------------------------------------
    @Test
    fun `trailing slash in imagePrefix throws IllegalArgumentException`() {
        assertThrows(IllegalArgumentException::class.java) {
            PrefixingPodmanImageRegistry(imagePrefix = "registry.local/")
        }
    }
}
