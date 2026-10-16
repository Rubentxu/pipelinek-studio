package dev.pipelinek.studio.infra.podman

/**
 * SFW-H5.3+ 27th follow-up: the
 * observability port for HTTP requests.
 * The 27th follow-up introduces a
 * minimal interface that operators can
 * implement to record metrics about
 * every HTTP request the sender makes
 * (host, status code, duration).
 *
 * ## Why an observability port
 *
 * A production Studio needs to know:
 * - How many requests is it making
 *   per host?
 * - What's the success/failure rate
 *   per host?
 * - What's the p50/p99 latency per
 *   host?
 *
 * The metrics port lets a production
 * boot plug in a Prometheus exporter
 * (or any other metrics backend)
 * without coupling the sender to a
 * specific implementation.
 */
interface HttpMetrics {
    /**
     * Record a completed HTTP request.
     * Called after every request
     * (success or failure), exactly
     * once per request.
     *
     * @param host the host of the
     *   request URL (e.g.,
     *   `"registry.local"`).
     * @param status the HTTP status
     *   code (e.g., 200, 404, 503).
     *   For a request that failed
     *   before the response was
     *   received (e.g., connect
     *   timeout), this is `-1`.
     * @param durationMs the total
     *   wall-clock duration of the
     *   request in milliseconds.
     * @param success `true` if the
     *   request returned a 2xx
     *   response; `false` otherwise
     *   (4xx, 5xx, or pre-response
     *   failure).
     */
    fun recordRequest(host: String, status: Int, durationMs: Long, success: Boolean)
}

/**
 * SFW-H5.3+ 27th follow-up: the
 * no-op [HttpMetrics]. The default
 * impl when no metrics is configured
 * (backwards-compatible).
 */
object NoopHttpMetrics : HttpMetrics {
    override fun recordRequest(host: String, status: Int, durationMs: Long, success: Boolean) {
        // no-op
    }
}
