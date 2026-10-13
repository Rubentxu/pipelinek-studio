package dev.pipelinek.studio.infra.podman

import dev.pipelinek.studio.application.ports.PodmanRunResult
import dev.pipelinek.studio.application.ports.PodmanRunnerPort
import dev.pipelinek.studio.application.ports.RunRequest

/**
 * SFW-H5.3 (2026-10-13): the stub [PodmanRunnerPort]
 * production adapter. H5.3 ships the abstract
 * surface; the actual `podman run` invocation
 * (the H5 UAT "podman-container run") is a
 * follow-up H5.3+ unit.
 *
 * ## What it does
 *
 * For H5.3, the adapter returns
 * [PodmanRunResult.Refused] with a "not yet
 * wired" reason. A production boot that wires
 * this adapter will see every Podman path
 * request surface as a refusal (the
 * conservative path; the operator can retry
 * or fall back to [dev.pipelinek.studio.application.ports.RuntimeBackend.Local]).
 *
 * ## Why a stub
 *
 * The H5.3 chain-start discipline mirrors the
 * H5.1 chain-start: ship the abstract surface
 * + a stub adapter that is honest about the
 * unwired state. The actual `podman run`
 * invocation requires:
 * 1. a Podman socket (the H5.3+ follow-up
 *    will pick between rootful + rootless
 *    based on the host config);
 * 2. an OCI image registry (the H5.3+
 *    follow-up will use the local Podman
 *    image store);
 * 3. a volume mount for the control root
 *    (the H5.3+ follow-up will use a
 *    tmpfs for the container's
 *    `STUDIO_HOME`);
 * 4. a network policy (the H5.3+ follow-up
 *    will use the host network by default,
 *    with an opt-in for `--network=none`).
 *
 * H5.3 ships the surface; the H5.3+ follow-up
 * wires the real `podman run` invocation.
 */
class PodmanRunnerAdapter : PodmanRunnerPort {

    override suspend fun runInContainer(
        request: RunRequest,
        image: String,
    ): PodmanRunResult = PodmanRunResult.Refused(
        reason = "H5.3 chain-start: the PodmanRunnerAdapter is a stub; " +
            "the actual podman run invocation is a follow-up H5.3+ unit. " +
            "request=${request.runId.value} image=$image",
    )

    override suspend fun stopContainer(containerId: String): PodmanRunResult =
        PodmanRunResult.Refused(
            reason = "H5.3 chain-start: the PodmanRunnerAdapter.stopContainer " +
                "is a stub; the actual podman stop + rm invocation is a " +
                "follow-up H5.3+ unit. containerId=$containerId",
        )
}
