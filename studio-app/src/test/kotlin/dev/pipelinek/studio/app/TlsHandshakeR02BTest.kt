package dev.pipelinek.studio.app

import dev.pipelinek.studio.http.TlsConfig
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyStore
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManagerFactory
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * S2 (prior user audit): "No daría por certificado HTTPS en ambos
 * ejecutables sin una prueba de handshake sobre cada distribución."
 *
 * The CLI already had a wire-level handshake test
 * (`studio-cli` `TlsHandshakeR02BTest`). The *app* distribution did not:
 * `Main.kt` generated the keystore and logged HTTPS, but booted
 * `embeddedServer(Netty, port, host)` — a plaintext connector. The app
 * therefore served HTTP on the HTTPS port, and the distribution's default
 * JVM args also pinned `7777`, so the documented HTTPS default could never
 * be reached.
 *
 * This test is the missing app-side proof. It boots the real entry point
 * in a child JVM with:
 *  - no `--insecure-http` (canonical default = HTTPS),
 *  - an explicit runtime port (explicit settings must be honored),
 *  - `-Duser.home` pointing at an ignored fixture, no dataDir override
 *    (the default data dir must expand under that home, not the operator's).
 *
 * It then performs a real TLS handshake whose trust anchor is *only* the
 * keystore the child generated at `<default data dir>/keystore.jks`. No
 * trust-all manager: a regression to a plaintext listener, to a different
 * cert, or to a literal `${user.home}` path fails this test.
 */
class TlsHandshakeR02BTest {

    @Test
    fun `real app boot serves the production VersionResponse over TLS anchored to its generated keystore`() {
        StudioAppProcess("app-tls-handshake").use { app ->
            val port = ServerSocket(0).use { it.localPort }
            app.start(
                jvmProperties = mapOf(
                    // The child's home IS the fixture: the default data dir
                    // has to be derived from this value, never from the
                    // operator's real home and never from an unexpanded
                    // `${user.home}` literal.
                    "user.home" to app.homeDir.toString(),
                    "pipelinek.studio.runtime.host" to "127.0.0.1",
                    "pipelinek.studio.runtime.port" to port.toString(),
                ),
                // Canonical default: no --insecure-http opt-in.
                appArgs = emptyList(),
            )

            val keystore = app.defaultDataDir.resolve(TlsConfig.KEYSTORE_FILENAME)
            app.await("app keystore at $keystore") { Files.isRegularFile(keystore) && Files.size(keystore) > 0L }

            // Trust anchored to the cert this very boot generated — the
            // wire-equivalent of `curl --cacert keystore.jks`, and the only
            // anchor the client accepts (no trust-all, no system store).
            val factory = pinToKeystore(keystore)

            app.await("TLS /health on https://127.0.0.1:$port") {
                httpsGet(factory, port, "/health").startsWith("HTTP/1.1 200")
            }

            // A real TLS session, not just bytes on a socket. Endpoint
            // identification is enabled so the cert's iPAddress SAN is
            // verified too — this test never disables verification.
            val session = (factory.createSocket("127.0.0.1", port) as SSLSocket).use { socket ->
                socket.soTimeout = 5_000
                socket.sslParameters = socket.sslParameters.apply { endpointIdentificationAlgorithm = "HTTPS" }
                socket.startHandshake()
                socket.session
            }
            assertNotNull(session.peerPrincipal, "TLS handshake yielded no peer principal")
            assertTrue(session.protocol.startsWith("TLS"), "expected a TLS protocol, got ${session.protocol}")

            val version = httpsGet(factory, port, "/api/version")
            assertTrue(
                version.startsWith("HTTP/1.1 200"),
                "expected HTTP/1.1 200 from /api/version over TLS, got: ${version.lineSequence().take(1).toList()}",
            )
            assertTrue(version.contains("\"studio\":"), "expected the production VersionResponse envelope, got: $version")

            // No silent downgrade: the same port must NOT answer plaintext
            // HTTP. A plaintext connector would answer 200 here.
            val plaintext = runCatching {
                Socket("127.0.0.1", port).use { socket ->
                    socket.soTimeout = 5_000
                    socket.getOutputStream().write(
                        ("GET /health HTTP/1.1\r\nHost: 127.0.0.1:$port\r\nConnection: close\r\n\r\n")
                            .toByteArray(StandardCharsets.US_ASCII),
                    )
                    socket.getOutputStream().flush()
                    BufferedReader(InputStreamReader(socket.getInputStream())).readText()
                }
            }.getOrNull()
            assertTrue(
                plaintext == null || !plaintext.startsWith("HTTP/1.1"),
                "the default listener answered plaintext HTTP; HTTPS must not silently degrade to HTTP. Got: $plaintext",
            )

            // Data-dir regression: the default data dir expanded under the
            // child's user.home, produced the SQLite store there, and the
            // boot log names that resolved path (nothing literal).
            assertTrue(
                Files.isRegularFile(app.defaultDataDir.resolve("studio.db")),
                "expected studio.db under the expanded default data dir ${app.defaultDataDir}; log:\n${app.log()}",
            )
            val log = app.log()
            assertTrue(
                log.contains("Studio data dir: ${app.defaultDataDir}"),
                "expected the boot log to report the expanded data dir ${app.defaultDataDir}; log:\n$log",
            )
            assertTrue(
                !log.contains("\${user.home}"),
                "the boot log still contains the literal \${user.home} placeholder; log:\n$log",
            )
            assertTrue(
                log.contains("https://127.0.0.1:$port"),
                "expected the boot log to advertise the honored HTTPS endpoint; log:\n$log",
            )
        }
    }

    /**
     * B1 + B2 (independent verifier, 2026-10-16). The *canonical* public UAT
     * — `scripts/uat-from-distribution.sh` against the installed HTTPS
     * endpoint, exactly as documented — used to die at step 1: real curl
     * negotiates HTTP/2, HTTP/2 carries the authority in `:authority`, and the
     * allow-list only read a `Host` header, so every request came back
     * `400 HOST_HEADER_MISSING`. The documented cert-export command was wrong
     * too (alias `pipelinek-studio` / `changeit`), so even a `--http1.1`
     * workaround could not be trusted.
     *
     * This test executes both public surfaces instead of describing them:
     *  - the documented `keytool` export is run verbatim on the keystore the
     *    boot generated and must exit 0, producing the served certificate;
     *  - real curl probes prove the protocol guard (HTTP/2 admitted by its
     *    authority, forged authority refused, HTTP/1.1 unchanged);
     *  - the canonical UAT runs to the runtime and reports **BLOCKED** (exit 3)
     *    on the real `409 RUNTIME_UNAVAILABLE`. It must NOT print
     *    `ALL UAT STEPS PASSED`: without a PipelineK SDK there is no execution,
     *    so this never certifies end-to-end execution.
     */
    @Test
    fun `canonical from-distribution UAT over TLS reaches the runtime and reports BLOCKED, never PASS`() {
        assumeTrue(
            listOf("bash", "curl", "python3").all { onPath(it) },
            "the canonical UAT harness needs bash, curl and python3 on PATH",
        )
        StudioAppProcess("app-canonical-uat").use { app ->
            val port = ServerSocket(0).use { it.localPort }
            app.start(
                jvmProperties = mapOf(
                    "user.home" to app.homeDir.toString(),
                    "pipelinek.studio.runtime.host" to "127.0.0.1",
                    "pipelinek.studio.runtime.port" to port.toString(),
                ),
                appArgs = emptyList(),
            )
            val keystore = app.defaultDataDir.resolve(TlsConfig.KEYSTORE_FILENAME)
            app.await("app keystore at $keystore") { Files.isRegularFile(keystore) && Files.size(keystore) > 0L }
            app.await("TLS listener on 127.0.0.1:$port") {
                runCatching { Socket("127.0.0.1", port).close() }.isSuccess
            }

            // --- B2: the documented export, executed, not grepped ----------
            val pem = app.workDir.resolve("studio-loopback.pem")
            val export = exec(
                listOf(
                    "keytool", "-exportcert", "-rfc",
                    "-alias", "studio-loopback",
                    "-keystore", keystore.toString(),
                    "-storepass", "studio-loopback-change-me",
                ),
            )
            assertEquals(
                0, export.exitCode,
                "the documented keytool export must succeed; stdout/stderr:\n${export.output}",
            )
            Files.writeString(pem, export.output)
            assertTrue(
                export.output.contains("-----BEGIN CERTIFICATE-----"),
                "the documented command must write a PEM, got:\n${export.output.take(400)}",
            )
            assertTrue(
                exportedCert(pem) == pinnedCertOf(keystore),
                "the documented export must yield exactly the cert this boot serves",
            )

            // --- B1: real curl, both protocols, forged authority -------------
            assertCurl(200, "2", "--http2", "--cacert", pem.toString(), "https://localhost:$port/health")
            assertCurl(200, "2", "--http2", "--cacert", pem.toString(), "https://127.0.0.1:$port/health")
            assertCurl(
                400, "2", "--http2", "--cacert", pem.toString(),
                "-H", "Host: evil.example.com", "https://localhost:$port/health",
                expectedBody = "HOST_NOT_ALLOWED",
            )
            // An untrusted forwarding header must not launder a forged authority.
            assertCurl(
                400, "2", "--http2", "--cacert", pem.toString(),
                "-H", "Host: evil.example.com", "-H", "X-Forwarded-Host: localhost",
                "https://localhost:$port/health",
                expectedBody = "HOST_NOT_ALLOWED",
            )
            // HTTP/1.1 keeps its previous contract: allowed 200, forged 400.
            assertCurl(200, "1.1", "--http1.1", "--cacert", pem.toString(), "https://localhost:$port/health")
            assertCurl(
                400, "1.1", "--http1.1", "--cacert", pem.toString(),
                "-H", "Host: evil.example.com", "https://localhost:$port/health",
                expectedBody = "HOST_NOT_ALLOWED",
            )

            // --- the canonical public UAT, over the canonical TLS wire ------
            val uat = exec(
                listOf("bash", uatScript().toString()),
                env = mapOf(
                    "STUDIO_HTTP" to "https://127.0.0.1:$port",
                    "STUDIO_CA_CERT" to pem.toString(),
                    "UAT_TIMEOUT" to "30",
                    "UAT_POLL_INTERVAL" to "1",
                    // Owned scratch, never a shared /tmp path.
                    "TMPDIR" to Files.createDirectories(app.workDir.resolve("uat-tmp")).toString(),
                ),
                timeoutSeconds = 180,
            )
            assertEquals(
                3, uat.exitCode,
                "without a PipelineK SDK the canonical UAT must report BLOCKED (exit 3), never a pass; " +
                    "output:\n${uat.output}",
            )
            assertTrue(uat.output.contains("UAT_VERDICT: BLOCKED"), "expected a BLOCKED verdict:\n${uat.output}")
            assertTrue(
                uat.output.contains("/health -> ok"),
                "the UAT must get past the host allow-list into the app:\n${uat.output}",
            )
            assertTrue(
                uat.output.contains("409") && uat.output.contains("RUNTIME_UNAVAILABLE"),
                "expected the real 409 RUNTIME_UNAVAILABLE refusal from the runtime:\n${uat.output}",
            )
            assertFalse(
                uat.output.contains("ALL UAT STEPS PASSED"),
                "a refusal must never print the acceptance banner:\n${uat.output}",
            )
        }
    }

    /** A client SSLContext that trusts exactly the cert in [keystore]. */
    private fun pinToKeystore(keystore: Path): SSLSocketFactory {
        val store = KeyStore.getInstance(KeyStore.getDefaultType())
        Files.newInputStream(keystore).use { store.load(it, TlsConfig.KEY_PASSWORD.toCharArray()) }
        val pinned = store.getCertificate(TlsConfig.KEY_ALIAS) as X509Certificate
        val trustStore = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
            load(null, null)
            setCertificateEntry("studio-generated", pinned)
        }
        val trustManager = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        trustManager.init(trustStore)
        return SSLContext.getInstance("TLS").apply {
            init(null, trustManager.trustManagers, null)
        }.socketFactory
    }

    private fun httpsGet(factory: SSLSocketFactory, port: Int, path: String): String =
        (factory.createSocket("127.0.0.1", port) as SSLSocket).use { socket ->
            socket.soTimeout = 5_000
            socket.sslParameters = socket.sslParameters.apply { endpointIdentificationAlgorithm = "HTTPS" }
            socket.startHandshake()
            socket.outputStream.write(
                ("GET $path HTTP/1.1\r\nHost: 127.0.0.1:$port\r\nConnection: close\r\n\r\n")
                    .toByteArray(StandardCharsets.US_ASCII),
            )
            socket.outputStream.flush()
            BufferedReader(InputStreamReader(socket.inputStream)).readText()
        }

    /**
     * Runs real [curl] (no `-k`, no trust-all: the cert is passed with
     * `--cacert`) and asserts the negotiated protocol (curl's `%{http_version}`:
     * `2` for HTTP/2, `1.1` for HTTP/1.1), the status code and the documented
     * error code in the body.
     */
    private fun assertCurl(
        expectedStatus: Int,
        expectedProtocol: String,
        vararg args: String,
        expectedBody: String? = null,
    ) {
        val bodyFile = Files.createTempFile("uat-curl", ".body")
        try {
            val probe = exec(
                listOf(
                    "curl", "-sS", "-o", bodyFile.toString(),
                    "-w", "%{http_version} %{http_code}", "--max-time", "20",
                ) + args,
            )
            val body = Files.readString(bodyFile)
            assertEquals(
                "$expectedProtocol $expectedStatus", probe.output.trim(),
                "curl ${args.joinToString(" ")} -> ${probe.output} body=$body",
            )
            if (expectedBody != null) {
                assertTrue(body.contains(expectedBody), "expected $expectedBody in body, got: $body")
            }
        } finally {
            Files.deleteIfExists(bodyFile)
        }
    }

    /** The canonical public UAT script, resolved from the module working dir. */
    private fun uatScript(): Path {
        val candidates = listOf("..", "").map { Path.of(it, "scripts", "uat-from-distribution.sh") }
        return candidates.map { it.toAbsolutePath().normalize() }.firstOrNull { Files.isRegularFile(it) }
            ?: error("cannot locate scripts/uat-from-distribution.sh from ${Path.of("").toAbsolutePath()}")
    }

    private fun onPath(tool: String): Boolean =
        runCatching {
            exec(listOf("sh", "-c", "command -v $tool"), timeoutSeconds = 30).exitCode == 0
        }.getOrDefault(false)

    private fun pinnedCertOf(keystore: Path): X509Certificate {
        val store = KeyStore.getInstance(KeyStore.getDefaultType())
        Files.newInputStream(keystore).use { store.load(it, TlsConfig.KEY_PASSWORD.toCharArray()) }
        return store.getCertificate(TlsConfig.KEY_ALIAS) as X509Certificate
    }

    private fun exportedCert(pem: Path): X509Certificate =
        java.security.cert.CertificateFactory.getInstance("X.509")
            .generateCertificate(Files.newInputStream(pem)) as X509Certificate

    private data class ExecResult(val exitCode: Int, val output: String)

    /** Runs a child process with a bounded wait and returns its merged output. */
    private fun exec(
        command: List<String>,
        env: Map<String, String> = emptyMap(),
        timeoutSeconds: Long = 120,
    ): ExecResult {
        val builder = ProcessBuilder(command).redirectErrorStream(true)
        builder.environment().putAll(env)
        val process = builder.start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        assertTrue(
            process.waitFor(timeoutSeconds, TimeUnit.SECONDS),
            "${command.first()} did not finish within ${timeoutSeconds}s; output:\n$output",
        )
        return ExecResult(process.exitValue(), output)
    }
}
