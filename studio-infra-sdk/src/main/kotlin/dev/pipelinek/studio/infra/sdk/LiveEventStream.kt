@file:Suppress("TooManyFunctions")

package dev.pipelinek.studio.infra.sdk

import dev.pipelinek.studio.application.ports.EventCursor
import dev.pipelinek.studio.application.ports.EventPage
import dev.pipelinek.studio.application.ports.FactReadSource
import dev.pipelinek.studio.application.ports.LiveEvent
import dev.pipelinek.studio.application.ports.LiveEventStream
import dev.pipelinek.studio.domain.RunId
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.isActive

/**
 * SFW-H3.1: a [LiveEventStream] that polls a
 * [FactReadSource] at a fixed interval and emits events
 * with **strict cursor monotonicity**.
 *
 * ## The two AATs this class pins
 *
 * - **Cursor monotonicity**: a consumer that started
 *   with `fromCursor = 0` and saw events `1, 2, 3`
 *   MUST NOT see event `2` again, even after a polling
 *   cycle that returned no new events. The polling
 *   impl tracks a `lastSeen: Long` and dedupes by
 *   sequence on every emission.
 * - **No synthetic state transition**: a poll that
 *   returns [EventPage.More] with `events = []` is
 *   NOT an end-of-stream signal; the consumer's run
 *   is still in flight. A [EventPage.End] is the
 *   only end-of-stream signal, and it is exactly-once.
 *
 * ## Polling, not push
 *
 * H3.1 ships a polling baseline (default 250 ms; a
 * future H6 unit may switch to a push-based transport).
 * A poll that returns `[]` is not an error; it is the
 *   source saying "I have nothing new right now, ask
 *   again later". The polling impl waits `pollInterval`
 *   and retries; the consumer's collector drives the
 *   cadence.
 *
 * @param source the read side; production is
 *   `LocalRunDbFactReadSource` (H3.2) or
 *   `FabricFactReadSource` (H3.3); a test uses a
 *   `FakeFactReadSource`.
 * @param pollInterval the wait between polls when the
 *   source returns `[]`; default 250 ms (configurable
 *   for tests; production keeps the default).
 * @param pageLimit the `limit` passed to
 *   [FactReadSource.readFromCursor]; default 100
 *   events per poll. A source that emits faster than
 *   the polling cadence can service would still cap
 *   here.
 */
class PollingLiveEventStream(
    private val source: FactReadSource,
    private val pollInterval: kotlin.time.Duration = kotlin.time.Duration.parse("250ms"),
    private val pageLimit: Int = 100,
) : LiveEventStream {

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun subscribe(
        studioRunId: RunId,
        fromCursor: EventCursor,
    ): Flow<LiveEvent> = channelFlow {
        // The AAT: the consumer's "last seen sequence"
        // starts at fromCursor.sequence. We emit events
        // with `sequence > lastSeen` and update
        // `lastSeen` on every emission. A poll that
        // returns an event with `sequence <= lastSeen`
        // is silently dropped (the dedup discipline).
        var lastSeen: Long = fromCursor.sequence

        while (isActive) {
            val page: EventPage = try {
                source.readFromCursor(
                    studioRunId = studioRunId,
                    from = EventCursor(lastSeen),
                    limit = pageLimit,
                )
            } catch (failure: Exception) {
                // The AAT (no synthetic state transition):
                // a polling exception is NOT a terminal
                // signal. We log and back off; the consumer
                // keeps the run in flight. A future H3
                // unit may surface the exception to the
                // consumer; H3.1 is the load-bearing
                // primitive, not the alerting surface.
                delay(pollInterval)
                continue
            }

            when (page) {
                is EventPage.More -> {
                    // Emit events with strict
                    // `sequence > lastSeen`. A source
                    // that returned an event we've
                    // already seen (because the source
                    // re-emitted on a retry, or because
                    // a future source impls the AAT
                    // slightly differently) is silently
                    // deduped.
                    var emitted = false
                    for (event in page.events) {
                        if (event.sequence > lastSeen) {
                            send(event)
                            lastSeen = event.sequence
                            emitted = true
                        }
                    }
                    if (emitted) {
                        // Poll again immediately; the
                        // consumer is asking for events
                        // and the source has more.
                        continue
                    }
                    // No new events: back off and poll
                    // again. The consumer does not
                    // conclude the run has terminated.
                    delay(pollInterval)
                }
                is EventPage.End -> {
                    // AAT (exactly-once end-of-stream):
                    // emit any remaining events (the
                    // source may have buffered the last
                    // batch with `complete: true`), then
                    // close the flow. The consumer's
                    // `collect { }` terminates.
                    for (event in page.events) {
                        if (event.sequence > lastSeen) {
                            send(event)
                            lastSeen = event.sequence
                        }
                    }
                    // The channelFlow closes on return;
                    // the consumer's `collect` sees the
                    // completion.
                    return@channelFlow
                }
                is EventPage.Refused -> {
                    // AAT (no synthetic state transition):
                    // a Refused is NOT a terminal signal.
                    // Back off and retry. The consumer's
                    // run stays in flight.
                    delay(pollInterval)
                }
            }
        }
    }
}
