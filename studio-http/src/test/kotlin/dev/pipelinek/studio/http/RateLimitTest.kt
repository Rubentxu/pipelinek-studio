package dev.pipelinek.studio.http

import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * S5 E5.4 — Rate limit tests. The in-process token bucket must:
 *  - allow requests under the budget
 *  - return 429 with Retry-After once the budget is exhausted
 *  - carry the documented JSON error shape
 *
 * The tests use a tiny budget (3 requests per 1s) so they run fast and
 * we don't have to wait a full window.
 */
class RateLimitTest {

    @Test
    fun `requests under the budget pass through`() = testApplication {
        application {
            installRateLimit(requestsPerWindow = 3, windowMillis = 1_000)
            routing { get("/ping") { call.respondText("ok") } }
        }
        repeat(3) {
            val r = client.get("/ping")
            assertTrue(r.status.value in 200..299, "got ${r.status} on request ${it + 1}")
        }
    }

    @Test
    fun `requests over the budget are rejected with 429 and Retry-After`() = testApplication {
        application {
            installRateLimit(requestsPerWindow = 2, windowMillis = 1_000)
            routing { get("/ping") { call.respondText("ok") } }
        }
        // First two pass
        repeat(2) { assertTrue(client.get("/ping").status.value in 200..299) }
        // Third is rate-limited
        val r = client.get("/ping")
        assertEquals(HttpStatusCode.TooManyRequests, r.status)
        val retry = r.headers[HttpHeaders.RetryAfter]
        assertNotNull(retry, "Retry-After header missing")
        assertTrue(retry.toLong() >= 0, "Retry-After should be a non-negative seconds value, got $retry")
        val body = r.bodyAsText()
        assertTrue(body.contains("RATE_LIMITED"), "expected RATE_LIMITED, got: $body")
    }
}
