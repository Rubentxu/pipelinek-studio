@file:Suppress("TooManyFunctions")

package dev.pipelinek.studio.application.usecases

import dev.pipelinek.studio.application.ports.CancelRunResult
import dev.pipelinek.studio.application.ports.CancelRunUseCase
import dev.pipelinek.studio.application.ports.PipelineRunnerPort
import dev.pipelinek.studio.application.ports.RemoteSubmissionResult
import dev.pipelinek.studio.application.ports.RemoteSubmitPort
import dev.pipelinek.studio.application.ports.RetryRunResult
import dev.pipelinek.studio.application.ports.RetryRunUseCase
import dev.pipelinek.studio.application.ports.RunRequest
import dev.pipelinek.studio.application.ports.RuntimeBackend
import dev.pipelinek.studio.application.ports.RuntimeBackendSelectionResult
import dev.pipelinek.studio.application.ports.RuntimeBackendSelector
import dev.pipelinek.studio.application.ports.SubmissionResult
import dev.pipelinek.studio.domain.RunId

/**
 * SFW-H4.3: the implementation of
 * [RetryRunUseCase] that chains the H4.1
 * [CancelRunUseCase] with the submit path.
 *
 * ## The workflow in one paragraph
 *
 * The `retry(originalRunId, newRequest)` method:
 *  1. **cancel phase**: calls
 *     [CancelRunUseCase.cancel] with the
 *     original `RunId` and the `newRequest`
 *     (the synthetic request lets the H2.4
 *     selector pick the cancel's backend). On
 *     `CancelRunResult.CancelRequested` or
 *     `AlreadyTerminal`: continue to submit.
 *     On `Unknown`: return
 *     [RetryRunResult.Unknown]. On `Refused`:
 *     return [RetryRunResult.CancelRefused].
 *  2. **submit phase**: asks the H2.4
 *     [RuntimeBackendSelector] which backend the
 *     new request lands on; delegates to the
 *     local [PipelineRunnerPort.submit] (for
 *     `Local`) or the remote
 *     [RemoteSubmitPort.submit] (for `Fabric`).
 *     On success (Admitted or AlreadyRunning):
 *     return [RetryRunResult.Retried]. On
 *     refused: return
 *     [RetryRunResult.SubmitRefused].
 *
 * ## Why the cancel + submit selectors can
 *   differ
 *
 * The cancel phase asks the H2.4 selector
 * which backend the original run is on (using
 * the synthetic `RunRequest` for the selector).
 * The submit phase asks the selector which
 * backend the new request lands on. The two
 * decisions can differ if the selector's policy
 * has changed between the original submit and
 * the retry. The H4.3 use case is honest about
 * this: the cancel follows the original run's
 * backend; the submit follows the new request's
 * backend. A future H4 unit may add a
 * `RunBindingsH2Store.cancelSelection` field
 * to remember the original submit decision;
 * H4.3 ships the simple selector-based
 * decision.
 *
 * @param cancelUseCase the H4.1 cancel use
 *   case (delegates to the local + Fabric
 *   cancel ports via the H2.4 selector).
 * @param localPort the local SDK subprocess
 *   submit port (production: `PipelineKRunner`).
 * @param remotePort the remote Fabric submit
 *   port (production: the H4.3
 *   [dev.pipelinek.studio.infra.fabric.RemoteSubmitAdapter]).
 * @param selector the H2.4 [RuntimeBackendSelector]
 *   that picks which backend a request lands on.
 */
class RetryRunUseCaseImpl(
    private val cancelUseCase: CancelRunUseCase,
    private val localPort: PipelineRunnerPort,
    private val remotePort: RemoteSubmitPort,
    private val selector: RuntimeBackendSelector,
) : RetryRunUseCase {

    override suspend fun retry(
        originalRunId: RunId,
        newRequest: RunRequest,
    ): RetryRunResult {
        // 1. cancel phase.
        val cancelOutcome: CancelRunResult = cancelUseCase.cancel(originalRunId, newRequest)
        when (cancelOutcome) {
            is CancelRunResult.CancelRequested,
            is CancelRunResult.AlreadyTerminal,
            -> {
                // Continue to submit.
            }
            is CancelRunResult.Unknown ->
                return RetryRunResult.Unknown(
                    reason = "cancel returned Unknown: ${cancelOutcome.reason}",
                )
            is CancelRunResult.Refused ->
                return RetryRunResult.CancelRefused(
                    reason = "cancel returned Refused: ${cancelOutcome.reason}",
                )
        }

        // 2. submit phase.
        val selection: RuntimeBackendSelectionResult = selector.select(newRequest)
        return when (selection) {
            is RuntimeBackendSelectionResult.Allowed -> when (selection.backend) {
                RuntimeBackend.Local -> submitLocal(newRequest)
                RuntimeBackend.Fabric -> submitRemote(newRequest)
                // SFW-H5.3: the Podman path is a chain-start;
                // the actual `podman run` invocation is a
                // follow-up H5.3+ unit. The retry surfaces
                // as a SubmitRefused (the conservative path;
                // the operator can retry once the Podman
                // port is wired).
                RuntimeBackend.Podman -> RetryRunResult.SubmitRefused(
                    reason = "Podman submit not yet wired (H5.3 chain-start)",
                )
            }
            is RuntimeBackendSelectionResult.Refused ->
                RetryRunResult.SubmitRefused(
                    reason = "runtime-backend selector refused: ${selection.reason}",
                )
        }
    }

    private fun submitLocal(req: RunRequest): RetryRunResult = try {
        when (val r = localPort.submit(req)) {
            is SubmissionResult.Admitted -> RetryRunResult.Retried(
                newStudioRunId = req.runId,
                newUpstreamRunId = r.upstreamRunId,
                newControlRoot = r.controlRoot,
            )
            is SubmissionResult.AlreadyRunning -> RetryRunResult.Retried(
                newStudioRunId = req.runId,
                newUpstreamRunId = r.upstreamRunId,
                newControlRoot = java.nio.file.Path.of("/tmp/already-running"),
            )
            is SubmissionResult.Refused -> RetryRunResult.SubmitRefused(
                reason = "local submit refused: ${r.reason}",
            )
        }
    } catch (failure: Exception) {
        RetryRunResult.SubmitRefused(
            reason = "local submit failed: ${failure.javaClass.simpleName}: ${failure.message}",
        )
    }

    private suspend fun submitRemote(req: RunRequest): RetryRunResult = try {
        when (val r = remotePort.submit(req)) {
            is RemoteSubmissionResult.Admitted -> RetryRunResult.Retried(
                newStudioRunId = req.runId,
                newUpstreamRunId = r.upstreamRunId,
                newControlRoot = r.controlRoot,
            )
            is RemoteSubmissionResult.AlreadyRunning -> RetryRunResult.Retried(
                newStudioRunId = req.runId,
                newUpstreamRunId = r.upstreamRunId,
                newControlRoot = java.nio.file.Path.of("/tmp/already-running"),
            )
            is RemoteSubmissionResult.Refused -> RetryRunResult.SubmitRefused(
                reason = "remote submit refused: ${r.reason}",
            )
        }
    } catch (failure: Exception) {
        RetryRunResult.SubmitRefused(
            reason = "remote submit failed: ${failure.javaClass.simpleName}: ${failure.message}",
        )
    }
}
