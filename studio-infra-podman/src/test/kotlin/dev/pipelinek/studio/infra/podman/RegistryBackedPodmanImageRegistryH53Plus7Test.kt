package dev.pipelinek.studio.infra.podman

import dev.pipelinek.studio.application.ports.RunRequest
import dev.pipelinek.studio.domain.RunId
import dev.pipelinek.studio.domain.ScenarioId
import dev.pipelinek.studio.domain.SnapshotId
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Falsifiers for the SFW-H5.3+ seventh
 * follow-up: the [RegistryBackedPodmanImageRegistry]
 * + [PodmanRegistryClient] seam.
 *
 * The H5.3+ 7th follow-up ships:
 * 1. a [PodmanRegistryClient] port (the
 *    HTTP abstraction);
 * 2. a [NoopPodmanRegistryClient] default
 *    (returns `null` for every input);
 * 3. a [RegistryBackedPodmanImageRegistry]
 *    impl that uses the client.
 *
 * The actual HTTP impl of the client
 * is the H5.3+ 8th follow-up.
 */
@DisplayName("RegistryBackedPodmanImageRegistry (SFW-H5.3+ registry client)")
class RegistryBackedPodmanImageRegistryH53Plus7Test {

    private val request = RunRequest(
        runId = RunId("studio-h53p7-1"),
        snapshotId = SnapshotId("snapshot-h53p7"),
        scenarioId = ScenarioId("scenario-h53p7"),
        profile = dev.pipelinek.studio.application.ports.ExecutionProfile.TrustedLocal,
    )

    // ------------------------------------------------------------------
    // Falsifier 1: short name + client returns resolved -> returns resolved
    // ------------------------------------------------------------------
    @Test
    fun `short name + client returns resolved image returns the resolved image`() = runBlocking {
        val fake = FakePodmanRegistryClientForH53P7(
            lookupResult = "registry.local/studio:0.48.0-rc1",
        )
        val registry = RegistryBackedPodmanImageRegistry(
            client = fake,
            registryBaseUrl = "https://registry.local",
        )
        val resolved = registry.resolve(request, "studio:latest")
        assertEquals("registry.local/studio:0.48.0-rc1", resolved)
    }

    // ------------------------------------------------------------------
    // Falsifier 2: short name + client returns null -> returns input as-is
    // ------------------------------------------------------------------
    @Test
    fun `short name + client returns null returns the input image as-is (conservative fallback)`() = runBlocking {
        val fake = FakePodmanRegistryClientForH53P7(lookupResult = null)
        val registry = RegistryBackedPodmanImageRegistry(
            client = fake,
            registryBaseUrl = "https://registry.local",
        )
        val resolved = registry.resolve(request, "studio:latest")
        assertEquals("studio:latest", resolved)
    }

    // ------------------------------------------------------------------
    // Falsifier 3: namespaced image is returned as-is (no registry lookup)
    // ------------------------------------------------------------------
    @Test
    fun `namespaced image is returned as-is (no registry lookup)`() = runBlocking {
        val fake = FakePodmanRegistryClientForH53P7(
            lookupResult = "should-not-be-called",
        )
        val registry = RegistryBackedPodmanImageRegistry(
            client = fake,
            registryBaseUrl = "https://registry.local",
        )
        val resolved = registry.resolve(request, "quay.io/podman/hello")
        assertEquals("quay.io/podman/hello", resolved)
        assertEquals(0, fake.lookupCalls, "client must NOT be called for namespaced images")
    }

    // ------------------------------------------------------------------
    // Falsifier 4: short name + tag separator -> parses tag correctly
    // ------------------------------------------------------------------
    @Test
    fun `short name with explicit tag is parsed correctly (studio colon 0_48_0-rc1)`() = runBlocking {
        val fake = FakePodmanRegistryClientForH53P7(
            lookupResult = "registry.local/studio:0.48.0-rc1",
        )
        val registry = RegistryBackedPodmanImageRegistry(
            client = fake,
            registryBaseUrl = "https://registry.local",
        )
        registry.resolve(request, "studio:0.48.0-rc1")
        assertEquals("studio", fake.lastName, "name must be parsed from the input")
        assertEquals("0.48.0-rc1", fake.lastTag, "tag must be parsed from the input")
    }

    // ------------------------------------------------------------------
    // Falsifier 5: NoopPodmanRegistryClient returns null (no resolution)
    // ------------------------------------------------------------------
    @Test
    fun `NoopPodmanRegistryClient returns null for every input (no resolution)`() = runBlocking {
        val client = NoopPodmanRegistryClient()
        val registry = RegistryBackedPodmanImageRegistry(
            client = client,
            registryBaseUrl = "https://registry.local",
        )
        val resolved = registry.resolve(request, "studio:latest")
        assertEquals("studio:latest", resolved, "no resolution must return the input as-is")
    }
}

// --------------------------------------------------------------------
// Test fixture
// --------------------------------------------------------------------

private class FakePodmanRegistryClientForH53P7(
    val lookupResult: String?,
) : PodmanRegistryClient {
    var lookupCalls: Int = 0
        private set
    var lastRegistryBaseUrl: String? = null
        private set
    var lastName: String? = null
        private set
    var lastTag: String? = null
        private set

    override suspend fun lookupImage(registryBaseUrl: String, image: String, tag: String): String? {
        lookupCalls += 1
        lastRegistryBaseUrl = registryBaseUrl
        lastName = image
        lastTag = tag
        return lookupResult
    }
}
