@file:Suppress("TooManyFunctions")

package dev.pipelinek.studio.application.usecases

import dev.pipelinek.studio.application.ports.CancelRunResult
import dev.pipelinek.studio.application.ports.CancelRunUseCase
import dev.pipelinek.studio.application.ports.CancelSelectionLookup
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
 * SFW-H4.1 + SFW-H4.8: the implementation of
 * [CancelRunUseCase] that consolidates cancellation
 * across the local SDK subprocess (the
 * [PipelineRunnerPort]) and a remote Fabric server
 * (the [RemoteRunnerPort]).
 *
 * ## The selection + dispatch in one paragraph
 *
 * The `cancel(studioRunId, request)` method:
 * 1. if [cancelSelectionLookup] is wired AND
 *    returns a valid value (`"Local"` or
 *    `"Fabric"`), dispatch the cancel to the
 *    matching port WITHOUT re-running the
 *    selector (SFW-H4.8 — the persistence is
 *    the H4.7 `run_bindings.cancel_selection`
 *    column; the lookup is the new
 *    [CancelSelectionLookup] port);
 * 2. otherwise (the lookup is unwired, the
 *    binding is a pre-H4.7 row with
 *    `cancel_selection = null`, or the lookup
 *    throws), fall back to the H2.4
 *    [RuntimeBackendSelector] (the H4.1 honest
 *    default). On `Allowed(Local)`, the use
 *    case delegates to the local
 *    [PipelineRunnerPort.cancel] and maps the
 *    3-arm [CancellationResult] to the unified
 *    4-arm [CancelRunResult]. On
 *    `Allowed(Fabric)`, the use case delegates
 *    to the remote [RemoteRunnerPort.cancel] and
 *    maps the 4-arm [RemoteCancellationResult]
 *    to the unified [CancelRunResult]. On
 *    `Refused`, the use case returns
 *    [CancelRunResult.Refused] (the selector's
 *    refusal is a refusal — the use case cannot
 *    dispatch the cancel to a backend).
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
 *   Used as the FALLBACK when the
 *   [cancelSelectionLookup] is unwired, returns
 *   `null`, or throws.
 * @param cancelSelectionLookup the SFW-H4.8
 *   [CancelSelectionLookup] port that reads the
 *   H4.7 `run_bindings.cancel_selection` value.
 *   When non-null AND the lookup returns a
 *   valid value, the cancel is dispatched to
 *   the original backend WITHOUT re-running
 *   the selector. When `null` (the production
 *   boot did not wire the port) OR the lookup
 *   returns `null` (a pre-H4.7 row) OR the
 *   lookup throws, the use case falls back to
 *   the [selector]. Defaults to `null` for
 *   backwards compatibility with the H4.1
 *   tests that did not exercise the lookup.
 */
class CancelRunUseCaseImpl(
    private val localPort: PipelineRunnerPort,
    private val remotePort: RemoteRunnerPort,
    private val selector: RuntimeBackendSelector,
    /**
     * SFW-H4.8: the optional [CancelSelectionLookup]
     * port. When wired, the use case reads the
     * `run_bindings.cancel_selection` value
     * (the H4.7 persistence) and dispatches the
     * cancel to the original backend. When `null`,
     * the use case falls back to the [selector].
     */
    private val cancelSelectionLookup: CancelSelectionLookup? = null,
) : CancelRunUseCase {

    override suspend fun cancel(
        studioRunId: RunId,
        request: RunRequest,
    ): CancelRunResult {
        // SFW-H4.8: try the lookup first; if it
        // returns a valid value, dispatch to the
        // matching port WITHOUT re-running the
        // selector. If the lookup is unwired,
        // returns null, or throws, fall back to
        // the H4.1 selector-based dispatch.
        val lookedUp = tryReadCancelSelection(studioRunId)
        if (lookedUp != null) {
            return when (lookedUp) {
                RuntimeBackend.Local -> cancelLocal(studioRunId)
                RuntimeBackend.Fabric -> cancelRemote(studioRunId)
            }
        }
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

    /**
     * SFW-H4.8: try to read the H4.7
     * `cancel_selection` value via the
     * [cancelSelectionLookup] port. Returns the
     * mapped [RuntimeBackend] when the lookup is
     * wired AND returns a valid value
     * (`"Local"` or `"Fabric"`). Returns `null`
     * when the lookup is unwired, returns `null`
     * (a pre-H4.7 row), returns an unknown value
     * (defensive: a future H4+ unit may add more
     * values; the selector is the safe default),
     * or throws (the lookup is best-effort; the
     * selector is the safe fallback).
     */
    private suspend fun tryReadCancelSelection(studioRunId: RunId): RuntimeBackend? {
        val lookup = cancelSelectionLookup ?: return null
        val raw = try {
            lookup.cancelSelectionFor(studioRunId)
        } catch (failure: Exception) {
            // The lookup is best-effort; the
            // selector is the safe fallback.
            // The HTTP route renders the selector's
            // decision; the lookup failure is
            // NOT surfaced to the operator.
            return null
        } ?: return null
        return when (raw) {
            "Local" -> RuntimeBackend.Local
            "Fabric" -> RuntimeBackend.Fabric
            else -> null  // defensive: unknown value -> fall back to selector
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
