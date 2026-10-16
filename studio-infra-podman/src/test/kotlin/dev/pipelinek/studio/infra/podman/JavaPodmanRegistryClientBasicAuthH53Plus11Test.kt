package dev.pipelinek.studio.infra.podman

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Falsifiers for the SFW-H5.3+ eleventh
 * follow-up: HTTP Basic auth on
 * [JavaPodmanRegistryClient].
 *
 * The 11th follow-up adds an optional
 * `basicAuthCredentials: Pair<String, String>?`
 * constructor parameter. When set, the
 * client adds `Authorization: Basic
 * <base64>` to the manifest request.
 * Useful for legacy registries that don't
 * support Bearer token auth.
 */
@DisplayName("JavaPodmanRegistryClient (SFW-H5.3+ Basic auth)")
class JavaPodmanRegistryClientBasicAuthH53Plus11Test {

    // ------------------------------------------------------------------
    // Falsifier 1: basicAuthCredentials=null does NOT add Authorization
    // ------------------------------------------------------------------
    @Test
    fun `basicAuthCredentials null does NOT add Authorization header`() = runBlocking {
        val fake = FakeHttpRequestSenderForH53P11(
            response = HttpResponse(
                statusCode = 200,
                body = "{}",
                headers = mapOf("Docker-Content-Digest" to "sha256:abc123"),
            ),
        )
        val client = JavaPodmanRegistryClient(
            httpSender = fake,
            basicAuthCredentials = null,
        )
        client.lookupImage(
            registryBaseUrl = "https://registry.local",
            image = "studio",
            tag = "latest",
        )
        assertNull(
            fake.lastHeaders["Authorization"],
            "Authorization must NOT be set when basicAuthCredentials is null",
        )
    }

    // ------------------------------------------------------------------
    // Falsifier 2: basicAuthCredentials set adds Authorization: Basic
    // ------------------------------------------------------------------
    @Test
    fun `basicAuthCredentials set adds Authorization colon Basic header`() = runBlocking {
        val fake = FakeHttpRequestSenderForH53P11(
            response = HttpResponse(
                statusCode = 200,
                body = "{}",
                headers = mapOf("Docker-Content-Digest" to "sha256:abc123"),
            ),
        )
        val client = JavaPodmanRegistryClient(
            httpSender = fake,
            basicAuthCredentials = "user" to "pass",
        )
        client.lookupImage(
            registryBaseUrl = "https://registry.local",
            image = "studio",
            tag = "latest",
        )
        val auth = fake.lastHeaders["Authorization"]
        assertNotNull(auth, "Authorization must be set when basicAuthCredentials is set")
        assertEquals(
            true,
            auth!!.startsWith("Basic "),
            "Authorization must use the Basic scheme: $auth",
        )
        // The base64 of "user:pass" is "dXNlcjpwYXNz"
        assertEquals(
            "Basic dXNlcjpwYXNz",
            auth,
            "Authorization must be 'Basic dXNlcjpwYXNz' for user:pass",
        )
    }

    // ------------------------------------------------------------------
    // Falsifier 3: Basic auth works with empty password
    // ------------------------------------------------------------------
    @Test
    fun `Basic auth with empty password is accepted`() = runBlocking {
        val fake = FakeHttpRequestSenderForH53P11(
            response = HttpResponse(
                statusCode = 200,
                body = "{}",
                headers = mapOf("Docker-Content-Digest" to "sha256:abc123"),
            ),
        )
        val client = JavaPodmanRegistryClient(
            httpSender = fake,
            basicAuthCredentials = "user" to "",
        )
        client.lookupImage(
            registryBaseUrl = "https://registry.local",
            image = "studio",
            tag = "latest",
        )
        // The base64 of "user:" is "dXNlcjo="
        assertEquals(
            "Basic dXNlcjo=",
            fake.lastHeaders["Authorization"],
        )
    }

    // ------------------------------------------------------------------
    // Falsifier 4: Basic auth and Bearer auth coexist (Basic takes precedence)
    // ------------------------------------------------------------------
    @Test
    fun `Basic auth is sent on the first request (no Bearer challenge needed)`() = runBlocking {
        val fake = FakeHttpRequestSenderForH53P11(
            response = HttpResponse(
                statusCode = 200,
                body = "{}",
                headers = mapOf("Docker-Content-Digest" to "sha256:abc123"),
            ),
        )
        val client = JavaPodmanRegistryClient(
            httpSender = fake,
            basicAuthCredentials = "user" to "pass",
        )
        val resolved = client.lookupImage(
            registryBaseUrl = "https://registry.local",
            image = "studio",
            tag = "latest",
        )
        assertEquals(
            "https://registry.local/studio@sha256:abc123",
            resolved,
        )
        // Only 1 HTTP call (no Bearer challenge)
        assertEquals(1, fake.getCalls, "Basic auth must short-circuit the Bearer auth flow")
    }

    // ------------------------------------------------------------------
    // Falsifier 5: Basic auth with special characters is base64-encoded correctly
    // ------------------------------------------------------------------
    @Test
    fun `Basic auth with special characters is base64-encoded correctly`() = runBlocking {
        val fake = FakeHttpRequestSenderForH53P11(
            response = HttpResponse(
                statusCode = 200,
                body = "{}",
                headers = mapOf("Docker-Content-Digest" to "sha256:abc123"),
            ),
        )
        val client = JavaPodmanRegistryClient(
            httpSender = fake,
            basicAuthCredentials = "user@domain" to "p@ss:word",
        )
        client.lookupImage(
            registryBaseUrl = "https://registry.local",
            image = "studio",
            tag = "latest",
        )
        val auth = fake.lastHeaders["Authorization"]!!
        // Decode the base64 and verify the original
        val base64Part = auth.removePrefix("Basic ")
        val decoded = String(
            java.util.Base64.getDecoder().decode(base64Part),
            Charsets.UTF_8,
        )
        assertEquals(
            "user@domain:p@ss:word",
            decoded,
            "decoded credentials must match the input",
        )
    }
}

private class FakeHttpRequestSenderForH53P11(
    var response: HttpResponse = HttpResponse(200, "", emptyMap()),
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
        return response
    }
}
