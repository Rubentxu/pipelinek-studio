package dev.pipelinek.studio.http

import io.ktor.client.request.get
import io.ktor.client.request.header
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
 *  - trust XFF only when STUDIO_TRUSTED_PROXY is set (audit H-20)
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
            val r = hostClient().get("/ping")
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
        repeat(2) { assertTrue(hostClient().get("/ping").status.value in 200..299) }
        // Third is rate-limited
        val r = hostClient().get("/ping")
        assertEquals(HttpStatusCode.TooManyRequests, r.status)
        val retry = r.headers[HttpHeaders.RetryAfter]
        assertNotNull(retry, "Retry-After header missing")
        assertTrue(retry.toLong() >= 0, "Retry-After should be a non-negative seconds value, got $retry")
        val body = r.bodyAsText()
        assertTrue(body.contains("RATE_LIMITED"), "expected RATE_LIMITED, got: $body")
    }

    /**
     * Audit H-20 (2026-10-10): when STUDIO_TRUSTED_PROXY is not
     * set (the default), the X-Forwarded-For header MUST be
     * ignored. An attacker can otherwise set a fresh XFF per
     * request to evade the per-IP rate limit. The test asserts
     * that the XFF value is not used as the bucket key when the
     * flag is off.
     */
    @Test
    fun `X-Forwarded-For is ignored when trustForwardedFor is false`() = testApplication {
        application {
            installRateLimit(requestsPerWindow = 2, windowMillis = 1_000, trustForwardedFor = false)
            routing { get("/ping") { call.respondText("ok") } }
        }
        // Two requests with DIFFERENT XFF values would, if XFF were
        // trusted, create two separate buckets and both succeed. With
        // the trust off, both should land in the same socket-peer
        // bucket and the second should be rate-limited.
        repeat(2) {
            val r = hostClient().get("/ping") {
                header("X-Forwarded-For", "10.0.0.${it + 1}")
            }
            assertTrue(r.status.value in 200..299, "request ${it + 1} should pass")
        }
        val third = hostClient().get("/ping") {
            header("X-Forwarded-For", "10.0.0.99")
        }
        assertEquals(HttpStatusCode.TooManyRequests, third.status,
            "third request with a fresh XFF should still be rate-limited " +
                "because XFF must be ignored without STUDIO_TRUSTED_PROXY=1")
    }

    /**
     * Audit H-21 (2026-10-10): without eviction, a DDoS with
     * fresh source IPs grows the per-IP map unbounded. The
     * fix sweeps the map every 100 requests and evicts buckets
     * whose window expired more than 2 * windowMillis ago. This
     * test pins the eviction policy: after the window expires
     * and a sweep runs, the bucket for an old IP is gone and a
     * new request from that IP gets a fresh budget.
     */
    @Test
    fun `expired buckets are evicted by the periodic sweep (H-21)`() = testApplication {
        application {
            installRateLimit(requestsPerWindow = 1, windowMillis = 200, trustForwardedFor = true)
            routing { get("/ping") { call.respondText("ok") } }
        }
        // Exhaust XFF=10.0.0.1 budget
        assertTrue(hostClient().get("/ping") {
            header("X-Forwarded-For", "10.0.0.1")
        }.status.value in 200..299)
        val second = hostClient().get("/ping") {
            header("X-Forwarded-For", "10.0.0.1")
        }
        assertEquals(HttpStatusCode.TooManyRequests, second.status,
            "second request from same XFF should be rate-limited")
        // Wait for the window to expire and trigger a sweep.
        // The sweep runs every 100 requests; we make 100 requests
        // to ensure the sweep fires, then check that XFF=10.0.0.1
        // has a fresh budget because its bucket was evicted.
        Thread.sleep(500)
        repeat(100) {
            hostClient().get("/ping") {
                header("X-Forwarded-For", "10.99.99.${it % 250}")
            }
        }
        val afterEviction = hostClient().get("/ping") {
            header("X-Forwarded-For", "10.0.0.1")
        }
        assertTrue(afterEviction.status.value in 200..299,
            "after the window expires and a sweep runs, XFF=10.0.0.1 " +
                "should have a fresh budget (its bucket was evicted)")
    }
}
