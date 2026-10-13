@file:Suppress("TooManyFunctions")

package dev.pipelinek.studio.infra.fabric

import dev.pipelinek.studio.domain.RunId
import io.jenkins.pipelinek.fabric.contracts.model.RunId as FabricRunId
import io.jenkins.pipelinek.fabric.contracts.model.RunState
import io.jenkins.pipelinek.fabric.contracts.model.RunSummary
import io.jenkins.pipelinek.fabric.contracts.observation.FactCursor
import io.jenkins.pipelinek.fabric.contracts.observation.FactPage
import io.jenkins.pipelinek.fabric.contracts.observation.FactReadRefusal
import io.jenkins.pipelinek.fabric.contracts.observation.FactReadResult
import io.jenkins.pipelinek.fabric.contracts.observation.ProjectionFact
import io.jenkins.pipelinek.fabric.contracts.observation.RunListScope
import io.jenkins.pipelinek.fabric.contracts.observation.RunPageCursor
import io.jenkins.pipelinek.fabric.contracts.observation.RunReadRefusal
import io.jenkins.pipelinek.fabric.contracts.observation.RunSummaryResult
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Falsifiers for [FabricRunReadAdapter] — the second port of the
 * H2.2c contract (H2.2c+1).
 *
 * The seam under test is a [RunIndex] + [ProjectionSource]
 * (Fabric's read ports) + a [BindingLookup] (the studioRunId →
 * fabricRunId resolver H2.3 will back with SQLite). The tests
 * inject fakes so a unit test does not bring up a gRPC socket.
 *
 * What these tests pin:
 *  - the **summary translation** (Found → Found; the 8
 *    `RunReadRefusal` arms mapped to `Unknown` for the two
 *    "no record" arms and to `Refused` for the rest);
 *  - the **events translation** (Page → Page; the 5
 *    `FactReadRefusal` arms mapped to `Unknown` / `Refused`);
 *  - the **binding-lookup seam** (a `null` answer is the
 *    adapter's own `Unknown`, and no Fabric call is made);
 *  - the **transport-failure carve-out** (a `FabricTransportFailure`
 *    is `Refused`, not `Unknown` — same rule as H2.2c).
 */
@DisplayName("FabricRunReadAdapter (SFW-H2.2c+1 read port)")
class FabricRunReadAdapterTest {

    // ---- fixtures -------------------------------------------------------------

    private val studioRunId = RunId("studio-run-1")
    private val fabricRunId = FabricRunId("fabric-run-77")

    private fun bindingWith(map: Map<RunId, FabricRunId> = mapOf(studioRunId to fabricRunId)) =
        BindingLookup { id -> map[id] }

    // ---- summary: happy path --------------------------------------------------

    @Test
    fun `summary happy path is Found with the RunSummary Fabric returned`() = runBlocking {
        val summary = RunSummary(
            runId = fabricRunId,
            displayName = "studio:studio-run-1",
            state = RunState.Running,
            startedAt = java.time.Instant.parse("2026-10-13T10:00:00Z"),
            finishedAt = null,
        )
        val fakeIndex = FakeRunIndex().apply { summaryAnswer = RunSummaryResult.Found(summary) }
        val fakeProj = FakeProjectionSource()
        val adapter = FabricRunReadAdapter(fakeIndex, fakeProj, bindingWith())

        val result = adapter.summary(studioRunId)

        assertInstanceOf(FabricRunSummaryResult.Found::class.java, result)
        val found = result as FabricRunSummaryResult.Found
        assertEquals(summary, found.summary)
        assertEquals(1, fakeIndex.summaryCalls.size)
        assertEquals(fabricRunId, fakeIndex.summaryCalls.single())
    }

    // ---- summary: binding miss -----------------------------------------------

    @Test
    fun `summary with binding miss is Unknown and never calls RunIndex`() = runBlocking {
        val fakeIndex = FakeRunIndex()
        val fakeProj = FakeProjectionSource()
        val adapter = FabricRunReadAdapter(fakeIndex, fakeProj, bindingWith(mapOf()))  // empty

        val result = adapter.summary(studioRunId)

        assertInstanceOf(FabricRunSummaryResult.Unknown::class.java, result)
        val unknown = result as FabricRunSummaryResult.Unknown
        assertTrue(
            unknown.reason.contains("no binding for studioRunId"),
            "got: ${unknown.reason}",
        )
        // The seam: the adapter refuses BEFORE calling RunIndex.
        // A missing binding is a Studio-side gap (the run was never
        // submitted, or it was submitted to a different Fabric server).
        assertEquals(0, fakeIndex.summaryCalls.size)
    }

    // ---- summary: RunReadRefusal arms ----------------------------------------

    @Test
    fun `summary with UnknownRun refusal is Unknown`() = runBlocking {
        val fakeIndex = FakeRunIndex().apply {
            summaryAnswer = RunSummaryResult.Refused(
                RunReadRefusal.UnknownRun(fabricRunId),
            )
        }
        val adapter = FabricRunReadAdapter(fakeIndex, FakeProjectionSource(), bindingWith())

        val result = adapter.summary(studioRunId)

        assertInstanceOf(FabricRunSummaryResult.Unknown::class.java, result)
        assertTrue((result as FabricRunSummaryResult.Unknown).reason.contains("unknown run"))
    }

    @Test
    fun `summary with UnobservedRun refusal is Unknown`() = runBlocking {
        val fakeIndex = FakeRunIndex().apply {
            summaryAnswer = RunSummaryResult.Refused(
                RunReadRefusal.UnobservedRun(fabricRunId),
            )
        }
        val adapter = FabricRunReadAdapter(fakeIndex, FakeProjectionSource(), bindingWith())

        val result = adapter.summary(studioRunId)

        assertInstanceOf(FabricRunSummaryResult.Unknown::class.java, result)
        val unknown = result as FabricRunSummaryResult.Unknown
        assertTrue(
            unknown.reason.contains("unobserved run") && unknown.reason.contains("admitted but not yet observed"),
            "got: ${unknown.reason}",
        )
    }

    @Test
    fun `summary with IncompleteObservation refusal is Refused`() = runBlocking {
        val fakeIndex = FakeRunIndex().apply {
            summaryAnswer = RunSummaryResult.Refused(
                RunReadRefusal.IncompleteObservation(fabricRunId, "the projection stopped at stage 7"),
            )
        }
        val adapter = FabricRunReadAdapter(fakeIndex, FakeProjectionSource(), bindingWith())

        val result = adapter.summary(studioRunId)

        assertInstanceOf(FabricRunSummaryResult.Refused::class.java, result)
        val refused = result as FabricRunSummaryResult.Refused
        assertTrue(
            refused.reason.contains("incomplete observation") &&
                refused.reason.contains("the projection stopped at stage 7"),
            "got: ${refused.reason}",
        )
    }

    @Test
    fun `summary with ReadBudgetExhausted refusal is Refused`() = runBlocking {
        val fakeIndex = FakeRunIndex().apply {
            summaryAnswer = RunSummaryResult.Refused(
                RunReadRefusal.ReadBudgetExhausted(fabricRunId, reads = 47),
            )
        }
        val adapter = FabricRunReadAdapter(fakeIndex, FakeProjectionSource(), bindingWith())

        val result = adapter.summary(studioRunId)

        assertInstanceOf(FabricRunSummaryResult.Refused::class.java, result)
        val refused = result as FabricRunSummaryResult.Refused
        assertTrue(
            refused.reason.contains("read budget exhausted") && refused.reason.contains("47"),
            "got: ${refused.reason}",
        )
    }

    // ---- summary: transport carve-out ----------------------------------------

    @Test
    fun `summary with FabricTransportFailure is Refused, not Unknown`() = runBlocking {
        val fakeIndex = FakeRunIndex().apply {
            summaryThrows = FabricTransportFailure(
                callName = "RunIndex.summaryOf",
                message = "connection refused: no server answered",
            )
        }
        val adapter = FabricRunReadAdapter(fakeIndex, FakeProjectionSource(), bindingWith())

        val result = adapter.summary(studioRunId)

        // The seam: a transport failure is a caller-visible Refused,
        // not Unknown. See FabricTransportFailure KDoc.
        assertInstanceOf(FabricRunSummaryResult.Refused::class.java, result)
        val refused = result as FabricRunSummaryResult.Refused
        assertTrue(
            refused.reason.contains("transport failure") &&
                refused.reason.contains("RunIndex.summaryOf"),
            "got: ${refused.reason}",
        )
    }

    // ---- events: happy path ---------------------------------------------------

    @Test
    fun `events happy path is Page with facts and cursor`() = runBlocking {
        // ProjectionFact has 10 sealed variants; we leave the list
        // empty here (an empty page is a positive statement under
        // KL-008, not an error). The shape assertion is the
        // structural one below.
        val page = FactPage(
            runId = fabricRunId,
            facts = emptyList<ProjectionFact>(),
            next = FactCursor(42L),
            complete = true,
            version = 1,
        )
        val fakeProj = FakeProjectionSource().apply {
            readAfterAnswer = FactReadResult.Page(page)
        }
        val adapter = FabricRunReadAdapter(FakeRunIndex(), fakeProj, bindingWith())

        val result = adapter.events(studioRunId, after = FactCursor.NONE, limit = 100)

        assertInstanceOf(FabricEventsResult.Page::class.java, result)
        val p = result as FabricEventsResult.Page
        assertEquals(page.facts, p.facts)
        assertEquals(page.next, p.next)
        assertEquals(page.complete, p.complete)
        assertEquals(page.version, p.version)
        // Translation: the request carried the fabricRunId, not the
        // studioRunId, and the cursor / limit were passed through.
        val sent = fakeProj.readAfterCalls.single()
        assertEquals(fabricRunId, sent.runId)
        assertEquals(FactCursor.NONE, sent.after)
        assertEquals(100, sent.limit)
    }

    // ---- events: binding miss -------------------------------------------------

    @Test
    fun `events with binding miss is Unknown and never calls ProjectionSource`() = runBlocking {
        val fakeProj = FakeProjectionSource()
        val adapter = FabricRunReadAdapter(FakeRunIndex(), fakeProj, bindingWith(mapOf()))

        val result = adapter.events(studioRunId, after = FactCursor.NONE, limit = 100)

        assertInstanceOf(FabricEventsResult.Unknown::class.java, result)
        assertTrue(
            (result as FabricEventsResult.Unknown).reason.contains("no binding for studioRunId"),
        )
        assertEquals(0, fakeProj.readAfterCalls.size)
    }

    // ---- events: FactReadRefusal arms ----------------------------------------

    @Test
    fun `events with UnknownRun refusal is Unknown`() = runBlocking {
        val fakeProj = FakeProjectionSource().apply {
            readAfterAnswer = FactReadResult.Refused(FactReadRefusal.UnknownRun(fabricRunId))
        }
        val adapter = FabricRunReadAdapter(FakeRunIndex(), fakeProj, bindingWith())

        val result = adapter.events(studioRunId, after = FactCursor.NONE, limit = 100)

        assertInstanceOf(FabricEventsResult.Unknown::class.java, result)
        assertTrue((result as FabricEventsResult.Unknown).reason.contains("unknown run"))
    }

    @Test
    fun `events with CursorBeforeStart refusal is Refused`() = runBlocking {
        val fakeProj = FakeProjectionSource().apply {
            readAfterAnswer = FactReadResult.Refused(
                FactReadRefusal.CursorBeforeStart(
                    after = FactCursor(-1L),
                    firstSequence = 0L,
                ),
            )
        }
        val adapter = FabricRunReadAdapter(FakeRunIndex(), fakeProj, bindingWith())

        val result = adapter.events(studioRunId, after = FactCursor.NONE, limit = 100)

        assertInstanceOf(FabricEventsResult.Refused::class.java, result)
        val refused = result as FabricEventsResult.Refused
        assertTrue(
            refused.reason.contains("cursor before start") &&
                refused.reason.contains("after=-1") && refused.reason.contains("firstSequence=0"),
            "got: ${refused.reason}",
        )
    }

    @Test
    fun `events with EventVersionTooNew refusal is Refused`() = runBlocking {
        val fakeProj = FakeProjectionSource().apply {
            readAfterAnswer = FactReadResult.Refused(
                FactReadRefusal.EventVersionTooNew(
                    runId = fabricRunId,
                    after = FactCursor.NONE,
                    eventVersion = 5,
                    kind = "RunStarted",
                ),
            )
        }
        val adapter = FabricRunReadAdapter(FakeRunIndex(), fakeProj, bindingWith())

        val result = adapter.events(studioRunId, after = FactCursor.NONE, limit = 100)

        assertInstanceOf(FabricEventsResult.Refused::class.java, result)
        val refused = result as FabricEventsResult.Refused
        assertTrue(
            refused.reason.contains("event version too new") &&
                refused.reason.contains("eventVersion=5") && refused.reason.contains("RunStarted"),
            "got: ${refused.reason}",
        )
    }

    // ---- events: transport carve-out ----------------------------------------

    @Test
    fun `events with FabricTransportFailure is Refused`() = runBlocking {
        val fakeProj = FakeProjectionSource().apply {
            readAfterThrows = FabricTransportFailure(
                callName = "ProjectionSource.readAfter",
                message = "deadline exceeded after 30000 ms",
            )
        }
        val adapter = FabricRunReadAdapter(FakeRunIndex(), fakeProj, bindingWith())

        val result = adapter.events(studioRunId, after = FactCursor.NONE, limit = 100)

        assertInstanceOf(FabricEventsResult.Refused::class.java, result)
        val refused = result as FabricEventsResult.Refused
        assertTrue(
            refused.reason.contains("transport failure") &&
                refused.reason.contains("ProjectionSource.readAfter"),
            "got: ${refused.reason}",
        )
    }
}
