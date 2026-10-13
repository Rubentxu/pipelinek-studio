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

/**
 * SFW-H5.3+ (2026-10-13): a
 * [PodmanImageRegistry] that prefixes
 * every short image name with a
 * configured prefix. Useful for
 * production boots that pull from a
 * private registry (e.g.,
 * `registry.local/studio:0.48.0-rc1`).
 *
 * ## The dispatch
 *
 * If the input image already contains a
 * `/` (i.e., it's already a namespaced
 * image like `quay.io/podman/hello` or
 * `registry.local/studio`), the registry
 * returns the input as-is (the image is
 * already fully-qualified). Otherwise, the
 * registry prefixes the image:
 * `studio:latest` → `<imagePrefix>/studio:latest`.
 *
 * ## Why a prefix (and not a full registry lookup)
 *
 * A full registry lookup (HTTP call to
 * `https://registry.local/v2/...`) requires
 * an HTTP client + auth. A prefix-based
 * resolution is sufficient for most
 * production boots (the registry is
 * configured; the operator knows the
 * prefix). A future H5.3+ unit may add a
 * full registry-backed impl (auth +
 * manifest lookup + tag resolution).
 *
 * @param imagePrefix the prefix to add to
 *   short image names. Must NOT include a
 *   trailing `/` (the registry adds it).
 */
class PrefixingPodmanImageRegistry(
    private val imagePrefix: String,
) : PodmanImageRegistry {
    init {
        require(!imagePrefix.endsWith("/")) {
            "imagePrefix must not end with '/' (got '$imagePrefix')"
        }
        require(imagePrefix.isNotEmpty()) {
            "imagePrefix must not be empty"
        }
    }

    override suspend fun resolve(request: RunRequest, image: String): String {
        // If the image already contains a `/`,
        // it's namespaced (e.g.,
        // `quay.io/podman/hello` or
        // `registry.local/studio`). Return as-is.
        if ('/' in image) return image
        return "$imagePrefix/$image"
    }
}
