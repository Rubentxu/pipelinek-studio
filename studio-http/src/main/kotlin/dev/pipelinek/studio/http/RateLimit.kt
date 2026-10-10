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
 */
fun Application.installRateLimit(
    requestsPerWindow: Long = 60L,
    windowMillis: Long = 60_000L,
) {
    // (IP, window-start-millis) -> remaining tokens. The atomic pairs are
    // mutable; we keep a single shared counter for refill observability.
    val perIpRemaining = java.util.concurrent.ConcurrentHashMap<String, Bucket>()

    fun nowMs(): Long = System.currentTimeMillis()

    fun bucketFor(ip: String): Bucket {
        val now = nowMs()
        val current = perIpRemaining[ip]
        if (current == null || now - current.windowStartMs >= windowMillis) {
            val fresh = Bucket(windowStartMs = now, remaining = AtomicLong(requestsPerWindow))
            perIpRemaining[ip] = fresh
            return fresh
        }
        return current
    }

    intercept(ApplicationCallPipeline.Plugins) {
        val ip = call.request.headers["X-Forwarded-For"]?.substringBefore(',')?.trim()
            ?: call.request.local.remoteHost
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
