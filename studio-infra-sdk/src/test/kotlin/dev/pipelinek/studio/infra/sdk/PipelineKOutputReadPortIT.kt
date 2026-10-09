package dev.pipelinek.studio.infra.sdk

import dev.pipelinek.studio.application.ports.OutputCursor
import dev.pipelinek.studio.application.ports.OutputReadResult
import dev.pipelinek.studio.domain.RunId
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class PipelineKOutputReadPortIT {

    @Test
    fun `returns UnknownStream when stdout log missing`(@TempDir tmp: Path) {
        val port = PipelineKOutputReadPort(tmp)
        val res = port.read(RunId.new(), null, 4096)
        assertTrue(res is OutputReadResult.UnknownStream, "got $res")
    }

    @Test
    fun `reads bytes and advances cursor`(@TempDir tmp: Path) {
        val runId = RunId.new()
        val runDir = tmp.resolve(runId.value)
        Files.createDirectories(runDir)
        val log = runDir.resolve("stdout.log")
        // 1000-byte payload so a 64-byte budget does NOT exhaust EOF in one chunk.
        Files.writeString(log, "x".repeat(1000))
        val port = PipelineKOutputReadPort(tmp)

        val first: OutputReadResult = port.read(runId, null, 64)
        assertTrue(first is OutputReadResult.Ok)
        val ok = first as OutputReadResult.Ok
        assertEquals(1, ok.chunks.size)
        val chunk = ok.chunks[0]
        assertEquals("stdout", chunk.streamId)
        assertEquals(64, chunk.bytes.size)
        // Cursor advanced to current byte offset (64) and we have not reached EOF (1000).
        assertEquals(64L, chunk.cursor.cursor.toLong())
        assertEquals(false, chunk.eof)
        // Next cursor allows continuation
        val next = port.read(runId, chunk.cursor, 64) as OutputReadResult.Ok
        assertEquals(64, next.chunks[0].bytes.size)
        assertEquals(128L, next.chunks[0].cursor.cursor.toLong())
    }

    @Test
    fun `EOF when cursor reaches end of file`(@TempDir tmp: Path) {
        val runId = RunId.new()
        val runDir = tmp.resolve(runId.value)
        Files.createDirectories(runDir)
        val log = runDir.resolve("stdout.log")
        Files.writeString(log, "tiny")
        val port = PipelineKOutputReadPort(tmp)
        val res = port.read(runId, OutputCursor("4", "stdout"), 1024)
        assertTrue(res is OutputReadResult.Ok)
        val ok = res as OutputReadResult.Ok
        assertEquals(0, ok.chunks.size)
        assertEquals("4", ok.nextCursor.cursor)
    }

    @Test
    fun `byte budget honored`(@TempDir tmp: Path) {
        val runId = RunId.new()
        val runDir = tmp.resolve(runId.value)
        Files.createDirectories(runDir)
        val log = runDir.resolve("stdout.log")
        val payload = "x".repeat(10_000)
        Files.writeString(log, payload)
        val port = PipelineKOutputReadPort(tmp)
        val res = port.read(runId, null, 100)
        assertTrue(res is OutputReadResult.Ok)
        val ok = res as OutputReadResult.Ok
        assertEquals(1, ok.chunks.size)
        assertEquals(100, ok.chunks[0].bytes.size)
    }
}
