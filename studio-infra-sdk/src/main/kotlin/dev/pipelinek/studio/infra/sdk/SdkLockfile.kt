package dev.pipelinek.studio.infra.sdk

import org.slf4j.LoggerFactory

/**
 * SDK pin lockfile (audit R-22, 2026-10-10).
 *
 * The lockfile is the executable form of the version+SHA pin documented
 * in docs/04-sdk/sdk-compatibility.md. The studio's runtime probe
 * computes the SHA-256 of the installed SDK jar and compares it against
 * the entry for the jar's manifest version. On mismatch, [verify]
 * returns a [PinVerdict.Mismatch] so the probe can refuse to admit
 * runs, which is the only way an "SDK upgrade" can be detected without
 * trusting the operator to read release notes.
 *
 * The lockfile is a classpath resource loaded once at construction.
 * A missing or malformed file is treated as "no pins" (a permissive
 * default for development). Production deployments should ship the
 * resource and rely on the env-var `STUDIO_SDK_PIN=enforce` to make
 * the comparison mandatory.
 *
 * ## Pin policy
 *
 * The policy is driven by the `STUDIO_SDK_PIN` env var:
 *   - `enforce` (default in non-dev): mismatch → [PinVerdict.Mismatch]
 *   - `warn`: mismatch → [PinVerdict.Mismatch] is still returned, the
 *     caller decides whether to fail or proceed
 *   - `off`: no comparison; any SHA is accepted
 *
 * The env var is read in [SdkLockfileVerifier.policyFromEnv], not here,
 * so the policy is the probe's concern and the lockfile is just data.
 *
 * ## Parsing
 *
 * The lockfile is parsed by a tiny purpose-built reader rather than a
 * generic JSON library so this module stays free of a serialization
 * dependency. The file shape is documented in `sdk.lock.json` and is
 * the only file this reader accepts.
 */
data class SdkLockfile(
    val formatVersion: Int,
    val pinnedSdks: List<PinnedSdk>,
) {
    data class PinnedSdk(
        val version: String,
        val sha256: String,
        val status: String,
        val studioRelease: String?,
    )

    fun find(version: String): PinnedSdk? = pinnedSdks.firstOrNull { it.version == version }

    companion object {
        val EMPTY = SdkLockfile(formatVersion = 1, pinnedSdks = emptyList())
    }
}

sealed interface PinVerdict {
    /** The installed jar's SHA matches the lockfile entry. */
    data class Match(val pinned: SdkLockfile.PinnedSdk) : PinVerdict
    /** The installed jar's SHA does NOT match the lockfile entry. */
    data class Mismatch(
        val version: String,
        val expectedSha256: String,
        val actualSha256: String,
    ) : PinVerdict
    /** The jar's version is not in the lockfile at all. */
    data class Unknown(val version: String, val actualSha256: String) : PinVerdict
}

enum class PinPolicy { Enforce, Warn, Off }

class SdkLockfileVerifier(
    private val lockfile: SdkLockfile,
    private val log: org.slf4j.Logger = LoggerFactory.getLogger(SdkLockfileVerifier::class.java),
) {
    fun verify(version: String, actualSha256: String): PinVerdict {
        val pinned = lockfile.find(version)
        return when {
            pinned == null -> {
                log.info("SDK version {} not in lockfile (no pin recorded).", version)
                PinVerdict.Unknown(version, actualSha256)
            }
            pinned.sha256.equals(actualSha256, ignoreCase = true) -> {
                log.debug("SDK version {} SHA matches lockfile pin.", version)
                PinVerdict.Match(pinned)
            }
            else -> {
                log.warn(
                    "SDK SHA pin mismatch for version {}: expected={}, actual={}",
                    version, pinned.sha256, actualSha256,
                )
                PinVerdict.Mismatch(version, pinned.sha256, actualSha256)
            }
        }
    }

    companion object {
        private const val CLASSPATH_RESOURCE = "/sdk.lock.json"
        private val SHARED_LOG = LoggerFactory.getLogger(SdkLockfileVerifier::class.java)

        /** Load the lockfile from the classpath. Returns an empty lockfile on miss. */
        fun loadFromClasspath(cl: ClassLoader = SdkLockfileVerifier::class.java.classLoader): SdkLockfile {
            val stream = cl.getResourceAsStream(CLASSPATH_RESOURCE) ?: return SdkLockfile.EMPTY
            return try {
                stream.use {
                    val text = it.readBytes().toString(Charsets.UTF_8)
                    parseLockfile(text)
                }
            } catch (e: Throwable) {
                SHARED_LOG.warn("Failed to parse sdk.lock.json: {}", e.toString())
                SdkLockfile.EMPTY
            }
        }

        /** Read the [PinPolicy] from the STUDIO_SDK_PIN env var. */
        fun policyFromEnv(env: Map<String, String> = System.getenv()): PinPolicy {
            return when (env["STUDIO_SDK_PIN"]?.lowercase()) {
                "off", "disabled", "0" -> PinPolicy.Off
                "warn" -> PinPolicy.Warn
                else -> PinPolicy.Enforce
            }
        }

        /**
         * Hand-rolled JSON reader for the small, stable lockfile shape.
         * The reader is intentionally narrow: it only accepts the
         * documented fields and treats unknown fields as `$`-prefixed
         * comments. This keeps the lockfile self-documenting (the
         * `$comment` and `$schema_doc` keys are ignored) without
         * pulling in a serialization dependency.
         *
         * A malformed or unexpected input is NOT fatal — the function
         * returns [SdkLockfile.EMPTY] and the caller (typically
         * [loadFromClasspath]) decides what to do. The reasoning: a
         * corrupted lockfile must not crash the studio; it should
         * downgrade to the most restrictive policy (no pins, which
         * the Enforce policy treats as Unknown + Failure).
         */
        internal fun parseLockfile(text: String): SdkLockfile {
            return try {
                val obj = readObject(text)
                val formatVersion = (obj["format_version"] as? Long)?.toInt() ?: 1
                @Suppress("UNCHECKED_CAST")
                val arr = obj["pinned_sdks"] as? List<Map<String, Any?>>
                    ?: return SdkLockfile(formatVersion, emptyList())
                val pins = arr.mapNotNull { entry ->
                    val version = entry["version"] as? String ?: return@mapNotNull null
                    val sha = entry["sha256"] as? String ?: return@mapNotNull null
                    val status = entry["status"] as? String ?: "current"
                    val release = entry["studio_release"] as? String
                    SdkLockfile.PinnedSdk(version, sha, status, release)
                }
                SdkLockfile(formatVersion, pins)
            } catch (e: Throwable) {
                SHARED_LOG.warn("sdk.lock.json parse error: {}", e.toString())
                SdkLockfile.EMPTY
            }
        }

        // ---- minimal JSON parser for the lockfile only ----
        // The parser handles the subset of JSON we emit: objects
        // with string/number/array values, strings with no
        // surrogates or escape sequences beyond `\"` and `\\`, and
        // unquoted keys (we control the writer so this is fine).
        // Anything outside that subset returns null and the caller
        // treats the file as malformed.

        private fun readObject(s: String): Map<String, Any?> {
            val i = SkipWs(s, 0)
            require(s[i] == '{') { "expected '{' at $i" }
            val (obj, next) = readObjectBody(s, i + 1)
            return obj
        }

        private fun readObjectBody(s: String, start: Int): Pair<Map<String, Any?>, Int> {
            val out = linkedMapOf<String, Any?>()
            var i = SkipWs(s, start)
            if (s[i] == '}') return out to (i + 1)
            while (true) {
                i = SkipWs(s, i)
                val key = readKey(s, i)
                i = SkipWs(s, key.second)
                require(s[i] == ':') { "expected ':' at $i" }
                i = SkipWs(s, i + 1)
                val (v, ni) = readValue(s, i)
                // Skip $comment / $schema_doc and other $-prefixed keys
                if (!key.first.startsWith("$")) out[key.first] = v
                i = SkipWs(s, ni)
                if (s[i] == ',') { i++; continue }
                if (s[i] == '}') return out to (i + 1)
                error("expected ',' or '}' at $i")
            }
        }

        private fun readKey(s: String, start: Int): Pair<String, Int> {
            // Unquoted keys: [A-Za-z0-9_]+
            var i = start
            val sb = StringBuilder()
            while (i < s.length && (s[i].isLetterOrDigit() || s[i] == '_' || s[i] == '-')) {
                sb.append(s[i]); i++
            }
            if (sb.isEmpty()) {
                // Fall back to quoted string
                return readString(s, start)
            }
            return sb.toString() to i
        }

        private fun readValue(s: String, start: Int): Pair<Any?, Int> {
            return when (s[start]) {
                '"' -> readString(s, start)
                '{' -> {
                    val (o, ni) = readObjectBody(s, start + 1)
                    o to ni
                }
                '[' -> {
                    val list = mutableListOf<Any?>()
                    var i = SkipWs(s, start + 1)
                    if (s[i] == ']') return list to (i + 1)
                    while (true) {
                        i = SkipWs(s, i)
                        val (v, ni) = readValue(s, i)
                        list.add(v)
                        i = SkipWs(s, ni)
                        if (s[i] == ',') { i++; continue }
                        if (s[i] == ']') return list to (i + 1)
                        error("expected ',' or ']' at $i")
                    }
                    @Suppress("UNREACHABLE_CODE")
                    error("unreachable")
                }
                else -> readNumberOrLiteral(s, start)
            }
        }

        private fun readString(s: String, start: Int): Pair<String, Int> {
            require(s[start] == '"') { "expected '\"' at $start" }
            val sb = StringBuilder()
            var i = start + 1
            while (i < s.length && s[i] != '"') {
                if (s[i] == '\\' && i + 1 < s.length) {
                    when (s[i + 1]) {
                        '"' -> sb.append('"')
                        '\\' -> sb.append('\\')
                        '/' -> sb.append('/')
                        'n' -> sb.append('\n')
                        't' -> sb.append('\t')
                        'r' -> sb.append('\r')
                        else -> sb.append(s[i + 1])
                    }
                    i += 2
                } else {
                    sb.append(s[i]); i++
                }
            }
            require(i < s.length) { "unterminated string" }
            return sb.toString() to (i + 1)
        }

        private fun readNumberOrLiteral(s: String, start: Int): Pair<Any?, Int> {
            var i = start
            while (i < s.length && s[i] !in ",]} \n\t\r") i++
            val raw = s.substring(start, i)
            return when {
                raw == "true" -> true to i
                raw == "false" -> false to i
                raw == "null" -> null to i
                raw.contains('.') -> (raw.toDouble()) to i
                else -> (raw.toLongOrNull() ?: raw) to i
            }
        }

        private fun SkipWs(s: String, start: Int): Int {
            var i = start
            while (i < s.length && (s[i] == ' ' || s[i] == '\n' || s[i] == '\t' || s[i] == '\r')) i++
            // Also skip // line comments (we use them in the lockfile's prose)
            if (i + 1 < s.length && s[i] == '/' && s[i + 1] == '/') {
                val nl = s.indexOf('\n', i)
                return if (nl < 0) s.length else SkipWs(s, nl + 1)
            }
            return i
        }
    }
}
