@file:Suppress("TooManyFunctions")

package dev.pipelinek.studio.application.usecases

import dev.pipelinek.studio.application.ports.CancelRunResult
import dev.pipelinek.studio.application.ports.CancelRunUseCase
import dev.pipelinek.studio.application.ports.CancellationResult
import dev.pipelinek.studio.application.ports.PipelineRunnerPort
import dev.pipelinek.studio.application.ports.RemoteCancellationResult
import dev.pipelinek.studio.application.ports.RemoteRunnerPort
import dev.pipelinek.studio.application.ports.RunRequest
import dev.pipelinek.studio.application.ports.RuntimeBackend
import dev.pipelinek.studio.application.ports.RuntimeBackendSelectionResult
import dev.pipelinek.studio.application.ports.RuntimeBackendSelector
import dev.pipelinek.studio.domain.RunId

/**
 * SFW-H4.1: the implementation of
 * [CancelRunUseCase] that consolidates cancellation
 * across the local SDK subprocess (the
 * [PipelineRunnerPort]) and a remote Fabric server
 * (the [RemoteRunnerPort]).
 *
 * ## The selection + dispatch in one paragraph
 *
 * The `cancel(studioRunId, request)` method asks the
 * H2.4 [RuntimeBackendSelector] which backend the
 * submit would have landed on (the selector's
 * decision is deterministic on the [RunRequest]'s
 * shape, not the run's history — a future H4 unit
 * may add a `RunBindingsH2Store.cancelSelection`
 * field that remembers the original submit decision;
 * H4.1 ships the simple selector-based decision).
 * On `Allowed(Local)`, the use case delegates to
 * the local [PipelineRunnerPort.cancel] and maps
 * the 3-arm [CancellationResult] to the unified
 * 4-arm [CancelRunResult]. On `Allowed(Fabric)`,
 * the use case delegates to the remote
 * [RemoteRunnerPort.cancel] and maps the 4-arm
 * [RemoteCancellationResult] to the unified
 * [CancelRunResult]. On `Refused`, the use case
 * returns [CancelRunResult.Refused] (the
 * selector's refusal is a refusal — the use case
 * cannot dispatch the cancel to a backend).
 *
 * ## Why a 4-arm [CancelRunResult] (and not 3)
 *
 * The H2.2c `Refused` arm is a load-bearing
 * distinction: a transport failure is a refusal,
 * not an unknown. Unifying to 4 arms means the
 * HTTP route and the future UATs do not have to
 * special-case the two backends. The local path's
 * "unexpected exception" is now an explicit
 * [CancelRunResult.Refused] (wrapped in a
 * try/catch).
 *
 * @param localPort the local SDK subprocess
 *   cancel port (production: `PipelineKRunner`).
 * @param remotePort the remote Fabric cancel port
 *   (production: the H2.2c `FabricRunnerAdapter`
 *   implementing [RemoteRunnerPort]).
 * @param selector the H2.4 [RuntimeBackendSelector]
 *   that picks which backend a request lands on.
 */
class CancelRunUseCaseImpl(
    private val localPort: PipelineRunnerPort,
    private val remotePort: RemoteRunnerPort,
    private val selector: RuntimeBackendSelector,
) : CancelRunUseCase {

    override suspend fun cancel(
        studioRunId: RunId,
        request: RunRequest,
    ): CancelRunResult {
        // 1. Ask the selector which backend the
        //    submit would have landed on. The
        //    selector is deterministic on the
        //    request's shape; the cancel path
        //    follows the same decision.
        return when (val selection = selector.select(request)) {
            is RuntimeBackendSelectionResult.Allowed -> when (selection.backend) {
                RuntimeBackend.Local -> cancelLocal(studioRunId)
                RuntimeBackend.Fabric -> cancelRemote(studioRunId)
            }
            is RuntimeBackendSelectionResult.Refused ->
                // The selector's refusal is a
                // refusal: the use case cannot
                // dispatch the cancel to a backend.
                // The HTTP route renders this as
                // `503 CANCEL_REFUSED`.
                CancelRunResult.Refused(
                    reason = "runtime-backend selector refused: ${selection.reason}",
                )
        }
    }

    private fun cancelLocal(studioRunId: RunId): CancelRunResult = try {
        when (val r = localPort.cancel(studioRunId)) {
            is CancellationResult.CancelRequested -> CancelRunResult.CancelRequested
            is CancellationResult.AlreadyTerminal -> CancelRunResult.AlreadyTerminal(r.finalState)
            is CancellationResult.Unknown -> CancelRunResult.Unknown(r.reason)
        }
    } catch (failure: Exception) {
        // The H4.1 4-arm `CancelRunResult` has
        // an explicit `Refused` arm. The local
        // 3-arm `CancellationResult` does not;
        // the H4.1 contract is "any unexpected
        // exception on the local path is a
        // refusal, not an unknown". The HTTP
        // route renders this as
        // `503 CANCEL_REFUSED`.
        CancelRunResult.Refused(
            reason = "local cancel failed: ${failure.javaClass.simpleName}: ${failure.message}",
        )
    }

    private suspend fun cancelRemote(studioRunId: RunId): CancelRunResult = try {
        when (val r = remotePort.cancel(studioRunId)) {
            is RemoteCancellationResult.CancelRequested -> CancelRunResult.CancelRequested
            is RemoteCancellationResult.AlreadyTerminal -> CancelRunResult.AlreadyTerminal(r.finalState)
            is RemoteCancellationResult.Unknown -> CancelRunResult.Unknown(r.reason)
            is RemoteCancellationResult.Refused -> CancelRunResult.Refused(r.reason)
        }
    } catch (failure: Exception) {
        CancelRunResult.Refused(
            reason = "remote cancel failed: ${failure.javaClass.simpleName}: ${failure.message}",
        )
    }
}
