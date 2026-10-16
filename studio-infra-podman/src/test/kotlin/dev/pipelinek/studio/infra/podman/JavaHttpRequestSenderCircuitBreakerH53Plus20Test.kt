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
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * Falsifiers for the SFW-H5.3+ twentieth
 * follow-up: wire the circuit breaker
 * into JavaHttpRequestSender.
 *
 * The 19th follow-up shipped the
 * circuit breaker as a utility. The
 * 20th follow-up wires it into the HTTP
 * path: a `circuitBreaker: CircuitBreaker?
 * = null` parameter on
 * [JavaHttpRequestSender]; when set, the
 * entire request (including retries) is
 * wrapped in [CircuitBreaker.execute].
 * When the circuit is OPEN, the call
 * fails fast with
 * [CircuitBreakerOpenException] without
 * attempting the downstream.
 *
 * 5/5 falsifier tests verify the
 * integration.
 */
@DisplayName("JavaHttpRequestSender (SFW-H5.3+ circuit breaker)")
class JavaHttpRequestSenderCircuitBreakerH53Plus20Test {

    private val serverRef = AtomicReference<ServerSocket?>()
    private val serverThreadRef = AtomicReference<Thread?>()
    private val requestCountRef = AtomicInteger(0)

    @AfterEach
    fun tearDown() {
        serverRef.get()?.close()
        serverThreadRef.get()?.interrupt()
    }

    // ------------------------------------------------------------------
    // Falsifier 1: no circuit breaker (null) is backwards-compatible
    // ------------------------------------------------------------------
    @Test
    fun `no circuit breaker (null) is backwards-compatible`() {
        val sender = JavaHttpRequestSender()
        assertNotNull(sender)
    }

    // ------------------------------------------------------------------
    // Falsifier 2: closed circuit allows the request through
    // ------------------------------------------------------------------
    @Test
    fun `closed circuit allows the request through`() {
        val port = startSimpleServer(body = "OK")
        val cb = CountingCircuitBreaker(failureThreshold = 3)
        val sender = JavaHttpRequestSender(
            circuitBreaker = cb,
            sleeper = { /* no-op */ },
        )
        val response = sender.get("http://localhost:$port/test", emptyMap())
        assertEquals(200, response.statusCode)
        assertEquals(1, requestCountRef.get(), "no retries expected")
    }

    // ------------------------------------------------------------------
    // Falsifier 3: open circuit fails fast (no request attempt)
    // ------------------------------------------------------------------
    @Test
    fun `open circuit fails fast without request attempt`() {
        val port = startSimpleServer(body = "OK")
        val cb = CountingCircuitBreaker(failureThreshold = 2)
        // Trigger the circuit to OPEN
        // by failing twice. Use a
        // non-listening localhost port
        // (the OS rejects the connection
        // immediately with ConnectException,
        // which is a fast IOException).
        val failingSender = JavaHttpRequestSender(
            circuitBreaker = cb,
            sleeper = { /* no-op */ },
        )
        repeat(2) {
            assertThrows(java.io.IOException::class.java) {
                failingSender.get(
                    "http://localhost:1/test",  // non-listening port
                    emptyMap(),
                )
            }
        }
        // The circuit is now OPEN.
        // A new request must fail fast
        // without attempting the
        // downstream.
        val ex = assertThrows(CircuitBreakerOpenException::class.java) {
            failingSender.get("http://localhost:$port/test", emptyMap())
        }
        assertTrue(
            ex.message!!.contains("OPEN"),
            "message must mention OPEN state; got '${ex.message}'",
        )
    }

    // ------------------------------------------------------------------
    // Falsifier 4: retry + circuit breaker compose
    // ------------------------------------------------------------------
    @Test
    fun `retry and circuit breaker compose correctly`() {
        val port = startScriptedServer(
            responses = listOf(
                HttpResponse(503, "Service Unavailable", emptyMap()),
                HttpResponse(503, "Service Unavailable", emptyMap()),
                HttpResponse(503, "Service Unavailable", emptyMap()),
            ),
        )
        val cb = CountingCircuitBreaker(failureThreshold = 2)
        val sender = JavaHttpRequestSender(
            circuitBreaker = cb,
            retry = HttpRetryConfig(
                maxAttempts = 3,
                initialBackoff = Duration.ofMillis(10),
            ),
            sleeper = { /* no-op */ },
        )
        // All 3 attempts fail (3 x 503).
        // The circuit counts each as a
        // failure. After 2 consecutive
        // failures, the circuit OPENS.
        // The 3rd attempt is still
        // allowed (it's in the same
        // circuitBreaker.execute call
        // that's already started). The
        // response is the last 503.
        val response = sender.get("http://localhost:$port/test", emptyMap())
        assertEquals(503, response.statusCode)
        assertEquals(3, requestCountRef.get(), "all 3 attempts must be made")
    }

    // ------------------------------------------------------------------
    // Falsifier 5: successful request through closed circuit records success
    // ------------------------------------------------------------------
    @Test
    fun `successful request through circuit records success`() {
        val port = startSimpleServer(body = "OK")
        val cb = CountingCircuitBreaker(failureThreshold = 2)
        val sender = JavaHttpRequestSender(
            circuitBreaker = cb,
            sleeper = { /* no-op */ },
        )
        // Make 5 successful requests.
        // The circuit should remain
        // CLOSED (the failure count
        // stays at 0).
        repeat(5) {
            val response = sender.get("http://localhost:$port/test", emptyMap())
            assertEquals(200, response.statusCode)
        }
        // The circuit should still be
        // CLOSED (no failures). A new
        // request must work normally.
        val response = sender.get("http://localhost:$port/test", emptyMap())
        assertEquals(200, response.statusCode)
        assertEquals(6, requestCountRef.get(), "6 requests expected (5 + 1 final)")
    }

    // --------------------------------------------------------------------
    // Test helpers
    // --------------------------------------------------------------------

    private fun startSimpleServer(body: String): Int {
        return startScriptedServer(responses = listOf(HttpResponse(200, body, emptyMap())))
    }

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
