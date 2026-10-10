package dev.pipelinek.studio.domain

/**
 * B2.6 (2026-10-10): central, typed outcome classification.
 *
 * The pipeline SDK records a terminal event
 * (`RunFinished`) whose payload embeds the run's outcome
 * in a small JSON document. The pre-B2.6 reader in
 * [dev.pipelinek.studio.infra.storage.RunReconciler] did
 * `payload.contains("\"outcome\":\"success\"")` — a JSON
 * regex the B2 mandate explicitly forbids ("ningún match
 * de cadenas de resultado mediante regex sobre JSON").
 *
 * This file is the one place that decides what an outcome
 * is. Every reader (the reconciler, future retention, any
 * HTTP layer that has to surface the result) MUST go
 * through [RunOutcome.fromPayload] — never a substring
 * scan, never a hand-rolled regex.
 *
 * The classifier is intentionally minimal:
 *  - `Success` covers the SDK's `outcome=success` value;
 *  - `Failure` covers everything else the SDK records as a
 *    terminal failure (including the SDK's `outcome=failed`,
 *    `outcome=error`, and the absence of an `outcome` key
 *    on a `RunFinished` event — defensible because a
 *    `RunFinished` without an `outcome` field is not a
 *    success);
 *  - `Unknown` covers payloads that are not parseable
 *    as a `{"outcome":"..."}` envelope, so a future
 *    schema change can grow the ADT without breaking
 *    the call sites.
 *
 * The parser is a tiny hand-rolled key walker. It does
 * NOT use `kotlinx.serialization` (the domain module
 * has no JSON deps on purpose) and it does NOT use a
 * regex. It walks the payload string with `indexOf` and
 * length-bounded slices, so the B2.6 invariant "no
 * regex" is preserved.
 */
sealed interface RunOutcome {
    data object Success : RunOutcome
    data class Failure(val rawOutcome: String) : RunOutcome
    data object Unknown : RunOutcome

    companion object {
        /**
         * The set of values the SDK records as a success.
         * The set is small on purpose — adding a new
         * successful outcome is a wire-contract change
         * and must be done here, not at the call site.
         */
        private val SUCCESS_VALUES: Set<String> = setOf("success")

        /**
         * Centralized classifier. Returns a typed
         * [RunOutcome] for any payload string. The
         * function is total: a `null` or empty input
         * maps to [Unknown]; a payload that does not
         * contain a parseable `outcome` field maps to
         * [Unknown] (the reconciler treats Unknown as
         * "not a success", which is the safe default).
         *
         * Contract: the function never throws.
         */
        fun fromPayload(payload: String?): RunOutcome {
            if (payload.isNullOrBlank()) return Unknown
            val outcome = extractOutcomeField(payload) ?: return Unknown
            return if (outcome in SUCCESS_VALUES) Success
            else Failure(rawOutcome = outcome)
        }

        /**
         * Locate the value of the top-level `"outcome"`
         * field in [payload] using only `indexOf` and
         * length-bounded slices. The function:
         *  - refuses payloads longer than
         *    [MAX_PAYLOAD_LEN] (defense against a hostile
         *    SDK filling the events table with a 10MB
         *    payload);
         *  - looks for the literal `"outcome"` key with
         *    a colon after it (no leading-quote tricks —
         *    the walker requires the key to be quoted);
         *  - reads the value as a quoted JSON string up
         *    to the next unescaped `"`;
         *  - returns the unescaped value or `null` if
         *    the walker cannot find a parseable key.
         *
         * The walker is NOT a general JSON parser. It
         * only knows how to read one field at the top
         * level, which is exactly the shape the SDK
         * emits for `RunFinished`. If a future SDK
         * version nests the outcome under a parent
         * object, the walker returns `null` and the
         * caller maps to [Unknown] — which is the
         * safe default.
         */
        private const val MAX_PAYLOAD_LEN = 4096

        private fun extractOutcomeField(payload: String): String? {
            if (payload.length > MAX_PAYLOAD_LEN) return null
            // B2.6 (2026-10-10): the walker only considers the
            // top-level `"outcome"` key. A nested object like
            // `{"result":{"outcome":"success"}}` MUST NOT count
            // — the SDK contract is that `outcome` lives at the
            // top level. The walker tracks brace depth and only
            // accepts the match when depth == 1 (one `{` deep,
            // which is the top-level object's key namespace).
            // At depth 0 (before any `{`) we haven't entered
            // the object yet; at depth >= 2 we're inside a
            // nested object.
            var depth = 0
            var i = 0
            val n = payload.length
            val needle = "\"outcome\""
            while (i < n) {
                val c = payload[i]
                if (c == '{') {
                    depth++
                    i++
                    continue
                }
                if (c == '}') {
                    depth--
                    i++
                    continue
                }
                if (c == '"' && depth == 1) {
                    // Found a top-level key start. Check it
                    // matches "outcome".
                    if (i + needle.length <= n &&
                        payload.regionMatches(i, needle, 0, needle.length)
                    ) {
                        // Skip the closing quote of the key,
                        // then whitespace, then ':'.
                        var p = i + needle.length
                        while (p < n && payload[p].isWhitespace()) p++
                        if (p < n && payload[p] == ':') {
                            p++
                            while (p < n && payload[p].isWhitespace()) p++
                            if (p < n && payload[p] == '"') {
                                p++
                                val sb = StringBuilder()
                                while (p < n) {
                                    val ch = payload[p]
                                    if (ch == '\\' && p + 1 < n) {
                                        sb.append(payload[p + 1])
                                        p += 2
                                        continue
                                    }
                                    if (ch == '"') break
                                    sb.append(ch)
                                    p++
                                }
                                if (p < n && payload[p] == '"') {
                                    return sb.toString().take(MAX_PAYLOAD_LEN)
                                }
                                // Unterminated string — abandon.
                                return null
                            }
                        }
                    }
                    // Not the outcome key (or malformed) — skip
                    // past this quoted region so we don't read a
                    // char of the value as a top-level char.
                    i = skipQuotedRegion(payload, i)
                    continue
                }
                i++
            }
            return null
        }

        private fun skipQuotedRegion(payload: String, from: Int): Int {
            var i = from + 1
            val n = payload.length
            while (i < n) {
                val c = payload[i]
                if (c == '\\' && i + 1 < n) {
                    i += 2
                    continue
                }
                if (c == '"') return i + 1
                i++
            }
            return n
        }
    }
}
