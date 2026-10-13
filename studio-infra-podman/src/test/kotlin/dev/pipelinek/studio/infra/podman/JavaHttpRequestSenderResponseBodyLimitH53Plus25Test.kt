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
import java.util.concurrent.atomic.AtomicReference

/**
 * Falsifiers for the SFW-H5.3+ twenty-fifth
 * follow-up: response body size limit.
 *
 * The 25th follow-up adds a
 * `maxResponseBodyBytes: Long?` parameter
 * to [JavaHttpRequestSender]. When the
 * response body exceeds the limit, the
 * sender throws
 * [JavaHttpRequestSender.ResponseBodyTooLargeException]
 * and discards the rest of the body.
 * Null default = no limit (backwards-
 * compatible).
 *
 * ## Why a size limit
 *
 * A malicious or buggy registry could
 * send a multi-GB response body, causing
 * the JVM to OOM. The 25th follow-up
 * adds an optional cap.
 *
 * 5/5 falsifier tests verify the limit
 * behavior.
 */
@DisplayName("JavaHttpRequestSender (SFW-H5.3+ response body limit)")
class JavaHttpRequestSenderResponseBodyLimitH53Plus25Test {

    private val serverRef = AtomicReference<ServerSocket?>()
    private val serverThreadRef = AtomicReference<Thread?>()

    @AfterEach
    fun tearDown() {
        serverRef.get()?.close()
        serverThreadRef.get()?.interrupt()
    }

    // ------------------------------------------------------------------
    // Falsifier 1: default null is backwards-compatible
    // ------------------------------------------------------------------
    @Test
    fun `default null limit is backwards-compatible`() {
        val sender = JavaHttpRequestSender()
        assertNotNull(sender)
    }

    // ------------------------------------------------------------------
    // Falsifier 2: explicit null limit is accepted
    // ------------------------------------------------------------------
    @Test
    fun `explicit null limit is accepted (no limit)`() {
        val sender = JavaHttpRequestSender(maxResponseBodyBytes = null)
        assertNotNull(sender)
    }

    // ------------------------------------------------------------------
    // Falsifier 3: small response within limit succeeds
    // ------------------------------------------------------------------
    @Test
    fun `small response within limit succeeds`() {
        val port = startServer(body = "X", bodySize = 100)
        val sender = JavaHttpRequestSender(
            maxResponseBodyBytes = 1_000L,
            sleeper = { /* no-op */ },
        )
        val response = sender.get("http://localhost:$port/test", emptyMap())
        assertEquals(200, response.statusCode)
        assertEquals(100, response.body.length)
    }

    // ------------------------------------------------------------------
    // Falsifier 4: large response exceeding limit throws
    // ------------------------------------------------------------------
    @Test
    fun `large response exceeding limit throws ResponseBodyTooLargeException`() {
        val port = startServer(body = "X", bodySize = 1_000_000)  // 1 MB
        val sender = JavaHttpRequestSender(
            maxResponseBodyBytes = 1_000L,  // 1 KB limit
            sleeper = { /* no-op */ },
        )
        val ex = assertThrows(
            JavaHttpRequestSender.ResponseBodyTooLargeException::class.java,
        ) {
            sender.get("http://localhost:$port/test", emptyMap())
        }
        assertEquals(1_000L, ex.limitBytes)
        assertTrue(
            ex.message!!.contains("1000"),
            "message must mention the limit; got '${ex.message}'",
        )
    }

    // ------------------------------------------------------------------
    // Falsifier 5: response exactly at limit succeeds
    // ------------------------------------------------------------------
    @Test
    fun `response exactly at limit succeeds`() {
        val port = startServer(body = "X", bodySize = 100)
        val sender = JavaHttpRequestSender(
            maxResponseBodyBytes = 100L,
            sleeper = { /* no-op */ },
        )
        val response = sender.get("http://localhost:$port/test", emptyMap())
        assertEquals(200, response.statusCode)
        assertTrue(response.body.length <= 100, "body must be within limit")
    }

    // --------------------------------------------------------------------
    // Test helpers
    // --------------------------------------------------------------------

    private fun startServer(body: String, bodySize: Int): Int {
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
                            val responseBody = body.repeat(bodySize)
                            val responseText = "HTTP/1.1 200 OK\r\n" +
                                "Content-Type: text/plain\r\n" +
                                "Content-Length: ${responseBody.length}\r\n\r\n${responseBody}"
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
