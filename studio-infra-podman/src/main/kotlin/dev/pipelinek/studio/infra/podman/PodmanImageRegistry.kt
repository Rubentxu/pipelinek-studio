package dev.pipelinek.studio.infra.podman

import dev.pipelinek.studio.application.ports.RunRequest

/**
 * SFW-H5.3+ (2026-10-13): the abstract seam
 * the [PodmanRunnerAdapter] uses to resolve
 * a short image name (e.g., `studio:latest`)
 * to a fully-qualified image (e.g.,
 * `registry.local/studio:0.48.0-rc1`).
 *
 * ## Why an interface (and not a hard-coded resolution)
 *
 * A Studio that runs in a CI/CD environment
 * may want to resolve short image names via
 * a private registry. A Studio that runs in
 * a development environment may want to use
 * the local Podman image store. The interface
 * lets the production boot choose the right
 * resolution strategy.
 *
 * The default impl is [LocalPodmanImageRegistry]
 * (a no-op: the input is already fully-qualified).
 * A future H5.3+ unit may add a
 * `RegistryBackedImageRegistry` (resolves
 * short names via a registry).
 */
interface PodmanImageRegistry {
    suspend fun resolve(request: RunRequest, image: String): String
}

/**
 * SFW-H5.3+ (2026-10-13): the default
 * [PodmanImageRegistry] — a no-op that
 * returns the input image unchanged.
 * The H5.3+ chain-start used this as
 * the default; future H5.3+ units may
 * add a registry-backed impl.
 */
class LocalPodmanImageRegistry : PodmanImageRegistry {
    override suspend fun resolve(request: RunRequest, image: String): String = image
}
