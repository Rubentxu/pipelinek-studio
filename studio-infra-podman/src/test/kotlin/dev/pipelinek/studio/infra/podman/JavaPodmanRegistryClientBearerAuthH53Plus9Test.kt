package dev.pipelinek.studio.infra.podman

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Falsifiers for the SFW-H5.3+ ninth
 * follow-up: Bearer token auth for the
 * Docker Registry V2 manifest API.
 *
 * The auth flow:
 * 1. Client sends the manifest request.
 * 2. Server returns 401 + WWW-Authenticate
 *    header (Bearer realm + service).
 * 3. Client GETs `$realm?service=...&scope=...`
 *    to fetch a Bearer token.
 * 4. Client retries the manifest request
 *    with `Authorization: Bearer <token>`.
 */
@DisplayName("JavaPodmanRegistryClient (SFW-H5.3+ Bearer auth)")
class JavaPodmanRegistryClientBearerAuthH53Plus9Test {

    // ------------------------------------------------------------------
    // Falsifier 1: 401 + valid auth -> retry 200 + digest
    // ------------------------------------------------------------------
    @Test
    fun `401 with valid auth flow returns the fully-qualified image after retry`() = runBlocking {
        val fake = SequenceHttpRequestSenderForH53P9(
            responses = listOf(
                // First: 401 with WWW-Authenticate
                HttpResponse(
                    statusCode = 401,
                    body = "unauthorized",
                    headers = mapOf(
                        "WWW-Authenticate" to """Bearer realm="https://auth.registry.local/token", service="registry.local"""",
                    ),
                ),
                // Second: auth response (token)
                HttpResponse(
                    statusCode = 200,
                    body = """{"token": "test-bearer-token-abc123"}""",
                    headers = mapOf("Content-Type" to "application/json"),
                ),
                // Third: retry manifest (200 + digest)
                HttpResponse(
                    statusCode = 200,
                    body = "{}",
                    headers = mapOf("Docker-Content-Digest" to "sha256:abc123"),
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
            "https://registry.local/studio@sha256:abc123",
            resolved,
        )
        // Verify the auth flow sequence
        assertEquals(3, fake.getCalls.size, "expected 3 HTTP calls (manifest + auth + retry)")
        // First: manifest with no auth
        assertEquals(
            "https://registry.local/v2/studio/manifests/latest",
            fake.getCalls[0].first,
        )
        assertNull(
            fake.getCalls[0].second["Authorization"],
            "first call must NOT include Authorization",
        )
        // Second: auth request
        assertTrue(
            fake.getCalls[1].first.contains("/token?service=registry.local&scope=repository:studio:pull"),
            "second call must be the auth request: ${fake.getCalls[1].first}",
        )
        // Third: retry with Bearer
        assertEquals(
            "Bearer test-bearer-token-abc123",
            fake.getCalls[2].second["Authorization"],
            "third call must include the Bearer token",
        )
    }

    // ------------------------------------------------------------------
    // Falsifier 2: 401 without WWW-Authenticate -> null
    // ------------------------------------------------------------------
    @Test
    fun `401 without WWW-Authenticate returns null (no auth challenge)`() = runBlocking {
        val fake = SequenceHttpRequestSenderForH53P9(
            responses = listOf(
                HttpResponse(
                    statusCode = 401,
                    body = "unauthorized",
                    headers = emptyMap(),  // no WWW-Authenticate
                ),
            ),
        )
        val client = JavaPodmanRegistryClient(httpSender = fake)
        val resolved = client.lookupImage(
            registryBaseUrl = "https://registry.local",
            image = "studio",
            tag = "latest",
        )
        assertNull(resolved, "401 without WWW-Authenticate must return null")
    }

    // ------------------------------------------------------------------
    // Falsifier 3: 401 + auth returns 500 -> null
    // ------------------------------------------------------------------
    @Test
    fun `auth endpoint 500 returns null (conservative fallback)`() = runBlocking {
        val fake = SequenceHttpRequestSenderForH53P9(
            responses = listOf(
                HttpResponse(
                    statusCode = 401,
                    body = "unauthorized",
                    headers = mapOf(
                        "WWW-Authenticate" to """Bearer realm="https://auth.registry.local/token", service="registry.local"""",
                    ),
                ),
                HttpResponse(
                    statusCode = 500,
                    body = "internal error",
                    headers = emptyMap(),
                ),
            ),
        )
        val client = JavaPodmanRegistryClient(httpSender = fake)
        val resolved = client.lookupImage(
            registryBaseUrl = "https://registry.local",
            image = "studio",
            tag = "latest",
        )
        assertNull(resolved, "auth endpoint 500 must return null")
    }

    // ------------------------------------------------------------------
    // Falsifier 4: 401 + auth returns invalid JSON -> null
    // ------------------------------------------------------------------
    @Test
    fun `auth endpoint returns invalid JSON returns null (conservative fallback)`() = runBlocking {
        val fake = SequenceHttpRequestSenderForH53P9(
            responses = listOf(
                HttpResponse(
                    statusCode = 401,
                    body = "unauthorized",
                    headers = mapOf(
                        "WWW-Authenticate" to """Bearer realm="https://auth.registry.local/token", service="registry.local"""",
                    ),
                ),
                HttpResponse(
                    statusCode = 200,
                    body = "not json",
                    headers = emptyMap(),
                ),
            ),
        )
        val client = JavaPodmanRegistryClient(httpSender = fake)
        val resolved = client.lookupImage(
            registryBaseUrl = "https://registry.local",
            image = "studio",
            tag = "latest",
        )
        assertNull(resolved, "invalid auth JSON must return null")
    }

    // ------------------------------------------------------------------
    // Falsifier 5: retry after auth returns 404 -> null
    // ------------------------------------------------------------------
    @Test
    fun `retry after auth returns 404 returns null (image not found)`() = runBlocking {
        val fake = SequenceHttpRequestSenderForH53P9(
            responses = listOf(
                HttpResponse(
                    statusCode = 401,
                    body = "unauthorized",
                    headers = mapOf(
                        "WWW-Authenticate" to """Bearer realm="https://auth.registry.local/token", service="registry.local"""",
                    ),
                ),
                HttpResponse(
                    statusCode = 200,
                    body = """{"token": "test-bearer-token"}""",
                    headers = emptyMap(),
                ),
                HttpResponse(
                    statusCode = 404,
                    body = "not found",
                    headers = emptyMap(),
                ),
            ),
        )
        val client = JavaPodmanRegistryClient(httpSender = fake)
        val resolved = client.lookupImage(
            registryBaseUrl = "https://registry.local",
            image = "nonexistent",
            tag = "latest",
        )
        assertNull(resolved, "404 after auth must return null")
    }
}

// --------------------------------------------------------------------
// Test fixture: a sequence-based fake that returns the next response
// in the list, or throws if exhausted.
// --------------------------------------------------------------------

private class SequenceHttpRequestSenderForH53P9(
    val responses: List<HttpResponse>,
) : HttpRequestSender {
    val getCalls: MutableList<Pair<String, Map<String, String>>> = mutableListOf()
    private var index = 0

    override fun get(url: String, headers: Map<String, String>): HttpResponse {
        getCalls += url to headers
        check(index < responses.size) {
            "SequenceHttpRequestSenderForH53P9: exhausted (${responses.size} responses, " +
                "${getCalls.size} calls)"
        }
        return responses[index++]
    }
}
