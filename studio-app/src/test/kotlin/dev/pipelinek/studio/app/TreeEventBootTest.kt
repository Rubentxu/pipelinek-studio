package dev.pipelinek.studio.app

import org.junit.jupiter.api.Test
import java.net.ServerSocket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse.BodyHandlers
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.test.*

class TreeEventBootTest {
    @Test
    fun `real app boot delivers created experiment to SSE without SDK execution`() {
        StudioAppProcess("tree-event-boot").use { app ->
            val port = ServerSocket(0).use { it.localPort }
            val base = "http://127.0.0.1:$port"
            app.start(
                jvmProperties = mapOf(
                    "pipelinek.studio.runtime.dataDir" to app.workDir.resolve("data").toString(),
                    "pipelinek.studio.runtime.port" to port.toString(),
                ),
                appArgs = listOf("--insecure-http"),
            )
            val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).build()
            app.await("plain HTTP /health on $base") {
                http.send(HttpRequest.newBuilder(URI("$base/health")).timeout(Duration.ofSeconds(1)).build(), BodyHandlers.ofString()).statusCode() == 200
            }
            val stream = http.send(
                HttpRequest.newBuilder(URI("$base/events")).timeout(Duration.ofSeconds(5)).build(),
                BodyHandlers.ofInputStream(),
            )
            stream.body().use { input ->
                assertEquals(200, stream.statusCode(), "production SSE must be wired")
                val reader = input.bufferedReader()
                assertTrue(reader.readLine().startsWith(": stream-open"))
                assertEquals("", reader.readLine())
                val response = http.send(
                    HttpRequest.newBuilder(URI("$base/experiments"))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString("""{"name":"boot-event"}"""))
                        .build(), BodyHandlers.ofString(),
                )
                assertEquals(200, response.statusCode(), response.body())
                val frame = CompletableFuture.supplyAsync {
                    buildString {
                        while (true) {
                            val line = reader.readLine() ?: break
                            if (line.isEmpty()) break
                            appendLine(line)
                        }
                    }
                }.get(2, TimeUnit.SECONDS)
                assertTrue(frame.contains("event: experiment.created"), frame)
                assertTrue(frame.contains("\"name\":\"boot-event\""), frame)
                assertTrue(Files.isRegularFile(app.workDir.resolve("data").resolve("studio.db")))
            }
        }
    }
}

/**
 * Boots the real `studio-app` entry point (`MainKt`) in a child JVM.
 *
 * Shared by the app-level acceptance tests that must observe the *process*
 * behavior (connector, defaults, data-dir expansion), not just an in-process
 * Ktor module. Both the app boot test and the TLS handshake test use it so
 * there is one boot fixture instead of one per test file.
 *
 * Scratch state lives under the module's ignored `build/operational-tls`
 * directory: a failed boot stays inspectable, the child never writes to the
 * operator's real home, and [close] removes the fixture. Waits are bounded
 * condition polls ([await]) — never a fixed sleep.
 */
internal class StudioAppProcess(name: String) : AutoCloseable {
    val workDir: Path = Path.of("build", "operational-tls", "$name-${System.nanoTime()}").toAbsolutePath()
    val homeDir: Path = Files.createDirectories(workDir.resolve("home"))
    private val logFile: Path = workDir.resolve("server.log")
    private var process: Process? = null

    /** The default data dir the app resolves when no override is supplied. */
    val defaultDataDir: Path get() = homeDir.resolve(".pipelinek-studio").resolve("runtime")

    val isAlive: Boolean get() = process?.isAlive == true

    fun start(jvmProperties: Map<String, String>, appArgs: List<String> = emptyList()): StudioAppProcess {
        Files.createDirectories(workDir)
        val command = buildList {
            add("${System.getProperty("java.home")}/bin/java")
            jvmProperties.forEach { (key, value) -> add("-D$key=$value") }
            addAll(listOf("-cp", System.getProperty("java.class.path"), "dev.pipelinek.studio.app.MainKt"))
            addAll(appArgs)
        }
        process = ProcessBuilder(command)
            .redirectErrorStream(true)
            .redirectOutput(logFile.toFile())
            .start()
        return this
    }

    fun log(): String = if (Files.isRegularFile(logFile)) Files.readString(logFile) else ""

    /**
     * Polls [condition] until it holds or [timeoutMs] elapses. The failure
     * message carries the child's log so a red run is diagnosable from the
     * report alone.
     */
    fun await(what: String, timeoutMs: Long = 20_000, condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        while (System.nanoTime() < deadline && isAlive) {
            if (runCatching(condition).getOrDefault(false)) return
            Thread.sleep(50)
        }
        fail("timed out after ${timeoutMs}ms waiting for $what (child alive=$isAlive); server log:\n${log()}")
    }

    override fun close() {
        process?.let {
            it.destroyForcibly()
            it.waitFor(10, TimeUnit.SECONDS)
        }
        workDir.toFile().deleteRecursively()
    }
}
