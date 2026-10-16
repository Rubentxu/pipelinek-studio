package dev.pipelinek.studio.infra.fabric

import dev.pipelinek.studio.application.ports.RunRequest
import dev.pipelinek.studio.application.ports.StudioFabricCancellationResult
import dev.pipelinek.studio.application.ports.StudioFabricGateway
import dev.pipelinek.studio.application.ports.StudioFabricSubmissionResult
import dev.pipelinek.studio.domain.RunId

/**
 * SFW F4 Post-Bump Part 11 — the canonical SFW ADR-014 port adapter.
 *
 * Implements [StudioFabricGateway] (the application-layer, pure-Kotlin
 * port introduced in F4 Post-Bump Part 10) by **delegating** to the
 * existing H2.2c [FabricRunnerPort] implementation. The heavy lifting
 * (cancellation semantics, dedup, replay, `PackageRegistry` resolution)
 * is done by the H2.2c adapter; this shell only converts the wire-side
 * ADTs into pure-Kotlin ADTs and back.
 *
 * ## Why a separate adapter (and not have [FabricRunnerAdapter] also
 *    implement [StudioFabricGateway])
 *
 *  - Strict layering: [StudioFabricGateway] lives in `studio-application`;
 *    [FabricRunnerPort] lives in `studio-infra-fabric`. Combining both
 *    contracts on a single class would force a layering inversion
 *    (the Fabric implementation would carry app-layer ADTs, but the
 *    port shape stays infra-only for callers that don't care about
 *    the canonical name).
 *  - Use-case migration becomes a wiring change: when (and if) a future
 *    unit moves the 3 SFW-H6/H4 use cases onto [StudioFabricGateway],
 *    the wiring switch is one line (pass `StudioFabricGatewayAdapter`
 *    instead of `FabricRunnerAdapter`). No adapter internals change.
 *  - Test surface: the delegated adapter is testable with a fake
 *    `FabricRunnerPort`, independent of `FabricRunnerAdapter` and
 *    its transitive dependency on `RunControl` from the contracts JAR.
 *
 * ## How the ADT mapping works
 *
 *  - Submission: [FabricSubmissionResult.Admitted] carries a `FabricRunId`
 *    (an `io.jenkins.pipelinek.fabric.contracts.model.RunId` value class
 *    wrapping a String). The ADT mapping unwraps to a plain `String` so
 *    callers of [StudioFabricGateway] never see Fabric gRPC types.
 *  - Cancellation: 1:1 identity. The 4 arms map name-to-name; the
 *    `Refused.reason` and `Unknown.reason` strings flow through unchanged.
 *
 * @param delegate the H2.2c [FabricRunnerPort] implementation (typically
 *   [FabricRunnerAdapter] in production, a fake in tests).
 */
class StudioFabricGatewayAdapter(
    private val delegate: FabricRunnerPort,
) : StudioFabricGateway {

    override suspend fun submit(req: RunRequest): StudioFabricSubmissionResult =
        when (val r = delegate.submit(req)) {
            is FabricSubmissionResult.Admitted -> StudioFabricSubmissionResult.Admitted(
                studioRunId = req.runId,
                fabricRunId = r.fabricRunId.value,
            )
            is FabricSubmissionResult.Refused -> StudioFabricSubmissionResult.Refused(
                reason = r.reason,
            )
            is FabricSubmissionResult.AlreadyRunning -> StudioFabricSubmissionResult.AlreadyRunning(
                fabricRunId = r.fabricRunId.value,
            )
        }

    override suspend fun cancel(studioRunId: RunId): StudioFabricCancellationResult =
        when (val r = delegate.cancel(studioRunId)) {
            is FabricCancellationResult.CancelRequested -> StudioFabricCancellationResult.CancelRequested
            is FabricCancellationResult.AlreadyTerminal -> StudioFabricCancellationResult.AlreadyTerminal(
                finalState = r.finalState,
            )
            is FabricCancellationResult.Unknown -> StudioFabricCancellationResult.Unknown(
                reason = r.reason,
            )
            is FabricCancellationResult.Refused -> StudioFabricCancellationResult.Refused(
                reason = r.reason,
            )
        }
}
