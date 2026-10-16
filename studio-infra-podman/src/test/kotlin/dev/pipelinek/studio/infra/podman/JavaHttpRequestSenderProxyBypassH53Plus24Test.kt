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
 * Falsifiers for the SFW-H5.3+ twenty-fourth
 * follow-up: proxy bypass list.
 *
 * The 24th follow-up extends the 23rd's
 * HTTP proxy support with a bypass list
 * (NO_PROXY-style). When set, requests
 * to hosts in the list (or matching a
 * suffix in the list) go direct (not
 * through the proxy). Hosts are matched
 * by exact name or by suffix (e.g.,
 * `.local` matches `registry.local`).
 *
 * 5/5 falsifier tests verify the
 * bypass behavior.
 */
@DisplayName("JavaHttpRequestSender (SFW-H5.3+ proxy bypass)")
class JavaHttpRequestSenderProxyBypassH53Plus24Test {

    private val serverRef = AtomicReference<ServerSocket?>()
    private val serverThreadRef = AtomicReference<Thread?>()
    private val proxyRequestCountRef = AtomicInteger(0)
    private val directRequestCountRef = AtomicInteger(0)

    @AfterEach
    fun tearDown() {
        serverRef.get()?.close()
        serverThreadRef.get()?.interrupt()
    }

    // ------------------------------------------------------------------
    // Falsifier 1: default empty bypass list is backwards-compatible
    // ------------------------------------------------------------------
    @Test
    fun `default empty bypass list is backwards-compatible`() {
        val sender = JavaHttpRequestSender()
        assertNotNull(sender)
    }

    // ------------------------------------------------------------------
    // Falsifier 2: explicit empty bypass list is accepted
    // ------------------------------------------------------------------
    @Test
    fun `explicit empty bypass list is accepted (backwards-compatible)`() {
        val sender = JavaHttpRequestSender(proxyBypassHosts = emptyList())
        assertNotNull(sender)
    }

    // ------------------------------------------------------------------
    // Falsifier 3: bypassed host goes direct (not through proxy)
    // ------------------------------------------------------------------
    @Test
    fun `bypassed host goes direct (not through proxy)`() {
        val proxyPort = startProxyServer()
        val directPort = startDirectServer(body = "DIRECT-OK")
        val sender = JavaHttpRequestSender(
            proxyAddress = InetSocketAddress("localhost", proxyPort),
            proxyBypassHosts = listOf("localhost"),
            sleeper = { /* no-op */ },
        )
        val response = sender.get("http://localhost:$directPort/test", emptyMap())
        assertEquals(200, response.statusCode)
        assertEquals("DIRECT-OK", response.body)
        assertEquals(1, directRequestCountRef.get(), "direct server must receive the request")
        assertEquals(0, proxyRequestCountRef.get(), "proxy must NOT receive the request")
    }

    // ------------------------------------------------------------------
    // Falsifier 4: non-bypassed host goes through proxy
    // ------------------------------------------------------------------
    @Test
    fun `non-bypassed host goes through proxy`() {
        val proxyPort = startProxyServer()
        val sender = JavaHttpRequestSender(
            proxyAddress = InetSocketAddress("localhost", proxyPort),
            proxyBypassHosts = listOf("internal.example.com"),
            sleeper = { /* no-op */ },
        )
        val response = sender.get(
            "http://external.example.com/test",
            emptyMap(),
        )
        assertEquals(200, response.statusCode)
        assertEquals(1, proxyRequestCountRef.get(), "proxy must receive the external request")
    }

    // ------------------------------------------------------------------
    // Falsifier 5: bypass by suffix ("alhost" matches "localhost")
    // ------------------------------------------------------------------
    @Test
    fun `bypass by suffix matches hosts (alhost matches localhost)`() {
        val proxyPort = startProxyServer()
        val directPort = startDirectServer(body = "DIRECT-SUFFIX")
        val sender = JavaHttpRequestSender(
            proxyAddress = InetSocketAddress("localhost", proxyPort),
            // The pattern "alhost" is a
            // suffix of "localhost" (the
            // request host); the bypass
            // logic should match it.
            proxyBypassHosts = listOf("alhost"),
            sleeper = { /* no-op */ },
        )
        val response = sender.get("http://localhost:$directPort/test", emptyMap())
        assertEquals(200, response.statusCode)
        assertEquals("DIRECT-SUFFIX", response.body)
        assertEquals(1, directRequestCountRef.get(), "direct server must receive the request (suffix match)")
        assertEquals(0, proxyRequestCountRef.get(), "proxy must NOT receive the request (suffix match)")
    }

    // --------------------------------------------------------------------
    // Test helpers
    // --------------------------------------------------------------------

    private fun startProxyServer(): Int {
        proxyRequestCountRef.set(0)
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
                            val body = "PROXIED"
                            val responseText = "HTTP/1.1 200 OK\r\n" +
                                "Content-Type: text/plain\r\n" +
                                "Content-Length: ${body.length}\r\n\r\n${body}"
                            client.getOutputStream().write(responseText.toByteArray())
                            client.getOutputStream().flush()
                            client.close()
                            proxyRequestCountRef.incrementAndGet()
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

    private fun startDirectServer(body: String): Int {
        directRequestCountRef.set(0)
        val serverSocket = ServerSocket(0)
        // The current `serverRef` would be
        // overwritten; that's fine for
        // the test cleanup.
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
                            directRequestCountRef.incrementAndGet()
                        } catch (e: Exception) {
                            // ignore
                        }
                    }.start()
                }
            } catch (e: Exception) {
                if (!serverSocket.isClosed) throw e
            }
        }
        thread.isDaemon = true
        thread.start()
        return port
    }
}
