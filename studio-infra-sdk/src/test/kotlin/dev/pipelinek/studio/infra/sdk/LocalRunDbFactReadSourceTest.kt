@file:Suppress("TooManyFunctions")

package dev.pipelinek.studio.infra.sdk

import dev.pipelinek.studio.application.ports.EventCursor
import dev.pipelinek.studio.application.ports.EventPage
import dev.pipelinek.studio.application.ports.LiveEvent
import dev.pipelinek.studio.domain.RunId
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.sql.DriverManager

/**
 * Falsifiers for [LocalRunDbFactReadSource] — the
 * SFW-H3.2 local-side [dev.pipelinek.studio.application.ports.FactReadSource]
 * that reads from the SDK subprocess's `run.db`.
 *
 * The seam under test is a hand-built `run.db`
 * (matching the SDK's pinned schema) + the source.
 * The tests pin the local source's three arms of
 * [EventPage]:
 *
 *  - **`More`** (events returned) for a populated
 *    `run.db` queried with a cursor that has more
 *    events to give.
 *  - **`More`** (empty) for a `run.db` that does not
 *    yet exist (the run was admitted but the SDK has
 *    not flushed) or for a queried-from cursor that is
 *    past the last event but the run is not yet
 *    terminated.
 *  - **`End`** (empty) for a `run.db` whose last event
 *    is a terminal kind (`RunFinished`,
 *    `RunCancelled`, `RunFailed`).
 *  - **`Refused`** for a `run.db` that cannot be read
 *    (corrupt, locked, wrong schema). The polling impl
 *    backs off; the consumer's run stays in flight
 *    (the H3.1 AAT).
 */
@DisplayName("LocalRunDbFactReadSource (SFW-H3.2 local run.db read source)")
class LocalRunDbFactReadSourceTest {

    private val studioRunId = RunId("studio-run-h32-1")
    private val occurredAt = "2026-10-13T12:00:00Z"

    // ------------------------------------------------------------------
    // Falsifier 1: a populated run.db returns events in sequence order.
    // ------------------------------------------------------------------
    @Test
    fun `read from a populated run db returns events in sequence order`(@TempDir tmp: Path) = runBlocking {
        val source = LocalRunDbFactReadSource(tmp)
        writeRunDb(
            runDb = tmp.resolve(studioRunId.value).resolve("run.db"),
            rows = listOf(
                Triple(1L, "RunStarted", """{"step":"plan"}"""),
                Triple(2L, "StageStarted", """{"stage":"build"}"""),
                Triple(3L, "StepStarted", """{"step":"compile"}"""),
                Triple(4L, "StepFinished", """{"step":"compile","status":"ok"}"""),
            ),
        )

        val page = source.readFromCursor(studioRunId, EventCursor(0), limit = 100)

        assertTrue(page is EventPage.More, "expected More, got $page")
        val more = page as EventPage.More
        assertEquals(listOf(1L, 2L, 3L, 4L), more.events.map { it.sequence })
        assertEquals(EventCursor(4L), more.nextCursor)
        assertEquals(
            listOf("RunStarted", "StageStarted", "StepStarted", "StepFinished"),
            more.events.map { it.kind },
        )
    }

    // ------------------------------------------------------------------
    // Falsifier 2: a from-cursor skips events below the cursor.
    // ------------------------------------------------------------------
    @Test
    fun `read with a from cursor skips events below the cursor`(@TempDir tmp: Path) = runBlocking {
        val source = LocalRunDbFactReadSource(tmp)
        writeRunDb(
            runDb = tmp.resolve(studioRunId.value).resolve("run.db"),
            rows = listOf(
                Triple(1L, "RunStarted", null),
                Triple(2L, "StageStarted", null),
                Triple(3L, "StepStarted", null),
                Triple(4L, "StepFinished", null),
            ),
        )

        val page = source.readFromCursor(studioRunId, EventCursor(2), limit = 100)

        assertTrue(page is EventPage.More, "expected More, got $page")
        val more = page as EventPage.More
        assertEquals(listOf(3L, 4L), more.events.map { it.sequence })
        assertEquals(EventCursor(4L), more.nextCursor)
    }

    // ------------------------------------------------------------------
    // Falsifier 3: a run.db with no new events returns More empty
    // (the run is in flight, poll again).
    // ------------------------------------------------------------------
    @Test
    fun `read with no new events returns More empty - the run is in flight`(@TempDir tmp: Path) = runBlocking {
        val source = LocalRunDbFactReadSource(tmp)
        // Last event is `StageStarted` (not a terminal kind).
        writeRunDb(
            runDb = tmp.resolve(studioRunId.value).resolve("run.db"),
            rows = listOf(
                Triple(1L, "RunStarted", null),
                Triple(2L, "StageStarted", null),
            ),
        )

        val page = source.readFromCursor(studioRunId, EventCursor(2), limit = 100)

        assertTrue(page is EventPage.More, "expected More, got $page")
        val more = page as EventPage.More
        assertEquals(emptyList<LiveEvent>(), more.events)
        // The cursor is unchanged — the polling impl
        // will keep the same cursor on the next poll.
        assertEquals(EventCursor(2L), more.nextCursor)
    }

    // ------------------------------------------------------------------
    // Falsifier 4: a run.db whose last event is a terminal kind
    // returns End (exactly-once end-of-stream).
    // ------------------------------------------------------------------
    @Test
    fun `read with a terminal event as the last returns End`(@TempDir tmp: Path) = runBlocking {
        val source = LocalRunDbFactReadSource(tmp)
        writeRunDb(
            runDb = tmp.resolve(studioRunId.value).resolve("run.db"),
            rows = listOf(
                Triple(1L, "RunStarted", null),
                Triple(2L, "StepStarted", null),
                Triple(3L, "RunFinished", """{"status":"success"}"""),
            ),
        )

        // Query with a cursor past the last event:
        // the query is empty AND the last event is
        // terminal -> End.
        val page = source.readFromCursor(studioRunId, EventCursor(3), limit = 100)

        assertTrue(page is EventPage.End, "expected End, got $page")
        val end = page as EventPage.End
        assertEquals(emptyList<LiveEvent>(), end.events)
    }

    // ------------------------------------------------------------------
    // Falsifier 5: a non-existent run.db returns More empty
    // (the run has not started; poll again).
    // ------------------------------------------------------------------
    @Test
    fun `read with a non-existent run db returns More empty - the run has not started`(@TempDir tmp: Path) = runBlocking {
        val source = LocalRunDbFactReadSource(tmp)
        // No run.db written — the SDK has not yet
        // created the per-run subdirectory.

        val page = source.readFromCursor(studioRunId, EventCursor(0), limit = 100)

        assertTrue(page is EventPage.More, "expected More, got $page")
        val more = page as EventPage.More
        assertEquals(emptyList<LiveEvent>(), more.events)
        assertEquals(EventCursor(0L), more.nextCursor)
    }

    // ------------------------------------------------------------------
    // Falsifier 6: a corrupt run.db returns Refused (the polling
    // impl backs off; the consumer's run stays in flight).
    // ------------------------------------------------------------------
    @Test
    fun `read with a corrupt run db returns Refused`(@TempDir tmp: Path) = runBlocking {
        val source = LocalRunDbFactReadSource(tmp)
        // Write a file that exists but is not a sqlite
        // database. The JDBC open will fail.
        val runDbPath = tmp.resolve(studioRunId.value).resolve("run.db")
        java.nio.file.Files.createDirectories(runDbPath.parent)
        java.nio.file.Files.writeString(runDbPath, "this is not a sqlite database")

        val page = source.readFromCursor(studioRunId, EventCursor(0), limit = 100)

        assertTrue(page is EventPage.Refused, "expected Refused, got $page")
        val refused = page as EventPage.Refused
        // The reason is informative enough to debug
        // (a future H3 unit may surface it to the
        // consumer's UI; H3.2 ships the AAT).
        assertNotNull(refused.reason)
        assertTrue(
            refused.reason.contains("run.db", ignoreCase = true) ||
                refused.reason.contains("sqlite", ignoreCase = true),
            "Refused reason should reference run.db or sqlite: ${refused.reason}",
        )
    }

    // ------------------------------------------------------------------
    // Falsifier 7: a JSON payload is parsed to a flat Map<String, String>
    // a null payload is an empty map.
    // ------------------------------------------------------------------
    @Test
    fun `events with a JSON payload are emitted with a flat map - null payload is empty`(@TempDir tmp: Path) = runBlocking {
        val source = LocalRunDbFactReadSource(tmp)
        writeRunDb(
            runDb = tmp.resolve(studioRunId.value).resolve("run.db"),
            rows = listOf(
                Triple(1L, "RunStarted", """{"planId":"plan-1","status":"ok"}"""),
                Triple(2L, "StepStarted", null),
            ),
        )

        val page = source.readFromCursor(studioRunId, EventCursor(0), limit = 100)

        assertTrue(page is EventPage.More, "expected More, got $page")
        val more = page as EventPage.More
        assertEquals(2, more.events.size)
        // First event: JSON object -> flat map.
        assertEquals(
            mapOf("planId" to "plan-1", "status" to "ok"),
            more.events[0].payload,
        )
        // Second event: NULL payload -> empty map.
        assertEquals(emptyMap<String, String>(), more.events[1].payload)
    }

    // ------------------------------------------------------------------
    // Test helpers
    // ------------------------------------------------------------------
    private fun writeRunDb(runDb: Path, rows: List<Triple<Long, String, String?>>) {
        java.nio.file.Files.createDirectories(runDb.parent)
        DriverManager.getConnection("jdbc:sqlite:$runDb").use { conn ->
            conn.createStatement().use { st ->
                st.executeUpdate(
                    """
                    CREATE TABLE events (
                        event_id    TEXT NOT NULL,
                        run_id      TEXT NOT NULL,
                        sequence    INTEGER PRIMARY KEY,
                        kind        TEXT NOT NULL,
                        occurred_at TEXT NOT NULL,
                        payload     TEXT
                    )
                    """.trimIndent(),
                )
            }
            conn.prepareStatement(
                "INSERT INTO events(event_id, run_id, sequence, kind, occurred_at, payload) VALUES(?,?,?,?,?,?)",
            ).use { ps ->
                for ((seq, kind, payload) in rows) {
                    ps.setString(1, "e$seq")
                    ps.setString(2, studioRunId.value)
                    ps.setLong(3, seq)
                    ps.setString(4, kind)
                    ps.setString(5, occurredAt)
                    if (payload == null) ps.setNull(6, java.sql.Types.VARCHAR) else ps.setString(6, payload)
                    ps.executeUpdate()
                }
            }
        }
    }
}
