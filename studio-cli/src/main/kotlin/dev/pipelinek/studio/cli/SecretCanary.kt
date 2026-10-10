package dev.pipelinek.studio.cli

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

/**
 * B5.3 (2026-10-10): the secret canary per
 * ROADMAP §"S5 — Product readiness".
 *
 * The S5 mandate says the studio runs
 * "secret canaries" as part of the security
 * hardening pass. A canary is a small set
 * of well-known secret patterns (e.g.
 * AWS access keys, GitHub tokens, JWT
 * tokens, PEM private keys). The canary
 * scans a directory tree for any file that
 * matches a pattern and reports the
 * findings; the studio refuses to call
 * the deployment "certified" if a canary
 * fired.
 *
 * The canary is INTENTIONALLY heuristic.
 * A well-crafted secret that does not
 * match a pattern would bypass the canary.
 * The canary is one layer of defense; the
 * rest is policy (no secrets in source,
 * `keystore.jks` at 0600, etc.).
 *
 * The patterns are closed (a fixed list
 * in [DEFAULT_PATTERNS]). The B5.3 test
 * pins the catalog + a few positive +
 * negative matches.
 */
@Serializable
data class SecretCanaryReport(
    val scannedRoot: String,
    val filesScanned: Int,
    val findings: List<Finding>,
) {
    @Serializable
    data class Finding(
        val path: String,
        val patternName: String,
        val excerpt: String,
    )

    val hasFindings: Boolean
        get() = findings.isNotEmpty()

    fun toJson(): String = Json { prettyPrint = true }.encodeToString(serializer(), this)
}

class SecretCanary(
    private val patterns: List<SecretPattern> = DEFAULT_PATTERNS,
) {
    /**
     * Scan every regular file under [root].
     * The root itself is NOT included as a
     * finding. The scan is bounded: each
     * file is read at most [maxBytes] bytes
     * (default 1 MiB) so a 10 GiB log file
     * does not OOM the canary.
     */
    fun scan(root: Path, maxBytes: Long = 1L * 1024L * 1024L): SecretCanaryReport {
        val findings = mutableListOf<SecretCanaryReport.Finding>()
        var filesScanned = 0
        if (!Files.isDirectory(root)) {
            return SecretCanaryReport(root.toString(), 0, emptyList())
        }
        Files.walk(root).use { stream ->
            stream.filter { Files.isRegularFile(it) }
                .forEach { path ->
                    filesScanned++
                    val rel = root.relativize(path).toString()
                    val size = Files.size(path)
                    if (size > maxBytes) return@forEach
                    val text = runCatching {
                        Files.newInputStream(path).use { it.readBytes() }
                            .toString(Charsets.UTF_8)
                    }.getOrNull() ?: return@forEach
                    for (pat in patterns) {
                        val match = pat.regex.find(text) ?: continue
                        val excerptStart = maxOf(0, match.range.first - 8)
                        val excerptEnd = minOf(text.length, match.range.last + 1 + 8)
                        val excerpt = text.substring(excerptStart, excerptEnd)
                            .replace("\n", "\\n")
                        findings += SecretCanaryReport.Finding(
                            path = rel,
                            patternName = pat.name,
                            excerpt = excerpt,
                        )
                        // One match per file per
                        // pattern is enough; do
                        // not flood the report.
                    }
                }
        }
        return SecretCanaryReport(root.toString(), filesScanned, findings)
    }

    companion object {
        val DEFAULT_PATTERNS: List<SecretPattern> = listOf(
            SecretPattern(
                name = "aws-access-key",
                regex = Regex("""AKIA[0-9A-Z]{16}"""),
            ),
            SecretPattern(
                name = "github-token",
                regex = Regex("""gh[pousr]_[A-Za-z0-9_]{36,}"""),
            ),
            SecretPattern(
                name = "slack-token",
                regex = Regex("""xox[baprs]-[A-Za-z0-9-]{10,}"""),
            ),
            SecretPattern(
                name = "pem-private-key",
                regex = Regex("""-----BEGIN (?:RSA |EC |DSA |OPENSSH )?PRIVATE KEY-----"""),
            ),
            SecretPattern(
                name = "jwt",
                regex = Regex("""eyJ[A-Za-z0-9_-]+\.eyJ[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+"""),
            ),
        )
    }

    data class SecretPattern(
        val name: String,
        val regex: Regex,
    )
}
