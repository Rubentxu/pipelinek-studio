package dev.pipelinek.studio.http

import io.ktor.client.HttpClient
import io.ktor.client.plugins.DefaultRequest
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import io.ktor.server.testing.ApplicationTestBuilder

/**
 * Test support helpers (audit H-15, 2026-10-10).
 *
 * The ktor testApplication's default client does not set a Host
 * header. The studio's HostAllowList interceptor (H-15 fix) refuses
 * requests with a missing Host header, so every test request must
 * carry one. This helper creates a client with a default Host
 * header baked in.
 *
 * Usage in a test:
 * ```
 * testApplication {
 *     application { ... }
 *     val r = hostClient().get("/runs")
 * }
 * ```
 */
fun ApplicationTestBuilder.hostClient(): HttpClient = createClient {
    install(DefaultRequest) {
        header(HttpHeaders.Host, "127.0.0.1")
    }
}
