package dev.pipelinek.studio.application.ports

/**
 * AAT-DUR-006 — Retention / cleanup.
 *
 * Per ROADMAP §"S5 — Product Ready" + docs/05-testing/AAT_AND_FITNESS.md:
 *   "Retención/cleanup solo elimina runs terminales sin lease vivo;
 *    crash cleanup recuperable"
 *
 * The contract is conservative: a run is eligible for deletion ONLY
 * when both
 *   (a) it is in a terminal state (RunFinished event in run.db), AND
 *   (b) there is no live SDK subprocess bound to it (liveProcs map
 *       in the runner, or processFor() probe in the supervisor).
 *
 * If either is missing, the run stays. The point is to never
 * auto-delete a run that might still be observed by an operator
 * or that the runner is still processing.
 */
interface RetentionPort {
    /**
     * @param controlRootBase per-run control-root base (contains one dir per run)
     * @param liveRunToPid authoritative per-run subprocess bindings from the runner
     *        (run_id -> pid). A run is kept when its run_id is in this map; the
     *        retention service does NOT fall back to a global pid set, because
     *        a global /proc scan would conflate unrelated processes (e.g. the
     *        studio JVM itself) with run subprocesses.
     */
    fun sweep(controlRootBase: java.nio.file.Path, liveRunToPid: Map<String, Long>): SweepOutcome
}

data class SweepOutcome(
    val runsScanned: Int,
    val runsDeleted: Int,
    val runsKept: List<RunKeptReason>,
)

sealed interface RunKeptReason {
    val runId: String

    /** The run was not in a terminal state (no RunFinished event). */
    data class NotTerminal(override val runId: String) : RunKeptReason

    /** The run had a terminal event but a live SDK subprocess is still bound. */
    data class HasLiveSubprocess(override val runId: String, val pid: Long) : RunKeptReason

    /** The run.db was missing or unreadable; we leave it alone (no synthetic delete). */
    data class Unreadable(override val runId: String, val reason: String) : RunKeptReason
}
