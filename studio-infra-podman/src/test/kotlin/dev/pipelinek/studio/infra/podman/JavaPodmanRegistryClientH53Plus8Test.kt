package dev.pipelinek.studio.infra.podman

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Falsifiers for the SFW-H5.3+ eighth
 * follow-up: the real HTTP impl of
 * [PodmanRegistryClient] using
 * `java.net.http.HttpClient` (JDK 21
 * built-in; no new dependencies).
 *
 * The tests use a [FakeHttpRequestSender]
 * to inject deterministic responses
 * (the `java.net.http.HttpClient` is not
 * easy to fake in tests; the port
 * abstraction is the test seam).
 */
@DisplayName("JavaPodmanRegistryClient (SFW-H5.3+ real HTTP impl)")
class JavaPodmanRegistryClientH53Plus8Test {

    // ------------------------------------------------------------------
    // Falsifier 1: 200 with Docker-Content-Digest -> fully-qualified image
    // ------------------------------------------------------------------
    @Test
    fun `200 with Docker-Content-Digest returns fully-qualified image`() = runBlocking {
        val fake = FakeHttpRequestSenderForH53P8(
            response = HttpResponse(
                statusCode = 200,
                body = "{}",
                headers = mapOf(
                    "Docker-Content-Digest" to "sha256:abc123def456",
                    "Content-Type" to "application/vnd.docker.distribution.manifest.v2+json",
                ),
            ),
        )
        val client = JavaPodmanRegistryClient(httpSender = fake)
        val resolved = client.lookupImage(
            registryBaseUrl = "https://registry.local",
            image = "studio",
            tag = "latest",
        )
        assertEquals(
            "https://registry.local/studio@sha256:abc123def456",
            resolved,
        )
    }

    // ------------------------------------------------------------------
    // Falsifier 2: 404 -> null (image not found)
    // ------------------------------------------------------------------
    @Test
    fun `404 returns null (image not found)`() = runBlocking {
        val fake = FakeHttpRequestSenderForH53P8(
            response = HttpResponse(
                statusCode = 404,
                body = "not found",
                headers = emptyMap(),
            ),
        )
        val client = JavaPodmanRegistryClient(httpSender = fake)
        val resolved = client.lookupImage(
            registryBaseUrl = "https://registry.local",
            image = "nonexistent",
            tag = "latest",
        )
        assertNull(resolved, "404 must return null")
    }

    // ------------------------------------------------------------------
    // Falsifier 3: network error -> null (conservative fallback)
    // ------------------------------------------------------------------
    @Test
    fun `network error returns null (conservative fallback)`() = runBlocking {
        val fake = FakeHttpRequestSenderForH53P8(
            throwOnGet = java.net.ConnectException("Connection refused"),
        )
        val client = JavaPodmanRegistryClient(httpSender = fake)
        val resolved = client.lookupImage(
            registryBaseUrl = "https://registry.local",
            image = "studio",
            tag = "latest",
        )
        assertNull(resolved, "network error must return null")
    }

    // ------------------------------------------------------------------
    // Falsifier 4: 200 without Docker-Content-Digest -> null
    // ------------------------------------------------------------------
    @Test
    fun `200 without Docker-Content-Digest returns null`() = runBlocking {
        val fake = FakeHttpRequestSenderForH53P8(
            response = HttpResponse(
                statusCode = 200,
                body = "{}",
                headers = emptyMap(),
            ),
        )
        val client = JavaPodmanRegistryClient(httpSender = fake)
        val resolved = client.lookupImage(
            registryBaseUrl = "https://registry.local",
            image = "studio",
            tag = "latest",
        )
        assertNull(resolved, "200 without Docker-Content-Digest must return null")
    }

    // ------------------------------------------------------------------
    // Falsifier 5: URL is constructed correctly
    // ------------------------------------------------------------------
    @Test
    fun `URL is constructed as registryBaseUrl plus v2 plus image plus manifests plus tag`() = runBlocking {
        val fake = FakeHttpRequestSenderForH53P8(
            response = HttpResponse(
                statusCode = 200,
                body = "{}",
                headers = mapOf("Docker-Content-Digest" to "sha256:abc123"),
            ),
        )
        val client = JavaPodmanRegistryClient(httpSender = fake)
        client.lookupImage(
            registryBaseUrl = "https://registry.local",
            image = "studio",
            tag = "0.48.0-rc1",
        )
        assertEquals(
            "https://registry.local/v2/studio/manifests/0.48.0-rc1",
            fake.lastUrl,
            "URL must follow the Docker Registry V2 pattern",
        )
        // The Accept header is set
        assertEquals(
            "application/vnd.docker.distribution.manifest.v2+json",
            fake.lastHeaders["Accept"],
            "Accept header must be the Docker Registry V2 manifest MIME type",
        )
    }
}

// --------------------------------------------------------------------
// Test fixture
// --------------------------------------------------------------------

private class FakeHttpRequestSenderForH53P8(
    var response: HttpResponse = HttpResponse(200, "", emptyMap()),
    var throwOnGet: Throwable? = null,
) : HttpRequestSender {
    var getCalls: Int = 0
        private set
    var lastUrl: String? = null
        private set
    var lastHeaders: Map<String, String> = emptyMap()
        private set

    override fun get(url: String, headers: Map<String, String>): HttpResponse {
        getCalls += 1
        lastUrl = url
        lastHeaders = headers
        throwOnGet?.let { throw it }
        return response
    }
}
