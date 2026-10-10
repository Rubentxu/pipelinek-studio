package dev.pipelinek.studio.http

import java.security.SecureRandom

/**
 * B2.2 (2026-10-10): typed error envelope, no internal info
 * exposure.
 *
 * The studio's HTTP layer returns [ErrorResponse] for every
 * non-2xx response. Two invariants must hold:
 *
 *  1. **Typed** — the response is a [ErrorResponse] with a
 *     stable `code` from the allowed set. The wire contract
 *     is a small enum of codes, not free-form strings.
 *  2. **No internal info exposure** — the `message` field
 *     never echoes a Java exception's `getMessage()` raw,
 *     never echoes a filesystem path, and never carries
 *     anything an operator can use to fingerprint internals
 *     (stack trace, line numbers, class names, JDBC URLs,
 *     host names, etc.). The single allowed `message` is
 *     the redaction-safe, length-bounded, single-line
 *     result of [SafeErrorMessage.from].
 *
 * The correlation handle for support is the [ErrorResponse.code]
 * (or, when the studio opts in, a generated `error_id` exposed
 * by the catch-all handler — see `errorId()` below). The
 * server-side log carries the original exception; the wire
 * response carries only the redaction-safe summary.
 */
object SafeErrorMessage {

    private const val MAX_LEN = 240

    private val pathLikeUnix = Regex("""(/[^ \t\n\r,;]{2,})""")
    private val pathLikeWindows = Regex("""([A-Za-z]:\\[^\s,;]{2,})""")
    private val classFqn = Regex("""([A-Za-z_][A-Za-z0-9_]*(?:\.[A-Za-z_][A-Za-z0-9_]*){2,})""")
    private val emailLike = Regex("""([A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,})""")
    private val jwtLike = Regex("""(eyJ[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]{8,})""")
    private val bearerLike = Regex("""(?i)(bearer\s+)[A-Za-z0-9._\-+/=]{8,}""")
    private val controlChars = Regex("""[\p{Cntrl}]""")

    /**
     * Produce a wire-safe, single-line, length-bounded
     * summary of [raw]. The function is total: a `null`
     * input becomes a generic "internal" stub; a `raw`
     * with redaction candidates has them replaced with
     * `***`; the result never spans more than one line
     * and never exceeds [MAX_LEN] characters.
     *
     * The redaction set is intentionally conservative:
     * it errs on the side of replacing anything that
     * smells like a path, an FQN, an email, a JWT, or a
     * bearer token. Operators reading the wire response
     * see the high-level shape; the server-side log
     * keeps the original `cause`.
     */
    fun from(raw: String?): String {
        if (raw.isNullOrBlank()) return "internal"
        var s = raw
        // Path-like
        s = pathLikeUnix.replace(s, "***")
        s = pathLikeWindows.replace(s, "***")
        // FQNs (three or more dot-separated segments)
        s = classFqn.replace(s, "***")
        // Email / JWT / bearer
        s = emailLike.replace(s, "***")
        s = jwtLike.replace(s, "***")
        s = bearerLike.replace(s, "$1***")
        // Newlines -> space; control chars -> nothing
        s = s.replace('\n', ' ').replace('\r', ' ')
        s = controlChars.replace(s, "")
        // Collapse repeated spaces
        s = s.replace(Regex("""\s{2,}"""), " ").trim()
        // Bound length
        if (s.length > MAX_LEN) s = s.substring(0, MAX_LEN - 1) + "…"
        return s.ifEmpty { "internal" }
    }

    /**
     * Stable set of error codes the wire may carry. Adding a
     * code is a wire-contract change: bump the OpenAPI
     * version. The list is short on purpose — every entry
     * is a real status the SPA or operator UI can switch on.
     */
    enum class Code(val wire: String) {
        BadRequest("BAD_REQUEST"),
        MissingHeader("MISSING_HEADER"),
        IdempotencyKeyTooLong("IDEMPOTENCY_KEY_TOO_LONG"),
        IdempotencyKeyInvalidCharset("IDEMPOTENCY_KEY_INVALID_CHARSET"),
        IdempotencyConflict("IDEMPOTENCY_CONFLICT"),
        IdempotencyNotFound("IDEMPOTENCY_NOT_FOUND"),
        NotFound("NOT_FOUND"),
        Conflict("CONFLICT"),
        RuntimeUnavailable("RUNTIME_UNAVAILABLE"),
        Unprocessable("UNPROCESSABLE"),
        PreconditionRequired("PRECONDITION_REQUIRED"),
        RateLimited("RATE_LIMITED"),
        Forbidden("FORBIDDEN"),
        Unauthorized("UNAUTHORIZED"),
        PayloadTooLarge("PAYLOAD_TOO_LARGE"),
        MethodNotAllowed("METHOD_NOT_ALLOWED"),
        UnsupportedMediaType("UNSUPPORTED_MEDIA_TYPE"),
        Internal("INTERNAL"),
    }

    /**
     * Random, opaque correlation handle the catch-all
     * handler can put on the wire so the operator can
     * ask the studio log for the matching exception.
     * 8 bytes hex = 16 chars; 64 bits is plenty for
     * cross-referencing without being user-traceable.
     */
    fun errorId(): String {
        val buf = ByteArray(8)
        SecureRandom().nextBytes(buf)
        return buf.joinToString("") { "%02x".format(it) }
    }
}
