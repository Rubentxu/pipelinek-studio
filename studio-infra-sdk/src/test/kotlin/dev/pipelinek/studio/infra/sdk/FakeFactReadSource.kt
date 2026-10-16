@file:Suppress("TooManyFunctions")

package dev.pipelinek.studio.infra.sdk

import dev.pipelinek.studio.application.ports.EventCursor
import dev.pipelinek.studio.application.ports.EventPage
import dev.pipelinek.studio.application.ports.FactReadSource
import dev.pipelinek.studio.application.ports.LiveEvent
import dev.pipelinek.studio.domain.RunId
import java.time.Instant

/**
 * A test-only [FactReadSource] that records every call
 * and returns caller-configured answers.
 *
 * The H3.1 tests inject a [FakeFactReadSource] and
 * configure the page sequence it returns on each
 * `readFromCursor` call. The
 * [PollingLiveEventStream] polls the source; the
 * tests assert the emitted events.
 */
class FakeFactReadSource : FactReadSource {

    /**
     * The page sequence the source returns on each
     * call, in order. The first call returns
     * `pages[0]`, the second returns `pages[1]`, etc.
     * When the index runs out, the source returns
     * [EventPage.More] with an empty list (the
     * "no new events" path).
     */
    val pages: MutableList<EventPage> = mutableListOf()

    /** Every call the source received, in order. */
    val calls: MutableList<Call> = mutableListOf()

    /** If non-null, the source throws this on every call. */
    var throws: Throwable? = null

    data class Call(
        val studioRunId: RunId,
        val fromCursor: EventCursor,
        val limit: Int,
    )

    override suspend fun readFromCursor(
        studioRunId: RunId,
        from: EventCursor,
        limit: Int,
    ): EventPage {
        calls.add(Call(studioRunId, from, limit))
        throws?.let { throw it }
        // Pop the next page; default to "no new events"
        // when the index runs out.
        return if (pages.isNotEmpty()) pages.removeAt(0)
        else EventPage.More(events = emptyList(), nextCursor = from)
    }

    companion object {
        /**
         * Convenience: a [LiveEvent] with a default
         * `payload` (empty map) and an `occurredAt` of
         * the call's wall-clock time.
         */
        fun event(sequence: Long, kind: String): LiveEvent = LiveEvent(
            sequence = sequence,
            kind = kind,
            occurredAt = Instant.now(),
            payload = emptyMap(),
        )
    }
}
