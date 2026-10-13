package dev.pipelinek.studio.infra.podman

import dev.pipelinek.studio.application.ports.PodmanRunResult
import dev.pipelinek.studio.application.ports.PodmanRunnerPort
import dev.pipelinek.studio.application.ports.RunRequest
import java.nio.file.Path

/**
 * SFW-H5.3+ (2026-10-13): the [PodmanRunnerPort]
 * production adapter that actually invokes
 * `podman run` + `podman rm`. The H5.3 chain
 * start shipped a stub; this follow-up wires
 * the real subprocess invocation.
 *
 * ## What it does
 *
 * `runInContainer` invokes
 * `podman run --rm -d <image>` and parses the
 * containerId from stdout. The container runs
 * in detached mode (`-d`) so the
 * [PodmanRunnerAdapter] returns immediately
 * after the containerId is parsed. The
 * `STUDIO_HOME` is a tmpfs (`--mount type=tmpfs`)
 * inside the container; the host's control
 * root is the directory passed to
 * `runInContainer` (a future H5.3+ unit may
 * take a hostControlRoot parameter).
 *
 * `stopContainer` invokes `podman rm -f
 * <containerId>` to kill + remove the container.
 * The H5 AAT "container cleanup" relies on
 * this method.
 *
 * ## Why a `PodmanCommandRunner` interface
 *
 * The `podman` binary may not be installed on
 * every host. The `PodmanCommandRunner`
 * interface lets the tests inject a fake
 * (so the H5.3+ falsifier tests run on a
 * host without `podman`). The production impl
 * is [ProcessPodmanCommandRunner] (real
 * subprocess).
 */
class PodmanRunnerAdapter(
    private val commandRunner: PodmanCommandRunner = ProcessPodmanCommandRunner(),
) : PodmanRunnerPort {

    override suspend fun runInContainer(
        request: RunRequest,
        image: String,
    ): PodmanRunResult {
        // SFW-H5.3+ (2026-10-13): the actual
        // `podman run` invocation. The args:
        //   --rm: remove the container on exit
        //   -d:   run detached (the adapter
        //         returns immediately)
        //   --mount type=tmpfs,destination=/studio:
        //         STUDIO_HOME is a tmpfs inside
        //         the container
        //   <image>: the OCI image
        // The containerId is parsed from stdout.
        val args = listOf(
            "run",
            "--rm",
            "-d",
            "--mount", "type=tmpfs,destination=/studio",
            image,
        )
        val result = commandRunner.run(args)
        return when (result) {
            is PodmanCommandResult.Success -> {
                val containerId = result.stdout.trim()
                if (containerId.isEmpty()) {
                    PodmanRunResult.Unknown(
                        reason = "podman run succeeded but stdout was empty " +
                            "(could not parse containerId); request=${request.runId.value}",
                    )
                } else {
                    PodmanRunResult.Started(
                        containerId = containerId,
                        // The host's control root is the
                        // current working directory. A
                        // future H5.3+ unit may take a
                        // hostControlRoot parameter.
                        hostControlRoot = Path.of(""),
                    )
                }
            }
            is PodmanCommandResult.Failure -> {
                PodmanRunResult.Refused(
                    reason = "podman run failed (exit=${result.exitCode}): " +
                        "${result.stderr.trim()}; request=${request.runId.value}",
                )
            }
        }
    }

    override suspend fun stopContainer(containerId: String): PodmanRunResult {
        // SFW-H5.3+ (2026-10-13): `podman rm -f`
        // kills the container + removes it. The
        // H5 AAT "container cleanup" relies on
        // this method. A `podman rm` failure
        // (the container is already gone) is
        // treated as success (idempotent
        // cleanup).
        val result = commandRunner.run(listOf("rm", "-f", containerId))
        return when (result) {
            is PodmanCommandResult.Success -> PodmanRunResult.Started(
                containerId = containerId,
                hostControlRoot = Path.of(""),
            )
            is PodmanCommandResult.Failure -> {
                // `podman rm` returns exit code 1
                // when the container is already
                // gone. Treat that as success
                // (idempotent cleanup).
                if (result.stderr.contains("no such container", ignoreCase = true)) {
                    PodmanRunResult.Started(
                        containerId = containerId,
                        hostControlRoot = Path.of(""),
                    )
                } else {
                    PodmanRunResult.Refused(
                        reason = "podman rm failed (exit=${result.exitCode}): " +
                            "${result.stderr.trim()}; containerId=$containerId",
                    )
                }
            }
        }
    }
}
