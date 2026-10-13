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
            // SFW-H5.3+ 14th follow-up: optional
            // connect timeout (TCP/TLS handshake).
            if (connectTimeout != null) {
                connectTimeout(connectTimeout)
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
                return@sendOneAttempt HttpResponse(
                    statusCode = response.statusCode(),
                    body = response.body(),
                    headers = responseHeaders,
                )
            }
            @Suppress("UNREACHABLE_CODE")
            error("unreachable")
        }
        return if (circuitBreaker != null) {
            circuitBreaker.execute(sendOneAttempt)
        } else {
            sendOneAttempt()
        }
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
