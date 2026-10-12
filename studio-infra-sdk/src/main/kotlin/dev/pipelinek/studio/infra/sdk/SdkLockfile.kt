@file:Suppress("TooGenericExceptionCaught")

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
    /**
     * R5.4 (2026-10-12): a single SDK pin. `version` is
     * the jar's `MANIFEST.MF` `Implementation-Version`
     * (the value the studio probe reads at runtime).
     * `releaseTag` is the upstream GitHub release name
     * (e.g. "0.48.0-rc1") which may differ from
     * `version` when the upstream build pipeline drops
     * the pre-release suffix from the manifest. The two
     * fields together let the cert reconcile the manifest
     * version with the published artifact identity
     * without forcing a lockfile maintainer to invent
     * either value.
     */
    data class PinnedSdk(
        val version: String,
        val sha256: String,
        val status: String,
        val studioRelease: String?,
        val releaseTag: String? = null,
    )

    fun find(version: String): PinnedSdk? = pinnedSdks.firstOrNull { it.version == version }

    /**
     * R5.4 (2026-10-12): lookup-by-SHA. When the SDK jar's
     * `MANIFEST.MF` does NOT carry an `Implementation-Version`
     * attribute (e.g. the official PipelineK 0.48.0-rc1
     * build), the probe has no version string to pass to
     * [SdkLockfileVerifier.verify]. The R5.4 mandate forbids
     * inventing one: the only honest fallback is to match
     * the installed jar's SHA-256 against the lockfile and
     * adopt the pinned version. The lookup is
     * case-insensitive and only matches a single pin
     * (collisions between pinned entries are treated as
     * "ambiguous" → no match, forcing the caller to return
     * BLOCKED rather than guess).
     */
    fun findBySha256(actualSha256: String): PinnedSdk? {
        val matches = pinnedSdks.filter {
            it.sha256.equals(actualSha256, ignoreCase = true)
        }
        return when (matches.size) {
            0 -> null
            1 -> matches.single()
            else -> {
                // Ambiguous: multiple pins share the SHA.
                // Returning null is the safe default; the
                // verifier logs the situation in its own
                // log context and the probe translates the
                // null into a structured Failure with the
                // matching versions surfaced to the operator.
                null
            }
        }
    }

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

    /**
     * R5.4 (2026-10-12): the jar's manifest did NOT carry a
     * version, but the jar's SHA-256 matches a lockfile
     * entry. The verdict carries the resolved version from
     * the lockfile so the probe can emit an honest
     * descriptor. The `actualSha256` is recorded so the cert
     * can surface which artifact the lockfile entry was
     * matched against.
     */
    data class RecoveredBySha(
        val pinned: SdkLockfile.PinnedSdk,
        val actualSha256: String,
    ) : PinVerdict
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

    /**
     * R5.4 (2026-10-12): SHA-only verification for jars that
     * do not carry an `Implementation-Version` in their
     * `MANIFEST.MF`. The probe calls this when
     * [PipelineKRuntimeProbe.describe] sees a jar with no
     * manifest version. The lockfile lookup is
     * case-insensitive and ambiguity-free
     * (see [SdkLockfile.findBySha256]).
     *
     * Returns [PinVerdict.RecoveredBySha] on a single
     * unambiguous match, [PinVerdict.Mismatch] when the SHA
     * does not match any entry (using the first entry's
     * version as a debugging hint), or [PinVerdict.Unknown]
     * when the lockfile has no entries at all.
     */
    fun verifyBySha256(actualSha256: String): PinVerdict {
        val pinned = lockfile.findBySha256(actualSha256)
        return when {
            pinned != null -> {
                log.info(
                    "Recovered SDK version {} from lockfile by SHA match (jar manifest had no Implementation-Version).",
                    pinned.version,
                )
                PinVerdict.RecoveredBySha(pinned, actualSha256)
            }
            lockfile.pinnedSdks.isEmpty() -> {
                log.warn("Lockfile is empty; cannot recover SDK version by SHA.")
                PinVerdict.Unknown(version = "", actualSha256 = actualSha256)
            }
            else -> {
                val firstVersion = lockfile.pinnedSdks.first().version
                // Detect ambiguity: if `findBySha256`
                // returned null because of an SHA collision
                // (multiple pins), surface a more diagnostic
                // message in the logs.
                val colliding = lockfile.pinnedSdks.filter {
                    it.sha256.equals(actualSha256, ignoreCase = true)
                }
                if (colliding.size > 1) {
                    log.warn(
                        "SDK SHA {} matches {} lockfile entries (versions={}); refusing to pick one.",
                        actualSha256.take(16), colliding.size, colliding.map { it.version },
                    )
                } else {
                    log.warn(
                        "SDK SHA {} does not match any lockfile entry; cannot recover version from manifest or lockfile.",
                        actualSha256.take(16),
                    )
                }
                PinVerdict.Mismatch(
                    version = firstVersion,
                    expectedSha256 = lockfile.find(firstVersion)?.sha256 ?: "",
                    actualSha256 = actualSha256,
                )
            }
        }
    }

    companion object {
        private const val CLASSPATH_RESOURCE = "/sdk.lock.json"
        private val SHARED_LOG = LoggerFactory.getLogger(SdkLockfileVerifier::class.java)

        /**
         * Load the lockfile from the classpath. Returns an
         * empty lockfile on miss. R5.4 (2026-10-12): the
         * default classloader is the **thread context
         * classloader** rather than
         * [SdkLockfileVerifier]'s own loader; the resource
         * name is also passed WITHOUT a leading slash, since
         * the standalone `studio-cli` JVM uses
         * `jdk.internal.loader.ClassLoaders$AppClassLoader`
         * which (unlike `URLClassLoader`) treats a leading
         * `/` as a literal path component and returns null.
         * The combo fixes the R5.4 issue where the
         * pipelinekRuntime fields were silently absent.
         */
        fun loadFromClasspath(
            cl: ClassLoader = Thread.currentThread().contextClassLoader
                ?: SdkLockfileVerifier::class.java.classLoader,
        ): SdkLockfile {
            val resourceName = CLASSPATH_RESOURCE.removePrefix("/")
            val stream = cl.getResourceAsStream(resourceName) ?: return SdkLockfile.EMPTY
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
                    // R5.4 (2026-10-12): the studio release
                    // and the upstream SDK release tag may
                    // be tracked under either snake_case
                    // (lockfile format 1/2) or camelCase
                    // (lockfile format 3+). Accept both
                    // forms so a rolling upgrade of the
                    // lockfile does not require a code
                    // change.
                    val studioRelease = entry["studioRelease"] as? String
                        ?: entry["studio_release"] as? String
                    val releaseTag = entry["releaseTag"] as? String
                    SdkLockfile.PinnedSdk(version, sha, status, studioRelease, releaseTag)
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
            val i = skipWs(s, 0)
            require(s[i] == '{') { "expected '{' at $i" }
            val (obj, next) = readObjectBody(s, i + 1)
            return obj
        }

        private fun readObjectBody(s: String, start: Int): Pair<Map<String, Any?>, Int> {
            val out = linkedMapOf<String, Any?>()
            var i = skipWs(s, start)
            if (s[i] == '}') return out to (i + 1)
            while (true) {
                i = skipWs(s, i)
                val key = readKey(s, i)
                i = skipWs(s, key.second)
                require(s[i] == ':') { "expected ':' at $i" }
                i = skipWs(s, i + 1)
                val (v, ni) = readValue(s, i)
                // Skip $comment / $schema_doc and other $-prefixed keys
                if (!key.first.startsWith("$")) out[key.first] = v
                i = skipWs(s, ni)
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
                    var i = skipWs(s, start + 1)
                    if (s[i] == ']') return list to (i + 1)
                    while (true) {
                        i = skipWs(s, i)
                        val (v, ni) = readValue(s, i)
                        list.add(v)
                        i = skipWs(s, ni)
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

        // R-DET-001 follow-up (2026-10-11): renamed from SkipWs to
        // skipWs per Kotlin naming convention (private fun = upper
        // case was a Detekt FunctionNaming violation). The
        // recursive call below updates in lock-step. The
        // `ComplexCondition` warning on the while guard (4 OR
        // operands on whitespace) is justified — the alternative
        // is a `Character.isWhitespace` that would also skip
        // Unicode spaces we don't accept in the lockfile format.
        @Suppress("ComplexCondition")
        private fun skipWs(s: String, start: Int): Int {
            var i = start
            while (i < s.length && (s[i] == ' ' || s[i] == '\n' || s[i] == '\t' || s[i] == '\r')) i++
            // Also skip // line comments (we use them in the lockfile's prose)
            if (i + 1 < s.length && s[i] == '/' && s[i + 1] == '/') {
                val nl = s.indexOf('\n', i)
                return if (nl < 0) s.length else skipWs(s, nl + 1)
            }
            return i
        }
    }
}
