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
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * Falsifiers for the SFW-H5.3+ twenty-seventh
 * follow-up: observability metrics.
 *
 * The 27th follow-up introduces a
 * [HttpMetrics] port + [NoopHttpMetrics]
 * default + integration in
 * [JavaHttpRequestSender]. When the
 * `metrics` parameter is set, the
 * sender records every request
 * (host, status, duration, success).
 *
 * 5/5 falsifier tests verify the
 * integration.
 */
@DisplayName("JavaHttpRequestSender (SFW-H5.3+ observability metrics)")
class HttpMetricsH53Plus27Test {

    private val serverRef = AtomicReference<ServerSocket?>()
    private val serverThreadRef = AtomicReference<Thread?>()

    @AfterEach
    fun tearDown() {
        serverRef.get()?.close()
        serverThreadRef.get()?.interrupt()
    }

    // ------------------------------------------------------------------
    // Falsifier 1: no metrics (null) is backwards-compatible
    // ------------------------------------------------------------------
    @Test
    fun `no metrics (null) is backwards-compatible`() {
        val sender = JavaHttpRequestSender()
        assertNotNull(sender)
    }

    // ------------------------------------------------------------------
    // Falsifier 2: noop metrics is a no-op
    // ------------------------------------------------------------------
    @Test
    fun `noop metrics is a no-op`() {
        // NoopHttpMetrics.recordRequest
        // does nothing; verify it
        // doesn't throw.
        NoopHttpMetrics.recordRequest("host", 200, 100, true)
    }

    // ------------------------------------------------------------------
    // Falsifier 3: successful request records a success metric
    // ------------------------------------------------------------------
    @Test
    fun `successful request records a success metric`() {
        val recorded = CapturingMetrics()
        val port = startScriptedServer(HttpResponse(200, "OK", emptyMap()))
        val sender = JavaHttpRequestSender(
            metrics = recorded,
            sleeper = { /* no-op */ },
        )
        sender.get("http://localhost:$port/test", emptyMap())
        assertEquals(1, recorded.records.size, "metrics must record the request")
        val record = recorded.records[0]
        assertEquals("localhost", record.host)
        assertEquals(200, record.status)
        assertTrue(record.success, "success must be true for 2xx")
    }

    // ------------------------------------------------------------------
    // Falsifier 4: error response records a failure metric
    // ------------------------------------------------------------------
    @Test
    fun `error response records a failure metric`() {
        val recorded = CapturingMetrics()
        val port = startScriptedServer(HttpResponse(500, "Server Error", emptyMap()))
        val sender = JavaHttpRequestSender(
            metrics = recorded,
            sleeper = { /* no-op */ },
        )
        try {
            sender.get("http://localhost:$port/test", emptyMap())
        } catch (e: Exception) {
            // 500 is returned as a
            // successful response (the
            // sender doesn't throw on
            // 5xx by default); but the
            // metric should still
            // record it.
        }
        assertEquals(1, recorded.records.size)
        val record = recorded.records[0]
        assertEquals(500, record.status)
        assertTrue(!record.success, "success must be false for 5xx")
    }

    // ------------------------------------------------------------------
    // Falsifier 5: pre-response failure records a failure metric
    // ------------------------------------------------------------------
    @Test
    fun `pre-response failure records a failure metric`() {
        val recorded = CapturingMetrics()
        val sender = JavaHttpRequestSender(
            metrics = recorded,
            sleeper = { /* no-op */ },
        )
        // A non-listening port: the
        // connection fails before the
        // response is received.
        try {
            sender.get("http://localhost:1/test", emptyMap())
        } catch (e: Exception) {
            // expected
        }
        assertEquals(1, recorded.records.size, "metrics must record the failure")
        val record = recorded.records[0]
        assertEquals("localhost", record.host)
        assertEquals(-1, record.status, "status must be -1 for pre-response failure")
        assertTrue(!record.success, "success must be false for pre-response failure")
    }

    // --------------------------------------------------------------------
    // Test helpers
    // --------------------------------------------------------------------

    private data class CapturedRecord(
        val host: String,
        val status: Int,
        val durationMs: Long,
        val success: Boolean,
    )

    private class CapturingMetrics : HttpMetrics {
        val records = mutableListOf<CapturedRecord>()
        override fun recordRequest(host: String, status: Int, durationMs: Long, success: Boolean) {
            records.add(CapturedRecord(host, status, durationMs, success))
        }
    }

    private fun startScriptedServer(response: HttpResponse): Int {
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
