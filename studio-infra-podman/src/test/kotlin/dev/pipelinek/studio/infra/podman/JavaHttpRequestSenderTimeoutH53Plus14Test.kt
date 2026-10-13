package dev.pipelinek.studio.infra.podman

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.net.ServerSocket
import java.net.Socket
import java.time.Duration
import java.util.concurrent.atomic.AtomicReference

/**
 * Falsifiers for the SFW-H5.3+ fourteenth
 * follow-up: configurable request timeouts.
 *
 * The 14th follow-up adds two timeout
 * parameters to [JavaHttpRequestSender]:
 * - `connectTimeout: Duration?` — TCP/TLS
 *   handshake timeout (applied to
 *   `HttpClient.Builder.connectTimeout`).
 * - `requestTimeout: Duration?` — per-request
 *   timeout (applied to
 *   `HttpRequest.Builder.timeout`).
 *
 * Both default to `null` (no timeout,
 * backwards-compatible). A production
 * boot that hits a private registry
 * over a slow link should set both.
 */
@DisplayName("JavaHttpRequestSender (SFW-H5.3+ timeouts)")
class JavaHttpRequestSenderTimeoutH53Plus14Test {

    private val serverRef = AtomicReference<ServerSocket?>()
    private val serverThreadRef = AtomicReference<Thread?>()

    @AfterEach
    fun tearDown() {
        serverRef.get()?.close()
        serverThreadRef.get()?.interrupt()
    }

    // ------------------------------------------------------------------
    // Falsifier 1: default timeouts (null) are backwards-compatible
    // ------------------------------------------------------------------
    @Test
    fun `default timeouts (null) are backwards-compatible`() {
        // No timeouts; the default JDK
        // behavior is to wait indefinitely.
        // A normal request to a fast server
        // still succeeds.
        val sender = JavaHttpRequestSender()
        assertNotNull(sender)
    }

    // ------------------------------------------------------------------
    // Falsifier 2: explicit null timeouts are accepted
    // ------------------------------------------------------------------
    @Test
    fun `explicit null timeouts are accepted (backwards-compatible)`() {
        val sender = JavaHttpRequestSender(
            connectTimeout = null,
            requestTimeout = null,
        )
        assertNotNull(sender)
    }

    // ------------------------------------------------------------------
    // Falsifier 3: short request timeout on a slow server triggers HttpTimeoutException
    // ------------------------------------------------------------------
    @Test
    fun `short request timeout on a slow server triggers HttpTimeoutException`() {
        // A test server that accepts the
        // connection but never sends a
        // response. A short request timeout
        // must fire.
        val port = startSlowTestServer(delayMillis = 5_000)
        val sender = JavaHttpRequestSender(
            requestTimeout = Duration.ofMillis(300),
        )
        val start = System.currentTimeMillis()
        try {
            sender.get("http://localhost:$port/test", emptyMap())
            org.junit.jupiter.api.Assertions.fail<Unit>(
                "expected HttpTimeoutException",
            )
        } catch (e: java.net.http.HttpTimeoutException) {
            // expected
            val elapsed = System.currentTimeMillis() - start
            assertTrue(
                elapsed < 4_000,
                "timeout must fire before the server's 5s delay; elapsed=${elapsed}ms",
            )
        } catch (e: java.net.http.HttpConnectTimeoutException) {
            // The JDK may surface a connect
            // timeout instead when the
            // request timeout is shorter
            // than the connect timeout. Both
            // are valid timeout signals.
            val elapsed = System.currentTimeMillis() - start
            assertTrue(
                elapsed < 4_000,
                "timeout must fire before the server's 5s delay; elapsed=${elapsed}ms",
            )
        }
    }

    // ------------------------------------------------------------------
    // Falsifier 4: fast server returns 200 within request timeout
    // ------------------------------------------------------------------
    @Test
    fun `fast server returns 200 within request timeout`() {
        val port = startFastTestServer(body = "OK")
        val sender = JavaHttpRequestSender(
            requestTimeout = Duration.ofSeconds(2),
        )
        val response = sender.get("http://localhost:$port/test", emptyMap())
        assertEquals(200, response.statusCode)
        assertEquals("OK", response.body)
    }

    // ------------------------------------------------------------------
    // Falsifier 5: connect timeout fires on an unreachable host
    // ------------------------------------------------------------------
    @Test
    fun `connect timeout fires on an unreachable host`() {
        // A non-routable IP (TEST-NET-1)
        // causes the TCP handshake to hang.
        // A short connect timeout must
        // fire.
        val sender = JavaHttpRequestSender(
            connectTimeout = Duration.ofMillis(500),
        )
        val start = System.currentTimeMillis()
        try {
            // 192.0.2.1 is RFC 5737 TEST-NET-1;
            // a real connection attempt hangs.
            sender.get(
                "http://192.0.2.1:81/test",
                emptyMap(),
            )
            org.junit.jupiter.api.Assertions.fail<Unit>(
                "expected HttpConnectTimeoutException",
            )
        } catch (e: java.net.http.HttpConnectTimeoutException) {
            val elapsed = System.currentTimeMillis() - start
            assertTrue(
                elapsed < 3_000,
                "connect timeout must fire quickly; elapsed=${elapsed}ms",
            )
        } catch (e: java.net.ConnectException) {
            // Some network setups surface a
            // ConnectException instead of
            // HttpConnectTimeoutException.
            // Both indicate the connection
            // was refused/timed out.
            val elapsed = System.currentTimeMillis() - start
            assertTrue(
                elapsed < 3_000,
                "connect must fail quickly; elapsed=${elapsed}ms",
            )
        } catch (e: java.net.http.HttpTimeoutException) {
            // Or HttpTimeoutException.
            val elapsed = System.currentTimeMillis() - start
            assertTrue(
                elapsed < 3_000,
                "connect must fail quickly; elapsed=${elapsed}ms",
            )
        }
    }

    // --------------------------------------------------------------------
    // Test helpers
    // --------------------------------------------------------------------

    private fun startFastTestServer(body: String): Int {
        val serverSocket = ServerSocket(0)
        serverRef.set(serverSocket)
        val port = serverSocket.localPort
        val thread = Thread {
            try {
                while (!serverSocket.isClosed) {
                    val client = serverSocket.accept()
                    Thread {
                        try {
                            val reader = client.getInputStream().bufferedReader()
                            // Read request line + headers
                            val requestLine = reader.readLine() ?: return@Thread
                            while (true) {
                                val line = reader.readLine() ?: break
                                if (line.isEmpty()) break
                            }
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

    private fun startSlowTestServer(delayMillis: Long): Int {
        val serverSocket = ServerSocket(0)
        serverRef.set(serverSocket)
        val port = serverSocket.localPort
        val thread = Thread {
            try {
                while (!serverSocket.isClosed) {
                    val client: Socket = serverSocket.accept()
                    Thread {
                        try {
                            // Accept the connection but
                            // never send a response. The
                            // client's request timeout
                            // must fire.
                            client.getInputStream().read()
                            // Wait the delay before
                            // sending a response.
                            Thread.sleep(delayMillis)
                            val body = "slow"
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
}
