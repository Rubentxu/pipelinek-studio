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
import java.util.concurrent.atomic.AtomicReference

/**
 * Falsifiers for the SFW-H5.3+ twenty-sixth
 * follow-up: User-Agent header.
 *
 * The 26th follow-up adds an optional
 * `userAgent: String?` parameter to
 * [JavaHttpRequestSender]. When set,
 * every request includes
 * `User-Agent: <value>`. When `null`
 * (the default), no User-Agent is
 * added (the JDK HttpClient may add
 * its own, e.g.,
 * `Java-http-client/17.0.5`).
 *
 * ## Why a User-Agent
 *
 * Registry operators can identify
 * Studio traffic in their logs when
 * the User-Agent includes a
 * distinctive string (e.g.,
 * `pipelinek-studio/1.0.0`).
 *
 * 5/5 falsifier tests verify the
 * User-Agent behavior.
 */
@DisplayName("JavaHttpRequestSender (SFW-H5.3+ User-Agent)")
class JavaHttpRequestSenderUserAgentH53Plus26Test {

    private val serverRef = AtomicReference<ServerSocket?>()
    private val serverThreadRef = AtomicReference<Thread?>()
    private val capturedUserAgent = AtomicReference<String?>(null)

    @AfterEach
    fun tearDown() {
        serverRef.get()?.close()
        serverThreadRef.get()?.interrupt()
    }

    // ------------------------------------------------------------------
    // Falsifier 1: default null User-Agent is backwards-compatible
    // ------------------------------------------------------------------
    @Test
    fun `default null User-Agent is backwards-compatible`() {
        val sender = JavaHttpRequestSender()
        assertNotNull(sender)
    }

    // ------------------------------------------------------------------
    // Falsifier 2: explicit null User-Agent is accepted
    // ------------------------------------------------------------------
    @Test
    fun `explicit null User-Agent is accepted (no User-Agent)`() {
        val sender = JavaHttpRequestSender(userAgent = null)
        assertNotNull(sender)
    }

    // ------------------------------------------------------------------
    // Falsifier 3: custom User-Agent is sent to the server
    // ------------------------------------------------------------------
    @Test
    fun `custom User-Agent is sent to the server`() {
        val port = startCapturingServer()
        val sender = JavaHttpRequestSender(
            userAgent = "pipelinek-studio/1.0.0",
        )
        val response = sender.get("http://localhost:$port/test", emptyMap())
        assertEquals(200, response.statusCode)
        assertEquals(
            "pipelinek-studio/1.0.0",
            capturedUserAgent.get(),
            "server must receive the custom User-Agent",
        )
    }

    // ------------------------------------------------------------------
    // Falsifier 4: no User-Agent when null (server doesn't see one)
    // ------------------------------------------------------------------
    @Test
    fun `no User-Agent is sent when null`() {
        val port = startCapturingServer()
        val sender = JavaHttpRequestSender(userAgent = null)
        sender.get("http://localhost:$port/test", emptyMap())
        val ua = capturedUserAgent.get()
        // The JDK HttpClient may add
        // its own User-Agent
        // (e.g., "Java-http-client/...").
        // The 26th follow-up just
        // verifies the custom one is
        // NOT set.
        assertTrue(
            ua == null || !ua.startsWith("pipelinek-studio/"),
            "no pipelinek-studio User-Agent must be sent; got '$ua'",
        )
    }

    // ------------------------------------------------------------------
    // Falsifier 5: User-Agent is sent on error responses too
    // ------------------------------------------------------------------
    @Test
    fun `User-Agent is sent on error responses too`() {
        val port = startCapturingServer(status = 503, body = "Service Unavailable")
        val sender = JavaHttpRequestSender(
            userAgent = "pipelinek-studio/1.0.0",
        )
        val response = sender.get("http://localhost:$port/test", emptyMap())
        assertEquals(503, response.statusCode)
        assertEquals(
            "pipelinek-studio/1.0.0",
            capturedUserAgent.get(),
            "User-Agent must be sent even on error responses",
        )
    }

    // --------------------------------------------------------------------
    // Test helpers
    // --------------------------------------------------------------------

    private fun startCapturingServer(status: Int = 200, body: String = "OK"): Int {
        capturedUserAgent.set(null)
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
                            // Read headers, look for User-Agent
                            while (true) {
                                val line = reader.readLine() ?: break
                                if (line.isEmpty()) break
                                if (line.startsWith("User-Agent:", ignoreCase = true)) {
                                    capturedUserAgent.set(line.substring("User-Agent:".length).trim())
                                }
                            }
                            val responseText = "HTTP/1.1 $status OK\r\n" +
                                "Content-Type: text/plain\r\n" +
                                "Content-Length: ${body.length}\r\n\r\n${body}"
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
