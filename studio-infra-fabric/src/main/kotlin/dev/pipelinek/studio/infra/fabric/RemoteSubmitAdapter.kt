@file:Suppress("TooManyFunctions")

package dev.pipelinek.studio.infra.fabric

import dev.pipelinek.studio.application.ports.RemoteSubmissionResult
import dev.pipelinek.studio.application.ports.RemoteSubmitPort
import dev.pipelinek.studio.application.ports.RunRequest

/**
 * SFW-H4.3: the [RemoteSubmitPort] implementation
 * that adapts the H2.2c `FabricRunnerAdapter.submit`
 * to the use-case layer's abstract port. The
 * adapter delegates `submit` to the H2.2c
 * adapter and maps the 3-arm
 * [FabricSubmissionResult] to the 3-arm
 * [RemoteSubmissionResult] (near-identity
 * mapping; the `Admitted.fabricRunId` is mapped
 * to `upstreamRunId`; the `controlRoot` is a
 * placeholder for the Fabric path — Fabric
 * does not have a local `controlRoot`).
 */
class RemoteSubmitAdapter(
    private val fabricAdapter: FabricRunnerAdapter,
) : RemoteSubmitPort {

    override suspend fun submit(req: RunRequest): RemoteSubmissionResult =
        when (val r = fabricAdapter.submit(req)) {
            is FabricSubmissionResult.Admitted -> RemoteSubmissionResult.Admitted(
                upstreamRunId = r.fabricRunId.value,
                controlRoot = java.nio.file.Path.of("/tmp/fabric-${r.fabricRunId.value}"),
            )
            is FabricSubmissionResult.AlreadyRunning -> RemoteSubmissionResult.AlreadyRunning(
                upstreamRunId = r.fabricRunId.value,
            )
            is FabricSubmissionResult.Refused -> RemoteSubmissionResult.Refused(r.reason)
        }
}
