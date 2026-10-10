package dev.pipelinek.studio.application.usecases

import dev.pipelinek.studio.application.ports.AgentCapability
import dev.pipelinek.studio.application.ports.AgentCapabilityPort
import dev.pipelinek.studio.application.ports.LineRangeReconstructingRunSelection
import dev.pipelinek.studio.application.ports.PromoteVariantPort
import dev.pipelinek.studio.application.ports.PromoteVariantRequest
import dev.pipelinek.studio.application.ports.PromoteVariantResult
import dev.pipelinek.studio.application.ports.RunComparisonPort
import dev.pipelinek.studio.application.ports.RunSelectionRequest
import dev.pipelinek.studio.application.ports.RunSelectionResult
import dev.pipelinek.studio.application.ports.SnapshotDiffPort
import dev.pipelinek.studio.application.ports.SnapshotDiffResult

/**
 * B4.5 (2026-10-10): the capability-driven
 * `studio.agent` dispatcher per ROADMAP §"S4
 * API/MCP para variantes de snapshot y compare
 * de runs...".
 *
 * The S4 mandate says "capability-driven
 * (no tool-per-step)". A "tool-per-step"
 * design would expose N tools, one per
 * action, on the MCP surface (e.g.
 * `studio.runs.compare`, `studio.snapshots.diff`,
 * `studio.snapshots.promote`, `studio.runs.select`,
 * `studio.snapshots.mutate`). The mandate
 * rejects that: the agent calls ONE tool,
 * `studio.agent`, and passes the action as a
 * parameter. The dispatcher is the single
 * entry point; the capability gate is the
 * single decision point.
 *
 * The [AgentDispatcher] class owns the
 * capability check + port fanout. The MCP
 * server is a thin transport wrapper that
 * takes a string action + JSON payload,
 * decodes the [AgentAction], and calls
 * [AgentDispatcher.dispatch].
 *
 * The default impl wiring:
 *  - [runComparison] is the pre-B4.1 port
 *    (the B4.1 S4 dimension diff is a
 *    field-level enrichment on the
 *    pre-existing compare).
 *  - [snapshotDiff] is the B4.2 port.
 *  - [promoteVariant] is the B4.3 port.
 *  - [runSelection] is the B4.4 port.
 *  - [mutateSnapshot] is the B4.6 port
 *    (B4.6 wires the prompt-injection
 *    guard + mutation control; the
 *    dispatcher already has a hook for
 *    the action so the B4.6 work is
 *    plug-and-play).
 *
 * Tests in B4.5AgentDispatcherTest pin the
 * dispatch table, the capability gating, the
 * refusal format, and the "no tool-per-step"
 * property (only `studio.agent` is wired;
 * per-step tools are not registered).
 */
class AgentDispatcher(
    private val capability: AgentCapabilityPort,
    private val runComparison: RunComparisonPort,
    private val snapshotDiff: SnapshotDiffPort,
    private val promoteVariant: PromoteVariantPort,
    private val runSelection: LineRangeReconstructingRunSelection,
    private val mutateSnapshot: (AgentAction.MutateSnapshot) -> AgentActionResult,
) {
    /**
     * The single entry point. The dispatcher
     * consults the capability port BEFORE doing
     * any work; a disabled capability is a
     * short-circuit refusal (the studio will
     * not even read the run metadata).
     */
    fun dispatch(action: AgentAction): AgentActionResult {
        val required = action.requiredCapability()
        if (!capability.isEnabled(required)) {
            return AgentActionResult.Refused(
                "capability ${required.name} disabled; " +
                    "set STUDIO_AGENT_CAPS=${required.name} (or 'all') to enable",
            )
        }
        return when (action) {
            is AgentAction.CompareRuns -> {
                val r = runComparison.compare(action.base, action.other)
                when (r) {
                    is dev.pipelinek.studio.application.ports.RunComparisonResult.Ok ->
                        AgentActionResult.Ok(action.name, r.comparison)
                    is dev.pipelinek.studio.application.ports.RunComparisonResult.Failure ->
                        AgentActionResult.Failure(action.name, r.reason)
                }
            }
            is AgentAction.DiffSnapshots -> {
                val r = snapshotDiff.diff(action.a, action.b)
                when (r) {
                    is SnapshotDiffResult.Ok ->
                        AgentActionResult.Ok(action.name, r.diff)
                    is SnapshotDiffResult.Failure ->
                        AgentActionResult.Failure(action.name, r.reason)
                }
            }
            is AgentAction.PromoteVariant -> {
                val r = promoteVariant.promote(
                    PromoteVariantRequest(
                        variantSnapshotId = action.variantSnapshotId,
                        promoter = action.promoter,
                        note = action.note,
                    ),
                )
                when (r) {
                    is PromoteVariantResult.Ok ->
                        AgentActionResult.Ok(
                            action.name,
                            PromoteVariantOk(
                                newSnapshotId = r.newSnapshotId,
                                promotedFrom = r.promotedFrom,
                            ),
                        )
                    is PromoteVariantResult.Failure ->
                        AgentActionResult.Failure(action.name, r.reason)
                }
            }
            is AgentAction.SelectStep -> {
                val r = runSelection.select(
                    RunSelectionRequest(action.runId, action.stepOpId),
                )
                when (r) {
                    is RunSelectionResult.Ok ->
                        AgentActionResult.Ok(action.name, r)
                    is RunSelectionResult.Unknown ->
                        AgentActionResult.Unknown(action.name, r.reason)
                    is RunSelectionResult.Failure ->
                        AgentActionResult.Failure(action.name, r.reason)
                }
            }
            is AgentAction.MutateSnapshot -> mutateSnapshot(action)
        }
    }
}

/**
 * The action ADT. The S4 mandate is
 * "no tool-per-step": every agent-facing
 * action is one variant of this sealed
 * interface, dispatched through a single
 * `studio.agent(action, ...)` MCP tool.
 */
sealed interface AgentAction {
    val name: String

    data class CompareRuns(
        val base: dev.pipelinek.studio.domain.RunId,
        val other: dev.pipelinek.studio.domain.RunId,
    ) : AgentAction {
        override val name = "compare-runs"
    }

    data class DiffSnapshots(
        val a: dev.pipelinek.studio.domain.SnapshotId,
        val b: dev.pipelinek.studio.domain.SnapshotId,
    ) : AgentAction {
        override val name = "diff-snapshots"
    }

    data class PromoteVariant(
        val variantSnapshotId: dev.pipelinek.studio.domain.SnapshotId,
        val promoter: String,
        val note: String,
    ) : AgentAction {
        override val name = "promote-variant"
    }

    data class SelectStep(
        val runId: dev.pipelinek.studio.domain.RunId,
        val stepOpId: dev.pipelinek.studio.domain.OperationId,
    ) : AgentAction {
        override val name = "select-step"
    }

    data class MutateSnapshot(
        val snapshotId: dev.pipelinek.studio.domain.SnapshotId,
        val mutationId: String,
        val note: String,
    ) : AgentAction {
        override val name = "mutate-snapshot"
    }
}

/**
 * The capability required to dispatch each
 * action. The mapping is the security
 * contract: an action is only dispatched
 * when its required capability is enabled.
 */
fun AgentAction.requiredCapability(): AgentCapability = when (this) {
    is AgentAction.CompareRuns -> AgentCapability.COMPARE
    is AgentAction.DiffSnapshots -> AgentCapability.DIFF
    is AgentAction.PromoteVariant -> AgentCapability.PROMOTE
    is AgentAction.SelectStep -> AgentCapability.SELECT
    is AgentAction.MutateSnapshot -> AgentCapability.MUTATE
}

/**
 * The dispatch result. Three branches:
 *  - Ok: the action ran; [payload] is the
 *    typed response (typed per-action).
 *  - Refused: the capability gate refused
 *    the call; the payload is the refusal
 *    reason (the agent should retry only
 *    after enabling the capability).
 *  - Unknown: the action ran but the
 *    underlying port could not determine
 *    the answer (e.g. a Step with
 *    prevResult.). The agent gets a
 *    structured reason.
 *  - Failure: the action failed; the
 *    payload is the failure reason.
 */
sealed interface AgentActionResult {
    val actionName: String

    data class Ok(
        override val actionName: String,
        val payload: Any,
    ) : AgentActionResult

    data class Refused(
        val reason: String,
    ) : AgentActionResult {
        override val actionName: String = "refused"
    }

    data class Unknown(
        override val actionName: String,
        val reason: String,
    ) : AgentActionResult

    data class Failure(
        override val actionName: String,
        val reason: String,
    ) : AgentActionResult
}

/**
 * B4.5 (2026-10-10): the typed payload for
 * the promote-variant action. The dispatcher's
 * Ok branch carries this so the MCP transport
 * can serialize it without re-encoding from
 * the [PromoteVariantResult] hierarchy.
 */
data class PromoteVariantOk(
    val newSnapshotId: dev.pipelinek.studio.domain.SnapshotId,
    val promotedFrom: dev.pipelinek.studio.domain.SnapshotId,
)
