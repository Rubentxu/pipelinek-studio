@file:Suppress("TooManyFunctions")

package dev.pipelinek.studio.infra.fabric

import dev.pipelinek.studio.application.ports.RemoteCancellationResult
import dev.pipelinek.studio.application.ports.RemoteRunnerPort
import dev.pipelinek.studio.domain.RunId

/**
 * SFW-H4.1: the [RemoteRunnerPort] implementation
 * that adapts the H2.2c [FabricRunnerPort] to the
 * use-case layer's abstract port. The adapter
 * delegates `cancel` to the underlying
 * [FabricRunnerAdapter] (the H2.2c impl) and
 * maps the 4-arm [FabricCancellationResult] to
 * the use-case layer's 4-arm
 * [RemoteCancellationResult] (identity mapping:
 * the shapes are identical by design).
 *
 * ## Why a separate adapter
 *
 * The [FabricRunnerAdapter] is the H2.2c
 * concrete implementation of the
 * [FabricRunnerPort] (the H2.2c contract). It
 * returns [FabricCancellationResult] (the H2.2c
 * type). The use-case layer's [RemoteRunnerPort]
 * is the hexagonal abstraction the
 * [dev.pipelinek.studio.application.usecases.CancelRunUseCase]
 * depends on. A separate adapter keeps the
 * layering clean: the H2.2c contract is not
 * contaminated with the use-case types, and the
 * use case is not contaminated with the H2.2c
 * types.
 */
class RemoteRunnerAdapter(
    private val fabricAdapter: FabricRunnerAdapter,
) : RemoteRunnerPort {

    override suspend fun cancel(studioRunId: RunId): RemoteCancellationResult =
        when (val r = fabricAdapter.cancel(studioRunId)) {
            is FabricCancellationResult.CancelRequested -> RemoteCancellationResult.CancelRequested
            is FabricCancellationResult.AlreadyTerminal -> RemoteCancellationResult.AlreadyTerminal(r.finalState)
            is FabricCancellationResult.Unknown -> RemoteCancellationResult.Unknown(r.reason)
            is FabricCancellationResult.Refused -> RemoteCancellationResult.Refused(r.reason)
        }
}
