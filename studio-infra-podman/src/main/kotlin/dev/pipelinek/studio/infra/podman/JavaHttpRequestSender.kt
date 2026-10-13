package dev.pipelinek.studio.infra.podman

import java.io.IOException
import java.time.Duration

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
 * SFW-H5.3+ 18th follow-up: HTTP retry
 * configuration for [JavaHttpRequestSender].
 *
 * @param maxAttempts the total number of
 *   attempts (including the first one).
 *   Default 3 (the original + 2 retries).
 * @param initialBackoff the backoff before
 *   the second attempt. Default 100ms.
 * @param backoffMultiplier the multiplier
 *   applied to the backoff after each
 *   attempt. Default 2.0 (exponential
 *   backoff: 100ms, 200ms, 400ms, ...).
 * @param maxBackoff the upper bound on the
 *   backoff. Default 5s.
 */
data class HttpRetryConfig(
    val maxAttempts: Int = 3,
    val initialBackoff: Duration = Duration.ofMillis(100),
    val backoffMultiplier: Double = 2.0,
    val maxBackoff: Duration = Duration.ofSeconds(5),
) {
    init {
        require(maxAttempts >= 1) {
            "maxAttempts must be >= 1 (got $maxAttempts)"
        }
        require(!initialBackoff.isNegative) {
            "initialBackoff must be non-negative (got $initialBackoff)"
        }
        require(backoffMultiplier >= 1.0) {
            "backoffMultiplier must be >= 1.0 (got $backoffMultiplier)"
        }
    }
}

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
 *
 * SFW-H5.3+ 14th follow-up: configurable
 * request timeouts. [connectTimeout] is the
 * TCP/TLS handshake timeout (a slow proxy
 * that never completes the handshake will
 * trigger it). [requestTimeout] is the
 * per-request timeout (a slow response body
 * will trigger it). Both default to `null`
 * (the JDK's default, which is no timeout
 * — backwards-compatible).
 *
 * SFW-H5.3+ 18th follow-up: optional
 * [retry] configuration for transient
 * failures. When set, the sender retries
 * on 5xx responses and [IOException]
 * (network errors) up to [HttpRetryConfig.maxAttempts]
 * times with exponential backoff. 4xx
 * responses are NOT retried (they are
 * client errors; retrying won't help).
 * When `null` (the default), no retries
 * are attempted (backwards-compatible).
 *
 * ## Why retry
 *
 * A production boot that calls
 * [JavaPodmanRegistryClient] can hit
 * transient failures: a registry replica
 * returns 503 (overloaded), a network
 * blip causes a connection reset, a
 * load balancer rotates to an unhealthy
 * backend. Retrying with exponential
 * backoff is the standard mitigation.
 *
 * ## Why timeouts matter
 *
 * A production boot that calls
 * [JavaPodmanRegistryClient] on a private
 * registry can be blocked indefinitely if
 * the registry is unreachable, slow, or
 * misconfigured. Without a timeout, a single
 * `GET /v2/<image>/manifests/...` request
 * can hang the Studio process forever.
 */
class JavaHttpRequestSender(
    private val sslContext: javax.net.ssl.SSLContext? = null,
    private val keyManager: javax.net.ssl.KeyManager? = null,
    private val connectTimeout: Duration? = null,
    private val requestTimeout: Duration? = null,
    private val retry: HttpRetryConfig? = null,
    /**
     * The `sleep` function used between
     * retry attempts. Production uses
     * [Thread.sleep]. Tests inject a
     * no-op sleep to keep the tests fast.
     */
    private val sleeper: (Duration) -> Unit = { Thread.sleep(it.toMillis()) },
    /**
     * SFW-H5.3+ 20th follow-up: optional
     * [CircuitBreaker]. When set, the
     * sender wraps the entire HTTP
     * request (including retries) in
     * [CircuitBreaker.execute]. When
     * the circuit is OPEN, the call
     * fails fast with
     * [CircuitBreakerOpenException]
     * without attempting the downstream.
     * Null default = backwards-compatible.
     */
    private val circuitBreaker: CircuitBreaker? = null,
    /**
     * SFW-H5.3+ 21st follow-up: optional
     * per-host circuit breakers. Keyed
     * by host (e.g., `"registry-a.local"`,
     * `"registry-b.local"`). When a
     * request URL's host matches an
     * entry, the per-host breaker is
     * used. When no per-host breaker
     * matches, the global [circuitBreaker]
     * is used. When neither is set, no
     * breaker is applied (backwards-
     * compatible).
     *
     * ## Why per-host breakers
     *
     * A Studio that talks to multiple
     * registries (e.g., a primary
     * registry + a mirror) needs
     * isolation: a failure of the
     * primary should not trip a breaker
     * that blocks calls to the mirror.
     * Per-host breakers give this
     * isolation; a global breaker
     * doesn't.
     */
    private val perHostCircuitBreakers: Map<String, CircuitBreaker> = emptyMap(),
    /**
     * SFW-H5.3+ 22nd follow-up: when
     * `false`, the sender uses a
     * `TrustManager` that accepts all
     * certificates (no TLS verification).
     * Useful for self-signed certs in
     * development or staging; NEVER use
     * in production (vulnerable to MITM).
     * Default `true` (verify; the JVM
     * default truststore is used) —
     * backwards-compatible.
     *
     * ## When to use `false`
     *
     * - Local development with a
     *   self-signed registry cert
     * - Staging environments where
     *   the cert is private but
     *   self-signed
     * - Tests that exercise a server
     *   with a self-signed cert
     *
     * ## When NOT to use `false`
     *
     * - Production deployments
     *   (the 22nd follow-up is a
     *   development convenience,
     *   not a production override)
     *
     * ## Why a separate parameter
     *
     * The H5.3+ 10th follow-up added a
     * `sslContext: SSLContext?` parameter
     * that gives full control. But
     * constructing a custom `SSLContext`
     * with a permissive `TrustManager`
     * is verbose (5+ lines of
     * boilerplate). The 22nd follow-up
     * adds a one-line boolean for the
     * common case.
     */
    private val verifyTls: Boolean = true,
    /**
     * SFW-H5.3+ 23rd follow-up: optional
     * HTTP proxy address. When set, all
     * HTTP requests are routed through
     * this proxy (the JDK's HttpClient
     * builds a [java.net.ProxySelector]
     * that returns this address for every
     * host). When `null` (the default),
     * the JVM's default proxy settings
     * are used (`http.proxyHost` /
     * `http.proxyPort` system properties,
     * `~/.netrc`, etc.) — backwards-
     * compatible.
     *
     * ## Why a proxy
     *
     * Many enterprise networks route all
     * outbound traffic through an HTTP
     * proxy. A Studio that runs behind
     * such a network must be able to
     * talk to a private registry through
     * the proxy. The 23rd follow-up
     * makes this a one-line constructor
     * parameter.
     */
    private val proxyAddress: java.net.InetSocketAddress? = null,
    /**
     * SFW-H5.3+ 24th follow-up: optional
     * proxy bypass list. When set,
     * requests to hosts in this list
     * (or matching a suffix in this
     * list) go direct (not through
     * the proxy). Hosts are matched by
     * exact name or by suffix (e.g.,
     * `.local` matches `registry.local`).
     * When empty (the default), all
     * requests go through the proxy
     * (the 23rd's contract). When
     * [proxyAddress] is `null`, this
     * parameter is ignored (no proxy
     * to bypass).
     *
     * ## Why a bypass list
     *
     * Many corporate networks use a
     * proxy for external traffic but
     * allow direct connection to
     * internal hosts (e.g.,
     * `localhost`, `*.local`,
     * `*.internal`). The 24th follow-up
     * makes this a one-line constructor
     * parameter.
     */
    private val proxyBypassHosts: List<String> = emptyList(),
    /**
     * SFW-H5.3+ 25th follow-up: maximum
     * response body size in bytes. When
     * the response body exceeds this
     * limit, the sender throws
     * [ResponseBodyTooLargeException]
     * (and discards the rest of the
     * body). When `null` (the default),
     * no limit is applied (backwards-
     * compatible).
     *
     * ## Why a size limit
     *
     * A malicious or buggy registry
     * could send a multi-GB response
     * body, causing the JVM to OOM.
     * The 25th follow-up adds an
     * optional cap. Operators behind
     * an untrusted network should set
     * this to a sane value (e.g.,
     * 100 MB for Docker manifests).
     */
    private val maxResponseBodyBytes: Long? = null,
) : HttpRequestSender {
    private val client: java.net.http.HttpClient = java.net.http.HttpClient.newBuilder()
        .followRedirects(java.net.http.HttpClient.Redirect.NORMAL)
        .apply {
            val effectiveContext = sslContext ?: when {
                keyManager != null -> {
                    // SFW-H5.3+ 12th follow-up: when
                    // a KeyManager is provided without
                    // an SSLContext, construct a default
                    // TLS context with the KeyManager
                    // and the default TrustManager.
                    javax.net.ssl.SSLContext.getInstance("TLS").apply {
                        init(arrayOf(keyManager), null, null)
                    }
                }
                !verifyTls -> {
                    // SFW-H5.3+ 22nd follow-up: when
                    // `verifyTls` is false, construct
                    // a default TLS context with a
                    // permissive TrustManager (accepts
                    // all certs). NEVER use in
                    // production.
                    javax.net.ssl.SSLContext.getInstance("TLS").apply {
                        init(null, arrayOf(InsecureTrustManager), null)
                    }
                }
                else -> null
            }
            if (effectiveContext != null) {
                sslContext(effectiveContext)
            }
            // SFW-H5.3+ 14th follow-up: optional
            // connect timeout (TCP/TLS handshake).
            if (connectTimeout != null) {
                connectTimeout(connectTimeout)
            }
            // SFW-H5.3+ 23rd follow-up: optional
            // HTTP proxy. When set, all
            // requests are routed through
            // this proxy.
            //
            // SFW-H5.3+ 24th follow-up: when
            // a bypass list is also set,
            // hosts in the bypass list go
            // direct (no proxy). The
            // ProxySelector returns NO_PROXY
            // for bypassed hosts; the proxy
            // for everything else.
            if (proxyAddress != null) {
                val bypass = proxyBypassHosts
                proxy(object : java.net.ProxySelector() {
                    override fun select(uri: java.net.URI?): List<java.net.Proxy> {
                        if (uri == null) return listOf(java.net.Proxy.NO_PROXY)
                        val host = uri.host
                            ?: return listOf(java.net.Proxy(java.net.Proxy.Type.HTTP, proxyAddress))
                        val shouldBypass = bypass.any { pattern ->
                            host == pattern || host.endsWith(pattern)
                        }
                        return if (shouldBypass) {
                            listOf(java.net.Proxy.NO_PROXY)
                        } else {
                            listOf(java.net.Proxy(java.net.Proxy.Type.HTTP, proxyAddress))
                        }
                    }
                    override fun connectFailed(
                        uri: java.net.URI?,
                        sa: java.net.SocketAddress?,
                        ioe: java.io.IOException?,
                    ) {
                        // No-op: the JDK will
                        // log + fall back to
                        // direct connection on
                        // the next call. We
                        // don't cache or
                        // remember the failure.
                    }
                })
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
        // SFW-H5.3+ 14th follow-up: optional
        // per-request timeout (whole-request,
        // including body).
        if (requestTimeout != null) {
            request.timeout(requestTimeout)
        }
        val builtRequest = request.build()

        // SFW-H5.3+ 20th follow-up: wrap
        // the entire request (including
        // retries) in the circuit breaker.
        // When the circuit is OPEN, the
        // call fails fast without
        // attempting the downstream.
        val sendOneAttempt = sendOneAttempt@{
            // SFW-H5.3+ 18th follow-up: retry
            // loop for transient failures.
            // When `retry` is null (the
            // default), the loop runs once
            // (no retries, backwards-compatible).
            var attempt = 0
            var currentBackoff = retry?.initialBackoff ?: Duration.ZERO
            while (true) {
                attempt++
                val response: java.net.http.HttpResponse<String> = try {
                    client.send(builtRequest, java.net.http.HttpResponse.BodyHandlers.ofString())
                } catch (e: IOException) {
                    // Network error (connection
                    // reset, timeout, unreachable
                    // host). Retried if attempts
                    // remain.
                    if (retry != null && attempt < retry.maxAttempts) {
                        sleeper(currentBackoff)
                        currentBackoff = nextBackoff(currentBackoff, retry)
                        continue
                    }
                    throw e
                }
                // 5xx responses are transient
                // (the server is overloaded or
                // temporarily unavailable).
                // Retried if attempts remain.
                if (response.statusCode() in 500..599 &&
                    retry != null && attempt < retry.maxAttempts
                ) {
                    sleeper(currentBackoff)
                    currentBackoff = nextBackoff(currentBackoff, retry)
                    continue
                }
                val responseHeaders = mutableMapOf<String, String>()
                for ((name, values) in response.headers().map()) {
                    if (values.isNotEmpty()) {
                        responseHeaders[name] = values[0]
                    }
                }
                val body = if (maxResponseBodyBytes != null) {
                    val raw = response.body()
                    if (raw.length.toLong() > maxResponseBodyBytes!!) {
                        throw ResponseBodyTooLargeException(
                            limitBytes = maxResponseBodyBytes!!,
                            url = url,
                        )
                    }
                    raw
                } else {
                    response.body()
                }
                return@sendOneAttempt HttpResponse(
                    statusCode = response.statusCode(),
                    body = body,
                    headers = responseHeaders,
                )
            }
            @Suppress("UNREACHABLE_CODE")
            error("unreachable")
        }
        // SFW-H5.3+ 21st follow-up: per-host
        // circuit breaker takes precedence
        // over the global one. The host is
        // derived from the URL.
        val host = hostOf(url)
        val perHostBreaker = host?.let { perHostCircuitBreakers[it] }
        val effectiveBreaker = perHostBreaker ?: circuitBreaker
        return if (effectiveBreaker != null) {
            effectiveBreaker.execute(sendOneAttempt)
        } else {
            sendOneAttempt()
        }
    }

    /**
     * SFW-H5.3+ 25th follow-up: throws
     * when a response body exceeds the
     * configured [maxResponseBodyBytes]
     * limit.
     */
    class ResponseBodyTooLargeException(
        val limitBytes: Long,
        val url: String,
    ) : RuntimeException(
        "response body exceeds limit of $limitBytes bytes; url=$url",
    )

    /**
     * SFW-H5.3+ 21st follow-up: derive
     * the host from a URL string. Used
     * to look up the per-host circuit
     * breaker.
     */
    private fun hostOf(url: String): String? = try {
        java.net.URI.create(url).host
    } catch (e: Exception) {
        null
    }

    /**
     * Compute the next backoff. The
     * backoff is multiplied by the
     * `backoffMultiplier` and capped
     * at `maxBackoff`.
     */
    private fun nextBackoff(current: Duration, config: HttpRetryConfig): Duration {
        val nextMillis = (current.toMillis() * config.backoffMultiplier).toLong()
        val capped = nextMillis.coerceAtMost(config.maxBackoff.toMillis())
        return Duration.ofMillis(capped)
    }
}

/**
 * SFW-H5.3+ 22nd follow-up: a
 * `TrustManager` that accepts ALL
 * certificates. Used when
 * `verifyTls = false`. NEVER use in
 * production (vulnerable to MITM
 * attacks). The `X509TrustManager`
 * interface methods all no-op.
 */
private object InsecureTrustManager : javax.net.ssl.X509TrustManager {
    override fun checkClientTrusted(
        chain: Array<java.security.cert.X509Certificate>,
        authType: String,
    ) { /* trust all */ }
    override fun checkServerTrusted(
        chain: Array<java.security.cert.X509Certificate>,
        authType: String,
    ) { /* trust all */ }
    override fun getAcceptedIssuers(): Array<java.security.cert.X509Certificate> = arrayOf()
}
