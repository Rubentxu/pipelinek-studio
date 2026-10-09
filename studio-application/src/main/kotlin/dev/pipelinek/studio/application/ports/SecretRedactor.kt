package dev.pipelinek.studio.application.ports

/**
 * S5 secret-canary defence: anything rendered for the SPA, CLI, or MCP
 * must NOT echo a secret through. The redactor scans a string and
 * replaces the matched substrings with a stable placeholder.
 *
 * The port is intentionally narrow: a single `redact` function that
 * returns a redacted copy. Implementations decide what counts as a
 * secret (regex patterns, dictionary, ML model). The renderer chain
 * always invokes it on every Text/Json/Table cell BEFORE formatting.
 *
 * No concrete redactor is wired in by default — the no-op default
 * leaves everything visible so the operator can opt in by binding
 * [PatternSecretRedactor] (or any other impl) in the composition
 * root. This matches the D-005 "no silent security posture" rule:
 * the operator chooses.
 */
fun interface SecretRedactor {
    fun redact(input: String): String
}

/** No-op redactor used when the composition root has not bound one. */
object NoopSecretRedactor : SecretRedactor {
    override fun redact(input: String): String = input
}

/**
 * Pattern-based redactor. Catches the common low-hanging fruit:
 *  - AWS access keys (`AKIA`/`ASIA` followed by 16 uppercase alnum)
 *  - GitHub PATs (ghp_, gho_, ghu_, ghs_, ghr_ prefixes + 36+ chars)
 *  - Slack tokens (xox[bpars]-...)
 *  - `STUDIO_TOKEN=...` style env assignments
 *  - High-entropy hex/base64 blobs (>= 32 chars, no whitespace)
 *
 * The replacements are stable and human-readable so the operator can
 * tell what kind of secret was caught without leaking the value.
 */
class PatternSecretRedactor(
    private val placeholder: String = "[REDACTED]",
) : SecretRedactor {

    private val patterns: List<Pair<Regex, String>> = listOf(
        Regex("""\b(?:AKIA|ASIA)[0-9A-Z]{16}\b""") to "aws-key",
        Regex("""\bgh[pousr]_[A-Za-z0-9]{30,}\b""") to "github-token",
        Regex("""\bxox[bpars]-[A-Za-z0-9-]{10,}\b""") to "slack-token",
        Regex("""(?i)(STUDIO_[A-Z0-9_]*(?:TOKEN|KEY|SECRET|PASSWORD))\s*=\s*['"]?([^\s'";]+)""") to "studio-env-secret",
        Regex("""\b[A-Za-z0-9+/=_-]{40,}\b""") to "high-entropy-blob",
    )

    override fun redact(input: String): String {
        var out = input
        for ((pattern, kind) in patterns) {
            out = pattern.replace(out) { match ->
                when (kind) {
                    "studio-env-secret" -> {
                        // Keep the variable name; redact only the value.
                        val name = match.groupValues[1]
                        "$name=$placeholder"
                    }
                    else -> "[$kind:$placeholder]"
                }
            }
        }
        return out
    }
}
