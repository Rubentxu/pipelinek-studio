package dev.pipelinek.studio.http

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.application.log
import io.ktor.server.response.respondText
import io.netty.handler.codec.http2.Http2Headers

/**
 * AAT-SEC-005 — transport authority allow-list. The browser-driven authority
 * is a known DNS-rebinding / CSRF surface; a naive server that trusts it will
 * treat an attacker-chosen origin as canonical even when the attacker reached
 * the loopback through a poisoned DNS record. We reject requests whose
 * transport authority does not name a configured [allowedHosts] entry.
 *
 * The authority is read from exactly two places, both of them wire facts:
 *
 * - `Host` — the HTTP/1.x header, and the regular header form HTTP/2 also
 *   permits;
 * - `:authority` — the HTTP/2 pseudo-header, via [transportAuthority].
 *
 * `Forwarded` and `X-Forwarded-Host` are never consulted: they are client
 * controlled, so trusting them would hand the allow-list back to the attacker
 * it exists to stop (a reverse proxy in front of the studio MUST rewrite
 * `Host`/`:authority` instead).
 *
 * Default allowed hosts: `127.0.0.1`, `localhost`, `0.0.0.0`. These cover
 * every deployment documented in [RELEASE_NOTES.md] and
 * [docs/06-operations/COMPATIBILITY_MATRIX.md]. Production deployments
 * behind a reverse proxy MUST extend the allow-list explicitly via
 * `STUDIO_HOST_ALLOWLIST` (comma-separated) — the proxy's hostname
 * becomes the canonical authority and must be allowed.
 *
 * D-005: this is the bare minimum; for an actual public deployment,
 * the operator should ALSO put the server behind a reverse proxy that
 * sets the canonical authority (see `docs/05-testing/SANDBOX_TLS.md`).
 */
fun Application.installHostAllowList(
    allowedHosts: Set<String> = DEFAULT_ALLOWED_HOSTS,
) {
    if (!Http2AuthorityBridge.isAvailable) {
        log.warn(
            "HTTP/2 authority bridge unavailable ({}); HTTP/2 requests will be refused " +
                "with HOST_HEADER_MISSING instead of being admitted",
            Http2AuthorityBridge.expectedType,
        )
    }
    intercept(ApplicationCallPipeline.Plugins) {
        val verdict = resolveRequestHost(
            hostHeader = call.request.headers[HttpHeaders.Host],
            http2Authority = call.transportAuthority(),
            isHttp2 = call.request.local.version.startsWith(HTTP_2_VERSION_PREFIX),
            allowedHosts = effectiveAllowedHosts(allowedHosts),
        )
        if (verdict is HostVerdict.Rejected) {
            call.response.status(HttpStatusCode.BadRequest)
            call.respondText(
                """{"code":"${verdict.code}","message":"${verdict.message}"}""",
                contentType = ContentType.Application.Json,
            )
            finish()
        }
    }
}

/** Machine-readable refusal codes for the allow-list. */
internal const val HOST_HEADER_MISSING: String = "HOST_HEADER_MISSING"
internal const val HOST_NOT_ALLOWED: String = "HOST_NOT_ALLOWED"
internal const val HOST_AUTHORITY_CONFLICT: String = "HOST_AUTHORITY_CONFLICT"

/** [io.ktor.http.RequestConnectionPoint.version] of an HTTP/2 request. */
private const val HTTP_2_VERSION_PREFIX: String = "HTTP/2"

/** The allow-list decision for one request, as one total function. */
internal sealed interface HostVerdict {
    /** [host] is the bare (port-stripped) authority that matched the allow-list. */
    data class Allowed(val host: String) : HostVerdict

    /** The request must be refused with `400` and this machine-readable [code]. */
    data class Rejected(val code: String, val message: String) : HostVerdict
}

/**
 * Decides the request's canonical host from the two places an authority can
 * legitimately appear, and only those two.
 *
 * The protocol seam is deliberate. HTTP/2 may fall back to `:authority`,
 * because that is where its transport authority lives; HTTP/1.x is decided by
 * `Host` alone, so a missing `Host` keeps failing closed on every engine and
 * can never be satisfied by the listener address, the socket peer, or a
 * forwarding header. When a request carries both values they must agree:
 * RFC 7540 forbids a client from sending a `Host` that differs from
 * `:authority`, so a disagreement is smuggling, not something to resolve
 * silently.
 */
internal fun resolveRequestHost(
    hostHeader: String?,
    http2Authority: String?,
    isHttp2: Boolean,
    allowedHosts: Set<String>,
): HostVerdict {
    val authority = if (isHttp2) http2Authority?.trim()?.takeIf { it.isNotEmpty() } else null
    val host = hostHeader?.takeIf { !it.isBlank() }
    if (host != null && authority != null && hostOnly(host) != hostOnly(authority)) {
        return HostVerdict.Rejected(
            HOST_AUTHORITY_CONFLICT,
            "the Host header and the HTTP/2 :authority name different hosts",
        )
    }
    val candidate = host ?: authority ?: return HostVerdict.Rejected(
        HOST_HEADER_MISSING,
        "Host header is required; the allow-list cannot be enforced without it",
    )
    val bareHost = hostOnly(candidate)
    return if (bareHost in allowedHosts) {
        HostVerdict.Allowed(bareHost)
    } else {
        HostVerdict.Rejected(HOST_NOT_ALLOWED, "host '$bareHost' is not in the allow-list")
    }
}

/**
 * The allow-list has always been port-agnostic (`127.0.0.1:7070` matches
 * `127.0.0.1`); the HTTP/2 authority is normalized by the same function so
 * both protocols are judged by one rule.
 */
private fun hostOnly(authority: String): String = authority.substringBefore(":")

/**
 * The `:authority` pseudo-header of an HTTP/2 request, exactly as the engine
 * received it — or `null` when the engine exposes no such value.
 *
 * Ktor builds `request.headers` from the HTTP/2 header frame by *skipping*
 * every pseudo-header, `:authority` included, so an HTTP/2 request carries no
 * `Host` value at all. Reading only `Host` therefore rejected the canonical
 * TLS listener with `HOST_HEADER_MISSING` before the app could run — the real
 * curl in the from-distribution UAT negotiates HTTP/2 and never got past
 * `/health`.
 *
 * The value is returned verbatim: never filled in from the listen address
 * (Ktor's `local.serverHost` falls back to the local socket address, which is
 * in the default allow-list, so using it would turn "no authority" into an
 * accidental pass) and never taken from a forwarding header. Reading the
 * engine's own header map is what keeps *missing* distinguishable from
 * *allowed*.
 */
private fun ApplicationCall.transportAuthority(): String? = Http2AuthorityBridge.authorityOf(request)

/**
 * Bridge to the engine's HTTP/2 header frame.
 *
 * Ktor 3.0.1 keeps the `:authority` value on an **internal** request subclass,
 * `io.ktor.server.netty.http2.NettyHttp2ApplicationRequest`; the public
 * `ApplicationRequest` surface deliberately hides pseudo-headers. There is no
 * public accessor and no safe substitute: `local.serverHost`/`local.host`
 * silently fall back to the listen address, which the default allow-list
 * contains.
 *
 * The bridge is therefore reflective *and optional*. If a Ktor upgrade renames
 * the class or the accessor, [isAvailable] turns false, the listener logs one
 * warning, and HTTP/2 requests fail closed (`400 HOST_HEADER_MISSING`) instead
 * of being admitted on an unverified authority. The real-Netty HTTP/2 test in
 * `HostAllowListTest` pins the seam: an upgrade that moves it turns that test
 * red instead of the product silent.
 */
private object Http2AuthorityBridge {
    /** FQN of the engine request type that carries the HTTP/2 header frame. */
    const val expectedType: String = "io.ktor.server.netty.http2.NettyHttp2ApplicationRequest"

    private val nettyHeadersAccessor: java.lang.reflect.Method? = runCatching {
        Class.forName(expectedType, false, Http2AuthorityBridge::class.java.classLoader)
            .getMethod("getNettyHeaders")
            .apply { isAccessible = true }
    }.getOrNull()

    val isAvailable: Boolean get() = nettyHeadersAccessor != null

    fun authorityOf(request: io.ktor.server.request.ApplicationRequest): String? {
        val accessor = nettyHeadersAccessor ?: return null
        if (!accessor.declaringClass.isInstance(request)) return null
        val headers = runCatching { accessor.invoke(request) }.getOrNull() as? Http2Headers ?: return null
        return runCatching { headers.authority()?.toString() }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
    }
}

/** The operator's `STUDIO_HOST_ALLOWLIST` widens the compiled defaults. */
private fun effectiveAllowedHosts(allowedHosts: Set<String>): Set<String> {
    val envOverride = System.getenv("STUDIO_HOST_ALLOWLIST")
    return if (!envOverride.isNullOrBlank()) {
        envOverride.split(",").map { it.trim() }.filter { it.isNotEmpty() }.toSet() + allowedHosts
    } else {
        allowedHosts
    }
}

/** Conservative default: loopback only. The operator widens via env or composition root. */
val DEFAULT_ALLOWED_HOSTS: Set<String> = setOf("127.0.0.1", "localhost", "0.0.0.0")
