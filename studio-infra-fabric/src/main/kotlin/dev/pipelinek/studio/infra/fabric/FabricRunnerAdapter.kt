@file:Suppress("TooGenericExceptionCaught")

package dev.pipelinek.studio.infra.fabric

import dev.pipelinek.studio.application.ports.RunRequest
import dev.pipelinek.studio.domain.RunId
import io.jenkins.pipelinek.fabric.contracts.model.CommandResult
import io.jenkins.pipelinek.fabric.contracts.model.Rejection
import io.jenkins.pipelinek.fabric.contracts.model.RunParameters
import io.jenkins.pipelinek.fabric.contracts.runcontrol.CancelRunCommand
import io.jenkins.pipelinek.fabric.contracts.runcontrol.RunControl
import io.jenkins.pipelinek.fabric.contracts.runcontrol.StartRunRequest
import io.jenkins.pipelinek.fabric.contracts.runcontrol.newStartRunId

/**
 * The first port of the H2.2c contract: a [FabricRunnerPort] that
 * answers over Fabric's [RunControl].
 *
 * ## What the adapter does, in one paragraph
 *
 * A Studio caller hands the adapter a [RunRequest]. The adapter
 *
 *  1. mints a fresh Fabric [StartRunRequest.commandId] (the only way
 *     Fabric can de-dup retries — the id is the caller's, not the
 *     server's, and a re-used id is a silently-dropped retry);
 *  2. sets [StartRunRequest.studioRunId] to the Studio's [RunId] so
 *     Fabric can echo it back without trusting the response;
 *  3. resolves the [StartRunRequest.packageDigest] from the injected
 *     [PackageRegistry] (a `null` answer is the adapter's own
 *     [FabricSubmissionResult.Refused] and `RunControl.start` is
 *     never called);
 *  4. sets [StartRunRequest.displayName] to `"studio:<runId>"` so a
 *     Fabric-side log can name the run without reverse-resolving the
 *     id;
 *  5. leaves [StartRunRequest.parameters] empty because the scenario
 *     descriptor travels **inside** the package, not alongside it
 *     (H2 contract, [sfw-h2-snapshot-package-contract.md] §"The
 *     3-identities rule").
 *
 * The reply is mapped: [CommandResult.Accepted] →
 * [FabricSubmissionResult.Admitted] with both identities;
 * [CommandResult.Rejected] → [FabricSubmissionResult.Refused] with a
 * human-readable reason (the [Rejection] is stringified so the HTTP
 * layer can render it without a Fabric type on the response surface);
 * [FabricTransportFailure] → [FabricSubmissionResult.Refused] (the
 * seam, see [FabricTransportFailure] KDoc).
 *
 * ## Why the [PackageRegistry] is a constructor parameter
 *
 * H2.3 owns the persistence (the SQLite-backed `(snapshotId,
 * packageDigest)` table). The H2.2c adapter declares the seam and
 * leaves the backing store to the next work-unit. The unit tests
 * pass an in-memory map; production wires a SQLite implementation.
 *
 * @param runControl Fabric's [RunControl] (a [io.jenkins.pipelinek.fabric.control.client.FabricClient]
 *   in production, a fake in unit tests). Required.
 * @param packageRegistry the [SnapshotId] → package-digest resolver.
 *   Required — a null return is the adapter's own
 *   [FabricSubmissionResult.Refused].
 */
class FabricRunnerAdapter(
    private val runControl: RunControl,
    private val packageRegistry: PackageRegistry,
) : FabricRunnerPort {

    override suspend fun submit(req: RunRequest): FabricSubmissionResult {
        // (3) Resolve the package digest BEFORE minting a commandId.
        // If the snapshot is not registered, the adapter refuses
        // without contacting Fabric at all — a missing registration
        // is a Studio-side bug, not a server-side refusal, and the
        // contract is explicit: the adapter does not call RunControl
        // when the seam answers null.
        val packageDigest: String? = packageRegistry.packageDigestFor(req.snapshotId)
        if (packageDigest == null) {
            return FabricSubmissionResult.Refused(
                "no package registered for snapshot ${req.snapshotId.value}",
            )
        }

        // (1) + (2) + (4) + (5). The commandId is the ONLY thing
        // Fabric uses to de-dup retries; reusing it across distinct
        // Studio runs is a silently-collapsed second run. The test
        // 'two submits mint distinct commandIds' pins this.
        val fabricRequest = StartRunRequest(
            commandId = newStartRunId(),
            pipelineKey = "package:$packageDigest",
            displayName = "studio:${req.runId.value}",
            parameters = RunParameters.EMPTY,
            correlation = null,
            packageDigest = packageDigest,
            studioRunId = req.runId.value,
        )

        return try {
            when (val reply = runControl.start(fabricRequest)) {
                is CommandResult.Accepted -> FabricSubmissionResult.Admitted(
                    studioRunId = req.runId,
                    fabricRunId = reply.value,
                )
                is CommandResult.Rejected -> mapRejection(reply.reason)
            }
        } catch (failure: FabricTransportFailure) {
            // The seam: a transport failure is a caller-visible
            // Refusal, not Unknown. See FabricTransportFailure KDoc.
            FabricSubmissionResult.Refused(
                "transport failure on RunControl.start: ${failure.message}",
            )
        } catch (failure: Exception) {
            // Any other unexpected failure is a Studio-side bug.
            // Refusing (rather than crashing) keeps the HTTP layer
            // honest — a 500 is a 500, not a silent Unknown.
            FabricSubmissionResult.Refused(
                "unexpected error on RunControl.start: ${failure.javaClass.simpleName}: ${failure.message}",
            )
        }
    }

    override suspend fun cancel(studioRunId: RunId): FabricCancellationResult {
        // Fabric's CancelRunCommand takes a Fabric-side RunId, but
        // the Studio caller knows the studio-side id. We use the
        // studio id as the wire's runId because (a) the H2 contract
        // promises Fabric echoes it back, and (b) using a different
        // id would require a second lookup that the Studio does not
        // have yet. H2.3 will replace this with a (studioRunId ->
        // fabricRunId) lookup.
        val command = CancelRunCommand(
            runId = io.jenkins.pipelinek.fabric.contracts.model.RunId(studioRunId.value),
            reason = null,
        )
        return try {
            when (val reply = runControl.cancel(command)) {
                is CommandResult.Accepted -> FabricCancellationResult.CancelRequested
                is CommandResult.Rejected -> mapCancellationRejection(reply.reason)
            }
        } catch (failure: FabricTransportFailure) {
            FabricCancellationResult.Refused(
                "transport failure on RunControl.cancel: ${failure.message}",
            )
        } catch (failure: Exception) {
            FabricCancellationResult.Refused(
                "unexpected error on RunControl.cancel: ${failure.javaClass.simpleName}: ${failure.message}",
            )
        }
    }

    /**
     * Map a Fabric-side [Rejection] to a Studio-side
     * [FabricSubmissionResult].
     *
     * The five arms in [Rejection] map to a `Refused` whose `reason`
     * is a short human-readable string. The "already running" case
     * (a [Rejection.Conflict] with `kind == "start"`) maps to
     * [FabricSubmissionResult.AlreadyRunning] so the HTTP layer can
     * render it as a 200 with the existing run.
     */
    private fun mapRejection(reason: Rejection): FabricSubmissionResult = when (reason) {
        is Rejection.Conflict ->
            if (reason.kind == "start" && reason.detail.contains("already running", ignoreCase = true)) {
                // No way to recover the fabricRunId from a Conflict
                // (it carries kind+id+detail, not a value). The HTTP
                // layer treats AlreadyRunning without a fabricRunId
                // as "the run exists, look it up by studioRunId" —
                // a H2.3 lookup table will provide the binding.
                FabricSubmissionResult.AlreadyRunning(
                    fabricRunId = io.jenkins.pipelinek.fabric.contracts.model.RunId(""),
                )
            } else {
                FabricSubmissionResult.Refused("conflict: ${reason.detail}")
            }
        is Rejection.NotFound ->
            FabricSubmissionResult.Refused("not found: ${reason.kind} ${reason.id}")
        is Rejection.NotPermitted ->
            FabricSubmissionResult.Refused("not permitted: ${reason.detail}")
        is Rejection.UnsupportedVersion ->
            FabricSubmissionResult.Refused(
                "unsupported version: client=${reason.found}, server=${reason.supported}",
            )
        // SFW-H2.2: the three package-side refusals on the StartRun
        // reply when packageDigest is set. Each carries data the
        // generic Conflict arm cannot, and a Studio caller benefits
        // from seeing it (e.g. for splitting the bundle on
        // PackageTooLarge). The Studio-side rendering is a
        // human-readable reason; the structured fields would be a
        // future refinement if a UI ever wants them.
        is Rejection.PackageTooLarge ->
            FabricSubmissionResult.Refused(
                "package too large: ${reason.sizeBytes} bytes > ${reason.maxBytes} max",
            )
        is Rejection.PackageDigestMismatch ->
            FabricSubmissionResult.Refused(
                "package digest mismatch: declared=${reason.declared}, actual=${reason.actual}",
            )
        is Rejection.AlreadyExistsWithDifferentBytes ->
            FabricSubmissionResult.Refused(
                "package exists with different bytes: requested=${reason.packageDigest}, " +
                    "stored=${reason.existingDigestOfBytes}",
            )
    }

    /**
     * Map a Fabric-side [Rejection] for cancel to a Studio-side
     * [FabricCancellationResult].
     *
     * The distinction the cancel call needs to honour: a
     * [Rejection.Conflict] with `kind == "cancel"` and detail
     * mentioning a terminal state is
     * [FabricCancellationResult.AlreadyTerminal], not a generic
     * `Refused`; a [Rejection.NotFound] is [FabricCancellationResult.Unknown]
     * (Fabric does not know about the run, which is a different fact
     * from "we tried to cancel and Fabric refused on grounds we do
     * not understand").
     */
    private fun mapCancellationRejection(
        reason: Rejection,
    ): FabricCancellationResult = when (reason) {
        is Rejection.Conflict ->
            if (reason.detail.contains("terminal", ignoreCase = true) ||
                reason.detail.contains("finished", ignoreCase = true) ||
                reason.detail.contains("cancelled", ignoreCase = true)
            ) {
                FabricCancellationResult.AlreadyTerminal(reason.detail)
            } else {
                FabricCancellationResult.Refused("conflict: ${reason.detail}")
            }
        is Rejection.NotFound ->
            FabricCancellationResult.Unknown("not found: ${reason.kind} ${reason.id}")
        is Rejection.NotPermitted ->
            FabricCancellationResult.Refused("not permitted: ${reason.detail}")
        is Rejection.UnsupportedVersion ->
            FabricCancellationResult.Refused(
                "unsupported version: client=${reason.found}, server=${reason.supported}",
            )
        // The three H2.2 package-side refusals never reach a cancel
        // (cancel takes a runId, not a packageDigest), but the when
        // expression must be exhaustive over the closed Rejection
        // family. Map them to a generic Refused; if a future
        // protocol version makes one reachable on cancel, the
        // mapping can be refined.
        is Rejection.PackageTooLarge ->
            FabricCancellationResult.Refused(
                "package too large on cancel (unexpected): ${reason.sizeBytes} bytes",
            )
        is Rejection.PackageDigestMismatch ->
            FabricCancellationResult.Refused("package digest mismatch on cancel (unexpected)")
        is Rejection.AlreadyExistsWithDifferentBytes ->
            FabricCancellationResult.Refused(
                "package exists with different bytes on cancel (unexpected)",
            )
    }
}
