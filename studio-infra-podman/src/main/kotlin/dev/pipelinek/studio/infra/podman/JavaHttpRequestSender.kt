package dev.pipelinek.studio.infra.podman

/**
 * SFW-H5.3+ (2026-10-13): the abstract
 * port the [JavaPodmanRegistryClient] uses
 * to send HTTP requests. The port is a
 * thin wrapper around `java.net.http.HttpClient`
 * that lets the test infrastructure inject
 * a fake; the production impl is
 * [JavaHttpRequestSender].
 */
interface HttpRequestSender {
    /**
     * Send an HTTP GET request to the given
     * URL with the given headers. Returns
     * the response status code + body, or
     * throws on network error.
     */
    fun get(url: String, headers: Map<String, String>): HttpResponse
}

data class HttpResponse(
    val statusCode: Int,
    val body: String,
    val headers: Map<String, String>,
)

/**
 * SFW-H5.3+ (2026-10-13): the production
 * [HttpRequestSender] that uses
 * `java.net.http.HttpClient`. The client
 * is created once and reused (the
 * `java.net.http.HttpClient` is thread-safe).
 *
 * SFW-H5.3+ 10th follow-up: optional custom
 * [javax.net.ssl.SSLContext] for private
 * registries with self-signed certs. When
 * `null` (the default), the JVM's default
 * `SSLContext` is used (the standard
 * truststore). A production boot that needs
 * to trust a self-signed cert constructs
 * an `SSLContext` with a custom `TrustManager`
 * and passes it here.
 *
 * SFW-H5.3+ 12th follow-up: optional custom
 * [javax.net.ssl.KeyManager] for mTLS
 * (mutual TLS). When set, the
 * [sslContext] is built with both the
 * `KeyManager` and the `TrustManager`
 * (a custom `SSLContext` must be provided
 * when the `KeyManager` is set; the sender
 * does not construct a default `SSLContext`
 * with a `KeyManager`).
 */
class JavaHttpRequestSender(
    private val sslContext: javax.net.ssl.SSLContext? = null,
    private val keyManager: javax.net.ssl.KeyManager? = null,
) : HttpRequestSender {
    private val client: java.net.http.HttpClient = java.net.http.HttpClient.newBuilder()
        .followRedirects(java.net.http.HttpClient.Redirect.NORMAL)
        .apply {
            val effectiveContext = sslContext ?: if (keyManager != null) {
                // SFW-H5.3+ 12th follow-up: when
                // a KeyManager is provided without
                // an SSLContext, construct a default
                // TLS context with the KeyManager
                // and the default TrustManager.
                javax.net.ssl.SSLContext.getInstance("TLS").apply {
                    init(arrayOf(keyManager), null, null)
                }
            } else {
                null
            }
            if (effectiveContext != null) {
                sslContext(effectiveContext)
            }
        }
        .build()

    override fun get(url: String, headers: Map<String, String>): HttpResponse {
        val request = java.net.http.HttpRequest.newBuilder()
            .uri(java.net.URI.create(url))
            .GET()
        for ((key, value) in headers) {
            request.header(key, value)
        }
        val response = client.send(
            request.build(),
            java.net.http.HttpResponse.BodyHandlers.ofString(),
        )
        val responseHeaders = mutableMapOf<String, String>()
        for ((name, values) in response.headers().map()) {
            if (values.isNotEmpty()) {
                responseHeaders[name] = values[0]
            }
        }
        return HttpResponse(
            statusCode = response.statusCode(),
            body = response.body(),
            headers = responseHeaders,
        )
    }
}
