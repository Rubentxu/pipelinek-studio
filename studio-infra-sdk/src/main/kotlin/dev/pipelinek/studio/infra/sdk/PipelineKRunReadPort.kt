package dev.pipelinek.studio.infra.sdk

import dev.pipelinek.studio.application.ports.EventCursor
import dev.pipelinek.studio.application.ports.RunEvent
import dev.pipelinek.studio.application.ports.RunReadPort
import dev.pipelinek.studio.application.ports.RunReadResult
import dev.pipelinek.studio.domain.RunId
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import java.time.Instant

/**
 * RunReadPort backed by a real PipelineK `run.db` (Event Plane authority per
 * UPSTREAM_GROUNDING.md). The SDK writes a per-run DB under the control-root the
 * runner was given; Studio locates it via `<controlRootBase>/<runId>/run.db`.
 *
 * Open the file in READ-ONLY mode (SQLite allows concurrent readers + 1 writer;
 * Studio never opens `run.db` in writer mode). Translates JSON payloads to map
 * fields. Returns Unknown if the run.db is missing (RUN is UNKNOWN per the Event
 * Plane contract), never invents events.
 *
 * Per R-04: NEVER queries upstream's classloader or uses reflection; talks to
 * run.db as documented in UPSTREAM_GROUNDING.md only.
 */
class PipelineKRunReadPort(
    private val controlRootBase: Path,
) : RunReadPort {

    private val log = LoggerFactory.getLogger(javaClass)

    private fun runDbPath(runId: RunId): Path =
        controlRootBase.resolve(runId.value).resolve("run.db")

    private fun openReadonly(runId: RunId): java.sql.Connection? {
        val db = runDbPath(runId)
        if (!Files.isRegularFile(db)) return null
        // R0.3.2 (2026-10-11): route through SqliteConnection so
        // PRAGMA foreign_keys=ON is set on the run.db read handle.
        // Studio is read-only on run.db per ADR-003.
        return dev.pipelinek.studio.infra.storage.SqliteConnection.openReadOnly(db)
    }

    private fun decodePayload(json: String): Map<String, String> {
        // Minimal JSON shape → flat string map; avoid pulling a JSON dep when payload
        // is small enough to scan with a regex. Accepts either "k":"v" or "k":v forms.
        val out = LinkedHashMap<String, String>()
        val re = Regex(""""([A-Za-z0-9_]+)"\s*:\s*(?:"([^"]*)"|([A-Za-z0-9_.\-+]+))""")
        for (m in re.findAll(json)) {
            val key = m.groupValues[1]
            val value = m.groupValues[2].ifEmpty { m.groupValues[3] }
            out[key] = value
        }
        return out
    }

    override fun events(runId: RunId, after: EventCursor?, limit: Int): RunReadResult {
        val conn = openReadonly(runId) ?: return RunReadResult.Unknown(
            "run.db not found at ${runDbPath(runId)}; SDK subprocess may have failed to start",
        )
        conn.use { c ->
            val safeLimit = limit.coerceIn(1, 1000)
            val afterSeq = after?.sequence ?: 0L
            // The control-root is the per-run scope: one run.db per run, one
            // run per control-root. The SDK assigns its own `run_id` UUID
            // which is independent of the studio `runId`, so we do NOT
            // filter by `run_id` here. Filter by sequence only, which the
            // SDK guarantees is monotonically increasing per run.db.
            val sql = "SELECT sequence, kind, occurred_at, payload FROM events WHERE sequence > ? ORDER BY sequence LIMIT ?"
            val rows = mutableListOf<RunEvent>()
            var maxSeq: Long = afterSeq
            c.prepareStatement(sql).use { ps ->
                ps.setLong(1, afterSeq)
                ps.setInt(2, safeLimit)
                ps.executeQuery().use { rs ->
                    while (rs.next()) {
                        val seq = rs.getLong("sequence")
                        if (seq > maxSeq) maxSeq = seq
                        val kind = rs.getString("kind") ?: ""
                        val occurredAtStr = rs.getString("occurred_at") ?: "1970-01-01T00:00:00Z"
                        val payloadStr = rs.getString("payload") ?: "{}"
                        val occurredAt = parseInstant(occurredAtStr)
                        val payload = decodePayload(payloadStr)
                        rows += RunEvent(sequence = seq, kind = kind, occurredAt = occurredAt, payload = payload)
                    }
                }
            }
            // nextSequence is null if we got fewer rows than asked for (EOF reached).
            val next = if (rows.size < safeLimit) null else EventCursor(maxSeq)
            return RunReadResult.Ok(events = rows, nextSequence = next)
        }
    }

    override fun state(runId: RunId): RunReadResult {
        // Per DATA_MODEL_AND_PERSISTENCE.md: state is derivable from terminal kinds.
        // We return the highest-severity kind known; FAILED/AWAIT_CANCEL/etc. drive
        // state inference. For S0 bootstrap we surface the latest 5 events to give
        // the HTTP /runs endpoint enough material to render a meaningful state.
        val conn = openReadonly(runId) ?: return RunReadResult.Unknown(
            "run.db not found; cannot infer state",
        )
        conn.use { c ->
            return c.prepareStatement(
                "SELECT sequence, kind FROM events ORDER BY sequence DESC LIMIT 5",
            ).use { ps ->
                ps.executeQuery().use { rs ->
                    if (!rs.next()) {
                        @Suppress("UNREACHABLE_CODE")
                        return@use RunReadResult.Unknown("no events yet")
                    }
                    val last = rs.getString("kind") ?: "UNKNOWN"
                    val synthetic = RunEvent(
                        sequence = rs.getLong("sequence"),
                        kind = "SyntheticStateHint",
                        occurredAt = Instant.now(),
                        payload = mapOf("lastKind" to last),
                    )
                    RunReadResult.Ok(events = listOf(synthetic), nextSequence = null)
                }
            }
        }
    }

    /**
     * R6.1 (2026-10-12): override the default observation so
     * the cert sees a real terminal state when the subprocess
     * finishes successfully. The default port (RunReadPort
     * default impl in Ports.kt) returns Terminal(DeclaredPending)
     * for any `state() == Ok`, which the HTTP layer projects as
     * `state=CREATED`. That projection is the documented
     * pre-pipeline shape; with the SDK adapter in place, the
     * cert should see the actual outcome (SUCCEEDED / FAILED)
     * once the subprocess writes the RunFinished event to
     * run.db.
     *
     * The adapter reads the latest event; if it is
     * `RunFinished`, the outcome in the payload becomes the
     * terminal. Otherwise the observation is DeclaredPending.
     */
    override fun observation(runId: RunId): dev.pipelinek.studio.domain.RunObservation {
        val conn = openReadonly(runId) ?: return dev.pipelinek.studio.domain.RunObservation.Unavailable(
            "run.db not found at ${runDbPath(runId)}; SDK subprocess may not have started",
        )
        conn.use { c ->
            return c.prepareStatement(
                "SELECT sequence, kind, payload FROM events ORDER BY sequence DESC LIMIT 1",
            ).use { ps ->
                ps.executeQuery().use { rs ->
                    if (!rs.next()) {
                        return@use dev.pipelinek.studio.domain.RunObservation.Terminal(
                            dev.pipelinek.studio.domain.VerifiedOutcome.DeclaredPending,
                        )
                    }
                    val kind = rs.getString("kind") ?: "UNKNOWN"
                    val payload = rs.getString("payload") ?: ""
                    if (kind != "RunFinished") {
                        return@use dev.pipelinek.studio.domain.RunObservation.Terminal(
                            dev.pipelinek.studio.domain.VerifiedOutcome.DeclaredPending,
                        )
                    }
                    val outcome = Regex(""""outcome"\s*:\s*"([^"]+)"""").find(payload)?.groupValues?.get(1)
                        ?: "UNKNOWN"
                    // R6.1 (2026-10-12): the SDK writes
                    // outcome=success/failure, but the studio's
                    // documented wire contract (and the cert's
                    // expected set) uses the typed RunState
                    // names: SUCCEEDED / FAILED / CANCELLED.
                    // Translate at the boundary so consumers
                    // see the documented state.
                    val terminalState = when (outcome.lowercase()) {
                        "success" -> "SUCCEEDED"
                        "failure", "failed" -> "FAILED"
                        "cancelled", "canceled" -> "CANCELLED"
                        else -> "UNKNOWN"
                    }
                    val terminalSeq = rs.getLong("sequence")
                    val terminalSha = java.security.MessageDigest.getInstance("SHA-256")
                        .digest("$kind|$payload|$terminalSeq".toByteArray(Charsets.UTF_8))
                        .joinToString("") { "%02x".format(it) }
                    return@use dev.pipelinek.studio.domain.RunObservation.Terminal(
                        dev.pipelinek.studio.domain.VerifiedOutcome.Executed(
                            terminal = terminalState,
                            evidence = dev.pipelinek.studio.domain.RunEvidence(
                                runDbPath = runDbPath(runId).toString(),
                                terminalSequence = terminalSeq,
                                terminalKind = kind,
                                terminalSha256 = terminalSha,
                            ),
                        ),
                    )
                }
            }
        }
    }

    /** SQLite stores event timestamps as ISO-8601 strings (per UPSTREAM_GROUNDING.md). */
    private fun parseInstant(s: String): Instant {
        return try {
            Instant.parse(s)
        } catch (_: Throwable) {
            Instant.EPOCH
        }
    }
}
