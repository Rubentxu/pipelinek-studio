@file:Suppress("TooManyFunctions")

package dev.pipelinek.studio.infra.fabric

import dev.pipelinek.studio.application.ports.EventCursor
import dev.pipelinek.studio.application.ports.EventPage
import dev.pipelinek.studio.application.ports.LiveEvent
import dev.pipelinek.studio.domain.RunId
import io.jenkins.pipelinek.fabric.contracts.model.RunId as FabricRunId
import io.jenkins.pipelinek.fabric.contracts.model.RunOutcome
import io.jenkins.pipelinek.fabric.contracts.model.RunPhase
import io.jenkins.pipelinek.fabric.contracts.model.RunRegistration
import io.jenkins.pipelinek.fabric.contracts.observation.FactCursor
import io.jenkins.pipelinek.fabric.contracts.observation.FactOrigin
import io.jenkins.pipelinek.fabric.contracts.observation.ProjectionFact
import io.jenkins.pipelinek.fabric.contracts.observation.ProjectionFact.RunPhaseChanged
import io.jenkins.pipelinek.fabric.contracts.observation.ProjectionFact.RunRegistered
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * Falsifiers for [FabricFactReadSource] — the SFW-H3.3
 * remote-side [dev.pipelinek.studio.application.ports.FactReadSource]
 * that wraps the H2.2c+1 [FabricRunReadPort].
 *
 * The seam under test is a hand-built
 * [FakeFabricRunReadPort] (matching the H2.2c+1 contract
 * shape) + the H3.3 source. The tests pin the four arms
 * of [EventPage] in the remote source's vocabulary:
 *
 *  - **`More`** (events returned) for a `Page(facts, ...)`
 *    with non-empty facts.
 *  - **`End`** (empty) for a `Page(facts = [], complete = true)`
 *    — Fabric has reached the end of the stream.
 *  - **`More`** (empty) for a `Page(facts = [], complete = false)`
 *    — the run is in flight; poll again.
 *  - **`More`** (empty) for an `Unknown(reason)` — the run
 *    was admitted but not yet observed; poll again.
 *  - **`Refused`** for a `Refused(reason)` — the source
 *    cannot answer; the H3.1 polling impl backs off; the
 *    consumer's run stays in flight.
 *
 * Plus three structural assertions: the cursor mapping is
 * identity (`FactCursor(sequence: N) = EventCursor(sequence: N)`);
 * the limit passes through unchanged; the `observedAt`
 * Instant passes through unchanged (no clock drift, no
 * timezone conversion).
 */
@DisplayName("FabricFactReadSource (SFW-H3.3 remote-side FactReadSource)")
class FabricFactReadSourceTest {

    private val studioRunId = RunId("studio-run-h33-1")
    private val t1 = Instant.parse("2026-10-13T13:00:00Z")
    private val t2 = Instant.parse("2026-10-13T13:00:01Z")
    private val t3 = Instant.parse("2026-10-13T13:00:02Z")

    // ------------------------------------------------------------------
    // Falsifier 1: a Page with non-empty facts maps each
    // ProjectionFact to a LiveEvent with the variant's
    // class-name as kind.
    // ------------------------------------------------------------------
    @Test
    fun `a Page with non-empty facts maps each to a LiveEvent with the variant's class name as kind`() = runBlocking {
        val port = FakeFabricRunReadPort().apply {
            eventsPages.add(
                FabricEventsResult.Page(
                    facts = listOf(
                        runRegistered(1L, t1),
                        runPhaseChanged(2L, t2, RunPhase.RUNNING),
                        runConcluded(3L, t3),
                    ),
                    next = FactCursor(3L),
                    complete = true,
                    version = 1,
                ),
            )
        }
        val source = FabricFactReadSource(port)

        val page = source.readFromCursor(studioRunId, EventCursor(0), limit = 100)

        assertTrue(page is EventPage.More, "expected More, got $page")
        val more = page as EventPage.More
        assertEquals(3, more.events.size)
        // Sequences in order:
        assertEquals(listOf(1L, 2L, 3L), more.events.map { it.sequence })
        // Kinds are the variant class names:
        assertEquals(
            listOf("RunRegistered", "RunPhaseChanged", "RunConcluded"),
            more.events.map { it.kind },
        )
        // observedAt passes through unchanged:
        assertEquals(t1, more.events[0].occurredAt)
        assertEquals(t2, more.events[1].occurredAt)
        assertEquals(t3, more.events[2].occurredAt)
        // H3.3 ships empty payload (no typed extract per variant):
        assertTrue(more.events.all { it.payload.isEmpty() })
        // nextCursor is the max sequence:
        assertEquals(EventCursor(3L), more.nextCursor)
    }

    // ------------------------------------------------------------------
    // Falsifier 2: a Page with empty facts + complete = true
    // returns End (exactly-once end-of-stream).
    // ------------------------------------------------------------------
    @Test
    fun `a Page with empty facts and complete true returns End`() = runBlocking {
        val port = FakeFabricRunReadPort().apply {
            eventsPages.add(
                FabricEventsResult.Page(
                    facts = emptyList(),
                    next = FactCursor(5L),
                    complete = true,
                    version = 1,
                ),
            )
        }
        val source = FabricFactReadSource(port)

        val page = source.readFromCursor(studioRunId, EventCursor(5L), limit = 100)

        assertTrue(page is EventPage.End, "expected End, got $page")
        val end = page as EventPage.End
        assertEquals(emptyList<LiveEvent>(), end.events)
    }

    // ------------------------------------------------------------------
    // Falsifier 3: a Page with empty facts + complete = false
    // returns More empty (the run is in flight; poll again).
    // ------------------------------------------------------------------
    @Test
    fun `a Page with empty facts and complete false returns More empty - the run is in flight`() = runBlocking {
        val port = FakeFabricRunReadPort().apply {
            eventsPages.add(
                FabricEventsResult.Page(
                    facts = emptyList(),
                    next = FactCursor(5L),
                    complete = false,
                    version = 1,
                ),
            )
        }
        val source = FabricFactReadSource(port)

        val page = source.readFromCursor(studioRunId, EventCursor(5L), limit = 100)

        assertTrue(page is EventPage.More, "expected More, got $page")
        val more = page as EventPage.More
        assertEquals(emptyList<LiveEvent>(), more.events)
        // The cursor is unchanged:
        assertEquals(EventCursor(5L), more.nextCursor)
    }

    // ------------------------------------------------------------------
    // Falsifier 4: an Unknown returns More empty (the run was
    // admitted but not yet observed; poll again).
    // ------------------------------------------------------------------
    @Test
    fun `an Unknown returns More empty - the run was admitted but not yet observed`() = runBlocking {
        val port = FakeFabricRunReadPort().apply {
            eventsPages.add(
                FabricEventsResult.Unknown("no binding for studioRunId ${studioRunId.value}"),
            )
        }
        val source = FabricFactReadSource(port)

        val page = source.readFromCursor(studioRunId, EventCursor(0), limit = 100)

        assertTrue(page is EventPage.More, "expected More, got $page")
        val more = page as EventPage.More
        assertEquals(emptyList<LiveEvent>(), more.events)
        // The cursor is unchanged — polling continues.
        assertEquals(EventCursor(0L), more.nextCursor)
    }

    // ------------------------------------------------------------------
    // Falsifier 5: a Refused returns Refused (the source cannot
    // answer; the polling impl backs off).
    // ------------------------------------------------------------------
    @Test
    fun `a Refused returns Refused (the source cannot answer)`() = runBlocking {
        val port = FakeFabricRunReadPort().apply {
            eventsPages.add(
                FabricEventsResult.Refused("transport failure on ProjectionSource.readAfter: connection reset"),
            )
        }
        val source = FabricFactReadSource(port)

        val page = source.readFromCursor(studioRunId, EventCursor(0), limit = 100)

        assertTrue(page is EventPage.Refused, "expected Refused, got $page")
        val refused = page as EventPage.Refused
        assertTrue(
            refused.reason.contains("transport", ignoreCase = true),
            "Refused reason should reference transport: ${refused.reason}",
        )
    }

    // ------------------------------------------------------------------
    // Falsifier 6: the cursor mapping is identity — the
    // EventCursor(sequence: N) the source receives is mapped to
    // a FactCursor(sequence: N) on the wire (no offset, no
    // transformation).
    // ------------------------------------------------------------------
    @Test
    fun `the cursor mapping is identity - EventCursor sequence is passed to FactCursor unchanged`() = runBlocking {
        val port = FakeFabricRunReadPort().apply {
            eventsPages.add(FakeFabricRunReadPort.emptyPage(FactCursor(42L), complete = true))
        }
        val source = FabricFactReadSource(port)

        source.readFromCursor(studioRunId, EventCursor(42L), limit = 100)

        // The port received a FactCursor with the same
        // sequence as the EventCursor the source was
        // called with.
        assertEquals(1, port.eventsCalls.size)
        assertEquals(FactCursor(42L), port.eventsCalls[0].after)
    }

    // ------------------------------------------------------------------
    // Falsifier 7: the limit passes through unchanged.
    // ------------------------------------------------------------------
    @Test
    fun `the limit passes through to the port unchanged`() = runBlocking {
        val port = FakeFabricRunReadPort().apply {
            eventsPages.add(FakeFabricRunReadPort.emptyPage(FactCursor(0L), complete = true))
        }
        val source = FabricFactReadSource(port)

        source.readFromCursor(studioRunId, EventCursor(0L), limit = 73)

        assertEquals(1, port.eventsCalls.size)
        assertEquals(73, port.eventsCalls[0].limit)
    }

    // ------------------------------------------------------------------
    // Falsifier 8: the observedAt Instant passes through
    // unchanged (no clock drift, no timezone conversion).
    // ------------------------------------------------------------------
    @Test
    fun `the observedAt Instant passes through unchanged - no clock drift, no timezone conversion`() = runBlocking {
        val port = FakeFabricRunReadPort().apply {
            eventsPages.add(
                FabricEventsResult.Page(
                    facts = listOf(runRegistered(1L, t1)),
                    next = FactCursor(1L),
                    complete = false,
                    version = 1,
                ),
            )
        }
        val source = FabricFactReadSource(port)

        val page = source.readFromCursor(studioRunId, EventCursor(0L), limit = 100)

        val more = page as EventPage.More
        // Bit-exact equality — no Instant.ofEpochMilli,
        // no timezone shift, no clock drift.
        assertEquals(t1, more.events[0].occurredAt)
    }

    // ------------------------------------------------------------------
    // Test helpers — build real ProjectionFact variants so the
    // H3.3 source's `fact::class.simpleName` mapping has
    // something to discriminate against.
    // ------------------------------------------------------------------
    private fun runRegistered(seq: Long, at: Instant): ProjectionFact =
        RunRegistered(
            sequence = seq,
            observedAt = at,
            causedBy = null,
            registration = RunRegistration(
                displayName = "test",
                pipelineKey = "test",
                externalRefs = emptyList(),
            ),
            origin = FactOrigin.FabricOnly(fabricRunId = FabricRunId("fabric-run")),
        )

    private fun runPhaseChanged(seq: Long, at: Instant, phase: RunPhase): ProjectionFact =
        ProjectionFact.RunPhaseChanged(
            sequence = seq,
            observedAt = at,
            causedBy = null,
            phase = phase,
            origin = FactOrigin.FabricOnly(fabricRunId = FabricRunId("fabric-run")),
        )

    private fun runConcluded(seq: Long, at: Instant): ProjectionFact =
        ProjectionFact.RunConcluded(
            sequence = seq,
            observedAt = at,
            causedBy = null,
            outcome = RunOutcome.Success,
            origin = FactOrigin.FabricOnly(fabricRunId = FabricRunId("fabric-run")),
        )
}
