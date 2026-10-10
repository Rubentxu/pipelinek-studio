package dev.pipelinek.studio.http

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.response.respondText

/**
 * B2.1 (2026-10-10): Origin-allow-list for state-changing
 * requests.
 *
 * The Host allow-list closes the DNS-rebinding / Host-forgery
 * surface. The Origin allow-list closes the
 * cross-site-state-change surface: a browser-driven
 * POST/PUT/DELETE/PATCH must carry an `Origin` header whose
 * scheme+host+port matches the allow-list. A missing or
 * foreign Origin is refused with 403 BEFORE the route
 * handler runs. Safe methods (GET / HEAD / OPTIONS) are
 * exempt — the origin policy only protects state changes.
 *
 * The list is configurable via the constructor parameter
 * (production wires `setOf("https://studio.example.com")`)
 * and overridable by the env var `STUDIO_ALLOWED_ORIGINS`
 * (comma-separated). The default for tests and the dev
 * CLI is `emptySet()` so a non-production build can opt
 * in explicitly.
 *
 * Note: this is NOT a replacement for a CSRF token — a
 * token is the right defense for cookie-authenticated
 * apps. The studio uses bearer / lease tokens for
 * destructive ops (see [AAT-SEC-010] lease flow), so the
 * Origin check is the network-layer CSRF defense: a
 * cross-site form post is blocked because the browser
 * auto-attaches an `Origin` that the studio's allow-list
 * refuses.
 */
fun Application.installOriginAllowList(
    allowedOrigins: Set<String> = emptySet(),
) {
    val fromEnv = System.getenv("STUDIO_ALLOWED_ORIGINS")
        ?.split(",")
        ?.map { it.trim() }
        ?.filter { it.isNotEmpty() }
        ?.toSet()
        ?: emptySet()
    val effective = if (fromEnv.isNotEmpty()) fromEnv else allowedOrigins
    if (effective.isEmpty()) {
        // No allow-list configured. Skip installation so
        // the test harness / dev CLI works without
        // ceremony. Production wiring is mandatory via
        // STUDIO_ALLOWED_ORIGINS or the constructor arg.
        return
    }
    intercept(ApplicationCallPipeline.Plugins) {
        val method = call.request.local.method.value.uppercase()
        // Only state-changing methods require an Origin.
        // GET / HEAD / OPTIONS are safe and may be
        // cross-origin (e.g. a telemetry pull from a
        // dashboard host).
        if (method !in STATE_CHANGING_METHODS) return@intercept
        val origin = call.request.headers["Origin"]
        if (origin == null) {
            // A cross-origin form post that does NOT
            // include Origin (older browser, simple
            // request, or a non-browser client without
            // CSRF protection) is refused. The studio
            // exposes its destructive ops behind a
            // bearer / lease token, so a missing Origin
            // is always suspicious on a state-changing
            // method.
            call.response.headers.append("Vary", "Origin")
            call.respondText(
                """{"code":"ORIGIN_MISSING","message":"state-changing request must carry an Origin header"}""",
                status = HttpStatusCode.Forbidden,
                contentType = ContentType.Application.Json,
            )
            finish()
            return@intercept
        }
        val originNormalized = normalizeOrigin(origin)
        if (originNormalized !in effective) {
            val safeMessage = SafeErrorMessage.from(
                "origin '$origin' is not in the allow-list",
            )
            call.response.headers.append("Vary", "Origin")
            call.respondText(
                """{"code":"ORIGIN_NOT_ALLOWED","message":"$safeMessage"}""",
                status = HttpStatusCode.Forbidden,
                contentType = ContentType.Application.Json,
            )
            finish()
        }
    }
}

private val STATE_CHANGING_METHODS = setOf("POST", "PUT", "DELETE", "PATCH")

private fun normalizeOrigin(origin: String): String {
    // Lowercase the scheme + host; preserve the port
    // (case-sensitive per RFC 6454). Trailing slashes
    // are stripped. The studio never accepts a path
    // component in the allow-list.
    val trimmed = origin.trim().trimEnd('/')
    val schemeEnd = trimmed.indexOf("://")
    if (schemeEnd < 0) return trimmed.lowercase()
    val scheme = trimmed.substring(0, schemeEnd).lowercase()
    val rest = trimmed.substring(schemeEnd + 3)
    val slash = rest.indexOf('/')
    val hostAndPort = if (slash < 0) rest else rest.substring(0, slash)
    return "$scheme://$hostAndPort"
}
