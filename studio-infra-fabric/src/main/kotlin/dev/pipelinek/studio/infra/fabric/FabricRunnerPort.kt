@file:Suppress("TooGenericExceptionCaught")

package dev.pipelinek.studio.infra.fabric

import dev.pipelinek.studio.application.ports.RunRequest
import dev.pipelinek.studio.domain.RunId
import dev.pipelinek.studio.domain.SnapshotId
import io.jenkins.pipelinek.fabric.contracts.model.CommandResult
import io.jenkins.pipelinek.fabric.contracts.model.RunId as FabricRunId
import io.jenkins.pipelinek.fabric.contracts.runcontrol.CancelRunCommand
import io.jenkins.pipelinek.fabric.contracts.runcontrol.RunControl
import io.jenkins.pipelinek.fabric.contracts.runcontrol.StartRunRequest

/**
 * The Studio-side write port for a remote run, answered by a Fabric
 * server over gRPC.
 *
 * ## Why this is a sibling of [dev.pipelinek.studio.application.ports.PipelineRunnerPort],
 *    not a replacement
 *
 * The existing local port returns
 * `SubmissionResult.Admitted(upstreamRunId, controlRoot: Path)` where
 * `controlRoot` is a **local** path the Studio writes the snapshot to
 * before spawning its SDK subprocess. A remote runner has no such
 * directory: the package bytes live on the Fabric server, and a Studio
 * that tried to honour a `controlRoot` would either fabricate one
 * (silent lie) or change the existing port's shape (breaking change
 * to a sealed interface that has many call sites).
 *
 * `FabricRunnerPort` keeps the two surfaces separate. The HTTP /
 * use-case layer is the place that decides which to use; the local
 * port keeps its existing shape, the remote port declares its own.
 *
 * ## Why the result is `(studioRunId, fabricRunId)` and not just one
 *
 * The H2 contract is "three identities on the wire": `studioRunId`
 * (the id the Studio minted before the call), `commandId` (the
 * caller-minted `StartRunId` Fabric de-duplicates retries on, minted by
 * the adapter), and `fabricRunId` (the id **Fabric assigns**, returned
 * in `CommandResult.Accepted`). Collapsing them would force a caller to
 * choose between "I want my id" and "I want Fabric's id" — a choice
 * the contract does not let the caller make. Note that `commandId` and
 * `fabricRunId` are *different* identities: a run to cancel is named by
 * `fabricRunId`, never by `commandId` and never by `studioRunId`.
 */
interface FabricRunnerPort {

    /**
     * Order a run into existence on the Fabric server.
     *
     * @return [FabricSubmissionResult.Admitted] with both identities,
     *   [FabricSubmissionResult.Refused] with a human-readable reason,
     *   or [FabricSubmissionResult.AlreadyRunning] when Fabric says a
     *   run for this [RunRequest.runId] is already in flight.
     */
    suspend fun submit(req: RunRequest): FabricSubmissionResult

    /**
     * Order a run to stop.
     *
     * The argument is the **Studio** identity; the identity on the wire
     * is Fabric's. An implementation resolves the persisted Fabric
     * `RunId` (P0-04) and cancels through that value only; it must never
     * promote the Studio id into a Fabric id, because Fabric assigns its
     * own run ids and an unresolvable identity would address the wrong
     * run while reporting an effect.
     *
     * Only an accepted cancel is reported as
     * [FabricCancellationResult.CancelRequested] — an "intent" — and no
     * terminal state is synthesised from it.
     *
     * @return [FabricCancellationResult.CancelRequested] when the
     *   cancel was accepted, [FabricCancellationResult.AlreadyTerminal]
     *   when Fabric says the run is already in a terminal state,
     *   [FabricCancellationResult.Unknown] when there is no persisted
     *   binding (or Fabric does not know the run — in both cases no
     *   cancel was dispatched), or [FabricCancellationResult.Refused]
     *   for any other refusal, including an unreadable binding.
     */
    suspend fun cancel(studioRunId: RunId): FabricCancellationResult
}

/**
 * The result of [FabricRunnerPort.submit], remote-shaped.
 *
 * Distinct from [dev.pipelinek.studio.application.ports.SubmissionResult]
 * because the wire-side has no `controlRoot`; see [FabricRunnerPort] KDoc
 * for the rationale.
 */
sealed interface FabricSubmissionResult {

    /**
     * The run was admitted on the Fabric server. Both identities are
     * returned: [studioRunId] is the id the Studio minted, [fabricRunId]
     * is the id **Fabric assigned** and echoed in
     * `CommandResult.Accepted` — not the caller-minted `commandId` from
     * `StartRunRequest`, which is a separate de-duplication identity.
     *
     * This value is what a cancel must address (see
     * [FabricRunnerPort.cancel]).
     */
    data class Admitted(
        val studioRunId: RunId,
        val fabricRunId: FabricRunId,
    ) : FabricSubmissionResult

    /**
     * The Fabric server refused the call.
     *
     * [reason] is a human-readable string the HTTP layer can render
     * directly. The five [io.jenkins.pipelinek.fabric.contracts.model.Rejection]
     * arms the H2 contract admits are mapped to "conflict: ...", "not
     * found: kind", "not permitted: ...", "unsupported version: ..."
     * — the form is documented in the adapter's `mapRejection`.
     */
    data class Refused(val reason: String) : FabricSubmissionResult

    /**
     * The Fabric server says a run for this [studioRunId] is already
     * in flight. Distinct from [Refused] because the HTTP layer can
     * render it as a 200 with the existing run, not as a 4xx.
     */
    data class AlreadyRunning(val fabricRunId: FabricRunId) : FabricSubmissionResult
}

/**
 * The result of [FabricRunnerPort.cancel], remote-shaped.
 */
sealed interface FabricCancellationResult {
    /** The cancel was accepted by Fabric. */
    data object CancelRequested : FabricCancellationResult

    /** The run is already in a terminal state; [finalState] is what Fabric reported. */
    data class AlreadyTerminal(val finalState: String) : FabricCancellationResult

    /** Fabric does not know about this run — or the Studio has no
     * persisted binding for it. In both cases no cancel was dispatched.
     */
    data class Unknown(val reason: String) : FabricCancellationResult

    /** The cancel was refused for any other reason — including a binding
     * that could not be read; in that case the cancel was not dispatched.
     */
    data class Refused(val reason: String) : FabricCancellationResult
}

/**
 * The Studio-side mirror of Fabric's `TransportFailure`.
 *
 * The contract is: a transport failure is a **caller-visible refusal**,
 * not `Unknown`. A Studio that returned `Unknown` for "the server did
 * not answer" would be collapsing two facts the use case must tell
 * apart: "we have no record" (the local runner's `Unknown`) and
 * "we could not reach whoever would have the record" (the remote
 * runner's `Refused`). The adapter catches this exception from the
 * Fabric-side `RunControl` and renders it as
 * [FabricSubmissionResult.Refused] / [FabricCancellationResult.Refused].
 *
 * Production wiring (H2.2c+1 / E2E): a [dev.pipelinek.studio.infra.fabric.wire.FabricClientFactory]
 * catches `io.grpc.StatusRuntimeException` from gRPC and re-throws
 * this. For H2.2c the unit tests throw it directly; the wire-up is
 * out of scope.
 */
class FabricTransportFailure(
    val callName: String,
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

/**
 * Resolves a Studio [SnapshotId] to the Fabric package digest that
 * ships its bytes.
 *
 * H2.2c takes this as a function (the seam). H2.3 backs it with a
 * SQLite table (`studio.run_bindings`).
 */
fun interface PackageRegistry {
    fun packageDigestFor(snapshotId: SnapshotId): String?
}
