// Error boundary: both catches wrap a full run.db read (connect + query + timestamp
// decode) whose driver failures are not provable as SQLException alone, and any of
// them must become EventPage.Refused (never a synthetic End); no narrower catch is safe.
@file:Suppress("TooManyFunctions", "TooGenericExceptionCaught")

package dev.pipelinek.studio.infra.sdk

import dev.pipelinek.studio.application.ports.EventCursor
import dev.pipelinek.studio.application.ports.EventPage
import dev.pipelinek.studio.application.ports.FactReadSource
import dev.pipelinek.studio.application.ports.LiveEvent
import dev.pipelinek.studio.domain.RunId
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import java.time.Instant

/**
 * SFW-H3.2: a [FactReadSource] that reads from the
 * per-run `run.db` SQLite file the SDK subprocess
 * writes. The seam that the H3.1 polling impl plugs
 * into for **local** Studio runs.
 *
 * ## The contract surface
 *
 * - the `events` table pinned by the SDK subprocess:
 *   `(event_id TEXT NOT NULL, run_id TEXT NOT NULL,
 *   sequence INTEGER PRIMARY KEY, kind TEXT NOT NULL,
 *   occurred_at TEXT NOT NULL, payload TEXT)`.
 *   H3.2 reads 4 of these columns (`sequence`, `kind`,
 *   `occurred_at`, `payload`); the `event_id` and
 *   `run_id` are not used (the per-run `run.db` path
 *   already encodes the `studioRunId`; `event_id` is a
 *   UUID the SDK writes for its own bookkeeping).
 * - the path pinned by [PipelineKRunner]:
 *   `controlRootBase/<studioRunId.value>/run.db`. The
 *   `<studioRunId>` subdirectory is per-run; the
 *   `run.db` is the per-run event log.
 *
 * ## The three arms of [EventPage] in this source
 *
 * - [EventPage.More] (`events = [], nextCursor = from`)
 *   when the `run.db` does not yet exist — the run was
 *   admitted but the SDK subprocess has not yet flushed
 *   the first batch. The polling impl keeps polling.
 * - [EventPage.More] (`events = [], nextCursor = from`)
 *   when the `run.db` exists, the query is empty, and
 *   the last event is not a terminal kind — the run is
 *   still in flight; the polling impl keeps polling.
 * - [EventPage.More] (`events = [..], nextCursor = maxSeq`)
 *   when the query returned events — emit them and
 *   poll again immediately.
 * - [EventPage.End] (`events = []`) when the last event
 *   in the `run.db` is a terminal kind (`RunFinished`,
 *   `RunCancelled`, `RunFailed`). The polling impl
 *   closes the flow exactly once.
 * - [EventPage.Refused] when the JDBC connection or the
 *   query fails. The polling impl backs off; the
 *   consumer's run stays in flight (the H3.1 AAT).
 *
 * ## Why a per-call connection, not a pool
 *
 * The polling impl polls at 250 ms (configurable). A
 * per-call connection is wasteful but correct; a future
 * H3 unit may add a pool, but H3.2 ships the simple
 * version because the contract is the load-bearing
 * piece.
 *
 * @param controlRootBase the per-run root the
 *   [PipelineKRunner] writes to; the same
 *   `controlRootBase` used by the runner. H3.2
 *   reads `controlRootBase/<studioRunId.value>/run.db`.
 */
class LocalRunDbFactReadSource(
    private val controlRootBase: Path,
) : FactReadSource {

    override suspend fun readFromCursor(
        studioRunId: RunId,
        from: EventCursor,
        limit: Int,
    ): EventPage {
        val runDb = controlRootBase.resolve(studioRunId.value).resolve("run.db")

        // The run was admitted but the SDK subprocess
        // has not yet created the run.db. The polling
        // impl keeps polling; the run is in flight, the
        // events are not yet flushed.
        if (!Files.exists(runDb)) {
            return EventPage.More(events = emptyList(), nextCursor = from)
        }

        val events: List<LiveEvent> = try {
            DriverManager.getConnection("jdbc:sqlite:$runDb").use { conn ->
                conn.prepareStatement(
                    """
                    SELECT sequence, kind, occurred_at, payload
                    FROM events
                    WHERE sequence > ?
                    ORDER BY sequence ASC
                    LIMIT ?
                    """.trimIndent(),
                ).use { ps ->
                    ps.setLong(1, from.sequence)
                    ps.setInt(2, limit)
                    val results = ArrayList<LiveEvent>(limit)
                    ps.executeQuery().use { rs ->
                        while (rs.next()) {
                            results.add(
                                LiveEvent(
                                    sequence = rs.getLong("sequence"),
                                    kind = rs.getString("kind"),
                                    occurredAt = Instant.parse(rs.getString("occurred_at")),
                                    payload = decodePayload(rs.getString("payload")),
                                ),
                            )
                        }
                    }
                    results
                }
            }
        } catch (failure: Exception) {
            // The polling impl backs off; the consumer's
            // run stays in flight. The AAT (no synthetic
            // state transition) is the structural
            // guarantee.
            return EventPage.Refused(
                reason = "local run.db read failed: ${failure.javaClass.simpleName}: ${failure.message}",
            )
        }

        if (events.isNotEmpty()) {
            // Emit the events and poll again immediately
            // — the run is producing events.
            return EventPage.More(
                events = events,
                nextCursor = EventCursor(events.last().sequence),
            )
        }

        // The query was empty. The run is either:
        //  - still in flight (no new events since
        //    `from.sequence`); or
        //  - terminated (the last event in the run.db is
        //    a terminal kind).
        // The terminal check distinguishes the two
        // cases exactly once.
        val lastKind: String? = try {
            DriverManager.getConnection("jdbc:sqlite:$runDb").use { conn ->
                conn.prepareStatement(
                    """
                    SELECT kind FROM events
                    ORDER BY sequence DESC
                    LIMIT 1
                    """.trimIndent(),
                ).use { ps ->
                    ps.executeQuery().use { rs ->
                        if (rs.next()) rs.getString("kind") else null
                    }
                }
            }
        } catch (failure: Exception) {
            return EventPage.Refused(
                reason = "local run.db terminal-check read failed: ${failure.javaClass.simpleName}: ${failure.message}",
            )
        }

        return if (lastKind in TERMINAL_KINDS) {
            EventPage.End(events = emptyList())
        } else {
            // The run is in flight; the events are not
            // yet flushed. The polling impl backs off
            // and polls again.
            EventPage.More(events = emptyList(), nextCursor = from)
        }
    }

    private fun decodePayload(payload: String?): Map<String, String> {
        if (payload.isNullOrBlank()) return emptyMap()
        return try {
            val tree = Json.parseToJsonElement(payload)
            if (tree !is JsonObject) {
                emptyMap()
            } else {
                tree.entries.mapNotNull { (k, v) ->
                    if (v is JsonPrimitive && v.isString) k to v.content
                    else null
                }.toMap()
            }
        } catch (_: Exception) {
            // The payload is a foreign shape (not a JSON
            // object, or not JSON at all). The source
            // returns an empty payload; the consumer sees
            // the event with `payload = {}` and renders
            // what it can. A future H3 unit may add a
            // structured-typed payload variant; H3.2
            // ships the flat map baseline.
            emptyMap()
        }
    }

    companion object {
        /**
         * The kinds that mark the end of a run's event
         * stream. The source's `End` arm is honest
         * about them; an `End(events = [])` is the
         * exactly-once end-of-stream signal the
         * polling impl uses to close the flow.
         */
        internal val TERMINAL_KINDS: Set<String> = setOf(
            "RunFinished",
            "RunCancelled",
            "RunFailed",
        )
    }
}
