@file:Suppress("TooManyFunctions")

package dev.pipelinek.studio.application.ports

import dev.pipelinek.studio.domain.RunId
import java.time.Instant

/**
 * SFW-H3.1: a single event in a run's live event stream.
 *
 * ## Why a `Map<String, String>` payload
 *
 * A flat map is the smallest surface that lets a
 * consumer read the field it cares about without
 * depending on the upstream's typed vocabulary. A
 * future H3 unit may add a typed variant that the
 * consumer can opt into; the flat shape is the
 * baseline that every consumer can render.
 */
data class LiveEvent(
    /**
     * The upstream-issued sequence number. **Strictly
     * increasing** across the stream; the AAT
     * (cursor monotonicity) is the structural guarantee
     * that no consumer ever sees an event with a
     * `sequence <= fromCursor`.
     */
    val sequence: Long,

    /**
     * The event kind (`RunStarted`, `StageStarted`,
     * `StepStarted`, `StepFinished`, `RunFinished`,
     * `RunCancelled`, `RunFailed`, ...). Best-effort
     * mapping to the upstream's vocabulary; a consumer
     * that wants a closed hierarchy is a later H3
     * concern.
     */
    val kind: String,

    /**
     * When the source saw it. Server-issued (the
     * source's clock, not the consumer's).
     */
    val occurredAt: Instant,

    /**
     * Best-effort structured payload. The H3 contract
     * says "a flat map; a consumer that wants typed
     * access uses the upstream's vocabulary".
     */
    val payload: Map<String, String>,
)

/**
 * The result of a single [FactReadSource.readFromCursor] call.
 *
 * Three arms:
 * - [More] — the source has more events; the consumer
 *   should poll again.
 * - [End] — the source has reached the end of the
 *   stream; the consumer should stop polling.
 *   **A consumer that receives [End] does not have to
 *   conclude the run has terminated** — the AAT
 *   (no-synthetic-state-transition) is the discipline
 *   here: an empty [More] is not an [End], and an
 *   [End] is exactly-once.
 * - [Refused] — the source could not answer; the
 *   consumer's reaction is its own (the
 *   `PollingLiveEventStream` does NOT flip a run to
 *   terminal on a Refused; the AAT).
 */
sealed interface EventPage {
    data class More(
        val events: List<LiveEvent>,
        val nextCursor: EventCursor,
    ) : EventPage

    data class End(
        val events: List<LiveEvent>,
    ) : EventPage

    data class Refused(val reason: String) : EventPage
}

/**
 * The read side the [dev.pipelinek.studio.infra.sdk.LiveEventStream]
 * polls. A single method, a single return type. The
 * production impls are out of scope for H3.1:
 *  - [dev.pipelinek.studio.infra.sdk.LocalRunDbFactReadSource]
 *    (H3.2) reads from the SDK subprocess's `run.db`.
 *  - [dev.pipelinek.studio.infra.fabric.FabricFactReadSource]
 *    (H3.3) wraps the H2.2c+1 `FabricRunReadPort.events`.
 *
 * H3.1 ships the seam: a test fixture implements
 * [FactReadSource] and feeds the polling impl. The
 * production impls are next iterations.
 *
 * The cursor shape (`EventCursor(sequence: Long)`) is
 * the same as Fabric's `FactCursor(sequence: Long)`; the
 * H3.3 source does the mapping at the boundary.
 */
interface FactReadSource {
    suspend fun readFromCursor(
        studioRunId: RunId,
        from: EventCursor,
        limit: Int,
    ): EventPage
}
