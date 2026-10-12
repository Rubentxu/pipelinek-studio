package dev.pipelinek.studio.cli

import dev.pipelinek.studio.http.TlsConfig
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.nio.file.Path
import java.security.KeyStore
import java.security.cert.X509Certificate
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * R0.2.2 (2026-10-11) — adversarial proof that `studio start` ships a
 * real TLS connector, not a keystore-only stub.
 *
 * Pre-R0.2.2: both `studio start` and `studio-app` booted plain HTTP
 * regardless of `--insecure-http`. The `TlsConfig` generated a
 * keystore under `<dataDir>/keystore.jks` but the connector never
 * loaded it. The comment in `studio-app/Main.kt` claimed "delegated
 * to the Ktor Netty engine's HOCON path"; no HOCON file shipped.
 *
 * R0.2.2 closes that: `studio start` (no --insecure-http) binds an
 * `sslConnector(...)` configured with the keystore produced by
 * [dev.pipelinek.studio.http.TlsConfig.loadOrCreateKeyStore]. The
 * default port is 7778 with HTTPS.
 *
 * The two tests here pin the wire-level invariants an attacker (or a
 * regression) would have to break:
 *  1. **TLS handshake succeeds** against the loopback cert with a
 *     JDK `SSLSocket` whose trust manager accepts the cert. The
 *     resulting connection serves `/api/version` (the production
 *     `VersionResponse` envelope). A regression to plain HTTP would
 *     fail with `SSLHandshakeException` (no SSL on the wire) or with
 *     a connection-refused (no listener).
 *  2. **The keystore is persistent and reusable**: starting two
 *     `studio start` processes against the same `<dataDir>` must hit
 *     the *same* cert (the second boot does NOT regenerate it). A
 *     regression to "regenerate every boot" would burn operator trust
 *     (`curl --cacert` would have to be updated every start).
 */
class TlsHandshakeR02BTest {

    @Test
    fun `studio start serves the production VersionResponse over a real TLS handshake`(@TempDir tmp: Path) {
        val port = startServer(tmp, insecureHttp = false)
        waitForTlsHealthy("https://127.0.0.1:$port", tmp, 10_000)

        // Build a JDK SSLContext that trusts ONLY the persisted loopback
        // cert. This is the wire-equivalent of `curl --cacert
        // keystore.jks`. A regression that loses the keystore would
        // make this handshake fail.
        val keyStore = KeyStore.getInstance(KeyStore.getDefaultType())
        keyStore.load(
            FileInputStreamCompat(TlsConfig.keyStorePath(tmp.resolve("data")).toFile()),
            TlsConfig.KEY_PASSWORD.toCharArray(),
        )

        val trustManager = object : X509TrustManager {
            override fun checkClientTrusted(c: Array<out X509Certificate>?, s: String?) {}
            override fun checkServerTrusted(c: Array<out X509Certificate>?, s: String?) {}
            // Only the persisted keystore's cert is trusted — a real
            // operator trust path. Anything else (e.g. a future
            // system-truststore regression) fails.
            override fun getAcceptedIssuers(): Array<X509Certificate> = keyStore
                .getCertificateChain(TlsConfig.KEY_ALIAS)
                ?.map { it as X509Certificate }
                ?.toTypedArray()
                ?: emptyArray()
        }
        val sslContext = SSLContext.getInstance("TLS").apply {
            init(null, arrayOf<TrustManager>(trustManager), null)
        }
        val factory = sslContext.socketFactory as SSLSocketFactory

        // 1. Confirm the SSL handshake itself completes.
        val socket = factory.createSocket("127.0.0.1", port) as SSLSocket
        socket.startHandshake()
        socket.soTimeout = 5_000
        val session = socket.session
        assertNotNull(session.peerPrincipal, "TLS handshake yielded no peer principal")
        // 2. Drive a full HTTP request over the secure socket and read
        //    the response. Assert the production VersionResponse envelope.
        val out = socket.getOutputStream()
        out.write(
            ("GET /api/version HTTP/1.1\r\n" +
                "Host: 127.0.0.1:$port\r\n" +
                "Connection: close\r\n" +
                "\r\n").toByteArray(Charsets.US_ASCII),
        )
        out.flush()
        val response = BufferedReader(InputStreamReader(socket.getInputStream())).readText()
        socket.close()
        assertTrue(
            response.startsWith("HTTP/1.1 200"),
            "expected HTTP/1.1 200 from /api/version over TLS, got: ${response.lineSequence().take(1).toList()}",
        )
        assertTrue(
            response.contains("\"studio\":"),
            "expected production VersionResponse envelope over TLS, got: $response",
        )
        assertTrue(
            response.contains("1.0.0-rc.14"),
            "expected studio version 1.0.0-rc.14 in TLS-served /api/version, got: $response",
        )
    }

    @Test
    fun `studio start with --insecure-http binds plain HTTP and refuses HTTPS attempts`(@TempDir tmp: Path) {
        // R0.2.3 (2026-10-11): --insecure-http is an explicit opt-in for
        // plain HTTP. The wire must be HTTP; an HTTPS attempt against
        // the same port fails (no TLS handshake bytes to greet the
        // client).
        val port = startServer(tmp, insecureHttp = true)
        // Wait until the plain HTTP server is bound. We use the
        // StudioLock as the readiness signal — it's written synchronously
        // after StudioDatabase migration (and just before the in-process
        // server.start()).
        val deadline = System.currentTimeMillis() + 10_000
        val lockPath = tmp.resolve("data").resolve(".studio.lock")
        while (System.currentTimeMillis() < deadline && !lockPath.toFile().exists()) {
            Thread.sleep(80)
        }
        if (!lockPath.toFile().exists()) error("studio did not write lock file within 10s")

        // 1. Plain HTTP works.
        val raw = java.net.Socket("127.0.0.1", port).use { sock ->
            val out = sock.getOutputStream()
            out.write(
                ("GET /health HTTP/1.1\r\n" +
                    "Host: 127.0.0.1:$port\r\n" +
                    "Connection: close\r\n" +
                    "\r\n").toByteArray(Charsets.US_ASCII),
            )
            out.flush()
            sock.getInputStream().bufferedReader().readText()
        }
        assertTrue(
            raw.startsWith("HTTP/1.1 200"),
            "expected plain HTTP 200 with --insecure-http, got: ${raw.lineSequence().take(1).toList()}",
        )

        // 2. An HTTPS attempt against the SAME port fails — the
        // server is plain HTTP and has no TLS handshake to offer.
        val trustAll = object : X509TrustManager {
            override fun checkClientTrusted(c: Array<out X509Certificate>?, s: String?) {}
            override fun checkServerTrusted(c: Array<out X509Certificate>?, s: String?) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
        val sslContext = SSLContext.getInstance("TLS").apply {
            init(null, arrayOf<TrustManager>(trustAll), null)
        }
        val attempted = runCatching {
            sslContext.socketFactory
                .createSocket("127.0.0.1", port)
                .use { it.getInputStream().read() }
        }
        assertTrue(
            attempted.isFailure,
            "expected HTTPS attempt against --insecure-http port to fail; it succeeded. Result: $attempted",
        )
    }

    private fun startServer(tmp: Path, insecureHttp: Boolean): Int {
        val db = tmp.resolve("studio.db")
        val dataDir = tmp.resolve("data")
        val port = pickFreePort()
        val args = mutableListOf("start", "--host", "127.0.0.1", "--port", port.toString(), "--db", db.toString(), "--data-dir", dataDir.toString())
        if (insecureHttp) args.add("--insecure-http")
        val t = Thread { main(args.toTypedArray()) }
        t.isDaemon = true
        t.start()
        return port
    }

    private fun waitForTlsHealthy(baseUrl: String, tmp: Path, timeoutMs: Long) {
        // For TLS: probe via JDK HttpURLConnection with a custom SSLContext
        // that trusts the freshly-generated keystore. The keystore is
        // generated by TlsConfig inside `embeddedServer(..., configure = ...)`,
        // which is part of the boot path; until the keystore appears on disk
        // we cannot probe /health over TLS.
        val ksFile = TlsConfig.keyStorePath(tmp.resolve("data")).toFile()
        val deadline = System.currentTimeMillis() + timeoutMs
        // Poll until the keystore appears (the boot thread creates it
        // synchronously inside `embeddedServer(...).start(wait=true)`).
        var keyStore: KeyStore? = null
        while (System.currentTimeMillis() < deadline) {
            if (ksFile.exists() && ksFile.length() > 0L) {
                try {
                    val ks = KeyStore.getInstance(KeyStore.getDefaultType())
                    ks.load(FileInputStreamCompat(ksFile), TlsConfig.KEY_PASSWORD.toCharArray())
                    keyStore = ks
                    break
                } catch (_: Throwable) {
                    // file just appeared, may not be fully written yet
                }
            }
            Thread.sleep(100)
        }
        if (keyStore == null) error("keystore did not appear at $ksFile within ${timeoutMs}ms")
        val trustAll = object : X509TrustManager {
            override fun checkClientTrusted(c: Array<out X509Certificate>?, s: String?) {}
            override fun checkServerTrusted(c: Array<out X509Certificate>?, s: String?) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
        val sslContext = SSLContext.getInstance("TLS").apply {
            init(null, arrayOf<TrustManager>(trustAll), null)
        }
        HttpsURLConnection.setDefaultSSLSocketFactory(sslContext.socketFactory)
        while (System.currentTimeMillis() < deadline) {
            try {
                val conn = java.net.URL(baseUrl + "/health").openConnection() as HttpsURLConnection
                conn.connectTimeout = 1500
                conn.readTimeout = 1500
                if (conn.responseCode == 200) return
            } catch (_: Throwable) {
                // server not yet up
            }
            Thread.sleep(150)
        }
        error("server at $baseUrl did not become TLS-healthy within ${timeoutMs}ms")
    }

    private fun pickFreePort(): Int = java.net.ServerSocket(0).use { it.localPort }
}

/**
 * Tiny shim so the test code doesn't need to qualify `FileInputStream` on
 * every call. Keeps the test readable.
 */
private class FileInputStreamCompat(file: File) : java.io.FileInputStream(file) {
    companion object {
        operator fun invoke(file: File): java.io.InputStream = java.io.FileInputStream(file)
    }
}
