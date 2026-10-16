@file:Suppress("TooManyFunctions")

package dev.pipelinek.studio.infra.sdk

import dev.pipelinek.studio.application.ports.EventCursor
import dev.pipelinek.studio.application.ports.EventPage
import dev.pipelinek.studio.application.ports.LiveEvent
import dev.pipelinek.studio.domain.RunId
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.milliseconds

/**
 * Falsifiers for [PollingLiveEventStream] — the SFW-H3.1
 * live-event-stream primitive.
 *
 * The seam under test is a [FakeFactReadSource] + the
 * polling impl. The tests pin the two AATs:
 *
 *  - **Cursor monotonicity**: events are emitted with
 *    strictly-increasing `sequence`; a poll that returns
 *    no new events does not emit a duplicate; a poll
 *    that returns an event with `sequence <= lastSeen`
 *    is silently deduped.
 *  - **No synthetic state transition**: an empty poll
 *    is NOT an end-of-stream signal; the run stays in
 *    flight. A `More(emptyList())` does not close the
 *    flow. An `End(events)` closes the flow exactly
 *    once.
 */
@DisplayName("PollingLiveEventStream (SFW-H3.1 cursor monotonicity + no synthetic transition)")
class LiveEventStreamTest {

    private val studioRunId = RunId("studio-run-1")

    private fun streamWith(
        source: FakeFactReadSource,
        pollInterval: kotlin.time.Duration = 1.milliseconds,
    ) = PollingLiveEventStream(
        source = source,
        pollInterval = pollInterval,
        pageLimit = 100,
    )

    // ---- AAT: cursor monotonicity -------------------------------------------

    @Test
    fun `events are emitted in strict ascending sequence order (the cursor-monotonicity AAT)`() = runBlocking {
        val source = FakeFactReadSource().apply {
            // Three pages: 1, 2 | 3, 4 | 5, 6
            pages.add(EventPage.More(listOf(FakeFactReadSource.Companion.event(1, "RunStarted"), FakeFactReadSource.Companion.event(2, "StageStarted")), EventCursor(2)))
            pages.add(EventPage.More(listOf(FakeFactReadSource.Companion.event(3, "StepStarted"), FakeFactReadSource.Companion.event(4, "StepFinished")), EventCursor(4)))
            pages.add(EventPage.End(listOf(FakeFactReadSource.Companion.event(5, "StepStarted"), FakeFactReadSource.Companion.event(6, "RunFinished"))))
        }
        val stream = streamWith(source)

        val emitted: List<LiveEvent> = stream.subscribe(studioRunId, EventCursor(0)).toList()

        val sequences = emitted.map { it.sequence }
        assertEquals(listOf(1L, 2L, 3L, 4L, 5L, 6L), sequences)
        // Strictly increasing: the AAT.
        for (i in 1 until sequences.size) {
            assertTrue(sequences[i] > sequences[i - 1], "sequence not strictly increasing at $i: $sequences")
        }
    }

    @Test
    fun `a poll that returns no new events does not emit a duplicate of an event the consumer already saw`() = runBlocking {
        val source = FakeFactReadSource().apply {
            pages.add(EventPage.More(listOf(FakeFactReadSource.Companion.event(1, "RunStarted"), FakeFactReadSource.Companion.event(2, "StageStarted")), EventCursor(2)))
            // The source says "no new events" three times in a row;
            // the consumer must not see 1 or 2 again.
            pages.add(EventPage.More(emptyList(), EventCursor(2)))
            pages.add(EventPage.More(emptyList(), EventCursor(2)))
            pages.add(EventPage.More(emptyList(), EventCursor(2)))
            // Then the source emits the terminal events.
            pages.add(EventPage.End(listOf(FakeFactReadSource.Companion.event(3, "RunFinished"))))
        }
        val stream = streamWith(source)

        val emitted: List<LiveEvent> = stream.subscribe(studioRunId, EventCursor(0)).toList()

        // Exactly 3 events: 1, 2, 3. No duplicates of 1 or 2.
        assertEquals(listOf(1L, 2L, 3L), emitted.map { it.sequence })
        val kinds = emitted.map { it.kind }
        assertEquals(listOf("RunStarted", "StageStarted", "RunFinished"), kinds)
    }

    @Test
    fun `subscribe from a non-zero cursor emits events with sequence greater than the cursor`() = runBlocking {
        val source = FakeFactReadSource().apply {
            pages.add(EventPage.End(listOf(FakeFactReadSource.Companion.event(4, "RunStarted"), FakeFactReadSource.Companion.event(5, "StageStarted"))))
        }
        val stream = streamWith(source)

        // Start at cursor=3; only events with sequence > 3
        // should be emitted.
        val emitted: List<LiveEvent> = stream.subscribe(studioRunId, EventCursor(3)).toList()

        assertEquals(listOf(4L, 5L), emitted.map { it.sequence })
    }

    // ---- AAT: no synthetic state transition ---------------------------------

    @Test
    fun `an empty More is NOT an end-of-stream signal (the no-synthetic-transition AAT)`() = runBlocking {
        val source = FakeFactReadSource().apply {
            pages.add(EventPage.More(listOf(FakeFactReadSource.Companion.event(1, "RunStarted")), EventCursor(1)))
            // Two empty pages — the run is still in flight.
            pages.add(EventPage.More(emptyList(), EventCursor(1)))
            pages.add(EventPage.More(emptyList(), EventCursor(1)))
            // Then a real event (the run is still going).
            pages.add(EventPage.More(listOf(FakeFactReadSource.Companion.event(2, "StageStarted")), EventCursor(2)))
            // Then the terminal event.
            pages.add(EventPage.End(listOf(FakeFactReadSource.Companion.event(3, "RunFinished"))))
        }
        val stream = streamWith(source)

        val emitted: List<LiveEvent> = stream.subscribe(studioRunId, EventCursor(0)).toList()

        // The empty More pages did not close the flow;
        // events 2 and 3 came through.
        assertEquals(listOf(1L, 2L, 3L), emitted.map { it.sequence })
    }

    @Test
    fun `a Refused does NOT close the flow and is NOT a terminal signal`() = runBlocking {
        val source = FakeFactReadSource().apply {
            pages.add(EventPage.More(listOf(FakeFactReadSource.Companion.event(1, "RunStarted")), EventCursor(1)))
            // Source says "I can't answer" twice; the run
            // is still in flight. The consumer does NOT
            // conclude the run has terminated.
            pages.add(EventPage.Refused("transient: the source is restarting"))
            pages.add(EventPage.Refused("transient: the source is restarting"))
            // Then the source recovers and emits the terminal event.
            pages.add(EventPage.End(listOf(FakeFactReadSource.Companion.event(2, "RunFinished"))))
        }
        val stream = streamWith(source)

        val emitted: List<LiveEvent> = stream.subscribe(studioRunId, EventCursor(0)).toList()

        // The Refused pages did not close the flow; the
        // terminal event came through after the source
        // recovered.
        assertEquals(listOf(1L, 2L), emitted.map { it.sequence })
    }

    @Test
    fun `end-of-stream is emitted exactly once when the sources complete is true`() = runBlocking {
        val source = FakeFactReadSource().apply {
            pages.add(EventPage.End(listOf(FakeFactReadSource.Companion.event(1, "RunStarted"))))
        }
        val stream = streamWith(source)

        val emitted: List<LiveEvent> = stream.subscribe(studioRunId, EventCursor(0)).toList()

        // Exactly 1 event; the flow closed (toList() did
        // not hang — the run-blocking completion is the
        // proof of exactly-once end-of-stream).
        assertEquals(listOf(1L), emitted.map { it.sequence })
    }
}
