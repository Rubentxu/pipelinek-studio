package dev.pipelinek.studio.domain

import java.time.Instant

/**
 * Typed Run state per DATA_MODEL_AND_PERSISTENCE.md §"Estado durable y transiciones":
 *   CREATED → ADMITTED → QUEUED → STARTING → RUNNING → {SUCCEEDED,FAILED,CANCELLED,REFUSED}
 *   ⊕ RECOVERY_REQUIRED, LOST, UNKNOWN
 *
 * Anything not terminal is reconciled against runtime on next observation.
 * CANCEL_REQUESTED is a request signal, NOT a state — see [RunCancelRequested].
 */
enum class RunState {
    CREATED,
    ADMITTED,
    QUEUED,
    STARTING,
    RUNNING,
    SUCCEEDED,
    FAILED,
    CANCELLED,
    REFUSED,
    RECOVERY_REQUIRED,
    LOST,
    UNKNOWN;

    val isTerminal: Boolean
        get() = this == SUCCEEDED || this == FAILED || this == CANCELLED || this == REFUSED
}

/**
 * A run cannot be promoted to terminal arbitrarily.
 * Studio NEVER invents FAILED when the process is lost if PipelineK can still reconcile.
 */
data class RunStateTransition(
    val from: RunState,
    val to: RunState,
    val observedAt: Instant,
    val reason: String? = null,
)

object RunStateMachine {
    private val allowed: Set<Pair<RunState, RunState>> = setOf(
        RunState.CREATED to RunState.ADMITTED,
        RunState.ADMITTED to RunState.QUEUED,
        RunState.QUEUED to RunState.STARTING,
        RunState.STARTING to RunState.RUNNING,
        RunState.RUNNING to RunState.SUCCEEDED,
        RunState.RUNNING to RunState.FAILED,
        RunState.RUNNING to RunState.CANCELLED,
        RunState.RUNNING to RunState.RECOVERY_REQUIRED,
        RunState.STARTING to RunState.RECOVERY_REQUIRED,
        RunState.QUEUED to RunState.CANCELLED,
        RunState.ADMITTED to RunState.REFUSED,
        RunState.CREATED to RunState.REFUSED,
        RunState.QUEUED to RunState.REFUSED,
        RunState.STARTING to RunState.REFUSED,
        RunState.RECOVERY_REQUIRED to RunState.RUNNING,
        RunState.RECOVERY_REQUIRED to RunState.FAILED,
        RunState.RECOVERY_REQUIRED to RunState.LOST,
        RunState.LOST to RunState.UNKNOWN,
    )

    /** Returns true iff transition is allowed by the state machine. */
    fun canTransition(from: RunState, to: RunState): Boolean = (from to to) in allowed

    fun requireTransition(from: RunState, to: RunState) {
        require(canTransition(from, to)) {
            "invalid run state transition: $from -> $to (see DATA_MODEL_AND_PERSISTENCE.md)"
        }
    }
}
