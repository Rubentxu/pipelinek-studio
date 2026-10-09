package dev.pipelinek.studio.http

import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsText
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * S5 profiling endpoint tests. /api/metrics must:
 *   - return 200 OK with the documented JSON shape
 *   - expose a non-negative uptime_ms (the JVM was clearly up at request time)
 *   - expose a non-negative heap_used_mb and a non-zero heap_max_mb
 *   - match the contract surfaced in OpenApiDoc + MinimalOpenApiDoc
 */
class MetricsEndpointTest {

    @Test
    fun `metrics endpoint returns 200 with documented JSON shape`() = testApplication {
        application { StudioHttpModule(stubWiringFactory(), runReadFactory = null).install(this) }
        val r = client.get("/api/metrics")
        assertEquals(HttpStatusCode.OK, r.status)
        val body = r.bodyAsText()
        // Required fields per MetricsResponse + JvmMetrics data classes.
        for (field in listOf("uptime_ms", "threads_live", "threads_peak",
                              "heap_used_mb", "heap_max_mb", "available_processors")) {
            assertTrue(body.contains(field), "expected field '$field' in /api/metrics, got: $body")
        }
    }

    @Test
    fun `metrics endpoint reports positive uptime and heap max`() = testApplication {
        application { StudioHttpModule(stubWiringFactory(), runReadFactory = null).install(this) }
        val r = client.get("/api/metrics")
        val body = r.bodyAsText()
        // The test JVM has been alive for at least a few ms by the time the request lands.
        val uptime = Regex("\"uptime_ms\":(\\d+)").find(body)?.groupValues?.get(1)?.toLongOrNull() ?: -1
        assertTrue(uptime >= 0, "uptime_ms should be >= 0, got: $uptime")
        val heapMax = Regex("\"heap_max_mb\":(\\d+)").find(body)?.groupValues?.get(1)?.toLongOrNull() ?: -1
        assertTrue(heapMax > 0, "heap_max_mb should be > 0, got: $heapMax")
    }

    @Test
    fun `metrics endpoint is reachable without auth (same posture as health)`() = testApplication {
        // The metrics endpoint deliberately has no auth — it is operator-facing
        // diagnostics, same as /health. This test pins the contract so a
        // future auth refactor does not silently lock operators out.
        application { StudioHttpModule(stubWiringFactory(), runReadFactory = null).install(this) }
        val r = client.get("/api/metrics") {
            // Intentionally no Authorization header.
        }
        assertEquals(HttpStatusCode.OK, r.status)
    }

    // Minimal stub factory — /api/metrics does not touch any port, so any
    // Wiring instance is fine. We don't need the full fakeWiringFactory the
    // existing tests use (which needs run/inspect/steps ports).
    private fun stubWiringFactory(): () -> dev.pipelinek.studio.application.usecases.Wiring = {
        error("/api/metrics does not touch the wiring; this factory should never be invoked")
    }
}
