package dev.pipelinek.studio.http

import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsText
import io.ktor.http.*
import io.ktor.server.engine.applicationEnvironment
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.testing.*
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.ServerSocket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse.BodyHandlers
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyStore
import java.security.cert.X509Certificate
import java.time.Duration
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManagerFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * AAT-SEC-005 — Host header allow-list. The server must reject requests
 * whose Host header is not in the configured allow-list. This is the
 * DNS-rebinding / CSRF defense: a malicious actor that can poison the
 * resolver for the server's loopback cannot trick the server into
 * treating a different origin as canonical.
 */
class HostAllowListTest {

    @Test
    fun `forged Host header is rejected with 400 HOST_NOT_ALLOWED`() = testApplication {
        // Use an explicit narrow allow-list for this test so we don't
        // depend on the env override or the test framework's default.
        application {
            StudioHttpModule(
                fakeWiringFactory(),
                runReadFactory = null,
                hostAllowList = setOf("127.0.0.1", "localhost"),
            ).install(this)
        }
        val r = client.get("/health") {
            // Override the Host header to a value not in the allow-list.
            header(HttpHeaders.Host, "evil.example.com")
        }
        assertEquals(HttpStatusCode.BadRequest, r.status)
        val body = r.bodyAsText()
        assertTrue(body.contains("HOST_NOT_ALLOWED"), "expected HOST_NOT_ALLOWED, got: $body")
    }

    @Test
    fun `allowed Host header is accepted`() = testApplication {
        application {
            StudioHttpModule(
                fakeWiringFactory(),
                runReadFactory = null,
                hostAllowList = setOf("127.0.0.1", "localhost"),
            ).install(this)
        }
        val r = client.get("/health") {
            // 127.0.0.1 is in the allow-list.
            header(HttpHeaders.Host, "127.0.0.1")
        }
        // /health returns 200; if the allow-list had rejected, we'd get 400.
        assertEquals(HttpStatusCode.OK, r.status)
    }

    @Test
    fun `host header with port is normalized before allow-list check`() = testApplication {
        application {
            StudioHttpModule(
                fakeWiringFactory(),
                runReadFactory = null,
                hostAllowList = setOf("127.0.0.1"),
            ).install(this)
        }
        val r = client.get("/health") {
            // The "127.0.0.1:7070" host must be normalized to "127.0.0.1"
            // and accepted.
            header(HttpHeaders.Host, "127.0.0.1:7070")
        }
        assertEquals(HttpStatusCode.OK, r.status)
    }

    /**
     * Audit H-15 (2026-10-10): the previous implementation used
     * `?: return@intercept` for a missing Host header, which is
     * a fail-open: an HTTP/1.0 client, a probing tool, or an
     * attacker that strips the header would bypass the allow-list
     * entirely. The fix refuses the request with 400 when the
     * Host header is absent. The ktor test client always sets
     * a Host header; we exercise the missing-header branch with
     * an empty Host value, which triggers the same `isNullOrBlank`
     * check.
     */
    @Test
    fun `empty Host header is treated the same as missing (400 HOST_HEADER_MISSING)`() = testApplication {
        application {
            StudioHttpModule(
                fakeWiringFactory(),
                runReadFactory = null,
                hostAllowList = setOf("127.0.0.1", "localhost"),
            ).install(this)
        }
        val r = client.get("/health") {
            header(HttpHeaders.Host, "")
        }
        assertEquals(HttpStatusCode.BadRequest, r.status)
        val body = r.bodyAsText()
        assertTrue(body.contains("HOST_HEADER_MISSING"),
            "expected HOST_HEADER_MISSING, got: $body")
    }

    /**
     * B1 (independent verifier, 2026-10-16): a real curl against the canonical
     * HTTPS listener negotiates **HTTP/2**, and HTTP/2 carries the transport
     * authority in the `:authority` pseudo-header — never in a `Host` header.
     * Ktor's `request.headers` skips pseudo-headers, so the allow-list saw no
     * host at all and rejected the *canonical* listener with
     * `400 HOST_HEADER_MISSING`: no UAT over the default wire could ever reach
     * the runtime.
     *
     * This is the engine-level discriminator, with no curl and no trust-all:
     * a real Netty TLS listener plus a JDK client pinned to the certificate
     * that boot generated, over a genuinely negotiated HTTP/2 connection
     * (asserted, so an HTTP/1.1 fallback cannot make this green by accident).
     */
    @Test
    fun `real HTTP2 request to the TLS listener is admitted by its transport authority`() {
        withTlsStudioServer { port, context ->
            val client = HttpClient.newBuilder()
                .sslContext(context)
                .version(HttpClient.Version.HTTP_2)
                .build()
            val response = client.send(
                HttpRequest.newBuilder(URI("https://localhost:$port/health"))
                    .timeout(Duration.ofSeconds(15))
                    .build(),
                BodyHandlers.ofString(),
            )
            assertEquals(
                HttpClient.Version.HTTP_2, response.version(),
                "the probe must actually negotiate HTTP/2, otherwise it proves nothing: ${response.body()}",
            )
            assertEquals(
                200, response.statusCode(),
                "canonical HTTP/2 request to the allow-listed authority must reach the app: ${response.body()}",
            )
            assertTrue(response.body().contains("\"status\":\"ok\""), response.body())
        }
    }

    /**
     * PRESERVE (fail-closed): the HTTP/2 authority path must never become a
     * fallback for plain HTTP/1.x. A request with no `Host` header over
     * HTTP/1.1 is still refused with `400 HOST_HEADER_MISSING` — it is not
     * admitted by the listener address, by the socket's peer, or by anything
     * the new authority extraction sees.
     */
    @Test
    fun `real HTTP1 request without a Host header on the TLS listener is still refused`() {
        withTlsStudioServer { port, context ->
            val response = requestWithoutHostHeader(context.socketFactory, port)
            assertTrue(
                response.startsWith("HTTP/1.1 400"),
                "a missing Host header must stay refused on HTTP/1.1, got: ${response.lineSequence().firstOrNull()}",
            )
            assertTrue(
                response.contains("HOST_HEADER_MISSING"),
                "expected HOST_HEADER_MISSING, got: ${response.take(300)}",
            )
        }
    }

    /**
     * The transport-authority matrix (B1). These are the rules the listener
     * applies, exercised as one decision function so every branch is covered
     * without pretending an in-process test client can forge `:authority`.
     */
    @Test
    fun `HTTP2 authority naming an allowed host is admitted`() {
        assertEquals(
            HostVerdict.Allowed("localhost"),
            resolveRequestHost(
                hostHeader = null,
                http2Authority = "localhost:7778",
                isHttp2 = true,
                allowedHosts = setOf("localhost"),
            ),
            "the canonical HTTP/2 authority must reach the app; its port is not part of the allow-list decision",
        )
    }

    @Test
    fun `HTTP2 authority naming a hostile host is refused`() {
        assertEquals(
            HostVerdict.Rejected(HOST_NOT_ALLOWED, "host 'evil.example.com' is not in the allow-list"),
            resolveRequestHost(
                hostHeader = null,
                http2Authority = "evil.example.com",
                isHttp2 = true,
                allowedHosts = setOf("localhost"),
            ),
        )
    }

    @Test
    fun `HTTP2 request with no authority at all fails closed`() {
        val verdict = resolveRequestHost(
            hostHeader = null,
            http2Authority = null,
            isHttp2 = true,
            allowedHosts = DEFAULT_ALLOWED_HOSTS,
        )
        assertEquals(
            HOST_HEADER_MISSING, (verdict as HostVerdict.Rejected).code,
            "an absent authority must never be satisfied by the listen address or the peer",
        )
    }

    @Test
    fun `HTTP2 Host that disagrees with the authority is refused as a conflict`() {
        assertEquals(
            HostVerdict.Rejected(
                HOST_AUTHORITY_CONFLICT,
                "the Host header and the HTTP/2 :authority name different hosts",
            ),
            resolveRequestHost(
                hostHeader = "localhost",
                http2Authority = "evil.example.com",
                isHttp2 = true,
                allowedHosts = DEFAULT_ALLOWED_HOSTS,
            ),
        )
    }

    @Test
    fun `HTTP2 Host that agrees with the authority is admitted`() {
        assertEquals(
            HostVerdict.Allowed("localhost"),
            resolveRequestHost(
                hostHeader = "localhost:7778",
                http2Authority = "localhost:7778",
                isHttp2 = true,
                allowedHosts = DEFAULT_ALLOWED_HOSTS,
            ),
        )
    }

    @Test
    fun `HTTP1 never falls back to an engine authority`() {
        assertEquals(
            HOST_HEADER_MISSING,
            (resolveRequestHost(
                hostHeader = null,
                http2Authority = "localhost",
                isHttp2 = false,
                allowedHosts = DEFAULT_ALLOWED_HOSTS,
            ) as HostVerdict.Rejected).code,
            "the HTTP/2 fallback must be protocol-guarded; HTTP/1.1 missing Host stays refused",
        )
    }

    /**
     * Untrusted `Forwarded` / `X-Forwarded-Host` must not launder a forged
     * authority: the allow-list reads the wire authority only.
     */
    @Test
    fun `X-Forwarded-Host cannot rescue a forged Host`() = testApplication {
        application {
            StudioHttpModule(
                fakeWiringFactory(),
                runReadFactory = null,
                hostAllowList = setOf("127.0.0.1", "localhost"),
            ).install(this)
        }
        val r = client.get("/health") {
            header(HttpHeaders.Host, "evil.example.com")
            header("X-Forwarded-Host", "localhost")
            header("Forwarded", "host=localhost")
        }
        assertEquals(HttpStatusCode.BadRequest, r.status)
        val body = r.bodyAsText()
        assertTrue(body.contains("HOST_NOT_ALLOWED"), "expected HOST_NOT_ALLOWED, got: $body")
    }

    /**
     * Boots the production HTTP module on a real Netty TLS listener whose
     * certificate comes from [TlsConfig] (the same helper both entry points
     * use). The bind port is chosen by the OS; the client trusts exactly the
     * certificate the boot generated.
     */
    private fun withTlsStudioServer(block: (port: Int, context: SSLContext) -> Unit) {
        val dataDir: Path = Files.createTempDirectory("studio-http2-authority")
        val port = ServerSocket(0).use { it.localPort }
        val server = embeddedServer(
            factory = Netty,
            environment = applicationEnvironment { },
            configure = {
                TlsConfig.configureListener(this, secure = true, host = "127.0.0.1", port = port, dataDir = dataDir)
            },
            module = {
                StudioHttpModule(
                    fakeWiringFactory(),
                    runReadFactory = null,
                    hostAllowList = setOf("127.0.0.1", "localhost"),
                ).install(this)
            },
        )
        server.start(wait = false)
        try {
            awaitListening(port)
            block(port, pinnedToGeneratedKeystore(dataDir))
        } finally {
            server.stop(0, 2_000)
            dataDir.toFile().deleteRecursively()
        }
    }

    /** Bounded readiness poll — the condition is the listening socket, not a sleep. */
    private fun awaitListening(port: Int, timeoutMs: Long = 20_000) {
        val deadline = System.nanoTime() + Duration.ofMillis(timeoutMs).toNanos()
        while (System.nanoTime() < deadline) {
            val open = runCatching { java.net.Socket("127.0.0.1", port).close() }.isSuccess
            if (open) return
            Thread.sleep(25)
        }
        throw AssertionError("TLS listener on 127.0.0.1:$port never accepted a connection within ${timeoutMs}ms")
    }

    /** A client trust anchor that holds only the cert the server boot created. */
    private fun pinnedToGeneratedKeystore(dataDir: Path): SSLContext {
        val store = KeyStore.getInstance(KeyStore.getDefaultType())
        Files.newInputStream(TlsConfig.keyStorePath(dataDir)).use {
            store.load(it, TlsConfig.KEY_PASSWORD.toCharArray())
        }
        val pinned = store.getCertificate(TlsConfig.KEY_ALIAS) as X509Certificate
        val trustStore = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
            load(null, null)
            setCertificateEntry("studio-generated", pinned)
        }
        val trustManager = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        trustManager.init(trustStore)
        return SSLContext.getInstance("TLS").apply {
            init(null, trustManager.trustManagers, null)
        }
    }

    /** Writes an HTTP/1.1 request with no Host header at all. */
    private fun requestWithoutHostHeader(factory: SSLSocketFactory, port: Int): String =
        (factory.createSocket("127.0.0.1", port) as SSLSocket).use { socket ->
            socket.soTimeout = 10_000
            socket.startHandshake()
            val request = "GET /health HTTP/1.1\r\nConnection: close\r\n\r\n"
            socket.outputStream.write(request.toByteArray(StandardCharsets.US_ASCII))
            socket.outputStream.flush()
            BufferedReader(InputStreamReader(socket.inputStream, StandardCharsets.US_ASCII)).readText()
        }

    // A minimal wiring factory that does not exercise the metadata port.
    private fun fakeWiringFactory(): () -> dev.pipelinek.studio.application.usecases.Wiring {
        val noopMetadata = object : dev.pipelinek.studio.application.ports.StudioMetadataPort {
            override fun findExperiment(id: dev.pipelinek.studio.domain.ExperimentId) = null
            override fun findSnapshot(id: dev.pipelinek.studio.domain.SnapshotId) = null
            override fun findScenario(id: dev.pipelinek.studio.domain.ScenarioId) = null
            override fun findRun(id: dev.pipelinek.studio.domain.RunId) = null
            override fun listRuns(
                state: dev.pipelinek.studio.domain.RunState?,
                limit: Int,
                offset: Int,
            ): List<dev.pipelinek.studio.application.ports.RunBindingSnapshot> = emptyList()
                    override fun saveSnapshot(snap: dev.pipelinek.studio.domain.SourceSnapshot) { /* noop */ }
            override fun loadSnapshotSource(id: dev.pipelinek.studio.domain.SnapshotId): ByteArray? = null
            override fun saveExperiment(experiment: dev.pipelinek.studio.domain.Experiment) { /* noop */ }
            override fun persistRunBinding(binding: dev.pipelinek.studio.domain.RunBinding) { /* noop */ }
            override fun transitionRunState(studioRunId: dev.pipelinek.studio.domain.RunId, newState: dev.pipelinek.studio.domain.RunState) { /* noop */ }
        override fun listScenarios(limit: Int, offset: Int): List<dev.pipelinek.studio.application.ports.ScenarioSnapshot> = emptyList()
        override fun saveScenario(scenario: dev.pipelinek.studio.domain.SourceScenario) { /* noop */ }
        }
        val noopRunner = object : dev.pipelinek.studio.application.ports.PipelineRunnerPort {
            override fun submit(req: dev.pipelinek.studio.application.ports.RunRequest) =
                dev.pipelinek.studio.application.ports.SubmissionResult.Admitted("u", java.nio.file.Path.of("/tmp"))
            override fun cancel(runId: dev.pipelinek.studio.domain.RunId) =
                dev.pipelinek.studio.application.ports.CancellationResult.Unknown("stub")
            override fun status(runId: dev.pipelinek.studio.domain.RunId) =
                dev.pipelinek.studio.application.ports.RunHandleStatus.Unknown("stub")
        }
        val noopRead = object : dev.pipelinek.studio.application.ports.RunReadPort {
            override fun events(
                runId: dev.pipelinek.studio.domain.RunId,
                after: dev.pipelinek.studio.application.ports.EventCursor?,
                limit: Int,
            ) = dev.pipelinek.studio.application.ports.RunReadResult.Unknown("stub")
            override fun state(runId: dev.pipelinek.studio.domain.RunId) =
                dev.pipelinek.studio.application.ports.RunReadResult.Unknown("stub")
        }
        val noopOutput = object : dev.pipelinek.studio.application.ports.OutputReadPort {
            override fun read(
                runId: dev.pipelinek.studio.domain.RunId,
                cursor: dev.pipelinek.studio.application.ports.OutputCursor?,
                bytes: Int,
            ) = dev.pipelinek.studio.application.ports.OutputReadResult.UnknownStream("stub")
        }
        val noopIdem = object : dev.pipelinek.studio.application.ports.IdempotencyPort {
            override fun lookup(scope: String, key: String, requestDigestSha256: String) =
                dev.pipelinek.studio.application.ports.IdempotencyLookupResult.NotFound
            override fun record(
                scope: String, key: String,
                operationId: dev.pipelinek.studio.domain.OperationId,
                requestDigestSha256: String,
                responseRef: String?,
            ) = dev.pipelinek.studio.application.ports.IdempotencyRecordResult.Recorded
        }
        val noopRuntime = object : dev.pipelinek.studio.application.ports.PipelineKRuntimePort {
            override fun describe() =
                dev.pipelinek.studio.application.ports.RuntimeDescriptorResult.Unavailable
        }
        return {
            dev.pipelinek.studio.application.usecases.Wiring(
                noopMetadata, noopRunner, noopRead, noopOutput, noopIdem, noopRuntime,
            )
        }
    }
}
