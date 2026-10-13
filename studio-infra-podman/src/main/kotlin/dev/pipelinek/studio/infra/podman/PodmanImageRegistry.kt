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

/**
 * SFW-H5.3+ (2026-10-13): the abstract
 * port the [RegistryBackedPodmanImageRegistry]
 * uses to query a private registry. The
 * port is a thin wrapper around the
 * Docker Registry V2 API (`/v2/<name>/manifests/<tag>`).
 *
 * ## Why a port (and not a direct HTTP call)
 *
 * The Docker Registry V2 API requires an
 * HTTP client + auth (Bearer token from
 * `/v2/auth`). A port lets the test
 * infrastructure inject a fake; the
 * production impl is a real HTTP client
 * (the H5.3+ 8th follow-up will add it).
 */
interface PodmanRegistryClient {
    suspend fun lookupImage(registryBaseUrl: String, image: String, tag: String): String?
}

/**
 * SFW-H5.3+ (2026-10-13): the noop
 * [PodmanRegistryClient] — returns
 * `null` for every input. The H5.3+
 * 7th follow-up uses this for tests
 * that don't exercise the registry
 * lookup (the default behavior is
 * "no resolution; the image is used
 * as-is").
 */
class NoopPodmanRegistryClient : PodmanRegistryClient {
    override suspend fun lookupImage(registryBaseUrl: String, image: String, tag: String): String? = null
}

/**
 * SFW-H5.3+ (2026-10-13): the
 * [PodmanImageRegistry] that queries
 * a private registry via the
 * [PodmanRegistryClient]. When the
 * client returns a non-null result
 * (the fully-qualified image), the
 * registry returns the resolved
 * image. When the client returns
 * `null` (the registry doesn't know
 * about the image), the registry
 * returns the input image as-is
 * (the conservative path; the
 * caller may have already provided
 * a fully-qualified image).
 *
 * ## Why the conservative fallback
 *
 * A registry that returns `null`
 * (the image is unknown) is a
 * transient failure (network error,
 * auth failure, etc.). Returning the
 * input image as-is lets the caller
 * proceed (the `podman run` will fail
 * with a clear error if the image is
 * actually unknown). A `Refused`
 * surface would be more aggressive
 * but would couple the dedup use
 * case to transient network errors.
 */
class RegistryBackedPodmanImageRegistry(
    private val client: PodmanRegistryClient,
    private val registryBaseUrl: String,
) : PodmanImageRegistry {

    override suspend fun resolve(request: RunRequest, image: String): String {
        // If the image already contains a `/`,
        // it's namespaced. Skip the registry
        // lookup (the image is already
        // fully-qualified).
        if ('/' in image) return image

        // Split the image into the name + tag
        // (the registry's lookup API takes
        // them separately). A `:` separates
        // the tag; no `:` means `latest`.
        val parts = image.split(":")
        val name = parts[0]
        val tag = if (parts.size > 1) parts[1] else "latest"

        val resolved = client.lookupImage(registryBaseUrl, name, tag)
        return resolved ?: image
    }
}
