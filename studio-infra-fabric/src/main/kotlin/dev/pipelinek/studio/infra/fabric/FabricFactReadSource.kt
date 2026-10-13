@file:Suppress("TooManyFunctions")

package dev.pipelinek.studio.infra.fabric

import dev.pipelinek.studio.application.ports.EventCursor
import dev.pipelinek.studio.application.ports.EventPage
import dev.pipelinek.studio.application.ports.FactReadSource
import dev.pipelinek.studio.application.ports.LiveEvent
import dev.pipelinek.studio.domain.RunId
import io.jenkins.pipelinek.fabric.contracts.observation.FactCursor
import io.jenkins.pipelinek.fabric.contracts.observation.ProjectionFact

/**
 * SFW-H3.3: a [FactReadSource] that reads from a
 * Fabric server via the H2.2c+1 [FabricRunReadPort].
 * The remote-side counterpart of
 * [dev.pipelinek.studio.infra.sdk.LocalRunDbFactReadSource]
 * (H3.2).
 *
 * ## The seam in one paragraph
 *
 * A [dev.pipelinek.studio.application.ports.LiveEventStream]
 * (H3.1) is constructed with either a local source
 * (H3.2, reading from the SDK subprocess's `run.db`)
 * or a remote source (this class, reading from a
 * Fabric server). The polling impl does not know
 * which one is in play — it sees only the
 * [FactReadSource] contract. The two sources are
 * honest about the same four arms of [EventPage]
 * (More / End / Refused), and the H3.1 polling
 * impl is honest about what each one means.
 *
 * ## The cursor mapping
 *
 * The H2.2c+1 `FactCursor(sequence: Long)` IS the
 * H3.1 `EventCursor(sequence: Long)` at the type
 * level (different types, same shape). The mapping
 * in this class is identity: every
 * `EventCursor(sequence: N)` the polling impl
 * passes to [readFromCursor] is mapped to a
 * `FactCursor(sequence: N)` for the underlying
 * [FabricRunReadPort.events] call.
 *
 * ## The kind mapping
 *
 * The 10-arm [ProjectionFact] family is the
 * consumer's vocabulary (RunRegistered,
 * RunPhaseChanged, StageObserved, ...). This source
 * hands the consumer a `LiveEvent` with
 * `kind = fact::class.simpleName` (the variant's
 * discriminator) and `payload = emptyMap()`
 * (H3.3 does not extract variant-specific fields).
 * A consumer that wants the typed data uses the
 * [FabricRunReadPort.events] port directly OR a
 * future H3 unit's typed-events port.
 *
 * ## The four arms of [EventPage] in this source
 *
 * - [EventPage.More] with `events = []` and
 *   `nextCursor = from` when the underlying port
 *   returns [FabricEventsResult.Page] with
 *   `facts = []` and `complete = false` — the run
 *   is in flight; the polling impl keeps polling.
 * - [EventPage.More] with `events = []` and
 *   `nextCursor = from` when the underlying port
 *   returns [FabricEventsResult.Unknown] — the run
 *   was admitted but the binding-lookup missed OR
 *   Fabric has not yet observed it; the polling
 *   impl keeps polling (the conservative choice;
 *   a future H3 unit may add a `Stop(reason)` arm
 *   to [EventPage] for the case where the source
 *   definitively does not have the run).
 * - [EventPage.More] with `events = [..]` and
 *   `nextCursor = EventCursor(maxSequence)` when
 *   the underlying port returns
 *   [FabricEventsResult.Page] with `facts` non-empty
 *   — emit the events and poll again.
 * - [EventPage.End] with `events = []` when the
 *   underlying port returns
 *   [FabricEventsResult.Page] with `facts = []` and
 *   `complete = true` — Fabric has reached the end
 *   of the stream; the polling impl closes the
 *   flow exactly once.
 * - [EventPage.Refused] when the underlying port
 *   returns [FabricEventsResult.Refused] — the
 *   source cannot answer (transport failure,
 *   unmapped event, event version too new, etc.);
 *   the polling impl backs off; the consumer's
 *   run stays in flight (the H3.1 AAT:
 *   no-synthetic-state-transition).
 *
 * @param remoteReadPort the H2.2c+1 read port
 *   (production wires the real Fabric client; a
 *   unit test wires a `FakeFabricRunReadPort`).
 */
class FabricFactReadSource(
    private val remoteReadPort: FabricRunReadPort,
) : FactReadSource {

    override suspend fun readFromCursor(
        studioRunId: RunId,
        from: EventCursor,
        limit: Int,
    ): EventPage {
        val reply = remoteReadPort.events(
            studioRunId = studioRunId,
            after = FactCursor(from.sequence),
            limit = limit,
        )

        return when (reply) {
            is FabricEventsResult.Page -> {
                if (reply.facts.isNotEmpty()) {
                    val mapped: List<LiveEvent> = reply.facts.map(::mapFact)
                    EventPage.More(
                        events = mapped,
                        nextCursor = EventCursor(mapped.last().sequence),
                    )
                } else if (reply.complete) {
                    // Fabric has reached the end of the
                    // stream; no more events to emit.
                    EventPage.End(events = emptyList())
                } else {
                    // The run is in flight; poll again.
                    EventPage.More(events = emptyList(), nextCursor = from)
                }
            }
            is FabricEventsResult.Unknown ->
                // The run was admitted but the binding-lookup
                // missed OR Fabric has not yet observed it.
                // The conservative choice: keep polling.
                // A future H3 unit may add a `Stop(reason)`
                // arm to EventPage for the case where the
                // source definitively does not have the run.
                EventPage.More(events = emptyList(), nextCursor = from)
            is FabricEventsResult.Refused ->
                // The source cannot answer; the polling
                // impl backs off; the consumer's run stays
                // in flight (the H3.1 AAT:
                // no-synthetic-state-transition).
                EventPage.Refused(reason = reply.reason)
        }
    }

    private fun mapFact(fact: ProjectionFact): LiveEvent = LiveEvent(
        sequence = fact.sequence,
        kind = fact::class.simpleName ?: "Unknown",
        occurredAt = fact.observedAt,
        payload = emptyMap(),
    )
}
