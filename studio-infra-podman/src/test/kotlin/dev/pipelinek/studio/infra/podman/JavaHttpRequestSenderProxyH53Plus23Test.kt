package dev.pipelinek.studio.infra.podman

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * Falsifiers for the SFW-H5.3+ twenty-third
 * follow-up: HTTP proxy support.
 *
 * The 23rd follow-up adds a
 * `proxyAddress: InetSocketAddress?` parameter
 * to [JavaHttpRequestSender]. When set,
 * all HTTP requests are routed through
 * this proxy. When `null` (the default),
 * the JVM's default proxy settings are
 * used — backwards-compatible.
 *
 * 5/5 falsifier tests verify the
 * proxy routing.
 */
@DisplayName("JavaHttpRequestSender (SFW-H5.3+ HTTP proxy)")
class JavaHttpRequestSenderProxyH53Plus23Test {

    private val serverRef = AtomicReference<ServerSocket?>()
    private val serverThreadRef = AtomicReference<Thread?>()
    private val requestCountRef = AtomicInteger(0)
    private val proxiedRequestCountRef = AtomicInteger(0)

    @AfterEach
    fun tearDown() {
        serverRef.get()?.close()
        serverThreadRef.get()?.interrupt()
    }

    // ------------------------------------------------------------------
    // Falsifier 1: default (null proxy) is backwards-compatible
    // ------------------------------------------------------------------
    @Test
    fun `default null proxy is backwards-compatible`() {
        val sender = JavaHttpRequestSender()
        assertNotNull(sender)
    }

    // ------------------------------------------------------------------
    // Falsifier 2: explicit null proxy is accepted
    // ------------------------------------------------------------------
    @Test
    fun `explicit null proxy is accepted (backwards-compatible)`() {
        val sender = JavaHttpRequestSender(proxyAddress = null)
        assertNotNull(sender)
    }

    // ------------------------------------------------------------------
    // Falsifier 3: request through HTTP proxy succeeds
    // ------------------------------------------------------------------
    @Test
    fun `request through HTTP proxy succeeds`() {
        // A test HTTP proxy that just
        // returns 200 (a passthrough
        // would require connecting to
        // a real target; for this test
        // we verify the proxy receives
        // the request).
        val proxyPort = startTestProxy()
        val sender = JavaHttpRequestSender(
            proxyAddress = InetSocketAddress("localhost", proxyPort),
            sleeper = { /* no-op */ },
        )
        val response = sender.get(
            "http://example.com/test",
            emptyMap(),
        )
        assertEquals(200, response.statusCode)
        assertEquals(1, proxiedRequestCountRef.get(), "proxy must receive the request")
    }

    // ------------------------------------------------------------------
    // Falsifier 4: proxy receives the full target URL
    // ------------------------------------------------------------------
    @Test
    fun `proxy receives the full target URL`() {
        val capturedUrl = AtomicReference<String?>(null)
        val proxyPort = startTestProxy(captureUrl = capturedUrl)
        val sender = JavaHttpRequestSender(
            proxyAddress = InetSocketAddress("localhost", proxyPort),
            sleeper = { /* no-op */ },
        )
        sender.get("http://registry.example.com/v2/some-image/manifests/latest", emptyMap())
        val url = capturedUrl.get()
        assertNotNull(url, "proxy must capture the request URL")
        assertTrue(
            url!!.contains("registry.example.com"),
            "proxy must see the target host; got '$url'",
        )
        assertTrue(
            url.contains("/v2/some-image/manifests/latest"),
            "proxy must see the target path; got '$url'",
        )
    }

    // ------------------------------------------------------------------
    // Falsifier 5: no-proxy default path still works (direct connection)
    // ------------------------------------------------------------------
    @Test
    fun `no-proxy default path still works (direct connection)`() {
        val serverPort = startSimpleServer(body = "DIRECT")
        val sender = JavaHttpRequestSender(proxyAddress = null)
        val response = sender.get("http://localhost:$serverPort/test", emptyMap())
        assertEquals(200, response.statusCode)
        assertEquals("DIRECT", response.body)
        assertEquals(0, proxiedRequestCountRef.get(), "no proxy must be used")
    }

    // --------------------------------------------------------------------
    // Test helpers
    // --------------------------------------------------------------------

    private fun startSimpleServer(body: String): Int {
        requestCountRef.set(0)
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
                            val responseText = "HTTP/1.1 200 OK\r\n" +
                                "Content-Type: text/plain\r\n" +
                                "Content-Length: ${body.length}\r\n\r\n${body}"
                            client.getOutputStream().write(responseText.toByteArray())
                            client.getOutputStream().flush()
                            client.close()
                            requestCountRef.incrementAndGet()
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

    private fun startTestProxy(captureUrl: AtomicReference<String?>? = null): Int {
        proxiedRequestCountRef.set(0)
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
                            val requestLine = reader.readLine() ?: return@Thread
                            captureUrl?.set(requestLine)
                            while (true) {
                                val line = reader.readLine() ?: break
                                if (line.isEmpty()) break
                            }
                            val body = "PROXIED"
                            val responseText = "HTTP/1.1 200 OK\r\n" +
                                "Content-Type: text/plain\r\n" +
                                "Content-Length: ${body.length}\r\n\r\n${body}"
                            client.getOutputStream().write(responseText.toByteArray())
                            client.getOutputStream().flush()
                            client.close()
                            proxiedRequestCountRef.incrementAndGet()
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
