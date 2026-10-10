package dev.pipelinek.studio.application.ports

import java.nio.file.Path
import java.time.Duration

/**
 * AAT-DUR-006 — Retention / cleanup.
 *
 * Per ROADMAP §"S5 — Product Ready" + docs/05-testing/AAT_AND_FITNESS.md:
 *   "Retención/cleanup solo elimina runs terminales sin lease vivo;
 *    crash cleanup recuperable"
 *
 * The contract is conservative: a run is eligible for deletion ONLY
 * when ALL of the following hold:
 *   (a) it is in a terminal state (RunFinished event in run.db), AND
 *   (b) there is no live SDK subprocess bound to it (liveProcs map
 *       in the runner, or processFor() probe in the supervisor), AND
 *   (c) it is older than the configured TTL (B2 mandate § 6.9
 *       "TTL-bounded retention").
 *
 * B2.7 (2026-10-10) added the TTL cap and the dry-run mode
 * that makes cleanup "recoverable" per the B2 mandate. The
 * dry-run mode returns a [SweepOutcome] with the deletion
 * plan but does NOT actually delete. Operators can review
 * the plan and call [sweep] again with [dryRun] = false.
 */
interface RetentionPort {
    /**
     * @param controlRootBase per-run control-root base (contains one dir per run)
     * @param liveRunToPid authoritative per-run subprocess bindings from the runner
     *        (run_id -> pid). A run is kept when its run_id is in this map; the
     *        retention service does NOT fall back to a global pid set, because
     *        a global /proc scan would conflate unrelated processes (e.g. the
     *        studio JVM itself) with run subprocesses.
     * @param ttl B2.7 — minimum age a terminal run must reach before it is
     *        eligible for deletion. A run whose age (now - updated_at) is
     *        less than [ttl] is kept with [RunKeptReason.TooYoung]. The
     *        updated_at comes from the `runUpdatedAtProvider` (the
     *        `run_bindings.updated_at` column); when the provider returns
     *        null for a run, the service falls back to the per-run
     *        directory's mtime. Default 7 days; pass [Duration.ZERO] to
     *        disable the TTL check (the pre-B2.7 behavior).
     * @param dryRun B2.7 — when true, compute the deletion plan and return
     *        it in [SweepOutcome.runsWouldBeDeleted] without actually
     *        deleting. The plan reflects the would-be state after the
     *        sweep. This makes cleanup "recoverable" per the B2 mandate:
     *        an operator can dry-run, review, then re-sweep for real.
     * @param runUpdatedAtProvider B2.7 — supplies the `updated_at` for a
     *        given run_id, or null when the run_bindings row is missing
     *        (e.g. a fresh deployment). Defaults to a no-op provider
     *        (returns null) which falls back to the directory mtime.
     */
    fun sweep(
        controlRootBase: Path,
        liveRunToPid: Map<String, Long>,
        ttl: Duration,
        dryRun: Boolean,
        now: java.time.Instant,
        runUpdatedAtProvider: (String) -> java.time.Instant?,
    ): SweepOutcome
}

data class SweepOutcome(
    val runsScanned: Int,
    val runsDeleted: Int,
    val runsWouldBeDeleted: Int = 0,
    val dryRun: Boolean = false,
    val runsKept: List<RunKeptReason>,
)

/**
 * B2.7 (2026-10-10): convenience wrapper for the port
 * with the documented defaults. Kotlin forbids default
 * values on overrides, so the defaults live in this
 * extension instead. The extension delegates to the
 * strict port method, which carries the B2.7 contract.
 */
fun RetentionPort.sweep(
    controlRootBase: Path,
    liveRunToPid: Map<String, Long>,
    ttl: Duration = Duration.ofDays(7),
    dryRun: Boolean = false,
    now: java.time.Instant = java.time.Instant.now(),
    runUpdatedAtProvider: (String) -> java.time.Instant? = { null },
): SweepOutcome = sweep(
    controlRootBase = controlRootBase,
    liveRunToPid = liveRunToPid,
    ttl = ttl,
    dryRun = dryRun,
    now = now,
    runUpdatedAtProvider = runUpdatedAtProvider,
)

sealed interface RunKeptReason {
    val runId: String

    /** The run was not in a terminal state (no RunFinished event). */
    data class NotTerminal(override val runId: String) : RunKeptReason

    /** The run had a terminal event but a live SDK subprocess is still bound. */
    data class HasLiveSubprocess(override val runId: String, val pid: Long) : RunKeptReason

    /** The run.db was missing or unreadable; we leave it alone (no synthetic delete). */
    data class Unreadable(override val runId: String, val reason: String) : RunKeptReason

    /**
     * B2.7 (2026-10-10): the run is terminal, has no live
     * subprocess, but its `updated_at` is younger than the
     * configured TTL. We keep it because the B2 mandate
     * requires TTL-bounded retention, not just terminal
     * retention. The `ageSeconds` field is the run's
     * current age; the operator can decide to lower the
     * TTL or wait.
     */
    data class TooYoung(override val runId: String, val ageSeconds: Long) : RunKeptReason
}
