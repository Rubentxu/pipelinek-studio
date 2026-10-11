@file:Suppress("TooGenericExceptionCaught")

package dev.pipelinek.studio.infra.storage

import dev.pipelinek.studio.domain.IdFormat
import dev.pipelinek.studio.domain.RunId
import dev.pipelinek.studio.domain.RunState
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import java.time.Instant

/**
 * S5 server-side restart reconciliation (DUR-004).
 *
 * Contract (per docs/02-specifications/HTTP_AND_STREAMING_API.md
 * §Restart semantics): when studio-app restarts while runs are
 * in flight, the in-memory `liveProcs` map is empty and the SPA
 * cannot cancel or inspect subprocess state through the
 * supervisor. The durable ground truth is `run.db` — every
 * run.db whose latest event is non-terminal (no `RunFinished`,
 * `RunFailed`, `RunCancelled`) needs to surface honestly as
 * `RECOVERY_REQUIRED` (terminal state unknown) or `LOST` (no
 * events at all and the subprocess is gone).
 *
 * B2.5 (2026-10-10): [reconcile] now also persists the
 * reconciled state to `run_bindings` via the optional
 * [ReconcileOutcomePort]. The pre-B2.5 reconciler returned
 * the entries in memory only; the run_bindings row kept its
 * pre-crash state, so a GET /runs/{id} after restart would
 * report RUNNING for a run whose subprocess was actually
 * gone — a false-PASS channel the B2 mandate explicitly
 * closes. The new contract: every reconciled run is written
 * to its row in the same transaction that records the
 * `run_state_history` transition with observedAt. The
 * HTTP layer can then surface the new state without any
 * further read-side work.
 *
 * Per the contract, [reconcile] NEVER deletes run.db files,
 * NEVER spawns a new subprocess, and NEVER invents a terminal
 * state. It is read-only against the run.db files; the
 * transition (RECOVERY_REQUIRED / LOST) is reported AND
 * persisted in the same pass.
 */
class RunReconciler(
    private val controlRootBase: Path,
    private val outcomePort: dev.pipelinek.studio.application.ports.ReconcileOutcomePort? = null,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    data class ReconciliationEntry(
        val runId: RunId,
        val state: RunState,
        val lastEventKind: String?,
        val lastEventAt: Instant?,
        val reason: String,
    )

    data class ReconciliationReport(
        val controlRootBase: Path,
        val totalRuns: Int,
        val reconciled: List<ReconciliationEntry>,
    )

    fun reconcile(): ReconciliationReport {
        if (!Files.isDirectory(controlRootBase)) {
            return ReconciliationReport(controlRootBase, 0, emptyList())
        }
        val entries = mutableListOf<ReconciliationEntry>()
        val controlRootNormalized = controlRootBase.toAbsolutePath().normalize()
        Files.list(controlRootBase).use { stream ->
            for (dir in stream) {
                if (!Files.isDirectory(dir)) continue
                val dirName = dir.fileName?.toString() ?: continue
                // Audit H-25 (2026-10-10): a directory name that
                // isn't a canonical UUID is not a per-run
                // directory — it might be a sibling like
                // `last-run/` or `leases/`, or a malicious entry
                // like `../foo`. We refuse to surface it as a
                // RunId (the contract of the ReconciliationEntry)
                // and skip it with a log line. This also
                // prevents a path-traversal-shaped runId from
                // being rendered in /api/recovery.
                if (!IdFormat.isUuidLike(dirName)) {
                    log.warn(
                        "reconcile: ignoring non-UUID directory under controlRoot: {}",
                        dirName,
                    )
                    continue
                }
                val runDb = dir.resolve("run.db")
                if (!Files.isRegularFile(runDb)) continue
                // Defense in depth: even with a UUID-shaped
                // directory name, make sure the runDb path resolves
                // to a file that stays under controlRootBase. A
                // symlink could in principle redirect to anywhere
                // on disk — we want the reconciler to never read
                // outside its expected scope. `toRealPath()`
                // resolves symlinks; `toAbsolutePath().normalize()`
                // does not, so it would let a malicious link pass.
                val runDbReal = try {
                    runDb.toRealPath()
                } catch (e: java.io.IOException) {
                    log.warn("reconcile: could not resolve real path for {}: {}", runDb, e.toString())
                    continue
                }
                if (!runDbReal.startsWith(controlRootNormalized)) {
                    log.error(
                        "reconcile: refusing to read runDb outside controlRoot: {} -> {}",
                        runDb, runDbReal,
                    )
                    continue
                }
                val runId = RunId(dirName)
                val (lastKind, lastAt) = readTerminalHint(runDb)
                val (state, reason) = when {
                    lastKind == null ->
                        RunState.LOST to "no events in run.db; subprocess state unknown after restart"
                    lastKind == "RunFinished" && hasSuccessOutcome(runDb) ->
                        // lastKind alone is ambiguous — a RunFinished with
                        // outcome=success means SUCCEEDED, anything else
                        // means FAILED. We re-read the payload to be sure.
                        RunState.SUCCEEDED to "run.db terminal event: RunFinished(success)"
                    lastKind == "RunFinished" ->
                        RunState.FAILED to "run.db terminal event: RunFinished(!success)"
                    lastKind == "RunCancelled" ->
                        RunState.CANCELLED to "run.db terminal event: RunCancelled"
                    else ->
                        // In-flight event: CompilationStarted, StageStarted,
                        // RunStarted, StepStarted, etc. The subprocess is
                        // gone (no PID in liveProcs after restart) so the
                        // run is RECOVERY_REQUIRED.
                        RunState.RECOVERY_REQUIRED to "non-terminal event $lastKind; subprocess gone after restart"
                }
                entries += ReconciliationEntry(
                    runId = runId,
                    state = state,
                    lastEventKind = lastKind,
                    lastEventAt = lastAt,
                    reason = reason,
                )
                // B2.5: persist the reconciled state to run_bindings
                // (and append to run_state_history) so the GET
                // /runs/{id} handler surfaces the new state
                // without any further read-side work. Without
                // this, the pre-crash state stayed in the row
                // and a subsequent GET would return RUNNING for
                // a run whose subprocess was actually gone —
                // a false-PASS channel the B2 mandate closes.
                if (outcomePort != null) {
                    try {
                        outcomePort.recordReconciled(runId, state, reason, lastAt)
                    } catch (e: Exception) {
                        // Reconciliation must never abort the
                        // startup hook. Log and continue; the
                        // next reconcile() will retry.
                        log.error(
                            "reconcile: failed to persist {} -> {}: {}",
                            runId, state, e.toString(),
                        )
                    }
                }
            }
        }
        return ReconciliationReport(
            controlRootBase = controlRootBase,
            totalRuns = entries.size,
            reconciled = entries,
        )
    }

    private fun readTerminalHint(runDb: Path): Pair<String?, Instant?> {
        return try {
            DriverManager.getConnection("jdbc:sqlite:${runDb.toAbsolutePath()}").use { c ->
                c.prepareStatement(
                    "SELECT kind, occurred_at FROM events ORDER BY sequence DESC LIMIT 1",
                ).use { ps ->
                    ps.executeQuery().use { rs ->
                        if (rs.next()) {
                            rs.getString("kind") to runCatching {
                                Instant.parse(rs.getString("occurred_at"))
                            }.getOrNull()
                        } else {
                            null to null
                        }
                    }
                }
            }
        } catch (e: Exception) {
            log.warn("reconcile: could not read {} ({})", runDb, e.toString())
            null to null
        }
    }

    private fun hasSuccessOutcome(runDb: Path): Boolean {
        return try {
            DriverManager.getConnection("jdbc:sqlite:${runDb.toAbsolutePath()}").use { c ->
                c.prepareStatement(
                    "SELECT payload FROM events WHERE kind = 'RunFinished' ORDER BY sequence DESC LIMIT 1",
                ).use { ps ->
                    ps.executeQuery().use { rs ->
                        if (!rs.next()) return false
                        val payload = rs.getString("payload") ?: ""
                        // B2.6 (2026-10-10): the outcome is no
                        // longer read with a JSON regex. The
                        // single source of truth for what a
                        // success looks like is the domain's
                        // [RunOutcome] ADT — the reconciler
                        // asks the ADT, not the payload.
                        dev.pipelinek.studio.domain.RunOutcome.fromPayload(payload) ==
                            dev.pipelinek.studio.domain.RunOutcome.Success
                    }
                }
            }
        } catch (_: Exception) {
            false
        }
    }
}
