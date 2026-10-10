package dev.pipelinek.studio.http

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.response.respondText

/**
 * AAT-SEC-005 — Host header allow-list. The browser-driven Host header is
 * a known DNS-rebinding / CSRF surface; a naive server that trusts the Host
 * will treat the inbound value as the canonical origin even when the
 * attacker set it via a malicious DNS record. We reject requests whose
 * Host header does not match the configured [allowedHosts] set.
 *
 * Default allowed hosts: `127.0.0.1`, `localhost`, `0.0.0.0`. These cover
 * every deployment documented in [RELEASE_NOTES.md] and
 * [docs/06-operations/COMPATIBILITY_MATRIX.md]. Production deployments
 * behind a reverse proxy MUST extend the allow-list explicitly via
 * `STUDIO_HOST_ALLOWLIST` (comma-separated) — the proxy's hostname
 * becomes the canonical Host and must be allowed.
 *
 * D-005: this is the bare minimum; for an actual public deployment,
 * the operator should ALSO put the server behind a reverse proxy that
 * sets the canonical Host (see `docs/05-testing/SANDBOX_TLS.md`).
 */
fun Application.installHostAllowList(
    allowedHosts: Set<String> = DEFAULT_ALLOWED_HOSTS,
) {
    intercept(ApplicationCallPipeline.Plugins) {
        val envOverride = System.getenv("STUDIO_HOST_ALLOWLIST")
        val effective = if (!envOverride.isNullOrBlank()) {
            envOverride.split(",").map { it.trim() }.filter { it.isNotEmpty() }.toSet() + allowedHosts
        } else {
            allowedHosts
        }
        // Audit H-15 (2026-10-10): the previous implementation used
        // `?: return@intercept` to handle a missing Host header, which
        // is a fail-open: an HTTP/1.0 client, a probing tool, or an
        // attacker that strips the header would bypass the allow-list
        // entirely. The fix is to refuse the request with 400 when
        // the Host header is absent. Modern HTTP/1.1 clients always
        // send Host; legitimate HTTP/1.0 traffic should be rare on
        // the studio's loopback surface and can opt-in via
        // --insecure-http-no-host-allowlist (not yet exposed — the
        // override below lets a future patch add it without changing
        // this contract).
        val host = call.request.headers["Host"]
        if (host.isNullOrBlank()) {
            call.response.status(HttpStatusCode.BadRequest)
            call.respondText(
                """{"code":"HOST_HEADER_MISSING","message":"Host header is required; the allow-list cannot be enforced without it"}""",
                contentType = ContentType.Application.Json,
            )
            finish()
            return@intercept
        }
        // Strip the port (e.g. "127.0.0.1:7070" -> "127.0.0.1") so the
        // allow-list can be host-only.
        val bareHost = host.substringBefore(":")
        if (bareHost !in effective) {
            call.response.status(HttpStatusCode.BadRequest)
            call.respondText(
                """{"code":"HOST_NOT_ALLOWED","message":"host '$bareHost' is not in the allow-list"}""",
                contentType = ContentType.Application.Json,
            )
            finish()
        }
    }
}

/** Conservative default: loopback only. The operator widens via env or composition root. */
val DEFAULT_ALLOWED_HOSTS: Set<String> = setOf("127.0.0.1", "localhost", "0.0.0.0")
