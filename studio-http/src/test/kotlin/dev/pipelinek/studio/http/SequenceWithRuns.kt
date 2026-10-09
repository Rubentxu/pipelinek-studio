package dev.pipelinek.studio.http

import dev.pipelinek.studio.application.ports.EventCursor
import dev.pipelinek.studio.application.ports.RunEvent
import dev.pipelinek.studio.application.ports.RunReadPort
import dev.pipelinek.studio.application.ports.RunReadResult
import dev.pipelinek.studio.domain.RunId
import java.time.Instant

/**
 * Test-only ReadPort that emits a deterministic, bounded list of events and then exits.
 * Used by StudioHttpModuleTest to verify the SSE route shape.
 */
class SequenceWithRuns(private val events: List<RunEvent>) : RunReadPort {
    override fun events(runId: RunId, after: EventCursor?, limit: Int): RunReadResult {
        val afterSeq = after?.sequence ?: 0L
        val slice = events.dropWhile { it.sequence <= afterSeq }.take(limit.coerceAtLeast(1))
        val next = if (slice.size < limit || slice.isEmpty()) null else EventCursor(slice.last().sequence)
        return RunReadResult.Ok(events = slice, nextSequence = next)
    }

    override fun state(runId: RunId): RunReadResult =
        if (events.isEmpty()) RunReadResult.Unknown("no events")
        else RunReadResult.Ok(events = listOf(events.last()), nextSequence = null)
}

internal fun runReadList(): List<RunEvent> = listOf(
    RunEvent(1L, "CompilationStarted", Instant.parse("2026-10-09T20:00:00Z"), mapOf("stage" to "compile")),
    RunEvent(2L, "CompilationFinished", Instant.parse("2026-10-09T20:00:05Z"), mapOf("cacheKey" to "abc")),
)
