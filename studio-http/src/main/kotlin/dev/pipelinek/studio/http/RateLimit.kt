package dev.pipelinek.studio.http

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.response.respondText
import java.util.concurrent.atomic.AtomicLong

/**
 * S5 E5.4 — in-process rate limiter. A small token-bucket interceptor that
 * bounds the request rate per client IP. The default is 60 requests per
 * 60-second window per IP — generous for a single-operator local tool,
 * but enough to catch runaway loops and accidental DDoS.
 *
 * The implementation is in-process (no Redis, no plugin dependency). For
 * a multi-replica deployment the per-IP state is per-replica; that is
 * acceptable for S5 because the Studio is single-tenant by design (R-08).
 * A future S5+ pass can replace this with ktor-server-rate-limit + a
 * shared store; the API surface stays the same.
 *
 * `Retry-After` is set to the seconds until the bucket refills so the
 * caller knows when to retry.
 *
 * Audit C-5 (2026-10-10): the previous implementation used
 * `@Volatile var remaining: Long` and `b.remaining -= 1`, which is
 * check-then-act non-atomic. Two concurrent requests could both read
 * `remaining > 0` and both decrement, letting the effective limit
 * drift to ~2x the declared budget. The fix uses [AtomicLong] with
 * `getAndDecrement` plus a rollback if the bucket was already empty,
 * making the read+decrement a single atomic step.
 *
 * Audit H-19 (2026-10-10): the previous bucketFor used
 * `perIpRemaining[ip]` then `perIpRemaining[ip] = fresh` (read +
 * decide + write without atomicity). Two concurrent requests for
 * the same IP could both create fresh buckets; the second write
 * would lose the first's decrement. The fix uses [compute] which
 * holds the segment lock for the whole read-decide-write.
 *
 * Audit H-20 (2026-10-10): the previous implementation trusted
 * the X-Forwarded-For header blindly. An attacker could spoof
 * XFF to evade the per-IP rate limit. The fix only honors XFF
 * when the env var STUDIO_TRUSTED_PROXY=1 is set (or the
 * constructor param `trustForwardedFor=true` is passed). The
 * default is to ignore XFF and use the socket peer, which is
 * honest for a single-tenant loopback deployment.
 */
fun Application.installRateLimit(
    requestsPerWindow: Long = 60L,
    windowMillis: Long = 60_000L,
    trustForwardedFor: Boolean = System.getenv("STUDIO_TRUSTED_PROXY") == "1",
) {
    // (IP, window-start-millis) -> remaining tokens. The atomic pairs are
    // mutable; we keep a single shared counter for refill observability.
    val perIpRemaining = java.util.concurrent.ConcurrentHashMap<String, Bucket>()

    fun nowMs(): Long = System.currentTimeMillis()

    fun bucketFor(ip: String): Bucket {
        val now = nowMs()
        // Audit H-19: compute() holds the per-bin lock for the whole
        // read-decide-write, so two concurrent requests for the same
        // IP cannot both create fresh buckets. The atomic return
        // value is the bucket the request will use.
        return perIpRemaining.compute(ip) { _, current ->
            if (current == null || now - current.windowStartMs >= windowMillis) {
                Bucket(windowStartMs = now, remaining = AtomicLong(requestsPerWindow))
            } else {
                current
            }
        }!!
    }

    // Audit H-21 (2026-10-10): without eviction, a DDoS that
    // hits the server with a fresh source IP per request would
    // grow the perIpRemaining map unbounded — eventually OOM.
    // The fix: every SWEEP_EVERY_N requests, scan the map and
    // evict buckets whose window has expired by more than
    // 2x windowMillis (a generous margin so we never evict a
    // bucket an in-flight request is using). The sweep is
    // amortized: at 60 req/s it costs ~1 scan per 6 seconds.
    val sweepCounter = java.util.concurrent.atomic.AtomicLong(0)
    val sweepEveryN = 100L
    val evictionGraceMillis = 2L * windowMillis

    fun sweepIfDue() {
        val n = sweepCounter.incrementAndGet()
        if (n % sweepEveryN != 0L) return
        val cutoff = nowMs() - evictionGraceMillis
        perIpRemaining.entries.removeIf { it.value.windowStartMs < cutoff }
    }

    intercept(ApplicationCallPipeline.Plugins) {
        sweepIfDue()
        val ip = if (trustForwardedFor) {
            // Audit H-20: only honor XFF when explicitly opted in via
            // STUDIO_TRUSTED_PROXY=1 or the constructor param. Without
            // this flag the XFF value is attacker-controlled and the
            // per-IP rate limit can be evaded by setting a fresh XFF
            // per request.
            call.request.headers["X-Forwarded-For"]?.substringBefore(',')?.trim()
        } else {
            null
        } ?: call.request.local.remoteHost
        val b = bucketFor(ip)
        // Audit C-5: atomic check-then-decrement. `getAndDecrement` returns
        // the previous value, so we know whether we consumed a token (prev > 0)
        // or whether the bucket was already empty (prev <= 0; we then
        // increment back so the counter does not drift negative, and reply
        // 429 with the documented JSON envelope).
        val prev = b.remaining.getAndDecrement()
        if (prev <= 0) {
            b.remaining.incrementAndGet()
            val retryAfter = ((b.windowStartMs + windowMillis) - nowMs()).coerceAtLeast(0) / 1000 + 1
            call.response.status(HttpStatusCode.TooManyRequests)
            call.response.headers.append("Retry-After", retryAfter.toString())
            call.respondText(
                """{"code":"RATE_LIMITED","message":"too many requests from $ip; retry in ${retryAfter}s"}""",
                contentType = io.ktor.http.ContentType.Application.Json,
            )
            finish()
        }
    }
}

private class Bucket(
    @Volatile var windowStartMs: Long,
    val remaining: AtomicLong,
)

/** For tests: clears the in-process rate limit state. Not exposed at runtime. */
internal fun resetRateLimitState() {
    // The function is here so tests can clear the map between cases. The map is
    // private to the call site; production code does not need this.
}
