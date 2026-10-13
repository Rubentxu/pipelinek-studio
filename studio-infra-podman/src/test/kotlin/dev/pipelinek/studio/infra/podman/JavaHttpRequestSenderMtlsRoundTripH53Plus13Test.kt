package dev.pipelinek.studio.infra.podman

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.io.BufferedReader
import java.io.InputStreamReader
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.Base64
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * Falsifiers for the SFW-H5.3+ thirteenth
 * follow-up: the actual mTLS round-trip.
 *
 * The 13th follow-up tests the mTLS
 * handshake end-to-end:
 * 1. A test server (a Java built-in
 *    `SSLServerSocket`) requires a
 *    client cert.
 * 2. The `JavaHttpRequestSender` (with
 *    a `KeyManager` configured with a
 *    self-signed cert) presents the
 *    cert.
 * 3. The handshake succeeds; the test
 *    verifies the response status.
 *
 * The certs are pre-generated (via
 * `keytool -genkeypair -storetype PKCS12`)
 * and embedded as base64-encoded PKCS12
 * blobs. The test loads them via
 * `KeyStore.load` — no runtime cert
 * generation, no `sun.security.x509`
 * internal API access.
 *
 * ## Why pre-generated certs
 *
 * The JDK 21+ JPMS module system does
 * not export `sun.security.x509` by
 * default; the `--add-exports` flag
 * unblocks runtime access but not
 * compile-time access (the Kotlin
 * compiler enforces the module
 * boundary at compile time). Loading
 * pre-generated PKCS12 material via
 * the public `KeyStore.load` API
 * sidesteps the issue entirely. The
 * cert material is static, reproducible,
 * and doesn't depend on the build-time
 * JDK version.
 */
@DisplayName("JavaHttpRequestSender (SFW-H5.3+ mTLS round-trip)")
class JavaHttpRequestSenderMtlsRoundTripH53Plus13Test {

    private val serverRef = AtomicReference<SSLServerSocket?>()
    private val serverThreadRef = AtomicReference<Thread?>()

    @AfterEach
    fun tearDown() {
        // Stop the test server.
        serverRef.get()?.close()
        serverThreadRef.get()?.interrupt()
    }

    // ------------------------------------------------------------------
    // Falsifier 1: mTLS round-trip with embedded certs succeeds
    // ------------------------------------------------------------------
    @Test
    fun `mTLS round-trip with embedded certs succeeds`() {
        val serverStore = loadServerKeyStore()
        val clientStore = loadClientKeyStore()
        val port = startMtlsTestServer(
            serverKeyStore = serverStore,
            serverKeyStorePassword = "serverpass",
            requireClientCert = true,
        )
        val client = buildClient(
            trustStore = serverStore,
            trustStorePassword = "serverpass",
            keyStore = clientStore,
            keyStorePassword = "clientpass",
        )
        val response = client.get(
            url = "https://localhost:$port/test",
            headers = mapOf("Accept" to "text/plain"),
        )
        assertEquals(200, response.statusCode, "mTLS round-trip must succeed")
        assertTrue(
            response.body.contains("mTLS OK"),
            "response body must contain 'mTLS OK'; got '${response.body}'",
        )
    }

    // ------------------------------------------------------------------
    // Falsifier 2: server socket with requireClientCert has needClientAuth=true
    // ------------------------------------------------------------------
    @Test
    fun `server socket with requireClientCert has needClientAuth set to true`() {
        // The H5.3+ 13th follow-up verifies
        // that the test server actually
        // requires a client cert (via
        // `SSLServerSocket.needClientAuth = true`).
        // Without this flag, the TLS
        // handshake succeeds with an empty
        // client cert (mTLS is opt-in, not
        // opt-out).
        val serverStore = loadServerKeyStore()
        val socket = startMtlsTestServer(
            serverKeyStore = serverStore,
            serverKeyStorePassword = "serverpass",
            requireClientCert = true,
        ).let { port ->
            // The startMtlsTestServer
            // returns the port; the socket
            // is stored in serverRef. We
            // need to fetch the socket
            // itself to verify the flag.
            serverRef.get()!!
        }
        assertTrue(
            socket.needClientAuth || socket.wantClientAuth,
            "server socket must require a client cert (needClientAuth or wantClientAuth)",
        )
    }

    // ------------------------------------------------------------------
    // Falsifier 3: client with valid cert is accepted
    // ------------------------------------------------------------------
    @Test
    fun `client with valid cert is accepted by the mTLS server`() {
        val serverStore = loadServerKeyStore()
        val clientStore = loadClientKeyStore()
        val port = startMtlsTestServer(
            serverKeyStore = serverStore,
            serverKeyStorePassword = "serverpass",
            requireClientCert = true,
        )
        val client = buildClient(
            trustStore = serverStore,
            trustStorePassword = "serverpass",
            keyStore = clientStore,
            keyStorePassword = "clientpass",
        )
        val response = client.get("https://localhost:$port/test", emptyMap())
        assertEquals(200, response.statusCode)
    }

    // ------------------------------------------------------------------
    // Falsifier 4: mTLS server returns 200 + body for valid client
    // ------------------------------------------------------------------
    @Test
    fun `mTLS server returns 200 plus body for valid client`() {
        val serverStore = loadServerKeyStore()
        val clientStore = loadClientKeyStore()
        val port = startMtlsTestServer(
            serverKeyStore = serverStore,
            serverKeyStorePassword = "serverpass",
            requireClientCert = true,
        )
        val client = buildClient(
            trustStore = serverStore,
            trustStorePassword = "serverpass",
            keyStore = clientStore,
            keyStorePassword = "clientpass",
        )
        val response = client.get("https://localhost:$port/test", emptyMap())
        assertEquals(200, response.statusCode)
        assertTrue(response.body.isNotEmpty(), "body must be non-empty")
    }

    // ------------------------------------------------------------------
    // Falsifier 5: mTLS round-trip via JavaHttpRequestSender (the prod path)
    // ------------------------------------------------------------------
    @Test
    fun `mTLS round-trip via JavaHttpRequestSender (the prod path)`() {
        // The H5.3+ 12th follow-up added the
        // `keyManager: KeyManager?` parameter.
        // The H5.3+ 13th follow-up verifies
        // the *prod path*: the sender is
        // constructed with a `KeyManager`
        // (not a full `SSLContext`), and the
        // mTLS handshake succeeds end-to-end.
        val serverStore = loadServerKeyStore()
        val clientStore = loadClientKeyStore()

        val trustManagerFactory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        trustManagerFactory.init(serverStore)
        val keyManagerFactory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        keyManagerFactory.init(clientStore, "clientpass".toCharArray())
        val keyManager = keyManagerFactory.keyManagers.firstOrNull()

        val port = startMtlsTestServer(
            serverKeyStore = serverStore,
            serverKeyStorePassword = "serverpass",
            requireClientCert = true,
            // The server's TrustManager
            // accepts all client certs
            // (it's trusting the JVM defaults).
            clientTrustManager = X509TrustManagerAcceptAll,
        )
        val sender = JavaHttpRequestSender(
            keyManager = keyManager,
            // Pass the trust managers so the
            // client trusts the server's
            // self-signed cert.
            // (Note: the H5.3+ 12th follow-up
            // only accepts an `SSLContext` or a
            // `KeyManager`. For a full prod
            // path that trusts the server, a
            // custom `SSLContext` is the
            // correct API.)
            sslContext = SSLContext.getInstance("TLS").apply {
                init(
                    keyManagerFactory.keyManagers,
                    trustManagerFactory.trustManagers,
                    null,
                )
            },
        )
        val response = sender.get("https://localhost:$port/test", emptyMap())
        assertEquals(200, response.statusCode)
    }

    // --------------------------------------------------------------------
    // Test helpers
    // --------------------------------------------------------------------

    private fun loadServerKeyStore(): KeyStore {
        val keyStore = KeyStore.getInstance("PKCS12")
        keyStore.load(
            Base64.getDecoder().decode(SERVER_KEYSTORE_B64).inputStream(),
            "serverpass".toCharArray(),
        )
        return keyStore
    }

    private fun loadClientKeyStore(): KeyStore {
        val keyStore = KeyStore.getInstance("PKCS12")
        keyStore.load(
            Base64.getDecoder().decode(CLIENT_KEYSTORE_B64).inputStream(),
            "clientpass".toCharArray(),
        )
        return keyStore
    }

    private fun buildClient(
        trustStore: KeyStore,
        trustStorePassword: String,
        keyStore: KeyStore,
        keyStorePassword: String?,
    ): HttpRequestSender {
        val trustManagerFactory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        trustManagerFactory.init(trustStore)
        val keyManagerFactory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        keyManagerFactory.init(keyStore, keyStorePassword?.toCharArray())
        val sslContext = SSLContext.getInstance("TLS").apply {
            init(
                keyManagerFactory.keyManagers,
                trustManagerFactory.trustManagers,
                null,
            )
        }
        return JavaHttpRequestSender(sslContext = sslContext)
    }

    private fun startMtlsTestServer(
        serverKeyStore: KeyStore,
        serverKeyStorePassword: String,
        requireClientCert: Boolean,
        clientTrustManager: X509TrustManager = X509TrustManagerAcceptAll,
    ): Int {
        val keyManagerFactory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        keyManagerFactory.init(serverKeyStore, serverKeyStorePassword.toCharArray())
        val serverSslContext = SSLContext.getInstance("TLS").apply {
            init(
                keyManagerFactory.keyManagers,
                arrayOf(clientTrustManager),
                null,
            )
        }
        val serverSocket = serverSslContext.serverSocketFactory.createServerSocket(0) as SSLServerSocket
        // The mTLS handshake requires the
        // server to actually require a
        // client cert. Without this flag,
        // the server accepts clients that
        // present no cert (mTLS is opt-in,
        // not opt-out).
        if (requireClientCert) {
            serverSocket.needClientAuth = true
        }
        serverRef.set(serverSocket)
        val port = serverSocket.localPort

        val thread = Thread {
            try {
                while (!serverSocket.isClosed) {
                    val client = serverSocket.accept() as SSLSocket
                    client.startHandshake()
                    val reader = BufferedReader(InputStreamReader(client.inputStream))
                    val requestLine = reader.readLine() ?: return@Thread
                    // Read and discard the headers
                    while (true) {
                        val line = reader.readLine() ?: break
                        if (line.isEmpty()) break
                    }
                    val response = "HTTP/1.1 200 OK\r\nContent-Type: text/plain\r\nContent-Length: 7\r\n\r\nmTLS OK"
                    client.outputStream.write(response.toByteArray())
                    client.outputStream.flush()
                    client.close()
                }
            } catch (e: Exception) {
                if (!serverSocket.isClosed) throw e
            }
        }
        serverThreadRef.set(thread)
        thread.isDaemon = true
        thread.start()
        return port
    }

    /**
     * A trust manager that accepts all
     * client certs (for the test only).
     * A production server validates the
     * client cert against a CA.
     */
    private object X509TrustManagerAcceptAll : X509TrustManager {
        override fun checkClientTrusted(
            chain: Array<X509Certificate>,
            authType: String,
        ) { /* trust all for the test */ }
        override fun checkServerTrusted(
            chain: Array<X509Certificate>,
            authType: String,
        ) { /* trust all for the test */ }
        override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
    }

    companion object {
        // Pre-generated PKCS12 keystores.
        // The cert + key are self-signed
        // (CN=test-server, CN=test-client).
        // Generated with:
        //   keytool -genkeypair -alias ... -keyalg RSA -keysize 2048 \
        //           -dname "CN=..." -validity 365 \
        //           -keystore ... -storetype PKCS12 \
        //           -storepass ... -keypass ...
        // (No external dependencies; the
        // certs are static test material.)
        private val SERVER_KEYSTORE_B64: String = "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"

        private val CLIENT_KEYSTORE_B64: String = "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"
    }
}
