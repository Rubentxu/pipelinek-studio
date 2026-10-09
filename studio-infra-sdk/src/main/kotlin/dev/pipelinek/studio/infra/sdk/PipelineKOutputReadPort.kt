package dev.pipelinek.studio.infra.sdk

import dev.pipelinek.studio.application.ports.OutputChunk
import dev.pipelinek.studio.application.ports.OutputCursor
import dev.pipelinek.studio.application.ports.OutputReadPort
import dev.pipelinek.studio.application.ports.OutputReadResult
import dev.pipelinek.studio.domain.RunId
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path

/**
 * OutputReadPort backed by the captured stdout+stderr log file written by
 * [PipelineKRunner]. This is the **local Output Plane**; the upstream SDK
 * certificates `BLOCKED-BY-OBS` describe a richer streamed plane, but in the
 * S0 bootstrap we serve the bytes the subprocess actually emitted and tag
 * each cursor with a stream id so consumers can advance.
 *
 * R-04: reads raw bytes — never interprets them. Per DATA_MODEL_AND_PERSISTENCE.md
 * "Output Plane: NO es tabla de eventos", this port treats output as opaque bytes
 * with stable byte-level cursor (offset into the file).
 */
class PipelineKOutputReadPort(
    private val controlRootBase: Path,
) : OutputReadPort {

    private val log = LoggerFactory.getLogger(javaClass)

    override fun read(runId: RunId, cursor: OutputCursor?, byteBudget: Int): OutputReadResult {
        val logFile: Path = controlRootBase.resolve(runId.value).resolve("stdout.log")
        if (!Files.isRegularFile(logFile)) {
            return OutputReadResult.UnknownStream(
                "stdout.log not found at $logFile; subprocess may not have emitted yet",
            )
        }
        val size = Files.size(logFile)
        val startOffset = when (val c = cursor) {
            null -> 0L
            else -> parseOffset(c.cursor)
        }
        if (startOffset >= size) {
            return OutputReadResult.Ok(
                chunks = emptyList(),
                nextCursor = OutputCursor(size.toString(), streamId = "stdout"),
            )
        }
        val budget = byteBudget.coerceIn(1, 1024 * 1024)
        val readSize = (size - startOffset).coerceAtMost(budget.toLong()).toInt()
        if (readSize <= 0) {
            return OutputReadResult.Ok(
                chunks = emptyList(),
                nextCursor = OutputCursor(size.toString(), streamId = "stdout"),
            )
        }
        val bytes = ByteArray(readSize)
        Files.newInputStream(logFile).use { ins ->
            var skipped = 0L
            while (skipped < startOffset) {
                val n = ins.skip(startOffset - skipped)
                if (n <= 0) break
                skipped += n
            }
            var read = 0
            while (read < readSize) {
                val n = ins.read(bytes, read, readSize - read)
                if (n < 0) break
                read += n
            }
        }
        val newOffset = startOffset + bytes.size
        val eof = newOffset >= size
        return OutputReadResult.Ok(
            chunks = listOf(OutputChunk(streamId = "stdout", bytes = bytes, cursor = OutputCursor(newOffset.toString(), streamId = "stdout"), eof = eof)),
            nextCursor = OutputCursor(newOffset.toString(), streamId = "stdout"),
        )
    }

    private fun parseOffset(c: String): Long = try { c.toLong() } catch (_: Throwable) { 0L }
}
