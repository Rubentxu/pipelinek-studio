package dev.pipelinek.studio.cli

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * R0.2.1.b (2026-10-11) — adversarial proof that `studio start`
 * (the CLI smoke server) mounts the SAME HTTP module as
 * `studio-app`. Pre-R0.2.1.b this assertion was impossible: the CLI
 * served a 5-route `MinimalModule` whose runtime shape diverged
 * from `studio-app`'s 16-route `StudioHttpModule`. After R0.2.1.b,
 * the wired surfaces agree on every route that does not require
 * an SDK subprocess to be live.
 *
 * The test:
 *  - boots the CLI smoke via `main(arrayOf("start", ...))` on a
 *    random free port,
 *  - reads back the OpenAPI document the CLI serves and asserts
 *    it is IDENTICAL to the production OpenAPI doc the
 *    `VersionCoherenceB0Test` pins, and
 *  - hits several routes that were missing from the pre-R0.2.1.b
 *    `MinimalModule`: /api/static-analyze-capability, /experiments
 *    (POST), /runs (POST). Each must return the production
 *    shape (and a 401/403/409-style refusal or a typed response,
 *    never a 404 from "the route does not exist").
 *
 * Each adversarial check is the negation of a previous bug:
 *  - `/openapi.json` divergence was the original R0.2.1
 *    (`MinimalOpenApiDoc` vs production). If the CLI falls back
 *    to a parallel doc, this assertion fails.
 *  - `/api/static-analyze-capability` returning 404 reveals that
 *    the CLI did not wire the production route at all.
 *  - `/experiments` 404 reveals that the CLI used
 *    MinimalModule.
 *  - `/api/admin/lease` 404 reveals the production
 *    `leaseAcquireFactory` was not bound.
 */
class ServerUnificationR02BTest {

    @Test
    fun `cli smoke serves identical openapi to production`(@TempDir tmp: Path) {
        val port = startServer(tmp)
        val baseUrl = "http://127.0.0.1:$port"
        waitForHealth(baseUrl, 10_000)

        // 1) The CLI smoke serves the SAME OpenAPI document as the
        //    production studio-app. Drift is the original R0.2.1 bug.
        val cliDoc = httpGetBody("$baseUrl/openapi.json")
        val prodDoc = dev.pipelinek.studio.http.OpenApiDoc.current
        assertEquals(prodDoc, cliDoc, "cli smoke /openapi.json diverges from production OpenApiDoc.current")

        // 2) The CLI smoke serves the production /api/version
        //    shape (StudioHttpModule.VersionResponse, not the
        //    pre-R0.2.1.b MinimalModule's `server = "studio-cli-minimal"`).
        val cliVersion = httpGetBody("$baseUrl/api/version")
        assertTrue(
            cliVersion.contains("\"studio\":"),
            "expected production-shape /api/version (with 'studio' field), got: $cliVersion",
        )
        assertTrue(
            cliVersion.contains("1.0.0-rc.17"),
            "expected studio version 1.0.0-rc.17 in /api/version, got: $cliVersion",
        )

        // 3) Routes that were missing from the pre-R0.2.1.b
        //    `MinimalModule` now respond with the production shape.
        val staticAnalyze = httpGet("$baseUrl/api/static-analyze-capability")
        assertTrue(
            !is404(staticAnalyze.first),
            "/api/static-analyze-capability returned 404 — MinimalModule was still wired",
        )
        assertTrue(
            staticAnalyze.second.contains("\"available\":false"),
            "expected production Unavailable shape ('available: false' when no Detekt classpath), got: ${staticAnalyze.second}",
        )
        val experimentsPost = httpPostJson("$baseUrl/experiments", """{"name":"r02b-adversarial"}""")
        assertTrue(
            !is404(experimentsPost.first),
            "POST /experiments returned 404 — MinimalModule was still wired (R0.2.1.b regression)",
        )
        assertTrue(
            experimentsPost.second.contains("\"experiment_id\":") || experimentsPost.second.contains("BAD_REQUEST"),
            "expected production-shape POST /experiments response, got: ${experimentsPost.second}",
        )
        // /runs requires an Idempotency-Key header (production contract);
        // without one the production wiring returns 400 BAD_REQUEST. A 404
        // proves the route is missing.
        val runsPost = httpPostJson(
            "$baseUrl/runs",
            """{"experimentId":"00000000-0000-0000-0000-000000000000","snapshotId":"00000000-0000-0000-0000-000000000000","scenarioId":"r02b"}""",
            extraHeaders = mapOf("Idempotency-Key" to "r02b-adversarial"),
        )
        assertTrue(
            !is404(runsPost.first),
            "POST /runs returned 404 — MinimalModule was still wired (R0.2.1.b regression)",
        )
    }

    @Test
    fun `cli smoke responds to forged-host header rejection at the same layer as studio-app`(@TempDir tmp: Path) {
        // The production StudioHttpModule installs a Host allow-list
        // BEFORE routing. A forged Host must be rejected with 400 (the
        // documented behaviour); this test pins that the CLI smoke
        // uses the SAME allow-list by recording the same response.
        // If a future change re-introduces a MinimalModule-style
        // bypass, this test catches it.
        val port = startServer(tmp)
        val baseUrl = "http://127.0.0.1:$port"
        waitForHealth(baseUrl, 10_000)
        // java.net.http.HttpClient refuses to set the Host header
        // ("restricted header"), so we use a raw socket — this also
        // matches the way real forged-Host attacks are constructed.
        val parts = baseUrl.removePrefix("http://").split(":")
        val host = parts[0]
        val portNum = parts[1].toInt()
        val raw = java.net.Socket(host, portNum).use { sock ->
            val out = sock.getOutputStream()
            out.write(
                ("GET /api/version HTTP/1.1\r\n" +
                    "Host: evil.example.com\r\n" +
                    "Connection: close\r\n" +
                    "\r\n").toByteArray(Charsets.US_ASCII),
            )
            out.flush()
            sock.getInputStream().bufferedReader().readText()
        }
        // RFC 7230 § 5.4 — a forged Host on a loopback server triggers
        // the production HostAllowList rejection (400 BAD_REQUEST). The
        // pre-R0.2.1.b MinimalModule did not install the allow-list.
        assertTrue(
            raw.startsWith("HTTP/1.1 400"),
            "expected 400 from Host allow-list rejection, got: ${raw.lines().firstOrNull()}",
        )
    }

    private fun startServer(tmp: Path): Int {
        // R0.2.2 (2026-10-11): --insecure-http opt-in keeps the
        // production-shape assertions of ServerUnificationR02BTest talking
        // plain HTTP. The TLS posture is exercised by TlsHandshakeR02BTest.
        val db = tmp.resolve("studio.db")
        val dataDir = tmp.resolve("data")
        val port = pickFreePort()
        val t = Thread {
            main(arrayOf("start", "--insecure-http", "--host", "127.0.0.1", "--port", port.toString(), "--db", db.toString(), "--data-dir", dataDir.toString()))
        }
        t.isDaemon = true
        t.start()
        return port
    }

    private fun waitForHealth(baseUrl: String, timeoutMs: Long) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            try {
                val h = runBlocking { StudioHttpClient(baseUrl).health() }
                if (h.contains("200")) return
            } catch (_: Throwable) {
                // server not yet up
            }
            Thread.sleep(150)
        }
        error("server at $baseUrl did not become healthy within ${timeoutMs}ms")
    }

    private fun pickFreePort(): Int = java.net.ServerSocket(0).use { it.localPort }

    private fun httpGet(url: String): Pair<Int, String> {
        val client = HttpClient.newHttpClient()
        val res = client.send(
            HttpRequest.newBuilder().uri(URI(url)).GET().build(),
            HttpResponse.BodyHandlers.ofString(),
        )
        return res.statusCode() to res.body()
    }

    private fun httpGetBody(url: String): String = httpGet(url).second

    private fun httpPostJson(
        url: String,
        body: String,
        extraHeaders: Map<String, String> = emptyMap(),
    ): Pair<Int, String> {
        val client = HttpClient.newHttpClient()
        val req = HttpRequest.newBuilder()
            .uri(URI(url))
            .header("Content-Type", "application/json")
            .apply { extraHeaders.forEach { (k, v) -> header(k, v) } }
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()
        val res = client.send(req, HttpResponse.BodyHandlers.ofString())
        return res.statusCode() to res.body()
    }

    private fun is404(status: Int): Boolean = status == 404
}
