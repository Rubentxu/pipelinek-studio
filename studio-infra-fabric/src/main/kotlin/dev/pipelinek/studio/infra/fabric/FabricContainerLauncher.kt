package dev.pipelinek.studio.infra.fabric

import dev.pipelinek.studio.infra.podman.PodmanCommandResult
import dev.pipelinek.studio.infra.podman.PodmanCommandRunner
import java.util.concurrent.atomic.AtomicReference

/**
 * SFW H1 Phase B (Studio side): the launcher
 * for the Fabric server container. A thin
 * wrapper over [PodmanCommandRunner] that
 * starts the Fabric image with the right
 * port mapping and tracks the container ID
 * for `stop()`.
 *
 * # Why a launcher (and not a script)
 *
 * A production boot that runs Studio as
 * one process + the Fabric server as a
 * sibling container needs a single
 * composition root that starts and stops
 * both. A launcher is a programmatic way to
 * do that; a script would couple the boot
 * to an external file.
 *
 * # The protocol
 *
 * - `start()` invokes
 *   `podman run -d --rm -p <host>:<container> --name <name> <image>`.
 *   The container ID is parsed from
 *   stdout (the `application` plugin's
 *   `startScripts` returns the container ID
 *   on the first line of stdout).
 * - `stop()` invokes
 *   `podman rm -f <container-id>`.
 *   Idempotent: `podman rm` returns exit
 *   code 1 when the container is already
 *   gone; the launcher treats that as
 *   success.
 *
 * # The dependency
 *
 * The launcher depends on
 * [PodmanCommandRunner] (an abstract port)
 * rather than `PodmanRunnerAdapter` (the
 * production impl). This is the same
 * pattern as `FabricRunnerAdapter` vs
 * `FabricRunnerPort`: tests inject a fake
 * `PodmanCommandRunner` and don't need
 * the real subprocess.
 */
class FabricContainerLauncher(
    private val podman: PodmanCommandRunner,
    /**
     * The image name + tag (e.g.,
     * `"fabric-server:0.5.0-R1-alpha.8"`).
     * Set by the operator at boot time
     * (e.g., from a config file or env
     * var).
     */
    private val image: String,
    /**
     * The container name (e.g.,
     * `"fabric-server"`). Must be unique
     * per host; `podman run` will fail
     * if a container with the same name
     * already exists.
     */
    private val containerName: String = "fabric-server",
    /**
     * The host port the container's
     * gRPC port is mapped to. The
     * container's internal gRPC port
     * is fixed at 50051 (per the
     * Fabric server's
     * `application.conf`).
     */
    private val hostPort: Int = 50051,
    /**
     * The container's internal gRPC
     * port. Default 50051 (per the
     * Fabric server's config).
     */
    private val containerPort: Int = 50051,
    /**
     * The host name the container
     * binds to. Default `127.0.0.1`
     * (loopback) — Studio on the same
     * host reaches the Fabric server
     * via `localhost:50051`. For a
     * remote Fabric container, set
     * to `0.0.0.0` (or omit `-p` and
     * use `--network host`).
     */
    private val hostBind: String = "127.0.0.1",
) {
    private val containerIdRef = AtomicReference<String?>(null)

    /**
     * The container ID, or `null` if
     * `start()` has not been called
     * (or the container was stopped).
     */
    val containerId: String?
        get() = containerIdRef.get()

    /**
     * The gRPC endpoint URL (e.g.,
     * `http://127.0.0.1:50051`). Returns
     * `null` if the container has not
     * been started.
     */
    val endpoint: String?
        get() = containerIdRef.get()?.let { "http://$hostBind:$hostPort" }

    /**
     * Start the Fabric container. Returns
     * a [PodmanCommandResult]; the caller
     * checks the result type.
     *
     * If a container with the same name
     * is already running, `podman run`
     * will fail; the caller should call
     * `stop()` first.
     */
    suspend fun start(): PodmanCommandResult {
        val args = buildList {
            add("run")
            add("-d")          // detached
            add("--rm")        // remove on exit
            add("--name")
            add(containerName)
            add("-p")
            add("$hostBind:$hostPort:$containerPort")
            add(image)
        }
        val result = podman.run(args)
        return when (result) {
            is PodmanCommandResult.Success -> {
                val id = result.stdout.trim()
                if (id.isEmpty()) {
                    PodmanCommandResult.Failure(
                        stderr = "podman run succeeded but stdout was empty; args=${args.joinToString(" ")}",
                        exitCode = -1,
                    )
                } else {
                    containerIdRef.set(id)
                    result
                }
            }
            is PodmanCommandResult.Failure -> result
        }
    }

    /**
     * Stop the Fabric container. Returns
     * a [PodmanCommandResult]. Idempotent:
     * a non-existent container is
     * treated as success (exit code 1
     * from `podman rm` is swallowed).
     */
    suspend fun stop(): PodmanCommandResult {
        val id = containerIdRef.get() ?: run {
            // No ID recorded; try by name.
            // `podman rm` accepts the
            // name as well as the ID.
            containerName
        }
        val args = listOf("rm", "-f", id)
        val result = podman.run(args)
        return when (result) {
            is PodmanCommandResult.Success -> {
                containerIdRef.set(null)
                result
            }
            is PodmanCommandResult.Failure -> {
                if (result.stderr.contains("no such container", ignoreCase = true)) {
                    containerIdRef.set(null)
                    PodmanCommandResult.Success(stdout = "", exitCode = 0)
                } else {
                    result
                }
            }
        }
    }
}
