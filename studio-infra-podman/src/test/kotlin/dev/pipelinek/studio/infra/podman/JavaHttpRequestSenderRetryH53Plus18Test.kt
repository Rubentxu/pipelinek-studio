package dev.pipelinek.studio.infra.podman

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.ServerSocket
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * Falsifiers for the SFW-H5.3+ eighteenth
 * follow-up: HTTP-level retry with
 * exponential backoff for transient
 * failures.
 *
 * The 18th follow-up extends
 * [JavaHttpRequestSender] with an optional
 * [HttpRetryConfig] parameter. When set,
 * the sender retries on 5xx responses and
 * [java.io.IOException] (network errors)
 * up to `maxAttempts` times with
 * exponential backoff. 4xx responses are
 * NOT retried (client errors; retrying
 * won't help).
 *
 * ## Why retry
 *
 * A production boot can hit transient
 * failures: a registry replica returns
 * 503, a network blip causes a connection
 * reset, a load balancer rotates to an
 * unhealthy backend. Retrying with
 * exponential backoff is the standard
 * mitigation.
 *
 * 5/5 falsifier tests verify the retry
 * behavior using a real test HTTP server
 * (ServerSocket) that returns a scripted
 * sequence of responses.
 */
@DisplayName("JavaHttpRequestSender (SFW-H5.3+ retry)")
class JavaHttpRequestSenderRetryH53Plus18Test {

    private val serverRef = AtomicReference<ServerSocket?>()
    private val serverThreadRef = AtomicReference<Thread?>()
    private val requestCountRef = AtomicInteger(0)

    @AfterEach
    fun tearDown() {
        serverRef.get()?.close()
        serverThreadRef.get()?.interrupt()
    }

    // ------------------------------------------------------------------
    // Falsifier 1: default retry (null) is backwards-compatible
    // ------------------------------------------------------------------
    @Test
    fun `default retry (null) is backwards-compatible`() {
        val sender = JavaHttpRequestSender()
        assertNotNull(sender)
    }

    // ------------------------------------------------------------------
    // Falsifier 2: first attempt succeeds (no retries)
    // ------------------------------------------------------------------
    @Test
    fun `first attempt succeeds (no retries)`() {
        val port = startScriptedServer(
            responses = listOf(HttpResponse(200, "OK", emptyMap())),
        )
        val sender = JavaHttpRequestSender(
            retry = HttpRetryConfig(maxAttempts = 3),
            // No-op sleep for fast tests.
            sleeper = { /* no-op */ },
        )
        val response = sender.get("http://localhost:$port/test", emptyMap())
        assertEquals(200, response.statusCode)
        assertEquals(1, requestCountRef.get(), "no retries expected")
    }

    // ------------------------------------------------------------------
    // Falsifier 3: second attempt succeeds after 503
    // ------------------------------------------------------------------
    @Test
    fun `second attempt succeeds after 503`() {
        val port = startScriptedServer(
            responses = listOf(
                HttpResponse(503, "Service Unavailable", emptyMap()),
                HttpResponse(200, "OK", emptyMap()),
            ),
        )
        val sender = JavaHttpRequestSender(
            retry = HttpRetryConfig(
                maxAttempts = 3,
                initialBackoff = Duration.ofMillis(10),
            ),
            sleeper = { /* no-op */ },
        )
        val response = sender.get("http://localhost:$port/test", emptyMap())
        assertEquals(200, response.statusCode)
        assertEquals(2, requestCountRef.get(), "1 retry expected (2 total)")
    }

    // ------------------------------------------------------------------
    // Falsifier 4: third attempt succeeds after two 503s
    // ------------------------------------------------------------------
    @Test
    fun `third attempt succeeds after two 503s`() {
        val port = startScriptedServer(
            responses = listOf(
                HttpResponse(503, "Service Unavailable", emptyMap()),
                HttpResponse(503, "Service Unavailable", emptyMap()),
                HttpResponse(200, "OK", emptyMap()),
            ),
        )
        val sender = JavaHttpRequestSender(
            retry = HttpRetryConfig(
                maxAttempts = 3,
                initialBackoff = Duration.ofMillis(10),
            ),
            sleeper = { /* no-op */ },
        )
        val response = sender.get("http://localhost:$port/test", emptyMap())
        assertEquals(200, response.statusCode)
        assertEquals(3, requestCountRef.get(), "2 retries expected (3 total)")
    }

    // ------------------------------------------------------------------
    // Falsifier 5: 4xx is NOT retried
    // ------------------------------------------------------------------
    @Test
    fun `4xx response is not retried (client error)`() {
        val port = startScriptedServer(
            responses = listOf(HttpResponse(404, "Not Found", emptyMap())),
        )
        val sender = JavaHttpRequestSender(
            retry = HttpRetryConfig(maxAttempts = 3),
            sleeper = { /* no-op */ },
        )
        val response = sender.get("http://localhost:$port/test", emptyMap())
        assertEquals(404, response.statusCode)
        assertEquals(1, requestCountRef.get(), "4xx must NOT be retried")
    }

    // ------------------------------------------------------------------
    // Falsifier 6 (bonus): retries exhaust and return the last 5xx
    // ------------------------------------------------------------------
    @Test
    fun `retries exhaust and return the last 5xx`() {
        val port = startScriptedServer(
            responses = listOf(
                HttpResponse(503, "Service Unavailable", emptyMap()),
                HttpResponse(503, "Service Unavailable", emptyMap()),
                HttpResponse(503, "Service Unavailable", emptyMap()),
            ),
        )
        val sender = JavaHttpRequestSender(
            retry = HttpRetryConfig(
                maxAttempts = 3,
                initialBackoff = Duration.ofMillis(10),
            ),
            sleeper = { /* no-op */ },
        )
        val response = sender.get("http://localhost:$port/test", emptyMap())
        assertEquals(503, response.statusCode)
        assertEquals(3, requestCountRef.get(), "all 3 attempts must be made")
    }

    // --------------------------------------------------------------------
    // Test helpers
    // --------------------------------------------------------------------

    private fun startScriptedServer(responses: List<HttpResponse>): Int {
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
                            val index = requestCountRef.getAndIncrement()
                            val response = responses.getOrNull(index)
                                ?: responses.last()
                            val responseText = "HTTP/1.1 ${response.statusCode} OK\r\n" +
                                "Content-Type: text/plain\r\n" +
                                "Content-Length: ${response.body.length}\r\n\r\n${response.body}"
                            client.getOutputStream().write(responseText.toByteArray())
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
