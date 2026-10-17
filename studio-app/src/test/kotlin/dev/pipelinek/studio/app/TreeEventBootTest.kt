package dev.pipelinek.studio.app

import org.junit.jupiter.api.Test
import java.net.ServerSocket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse.BodyHandlers
import java.nio.file.Files
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.test.*

class TreeEventBootTest {
    @Test
    fun `real app boot delivers created experiment to SSE without SDK execution`() {
        val temp = Files.createTempDirectory("studio-tree-boot-")
        val port = ServerSocket(0).use { it.localPort }
        val base = "http://127.0.0.1:$port"
        val process = ProcessBuilder(
            "${System.getProperty("java.home")}/bin/java",
            "-Dpipelinek.studio.runtime.dataDir=$temp",
            "-Dpipelinek.studio.runtime.port=$port",
            "-cp", System.getProperty("java.class.path"),
            "dev.pipelinek.studio.app.MainKt", "--insecure-http",
        ).redirectErrorStream(true).redirectOutput(temp.resolve("server.log").toFile()).start()
        try {
            val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).build()
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
            var ready = false
            while (System.nanoTime() < deadline && process.isAlive) {
                ready = runCatching {
                    http.send(HttpRequest.newBuilder(URI("$base/health")).timeout(Duration.ofSeconds(1)).build(), BodyHandlers.ofString()).statusCode() == 200
                }.getOrDefault(false)
                if (ready) break
                Thread.sleep(50)
            }
            assertTrue(ready, Files.readString(temp.resolve("server.log")))
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
                assertTrue(Files.isRegularFile(temp.resolve("studio.db")))
            }
        } finally {
            process.destroyForcibly()
            process.waitFor(10, TimeUnit.SECONDS)
            temp.toFile().deleteRecursively()
        }
    }
}
