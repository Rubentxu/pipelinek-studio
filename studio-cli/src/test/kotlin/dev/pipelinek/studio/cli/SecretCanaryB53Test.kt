package dev.pipelinek.studio.cli

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * B5.3 (2026-10-10): the secret canary tests.
 *
 * The S5 mandate says the studio runs
 * "secret canaries" as part of the security
 * hardening pass. The canary is a small,
 * deterministic scanner: a closed catalog
 * of well-known secret patterns. A
 * deployment that contains a canary match
 * is not certifiable; a deployment that
 * passes the canary is not certified (the
 * canary is one layer of defense), but it
 * is one less thing to worry about.
 *
 * The tests pin:
 *  1. The closed pattern catalog: 5 default
 *     patterns (aws-access-key, github-token,
 *     slack-token, pem-private-key, jwt).
 *  2. Positive matches: each pattern
 *     triggers on a known-vector input.
 *  3. Negative matches: clean source does
 *     not trigger any pattern.
 *  4. The scan report shape: the report
 *     carries the scanned root, the file
 *     count, and a list of findings.
 *  5. The scan respects the maxBytes
 *     cap: a file larger than the cap is
 *     skipped (no OOM on a 10 GiB log).
 *  6. The scan handles a missing root
 *     gracefully (returns an empty report,
 *     not an exception).
 */
class SecretCanaryB53Test {

    @Test
    fun `B5_3 default pattern catalog is the closed 5-entry list`() {
        // The catalog is the security
        // contract; a change is a security-
        // relevant change. The test pins the
        // names + the count.
        val names = SecretCanary.DEFAULT_PATTERNS.map { it.name }
        assertEquals(5, names.size, "default pattern catalog must have 5 entries, got: $names")
        assertTrue("aws-access-key" in names)
        assertTrue("github-token" in names)
        assertTrue("slack-token" in names)
        assertTrue("pem-private-key" in names)
        assertTrue("jwt" in names)
    }

    @Test
    fun `B5_3 AWS access key pattern fires on a known vector`(@TempDir tmp: Path) {
        val f = tmp.resolve("creds.txt")
        Files.writeString(f, "AWS_ACCESS_KEY_ID=AKIAIOSFODNN7EXAMPLE")
        val report = SecretCanary().scan(tmp)
        assertTrue(report.hasFindings, "AWS access key must trigger the canary")
        val finding = report.findings.first { it.patternName == "aws-access-key" }
        assertTrue(
            finding.path == "creds.txt",
            "finding must name the file, got: ${finding.path}",
        )
        assertTrue(
            "AKIAIOSFODNN7EXAMPLE" in finding.excerpt,
            "excerpt must include the matched value, got: ${finding.excerpt}",
        )
    }

    @Test
    fun `B5_3 GitHub personal access token pattern fires on a known vector`(
        @TempDir tmp: Path,
    ) {
        val f = tmp.resolve("token.txt")
        Files.writeString(f, "token: ghp_abcdefghijklmnopqrstuvwxyz0123456789AB")
        val report = SecretCanary().scan(tmp)
        val finding = report.findings.firstOrNull { it.patternName == "github-token" }
        assertTrue(finding != null, "GitHub token must trigger the canary, got: ${report.findings}")
    }

    @Test
    fun `B5_3 PEM private key pattern fires on a known vector`(@TempDir tmp: Path) {
        val f = tmp.resolve("key.pem")
        Files.writeString(f, "-----BEGIN RSA PRIVATE KEY-----\nMIIE...\n-----END RSA PRIVATE KEY-----")
        val report = SecretCanary().scan(tmp)
        val finding = report.findings.firstOrNull { it.patternName == "pem-private-key" }
        assertTrue(finding != null, "PEM private key must trigger the canary")
    }

    @Test
    fun `B5_3 JWT pattern fires on a known vector`(@TempDir tmp: Path) {
        val f = tmp.resolve("token.txt")
        Files.writeString(
            f,
            "auth: eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJ0ZXN0In0.signature_here_xxxxxxxxxxxxxxxx",
        )
        val report = SecretCanary().scan(tmp)
        val finding = report.findings.firstOrNull { it.patternName == "jwt" }
        assertTrue(finding != null, "JWT must trigger the canary")
    }

    @Test
    fun `B5_3 clean source does not trigger any pattern`(@TempDir tmp: Path) {
        // Typical Studio artifacts: a
        // .kt source, a build log, a manifest.
        // None should match.
        Files.writeString(tmp.resolve("main.kt"), "fun main() { println(\"hello\") }")
        Files.writeString(tmp.resolve("build.log"), "BUILD SUCCESSFUL in 5s")
        Files.writeString(tmp.resolve("manifest.json"), "{\"format\": \"studio-distribution-manifest\"}")
        val report = SecretCanary().scan(tmp)
        assertFalse(
            report.hasFindings,
            "clean source must not trigger any pattern, got: ${report.findings}",
        )
        assertEquals(3, report.filesScanned)
    }

    @Test
    fun `B5_3 scan report shape is the wire contract`(@TempDir tmp: Path) {
        Files.writeString(tmp.resolve("creds.txt"), "AKIAIOSFODNN7EXAMPLE")
        val report = SecretCanary().scan(tmp)
        // JSON shape: scannedRoot, filesScanned, findings[].
        val text = report.toJson()
        assertTrue("\"scannedRoot\"" in text, "report must include scannedRoot")
        assertTrue("\"filesScanned\"" in text, "report must include filesScanned")
        assertTrue("\"findings\"" in text, "report must include findings")
        assertTrue("\"path\"" in text, "finding must include path")
        assertTrue("\"patternName\"" in text, "finding must include patternName")
        assertTrue("\"excerpt\"" in text, "finding must include excerpt")
    }

    @Test
    fun `B5_3 scan respects maxBytes cap (large file is skipped)`(@TempDir tmp: Path) {
        // A file > maxBytes is skipped.
        // The canary never reads more than
        // maxBytes from a single file.
        val big = tmp.resolve("big.txt")
        val padding = "a".repeat(100)
        Files.writeString(big, padding)
        // Insert a token past the cap.
        val token = "AKIAIOSFODNN7EXAMPLE"
        val more = "b".repeat(2_000)
        Files.writeString(big, padding + "\n" + more + "\n" + token)
        val report = SecretCanary().scan(tmp, maxBytes = 1_500L)
        // The big file is > 1.5 KB; the
        // scan skipped it. The token is
        // past the cap and must NOT fire.
        assertFalse(
            report.hasFindings,
            "files past maxBytes must be skipped, got: ${report.findings}",
        )
    }

    @Test
    fun `B5_3 scan on missing root returns an empty report, not an exception`() {
        // The canary must not throw on a
        // missing root (e.g. the data dir
        // has not been created yet).
        val report = SecretCanary().scan(Path.of("/nonexistent-root-for-canary-12345"))
        assertEquals(0, report.filesScanned)
        assertFalse(report.hasFindings)
    }

    @Test
    fun `B5_3 scan walks subdirectories recursively`(@TempDir tmp: Path) {
        // A canary hit in a subdir must
        // surface; the canary is not just a
        // top-level scan.
        Files.createDirectories(tmp.resolve("sub"))
        Files.writeString(tmp.resolve("sub/creds.txt"), "AKIAIOSFODNN7EXAMPLE")
        val report = SecretCanary().scan(tmp)
        assertTrue(report.hasFindings, "canary hit in a subdir must surface, got: ${report.findings}")
        assertEquals("sub/creds.txt", report.findings.first().path)
    }
}
