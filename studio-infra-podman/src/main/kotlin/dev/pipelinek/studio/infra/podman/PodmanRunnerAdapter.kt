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
 * ## Network policy (H5.3+ second follow-up)
 *
 * The H5.3+ second follow-up adds a configurable
 * network policy via the [network] constructor
 * parameter. The default is `"host"` (the
 * container shares the host's network namespace;
 * this is the right default for a Studio that
 * needs to call the PipelineK subprocess
 * supervisor). Operators that need a more
 * locked-down environment can pass `"none"`
 * (the container has no network; the
 * supervisor must be reachable via a sidecar).
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
    /**
     * SFW-H5.3+ (2026-10-13): the image
     * registry the adapter uses to resolve
     * short image names. The default is
     * [LocalPodmanImageRegistry] (a no-op:
     * the input is already fully-qualified).
     * Future H5.3+ units may add a
     * registry-backed impl.
     */
    private val imageRegistry: PodmanImageRegistry = LocalPodmanImageRegistry(),
    /**
     * SFW-H5.3+ (2026-10-13): the network
     * policy passed to `podman run --network`.
     * Defaults to `"host"` (the container
     * shares the host's network namespace;
     * required for a Studio that needs to
     * call the PipelineK subprocess
     * supervisor). Operators that need a
     * locked-down environment can pass
     * `"none"`. The H5.3+ second follow-up
     * pins the network policy as a
     * constructor parameter (not a
     * per-request parameter) so the policy
     * is consistent across all containers
     * in a production boot.
     */
    private val network: String = "host",
    /**
     * SFW-H5.3+ (2026-10-13): the Podman
     * socket path passed to `podman --url`.
     * When `null` (the default), the
     * adapter uses the `podman` CLI's
     * default socket resolution (which
     * respects `$XDG_RUNTIME_DIR` for the
     * current user; rootless by default).
     *
     * ## Format
     *
     * The value is either:
     * - A bare path (e.g. `"/var/run/podman/podman.sock"`):
     *   the adapter prepends `unix://` and
     *   passes `--url=unix://<socketPath>`
     *   to every `podman` invocation. This
     *   is the H5.3+ fourth follow-up's
     *   contract (backwards-compatible).
     * - A full URL with scheme (e.g.
     *   `"tcp://podman-host.example.com:2375"`):
     *   the adapter passes the value as-is.
     *   This is the H5.3+ fifteenth
     *   follow-up's contract — supports
     *   remote Podman over TCP.
     *
     * Operators that need rootful Podman
     * pass `"/var/run/podman/podman.sock"`.
     * Operators that need remote Podman
     * pass `"tcp://<host>:<port>"`.
     *
     * ## Why a constructor parameter
     *
     * The socket is a property of the
     * production boot, not a per-request
     * decision. A Studio that wants
     * rootful Podman sets the socket at
     * boot time; all containers in that
     * boot share the socket.
     */
    private val socketPath: String? = null,
) : PodmanRunnerPort {

    private val socketUrl: String? = socketPath?.let { path ->
        // The H5.3+ 15th follow-up: detect
        // an explicit scheme. A bare path
        // gets the unix:// prefix (the
        // H5.3+ 4th follow-up's contract).
        if (path.startsWith("unix://") || path.startsWith("tcp://")) {
            path
        } else {
            "unix://$path"
        }
    }

    override suspend fun runInContainer(
        request: RunRequest,
        image: String,
        hostControlRoot: Path,
    ): PodmanRunResult {
        // SFW-H5.3+ (2026-10-13): the actual
        // `podman run` invocation. The args:
        //   --rm: remove the container on exit
        //   -d:   run detached (the adapter
        //         returns immediately)
        //   --network <network>: the network
        //         policy (host by default;
        //         operators can pass "none"
        //         via the constructor)
        //   --mount type=bind,source=<hostControlRoot>,
        //     destination=/studio: the host's
        //     control root is bound into the
        //     container at /studio. The
        //     supervisor inside the container
        //     reads /studio as the host's
        //     control root.
        //   <image>: the OCI image
        // The containerId is parsed from stdout.
        val resolvedImage = imageRegistry.resolve(request, image)
        val args = buildList {
            if (socketUrl != null) {
                add("--url")
                add(socketUrl)
            }
            add("run")
            add("--rm")
            add("-d")
            add("--network"); add(network)
            add("--mount"); add("type=bind,source=${hostControlRoot.toAbsolutePath()},destination=/studio")
            add(resolvedImage)
        }
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
                        hostControlRoot = hostControlRoot,
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
        val args = buildList {
            if (socketUrl != null) {
                add("--url")
                add(socketUrl)
            }
            add("rm")
            add("-f")
            add(containerId)
        }
        val result = commandRunner.run(args)
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
