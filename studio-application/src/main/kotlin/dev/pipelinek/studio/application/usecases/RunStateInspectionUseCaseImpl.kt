@file:Suppress("TooManyFunctions")

package dev.pipelinek.studio.application.usecases

import dev.pipelinek.studio.application.ports.PipelineRunnerPort
import dev.pipelinek.studio.application.ports.RemoteInspectionResult
import dev.pipelinek.studio.application.ports.RemoteRunnerPort
import dev.pipelinek.studio.application.ports.RunHandleStatus
import dev.pipelinek.studio.application.ports.RunInspectionResult
import dev.pipelinek.studio.application.ports.RunRequest
import dev.pipelinek.studio.application.ports.RunStateInspectionUseCase
import dev.pipelinek.studio.application.ports.RuntimeBackend
import dev.pipelinek.studio.application.ports.RuntimeBackendSelectionResult
import dev.pipelinek.studio.application.ports.RuntimeBackendSelector
import dev.pipelinek.studio.domain.RunId

/**
 * SFW-H4.2: the implementation of
 * [RunStateInspectionUseCase] that consolidates
 * run state inspection across the local SDK
 * subprocess (the [PipelineRunnerPort.status])
 * and a remote Fabric server (the
 * [RemoteRunnerPort.summary]).
 *
 * ## The selection + dispatch in one paragraph
 *
 * The `inspect(studioRunId, request)` method
 * asks the H2.4 [RuntimeBackendSelector] which
 * backend the submit would have landed on. On
 * `Allowed(Local)`, the use case delegates to
 * the local [PipelineRunnerPort.status] and
 * maps the 3-arm [RunHandleStatus] to the
 * unified 4-arm [RunInspectionResult]. On
 * `Allowed(Fabric)`, the use case delegates to
 * the remote [RemoteRunnerPort.summary] and
 * maps the 3-arm [RemoteInspectionResult] to
 * the unified 4-arm [RunInspectionResult] (the
 * `Found(RunSummary)` arm splits into `Live`
 * and `Terminal` based on
 * `summary.state` — `RunState.Terminal` →
 * `Terminal`; all others → `Live`). On
 * `Refused`, the use case returns
 * [RunInspectionResult.Refused].
 *
 * ## Why a 4-arm [RunInspectionResult] (and not 3)
 *
 * The H2.2c+1 `Refused` arm is a load-bearing
 * distinction: a transport failure is a refusal,
 * not an unknown. Unifying to 4 arms means the
 * HTTP route and the future UATs do not have to
 * special-case the two backends. The local
 * path's "unexpected exception" is now an
 * explicit [RunInspectionResult.Refused]
 * (wrapped in a try/catch).
 *
 * @param localPort the local SDK subprocess
 *   inspect port (production: `PipelineKRunner`).
 * @param remotePort the remote Fabric inspect
 *   port (production: the H4.1
 *   `RemoteRunnerAdapter` implementing
 *   [RemoteRunnerPort.summary]).
 * @param selector the H2.4 [RuntimeBackendSelector]
 *   that picks which backend a request lands on.
 */
class RunStateInspectionUseCaseImpl(
    private val localPort: PipelineRunnerPort,
    private val remotePort: RemoteRunnerPort,
    private val selector: RuntimeBackendSelector,
) : RunStateInspectionUseCase {

    override suspend fun inspect(
        studioRunId: RunId,
        request: RunRequest,
    ): RunInspectionResult {
        return when (val selection = selector.select(request)) {
            is RuntimeBackendSelectionResult.Allowed -> when (selection.backend) {
                RuntimeBackend.Local -> inspectLocal(studioRunId)
                RuntimeBackend.Fabric -> inspectRemote(studioRunId)
            }
            is RuntimeBackendSelectionResult.Refused ->
                RunInspectionResult.Refused(
                    reason = "runtime-backend selector refused: ${selection.reason}",
                )
        }
    }

    private fun inspectLocal(studioRunId: RunId): RunInspectionResult = try {
        when (val r = localPort.status(studioRunId)) {
            is RunHandleStatus.Live -> RunInspectionResult.Live(
                upstreamRunId = r.upstreamRunId,
                finalState = null,
            )
            is RunHandleStatus.Terminal -> RunInspectionResult.Terminal(
                finalState = r.finalState,
                completedAt = null,
            )
            is RunHandleStatus.Unknown -> RunInspectionResult.Unknown(r.reason)
        }
    } catch (failure: Exception) {
        RunInspectionResult.Refused(
            reason = "local inspect failed: ${failure.javaClass.simpleName}: ${failure.message}",
        )
    }

    private suspend fun inspectRemote(studioRunId: RunId): RunInspectionResult = try {
        when (val r = remotePort.summary(studioRunId)) {
            is RemoteInspectionResult.Found -> {
                // The H4.2 contract: `summary.isTerminal`
                // is the load-bearing distinction. The
                // use-case-layer `RunSummary` is shaped
                // by the [RemoteRunnerAdapter] (which
                // adapts the H2.2c `RunState.Terminal` to
                // `isTerminal = true`; all other
                // `RunState` arms to `isTerminal = false`).
                val summary = r.summary
                if (summary.isTerminal) {
                    RunInspectionResult.Terminal(
                        finalState = summary.finalState,
                        completedAt = summary.finishedAt,
                    )
                } else {
                    RunInspectionResult.Live(
                        upstreamRunId = summary.upstreamRunId,
                        finalState = summary.finalState,
                    )
                }
            }
            is RemoteInspectionResult.Unknown -> RunInspectionResult.Unknown(r.reason)
            is RemoteInspectionResult.Refused -> RunInspectionResult.Refused(r.reason)
        }
    } catch (failure: Exception) {
        RunInspectionResult.Refused(
            reason = "remote inspect failed: ${failure.javaClass.simpleName}: ${failure.message}",
        )
    }
}
