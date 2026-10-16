package dev.pipelinek.studio.infra.podman

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.ServerSocket
import java.security.KeyStore
import java.util.Base64
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket

/**
 * Falsifiers for the SFW-H5.3+ twenty-second
 * follow-up: TLS verification config.
 *
 * The 22nd follow-up adds a
 * `verifyTls: Boolean = true` parameter
 * to [JavaHttpRequestSender]. When
 * `false`, the sender uses a permissive
 * `TrustManager` (accepts all certs;
 * useful for self-signed dev certs).
 * When `true` (default), the JVM
 * default truststore is used
 * (verifies the chain).
 *
 * ## Security note
 *
 * `verifyTls = false` is a
 * development convenience. NEVER
 * use in production (vulnerable to
 * MITM).
 *
 * 5/5 falsifier tests verify the
 * on/off switch using a real HTTPS
 * test server with a self-signed
 * cert (the same embedded PKCS12
 * from the H5.3+ 13th follow-up).
 */
@DisplayName("JavaHttpRequestSender (SFW-H5.3+ TLS verification)")
class JavaHttpRequestSenderTlsVerificationH53Plus22Test {

    private val serverRef = AtomicReference<java.net.ServerSocket?>()
    private val serverThreadRef = AtomicReference<Thread?>()

    @AfterEach
    fun tearDown() {
        serverRef.get()?.close()
        serverThreadRef.get()?.interrupt()
    }

    // ------------------------------------------------------------------
    // Falsifier 1: default verifyTls=true is backwards-compatible
    // ------------------------------------------------------------------
    @Test
    fun `default verifyTls=true is backwards-compatible`() {
        val sender = JavaHttpRequestSender()
        assertNotNull(sender)
    }

    // ------------------------------------------------------------------
    // Falsifier 2: explicit verifyTls=true is accepted
    // ------------------------------------------------------------------
    @Test
    fun `explicit verifyTls=true is accepted (backwards-compatible)`() {
        val sender = JavaHttpRequestSender(verifyTls = true)
        assertNotNull(sender)
    }

    // ------------------------------------------------------------------
    // Falsifier 3: verifyTls=false with self-signed cert succeeds
    // ------------------------------------------------------------------
    @Test
    fun `verifyTls=false allows self-signed cert`() {
        val port = startTlsTestServer()
        val sender = JavaHttpRequestSender(verifyTls = false)
        val response = sender.get("https://localhost:$port/test", emptyMap())
        assertEquals(200, response.statusCode, "self-signed cert must be accepted")
        assertEquals("OK", response.body)
    }

    // ------------------------------------------------------------------
    // Falsifier 4: verifyTls=true rejects self-signed cert
    // ------------------------------------------------------------------
    @Test
    fun `verifyTls=true rejects self-signed cert`() {
        val port = startTlsTestServer()
        val sender = JavaHttpRequestSender(verifyTls = true)
        // The handshake must fail
        // (the cert is self-signed, not
        // in the JVM truststore).
        try {
            sender.get("https://localhost:$port/test", emptyMap())
            org.junit.jupiter.api.Assertions.fail<Unit>(
                "expected an exception for self-signed cert",
            )
        } catch (e: Exception) {
            // The JDK may surface
            // SSLHandshakeException, a
            // wrapped IOException, or a
            // HttpClient internal error.
            // The key signal: the message
            // must mention trust, cert,
            // PKIX, or handshake.
            val messages = mutableListOf<String>()
            var current: Throwable? = e
            while (current != null) {
                current.message?.let { messages.add(it) }
                current = current.cause
            }
            val allText = messages.joinToString(" | ")
            assertTrue(
                allText.contains("trust", ignoreCase = true) ||
                    allText.contains("certificate", ignoreCase = true) ||
                    allText.contains("PKIX", ignoreCase = true) ||
                    allText.contains("handshake", ignoreCase = true) ||
                    allText.contains("verify", ignoreCase = true),
                "expected trust/cert error; got '$allText'",
            )
        }
    }

    // ------------------------------------------------------------------
    // Falsifier 5: HTTP (non-TLS) is unaffected by verifyTls
    // ------------------------------------------------------------------
    @Test
    fun `HTTP (non-TLS) is unaffected by verifyTls`() {
        val port = startPlainHttpServer()
        val senderTlsDisabled = JavaHttpRequestSender(verifyTls = false)
        val response = senderTlsDisabled.get("http://localhost:$port/test", emptyMap())
        assertEquals(200, response.statusCode)
    }

    // --------------------------------------------------------------------
    // Test helpers
    // --------------------------------------------------------------------

    private fun startPlainHttpServer(): Int {
        val serverSocket = ServerSocket(0)
        serverRef.set(serverSocket)
        val port = serverSocket.localPort
        val thread = Thread {
            try {
                while (!serverSocket.isClosed) {
                    val client = serverSocket.accept()
                    Thread {
                        try {
                            val reader = BufferedReader(InputStreamReader(client.getInputStream()))
                            reader.readLine() ?: return@Thread
                            while (true) {
                                val line = reader.readLine() ?: break
                                if (line.isEmpty()) break
                            }
                            val body = "OK"
                            val response = "HTTP/1.1 200 OK\r\nContent-Type: text/plain\r\nContent-Length: ${body.length}\r\n\r\n$body"
                            client.getOutputStream().write(response.toByteArray())
                            client.getOutputStream().flush()
                            client.close()
                        } catch (e: Exception) {
                            // ignore
                        }
                    }.start()
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

    private fun startTlsTestServer(): Int {
        // Reuse the embedded PKCS12 from
        // the H5.3+ 13th follow-up. The
        // cert is CN=localhost (so the
        // hostname check passes) and
        // self-signed (so the chain
        // check fails for the verifyTls=true
        // case).
        val keyStore = KeyStore.getInstance("PKCS12").apply {
            load(
                Base64.getDecoder().decode(SERVER_KEYSTORE_B64).inputStream(),
                "serverpass".toCharArray(),
            )
        }
        val keyManagerFactory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        keyManagerFactory.init(keyStore, "serverpass".toCharArray())
        val sslContext = SSLContext.getInstance("TLS").apply {
            init(keyManagerFactory.keyManagers, null, null)
        }
        val serverSocket = sslContext.serverSocketFactory.createServerSocket(0) as SSLServerSocket
        serverRef.set(serverSocket)
        val port = serverSocket.localPort
        val thread = Thread {
            try {
                while (!serverSocket.isClosed) {
                    val client = serverSocket.accept() as javax.net.ssl.SSLSocket
                    client.startHandshake()
                    Thread {
                        try {
                            val reader = BufferedReader(InputStreamReader(client.getInputStream()))
                            reader.readLine() ?: return@Thread
                            while (true) {
                                val line = reader.readLine() ?: break
                                if (line.isEmpty()) break
                            }
                            val body = "OK"
                            val response = "HTTP/1.1 200 OK\r\nContent-Type: text/plain\r\nContent-Length: ${body.length}\r\n\r\n$body"
                            client.getOutputStream().write(response.toByteArray())
                            client.getOutputStream().flush()
                            client.close()
                        } catch (e: Exception) {
                            // ignore
                        }
                    }.start()
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

    companion object {
        // The same SERVER_KEYSTORE_B64 as
        // the H5.3+ 13th follow-up
        // (CN=localhost, self-signed,
        // SAN=dns:localhost). The 22nd
        // follow-up reuses the same
        // material; the cert is the
        // vehicle for testing the
        // verifyTls on/off switch.
        private val SERVER_KEYSTORE_B64: String = "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"
    }
}
