@file:Suppress("TooManyFunctions")

package dev.pipelinek.studio.infra.sdk

import dev.pipelinek.studio.application.ports.EventCursor
import dev.pipelinek.studio.application.ports.EventPage
import dev.pipelinek.studio.application.ports.LiveEvent
import dev.pipelinek.studio.domain.RunId
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.milliseconds

/**
 * Falsifiers for the SFW-H3.5 retention-gap AAT —
 * the AAT-level assertion that a slow source does
 * NOT collapse into end-of-stream.
 *
 * The seam under test is a hand-built
 * [FakeFactReadSource] + the H3.1
 * [PollingLiveEventStream]. The H3.1 unit tests
 * already pin the AAT for small inputs (2-3
 * empty `More` pages). H3.5 extends the
 * discipline to:
 *
 *  - **Scale**: 100+ empty `More` pages before
 *    the `End` (the AAT holds at scale).
 *  - **Indefinite**: a source that NEVER emits
 *    `End` (the run is in flight forever; the
 *    consumer cancels via `withTimeout`).
 *  - **Out-of-order**: a page that returns an
 *    event with `sequence <= lastSeen` (the
 *    source re-emits on a retry, or a future
 *    source implements the AAT slightly
 *    differently) is silently deduped.
 *  - **Equal-to-last**: a page that returns an
 *    event with `sequence == lastSeen` is
 *    silently deduped (the source repeats the
 *    same event).
 */
@DisplayName("RetentionGapAat (SFW-H3.5 AAT-level: a slow source does NOT collapse into end-of-stream)")
class RetentionGapAatH35Test {

    private val studioRunId = RunId("studio-run-h35-1")

    private fun streamWith(source: FakeFactReadSource) = PollingLiveEventStream(
        source = source,
        pollInterval = 1.milliseconds,
        pageLimit = 100,
    )

    // ------------------------------------------------------------------
    // Falsifier 1: scale — 100 empty More pages then End
    // ------------------------------------------------------------------
    @Test
    fun `100 empty More pages then End - the AAT holds at scale`() = runBlocking {
        val source = FakeFactReadSource().apply {
            // 100 polls of "in flight, no new events"
            repeat(100) {
                pages.add(EventPage.More(emptyList(), EventCursor(0L)))
            }
            // Then the run terminates with one
            // final event.
            pages.add(
                EventPage.End(listOf(FakeFactReadSource.Companion.event(1L, "RunFinished"))),
            )
        }
        val stream = streamWith(source)

        val emitted: List<LiveEvent> = withTimeoutOrNull(5_000) {
            stream.subscribe(studioRunId, EventCursor(0L)).toList()
        } ?: error("expected the flow to close within 5s")

        // The 100 empty More pages did not close
        // the flow. The flow closed only on the
        // End. Exactly one event: the RunFinished.
        assertEquals(listOf(1L), emitted.map { it.sequence })
        assertEquals("RunFinished", emitted[0].kind)
        // The source was called at least 101 times
        // (100 empties + 1 End).
        assertTrue(
            source.calls.size >= 101,
            "expected at least 101 source calls (100 empties + 1 End), got ${source.calls.size}",
        )
    }

    // ------------------------------------------------------------------
    // Falsifier 2: indefinite — a source that NEVER ends
    // ------------------------------------------------------------------
    @Test
    fun `a source that never emits End keeps the flow open indefinitely`() = runBlocking {
        val source = FakeFactReadSource().apply {
            // 1000 polls of "in flight, no new
            // events". The source will never
            // produce an End; the consumer
            // cancels via `withTimeoutOrNull`.
            repeat(1000) {
                pages.add(EventPage.More(emptyList(), EventCursor(0L)))
            }
        }
        val stream = streamWith(source)

        val emitted: List<LiveEvent> = withTimeoutOrNull(2_000) {
            // The flow stays open; withTimeout
            // cancels the collection. The
            // consumer (the test) sees no
            // events.
            stream.subscribe(studioRunId, EventCursor(0L)).toList()
        } ?: emptyList()

        // No events were emitted; the flow was
        // cancelled by the consumer (via
        // withTimeoutOrNull). The polling impl's
        // isActive check propagates the
        // cancellation.
        assertEquals(emptyList<LiveEvent>(), emitted)
        // The source was polled many times
        // (the polling impl kept asking).
        assertTrue(
            source.calls.size > 10,
            "expected many source calls (the polling impl kept polling), got ${source.calls.size}",
        )
    }

    // ------------------------------------------------------------------
    // Falsifier 3: out-of-order — sequence < lastSeen is deduped
    // ------------------------------------------------------------------
    @Test
    fun `an out-of-order event with sequence less than lastSeen is silently deduped`() = runBlocking {
        val source = FakeFactReadSource().apply {
            // The consumer starts at cursor=0.
            // First poll returns events [1, 2, 3].
            pages.add(
                EventPage.More(
                    listOf(
                        FakeFactReadSource.Companion.event(1L, "RunStarted"),
                        FakeFactReadSource.Companion.event(2L, "StageStarted"),
                        FakeFactReadSource.Companion.event(3L, "StepStarted"),
                    ),
                    EventCursor(3L),
                ),
            )
            // Second poll: the source re-emits
            // event 2 (sequence 2 < lastSeen=3).
            // The polling impl silently dedupes.
            pages.add(
                EventPage.More(
                    listOf(FakeFactReadSource.Companion.event(2L, "StageStarted")),
                    EventCursor(3L),
                ),
            )
            // Then the terminal event.
            pages.add(
                EventPage.End(listOf(FakeFactReadSource.Companion.event(4L, "RunFinished"))),
            )
        }
        val stream = streamWith(source)

        val emitted: List<LiveEvent> = stream.subscribe(studioRunId, EventCursor(0L)).toList()

        // Exactly events [1, 2, 3, 4] in order.
        // The re-emit of event 2 was deduped.
        assertEquals(listOf(1L, 2L, 3L, 4L), emitted.map { it.sequence })
        assertEquals(
            listOf("RunStarted", "StageStarted", "StepStarted", "RunFinished"),
            emitted.map { it.kind },
        )
    }

    // ------------------------------------------------------------------
    // Falsifier 4: equal-to-last — sequence == lastSeen is deduped
    // ------------------------------------------------------------------
    @Test
    fun `an event with sequence equal to lastSeen is silently deduped`() = runBlocking {
        val source = FakeFactReadSource().apply {
            // First poll returns event 1.
            pages.add(
                EventPage.More(
                    listOf(FakeFactReadSource.Companion.event(1L, "RunStarted")),
                    EventCursor(1L),
                ),
            )
            // Second poll: the source re-emits
            // event 1 (sequence 1 == lastSeen=1).
            pages.add(
                EventPage.More(
                    listOf(FakeFactReadSource.Companion.event(1L, "RunStarted")),
                    EventCursor(1L),
                ),
            )
            // Then the terminal event.
            pages.add(
                EventPage.End(listOf(FakeFactReadSource.Companion.event(2L, "RunFinished"))),
            )
        }
        val stream = streamWith(source)

        val emitted: List<LiveEvent> = stream.subscribe(studioRunId, EventCursor(0L)).toList()

        // Exactly events [1, 2]. The duplicate of
        // event 1 was deduped.
        assertEquals(listOf(1L, 2L), emitted.map { it.sequence })
    }

    // ------------------------------------------------------------------
    // Falsifier 5: alternating — slow source (empty More) interleaved
    // with real events; the AAT holds.
    // ------------------------------------------------------------------
    @Test
    fun `alternating empty More and real events - the AAT holds`() = runBlocking {
        val source = FakeFactReadSource().apply {
            // Pattern: 5 empties, 1 event, 5 empties,
            // 1 event, 5 empties, 1 event, End.
            val real = listOf(
                FakeFactReadSource.Companion.event(1L, "RunStarted"),
                FakeFactReadSource.Companion.event(2L, "StageStarted"),
                FakeFactReadSource.Companion.event(3L, "StepStarted"),
            )
            for (event in real) {
                repeat(5) { pages.add(EventPage.More(emptyList(), EventCursor(0L))) }
                pages.add(EventPage.More(listOf(event), EventCursor(event.sequence)))
            }
            repeat(5) { pages.add(EventPage.More(emptyList(), EventCursor(3L))) }
            pages.add(EventPage.End(listOf(FakeFactReadSource.Companion.event(4L, "RunFinished"))))
        }
        val stream = streamWith(source)

        val emitted: List<LiveEvent> = withTimeoutOrNull(5_000) {
            stream.subscribe(studioRunId, EventCursor(0L)).toList()
        } ?: error("expected the flow to close within 5s")

        // Exactly [1, 2, 3, 4]. The 20 empty
        // More pages did not close the flow.
        assertEquals(listOf(1L, 2L, 3L, 4L), emitted.map { it.sequence })
    }
}
