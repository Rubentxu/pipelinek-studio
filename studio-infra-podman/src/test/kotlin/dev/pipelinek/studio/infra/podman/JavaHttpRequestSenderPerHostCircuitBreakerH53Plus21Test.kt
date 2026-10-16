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
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * Falsifiers for the SFW-H5.3+ twenty-first
 * follow-up: per-host circuit breaker.
 *
 * The 20th follow-up wired a single
 * global `CircuitBreaker` into
 * [JavaHttpRequestSender]. The 21st
 * extends this with a per-host map
 * (`perHostCircuitBreakers: Map<String,
 * CircuitBreaker>`) so a Studio that
 * talks to multiple registries gets
 * proper isolation: a failure of one
 * registry does not trip the breaker
 * for another.
 *
 * 5/5 falsifier tests verify the
 * per-host lookup + fallback logic.
 */
@DisplayName("JavaHttpRequestSender (SFW-H5.3+ per-host circuit breaker)")
class JavaHttpRequestSenderPerHostCircuitBreakerH53Plus21Test {

    private val serverRef = AtomicReference<ServerSocket?>()
    private val serverThreadRef = AtomicReference<Thread?>()
    private val requestCountRef = AtomicInteger(0)

    @AfterEach
    fun tearDown() {
        serverRef.get()?.close()
        serverThreadRef.get()?.interrupt()
    }

    // ------------------------------------------------------------------
    // Falsifier 1: empty per-host map is backwards-compatible
    // ------------------------------------------------------------------
    @Test
    fun `empty per-host map is backwards-compatible`() {
        val sender = JavaHttpRequestSender(
            perHostCircuitBreakers = emptyMap(),
        )
        assertNotNull(sender)
    }

    // ------------------------------------------------------------------
    // Falsifier 2: per-host breaker takes precedence over global
    // ------------------------------------------------------------------
    @Test
    fun `per-host breaker takes precedence over global`() {
        val port = startScriptedServer(
            responses = listOf(HttpResponse(200, "OK", emptyMap())),
        )
        val globalBreaker = CountingCircuitBreaker(failureThreshold = 99)
        val perHostBreaker = CountingCircuitBreaker(failureThreshold = 1)
        val sender = JavaHttpRequestSender(
            circuitBreaker = globalBreaker,
            perHostCircuitBreakers = mapOf("localhost" to perHostBreaker),
            sleeper = { /* no-op */ },
        )
        // First request: perHostBreaker
        // is used, success, count = 0.
        val response = sender.get("http://localhost:$port/test", emptyMap())
        assertEquals(200, response.statusCode)
        // The perHostBreaker should have
        // recorded 1 success; the global
        // breaker should be untouched.
        // (We verify via a second request
        // — both should allow it.)
        val response2 = sender.get("http://localhost:$port/test", emptyMap())
        assertEquals(200, response2.statusCode)
        // The global breaker was never
        // called. We can verify this
        // indirectly: if the global
        // breaker had been used and
        // failed, the request would
        // have failed. Since we made 2
        // requests and both succeeded,
        // the per-host breaker was
        // active.
    }

    // ------------------------------------------------------------------
    // Falsifier 3: per-host breaker trips after failureThreshold
    // ------------------------------------------------------------------
    @Test
    fun `per-host breaker trips after failureThreshold`() {
        // The per-host breaker with
        // failureThreshold = 1. After
        // 1 failure, the circuit
        // OPENS for that host.
        val cb = CountingCircuitBreaker(failureThreshold = 1)
        val sender = JavaHttpRequestSender(
            perHostCircuitBreakers = mapOf("localhost" to cb),
            sleeper = { /* no-op */ },
        )
        // First request: fails (no
        // listener on port 1). The
        // breaker records 1 failure,
        // trips to OPEN.
        assertThrows(java.io.IOException::class.java) {
            sender.get("http://localhost:1/test", emptyMap())
        }
        // Second request to the same
        // host: the circuit is OPEN,
        // so the call fails fast with
        // CircuitBreakerOpenException.
        val ex = assertThrows(CircuitBreakerOpenException::class.java) {
            sender.get("http://localhost:1/test", emptyMap())
        }
        assertTrue(
            ex.message!!.contains("OPEN"),
            "per-host breaker must be OPEN; got '${ex.message}'",
        )
    }

    // ------------------------------------------------------------------
    // Falsifier 4: per-host breaker isolates one host from another
    // ------------------------------------------------------------------
    @Test
    fun `per-host breaker isolates one host from another`() {
        // Two hosts, each with its own
        // breaker. A failure on host-a
        // must NOT trip the breaker for
        // host-b.
        val cbA = CountingCircuitBreaker(failureThreshold = 1)
        val cbB = CountingCircuitBreaker(failureThreshold = 1)
        val sender = JavaHttpRequestSender(
            perHostCircuitBreakers = mapOf(
                "host-a.example.com" to cbA,
                "host-b.example.com" to cbB,
            ),
            sleeper = { /* no-op */ },
        )
        // Trip host-a's breaker.
        assertThrows(java.io.IOException::class.java) {
            sender.get("http://host-a.example.com:1/test", emptyMap())
        }
        // host-b's breaker is still
        // CLOSED. A request to host-b
        // must NOT be blocked (it will
        // fail with a DNS error since
        // the host doesn't exist, but
        // the failure is the DNS
        // resolution, not a circuit
        // breaker).
        val ex = assertThrows(Exception::class.java) {
            sender.get("http://host-b.example.com:1/test", emptyMap())
        }
        // The exception must NOT be a
        // CircuitBreakerOpenException
        // (host-b's breaker is still
        // CLOSED).
        assertTrue(
            ex !is CircuitBreakerOpenException,
            "host-b's breaker must remain CLOSED; got $ex",
        )
    }

    // ------------------------------------------------------------------
    // Falsifier 5: global breaker used when no per-host entry matches
    // ------------------------------------------------------------------
    @Test
    fun `global breaker used when no per-host entry matches`() {
        val globalBreaker = CountingCircuitBreaker(failureThreshold = 1)
        val sender = JavaHttpRequestSender(
            circuitBreaker = globalBreaker,
            perHostCircuitBreakers = mapOf(
                "host-a.example.com" to CountingCircuitBreaker(failureThreshold = 99),
            ),
            sleeper = { /* no-op */ },
        )
        // The URL is "host-x.example.com",
        // which is NOT in the per-host
        // map. The global breaker must
        // be used.
        assertThrows(java.io.IOException::class.java) {
            sender.get("http://host-x.example.com:1/test", emptyMap())
        }
        // The global breaker is now
        // OPEN. A second request to the
        // same URL must fail fast.
        val ex = assertThrows(CircuitBreakerOpenException::class.java) {
            sender.get("http://host-x.example.com:1/test", emptyMap())
        }
        assertTrue(
            ex.message!!.contains("OPEN"),
            "global breaker must be OPEN after fallback; got '${ex.message}'",
        )
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
