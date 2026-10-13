@file:Suppress("TooManyFunctions")

package dev.pipelinek.studio.infra.fabric

import dev.pipelinek.studio.application.ports.RemoteCancellationResult
import dev.pipelinek.studio.application.ports.RemoteInspectionResult
import dev.pipelinek.studio.application.ports.RemoteRunnerPort
import dev.pipelinek.studio.domain.RunId

/**
 * SFW-H4.1 + SFW-H4.2: the [RemoteRunnerPort]
 * implementation that adapts the H2.2c
 * [FabricRunnerAdapter] (cancel) and the H2.2c+1
 * [FabricRunReadAdapter] (summary) to the
 * use-case layer's abstract port. The adapter
 * delegates:
 *  - `cancel` to the H2.2c [FabricRunnerAdapter];
 *  - `summary` to the H2.2c+1
 *    [FabricRunReadAdapter];
 * and maps the H2.2c types to the use-case
 * layer's abstract types (identity mappings:
 * the shapes are identical by design).
 *
 * ## Why one adapter (and not two)
 *
 * The H2.2c submit / cancel port and the H2.2c+1
 * read port are separate concerns, but for the
 * H4 use cases (cancel + inspect) they share the
 * same "remote runner" surface. A single
 * adapter that holds both refs keeps the use-
 * case wiring simple: one [RemoteRunnerPort] per
 * remote backend.
 */
class RemoteRunnerAdapter(
    private val fabricAdapter: FabricRunnerAdapter,
    private val fabricReadAdapter: FabricRunReadAdapter,
) : RemoteRunnerPort {

    override suspend fun cancel(studioRunId: RunId): RemoteCancellationResult =
        when (val r = fabricAdapter.cancel(studioRunId)) {
            is FabricCancellationResult.CancelRequested -> RemoteCancellationResult.CancelRequested
            is FabricCancellationResult.AlreadyTerminal -> RemoteCancellationResult.AlreadyTerminal(r.finalState)
            is FabricCancellationResult.Unknown -> RemoteCancellationResult.Unknown(r.reason)
            is FabricCancellationResult.Refused -> RemoteCancellationResult.Refused(r.reason)
        }

    override suspend fun summary(studioRunId: RunId): RemoteInspectionResult =
        when (val r = fabricReadAdapter.summary(studioRunId)) {
            is FabricRunSummaryResult.Found -> {
                // Adapt the H2.2c `RunSummary` to the
                // use-case-layer `RunSummary`. The shape
                // is near-identity; the `isTerminal` flag
                // is the load-bearing distinction (the
                // H2.2c `RunState.Terminal(outcome)` arm
                // maps to `isTerminal = true`; all other
                // arms map to `isTerminal = false`).
                val fabricSummary = r.summary
                val isTerminal = fabricSummary.state is io.jenkins.pipelinek.fabric.contracts.model.RunState.Terminal
                val stateName = fabricSummary.state::class.simpleName ?: "Unknown"
                val outcomeName = (fabricSummary.state as? io.jenkins.pipelinek.fabric.contracts.model.RunState.Terminal)
                    ?.outcome?.toString()
                RemoteInspectionResult.Found(
                    summary = dev.pipelinek.studio.application.ports.RunSummary(
                        upstreamRunId = fabricSummary.runId.value,
                        displayName = fabricSummary.displayName,
                        finalState = outcomeName ?: stateName,
                        isTerminal = isTerminal,
                        startedAt = fabricSummary.startedAt,
                        finishedAt = fabricSummary.finishedAt,
                    ),
                )
            }
            is FabricRunSummaryResult.Unknown -> RemoteInspectionResult.Unknown(r.reason)
            is FabricRunSummaryResult.Refused -> RemoteInspectionResult.Refused(r.reason)
        }
}
