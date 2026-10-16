@file:Suppress("TooManyFunctions")

package dev.pipelinek.studio.infra.fabric

import dev.pipelinek.studio.domain.RunId
import io.jenkins.pipelinek.fabric.contracts.model.RunId as FabricRunId
import io.jenkins.pipelinek.fabric.contracts.model.RunState
import io.jenkins.pipelinek.fabric.contracts.model.RunSummary
import io.jenkins.pipelinek.fabric.contracts.observation.FactCursor
import io.jenkins.pipelinek.fabric.contracts.observation.ProjectionFact
import java.time.Instant

/**
 * A test-only [FabricRunReadPort] that records every
 * call and returns caller-configured answers.
 *
 * The H3.3 tests inject a [FakeFabricRunReadPort] and
 * configure the page sequence it returns on each
 * [events] call. The H3.3 source polls the port; the
 * tests assert the emitted [dev.pipelinek.studio.application.ports.EventPage].
 */
class FakeFabricRunReadPort : FabricRunReadPort {

    /** The page sequence the port returns on each [events] call. */
    val eventsPages: MutableList<FabricEventsResult> = mutableListOf()

    /** The [RunSummary] (or refusal) the port returns on each [summary] call. */
    val summaryAnswers: MutableList<FabricRunSummaryResult> = mutableListOf()

    /** Every [events] call the port received, in order. */
    val eventsCalls: MutableList<EventsCall> = mutableListOf()

    /** Every [summary] call the port received, in order. */
    val summaryCalls: MutableList<RunId> = mutableListOf()

    data class EventsCall(
        val studioRunId: RunId,
        val after: FactCursor,
        val limit: Int,
    )

    override suspend fun summary(studioRunId: RunId): FabricRunSummaryResult {
        summaryCalls.add(studioRunId)
        // Pop the next configured answer; default to a
        // stub `Found` when the queue is empty (the
        // H3.3 source never calls [summary]).
        return if (summaryAnswers.isNotEmpty()) summaryAnswers.removeAt(0)
        else FabricRunSummaryResult.Found(
            summary = RunSummary(
                runId = FabricRunId("stub-fabric-run"),
                displayName = "stub",
                state = RunState.Queued,
                startedAt = null,
                finishedAt = null,
            ),
        )
    }

    override suspend fun events(
        studioRunId: RunId,
        after: FactCursor,
        limit: Int,
    ): FabricEventsResult {
        eventsCalls.add(EventsCall(studioRunId, after, limit))
        // Pop the next configured page; default to
        // "complete = true with no facts" when the
        // queue is empty (the conservative default
        // for an unfinished test).
        return if (eventsPages.isNotEmpty()) eventsPages.removeAt(0)
        else FabricEventsResult.Page(
            facts = emptyList(),
            next = after,
            complete = true,
            version = 1,
        )
    }

    companion object {
        /**
         * Convenience: a [ProjectionFact]-shaped helper.
         * Tests use the real Fabric `ProjectionFact`
         * variants (e.g. `RunRegistered`,
         * `RunPhaseChanged`); this companion is a
         * placeholder for fixture-style helpers in
         * future H3 units that need a typed fact
         * without wiring the full Fabric vocabulary.
         */
        fun emptyPage(after: FactCursor, complete: Boolean): FabricEventsResult.Page =
            FabricEventsResult.Page(
                facts = emptyList(),
                next = after,
                complete = complete,
                version = 1,
            )
    }
}
