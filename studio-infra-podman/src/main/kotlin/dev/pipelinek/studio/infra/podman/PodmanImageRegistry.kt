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

/**
 * SFW-H5.3+ (2026-10-13): the production
 * [PodmanRegistryClient] that uses the
 * Docker Registry V2 manifest API
 * (`/v2/<name>/manifests/<tag>`).
 *
 * ## The flow
 *
 * 1. GET `$registryBaseUrl/v2/$name/manifests/$tag`
 *    with `Accept: application/vnd.docker.distribution.manifest.v2+json`
 * 2. Parse the response:
 *    - 200: extract the digest (sha256:...) from
 *      the `Docker-Content-Digest` header
 *    - 401: try the auth flow (Bearer token
 *      from `/v2/auth`); retry with the token
 *    - 404: return `null` (image not found)
 *    - other: return `null` (conservative)
 * 3. Return the fully-qualified image:
 *    `$registryBaseUrl/$name@<digest>`
 *
 * ## Why a separate port (HttpRequestSender)
 *
 * The `java.net.http.HttpClient` is not
 * easy to fake in tests. A port lets the
 * test inject a fake `HttpRequestSender`
 * that records the call + returns a
 * deterministic response.
 */
class JavaPodmanRegistryClient(
    private val httpSender: HttpRequestSender = JavaHttpRequestSender(),
) : PodmanRegistryClient {

    override suspend fun lookupImage(registryBaseUrl: String, image: String, tag: String): String? {
        val url = "$registryBaseUrl/v2/$image/manifests/$tag"
        val manifestHeaders = mapOf(
            "Accept" to "application/vnd.docker.distribution.manifest.v2+json",
        )
        val firstResponse = try {
            httpSender.get(url, manifestHeaders)
        } catch (failure: Exception) {
            return null
        }
        when (firstResponse.statusCode) {
            200 -> {
                val digest = firstResponse.headers["Docker-Content-Digest"]
                if (digest == null) return null
                return "$registryBaseUrl/$image@$digest"
            }
            404 -> return null
            401 -> {
                // SFW-H5.3+ 9th follow-up: the
                // Bearer token auth flow. The 401
                // response includes a
                // WWW-Authenticate header that
                // names the auth realm + service.
                // We GET the realm with the
                // service + scope parameters to
                // get a Bearer token, then retry
                // the original request with the
                // token.
                val authHeader = firstResponse.headers["WWW-Authenticate"]
                    ?: return null
                val (realm, service) = parseWwwAuthenticate(authHeader)
                    ?: return null
                val token = fetchBearerToken(
                    realm = realm,
                    service = service,
                    image = image,
                ) ?: return null
                val authedHeaders = manifestHeaders + mapOf(
                    "Authorization" to "Bearer $token",
                )
                val retryResponse = try {
                    httpSender.get(url, authedHeaders)
                } catch (failure: Exception) {
                    return null
                }
                if (retryResponse.statusCode != 200) return null
                val digest = retryResponse.headers["Docker-Content-Digest"]
                if (digest == null) return null
                return "$registryBaseUrl/$image@$digest"
            }
            else -> return null
        }
    }

    /**
     * SFW-H5.3+ 9th follow-up: parse the
     * `WWW-Authenticate: Bearer realm="...",
     * service="..."` header. The format is
     * a `Bearer` scheme + a comma-separated
     * list of key="value" pairs. Returns
     * (realm, service) or `null` when the
     * header is malformed.
     */
    private fun parseWwwAuthenticate(header: String): Pair<String, String>? {
        if (!header.startsWith("Bearer ", ignoreCase = true)) return null
        val params = header.removePrefix("Bearer ").trim()
        var realm: String? = null
        var service: String? = null
        for (part in params.split(",")) {
            val trimmed = part.trim()
            val eqIdx = trimmed.indexOf('=')
            if (eqIdx < 0) continue
            val key = trimmed.substring(0, eqIdx).trim()
            var value = trimmed.substring(eqIdx + 1).trim()
            if (value.startsWith("\"") && value.endsWith("\"") && value.length >= 2) {
                value = value.substring(1, value.length - 1)
            }
            if (key == "realm") realm = value
            if (key == "service") service = value
        }
        if (realm == null || service == null) return null
        return realm to service
    }

    /**
     * SFW-H5.3+ 9th follow-up: fetch a Bearer
     * token from the registry's auth endpoint.
     * The endpoint is `$realm?service=$service&
     * scope=repository:$image:pull`. The response
     * is JSON: `{"token": "..."}`. Returns
     * the token or `null` on error.
     */
    private fun fetchBearerToken(realm: String, service: String, image: String): String? {
        val authUrl = "$realm?service=$service&scope=repository:$image:pull"
        val response = try {
            httpSender.get(authUrl, emptyMap())
        } catch (failure: Exception) {
            return null
        }
        if (response.statusCode != 200) return null
        // Minimal JSON extraction: the token is
        // a string value in `{"token": "..."}`.
        // Avoid pulling in a JSON dependency.
        val match = Regex("\"token\"\\s*:\\s*\"([^\"]+)\"").find(response.body)
        return match?.groupValues?.get(1)
    }
}
