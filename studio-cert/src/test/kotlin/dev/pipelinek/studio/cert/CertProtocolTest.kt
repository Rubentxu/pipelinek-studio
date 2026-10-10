package dev.pipelinek.studio.cert

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * B5.4 (2026-10-10): the certification
 * protocol contract tests.
 *
 * The S5 mandate says the studio ships an
 * "independent certification of the installed
 * artifact (no src checkout)". The protocol
 * is the ordered sequence of six steps. The
 * tests pin:
 *  1. The step list: exactly 6 steps in the
 *     documented order.
 *  2. The verifyChecksums helper against
 *     a known-input vector (a manifest with
 *     one file, matching sha256, must
 *     produce no mismatches).
 *  3. The FAIL contract: a missing file or
 *     a sha256 mismatch produces a non-empty
 *     mismatch list with a clear reason.
 *  4. The per-step CertStepResult shape:
 *     name, status, durationMs, detail.
 *  5. The CertReport overall: FAIL when any
 *     step is FAIL, PASS otherwise.
 *
 * The tests are intentionally source-only:
 * no network, no subprocess. The B5.7
 * release script runs the actual live
 * certification.
 */
class CertProtocolTest {

    @Test
    fun `B5_4 protocol has exactly 6 steps in the documented order`() {
        val steps = CertProtocol.CURRENT.steps.map { it.name }
        assertEquals(
            listOf(
                "fresh_clone",
                "dist_tests",
                "mcp_v2",
                "browser_offline",
                "runner_isolated",
                "metadata_sqlite_restore",
            ),
            steps,
        )
    }

    @Test
    fun `B5_4 verifyChecksums returns no mismatches when every file matches`(@TempDir tmp: Path) {
        // Build a tiny distribution + a
        // matching manifest.
        val dist = tmp.resolve("dist")
        Files.createDirectories(dist)
        Files.writeString(dist.resolve("a.txt"), "alpha")
        val shaA = sha256(dist.resolve("a.txt"))
        val manifest = tmp.resolve("manifest.json")
        Files.writeString(
            manifest,
            """
            {
              "format": "studio-distribution-manifest",
              "version": "0.1.0",
              "buildAt": 0,
              "files": [
                { "path": "a.txt", "size": 5, "sha256": "$shaA" }
              ]
            }
            """.trimIndent(),
        )
        val mismatches = CertProtocol.verifyChecksums(dist, manifest)
        assertTrue(
            mismatches.isEmpty(),
            "expected no mismatches, got: $mismatches",
        )
    }

    @Test
    fun `B5_4 verifyChecksums reports a sha256 mismatch as a clear reason`(@TempDir tmp: Path) {
        val dist = tmp.resolve("dist")
        Files.createDirectories(dist)
        Files.writeString(dist.resolve("a.txt"), "alpha")
        val manifest = tmp.resolve("manifest.json")
        Files.writeString(
            manifest,
            """
            {
              "format": "studio-distribution-manifest",
              "version": "0.1.0",
              "buildAt": 0,
              "files": [
                { "path": "a.txt", "size": 5, "sha256": "0000000000000000000000000000000000000000000000000000000000000000" }
              ]
            }
            """.trimIndent(),
        )
        val mismatches = CertProtocol.verifyChecksums(dist, manifest)
        assertEquals(1, mismatches.size, "expected 1 mismatch, got: $mismatches")
        assertTrue(
            mismatches[0].contains("sha256 mismatch") && mismatches[0].contains("a.txt"),
            "mismatch must name the file and the property, got: ${mismatches[0]}",
        )
    }

    @Test
    fun `B5_4 verifyChecksums reports a missing file as a clear reason`(@TempDir tmp: Path) {
        val dist = tmp.resolve("dist")
        Files.createDirectories(dist)
        val manifest = tmp.resolve("manifest.json")
        Files.writeString(
            manifest,
            """
            {
              "format": "studio-distribution-manifest",
              "version": "0.1.0",
              "buildAt": 0,
              "files": [
                { "path": "missing.txt", "size": 0, "sha256": "00" }
              ]
            }
            """.trimIndent(),
        )
        val mismatches = CertProtocol.verifyChecksums(dist, manifest)
        assertEquals(1, mismatches.size)
        assertTrue(
            mismatches[0].contains("missing") && mismatches[0].contains("missing.txt"),
            "missing file must be named, got: ${mismatches[0]}",
        )
    }

    @Test
    fun `B5_4 CertReport overall is FAIL when any step is FAIL`() {
        val r = CertReport(
            steps = listOf(
                CertStepResult("fresh_clone", CertStepResult.Status.PASS, 100L),
                CertStepResult("dist_tests", CertStepResult.Status.FAIL, 50L, "doctor exited 1"),
                CertStepResult("mcp_v2", CertStepResult.Status.PASS, 200L),
            ),
        )
        assertEquals(CertStepResult.Status.FAIL, r.overall)
    }

    @Test
    fun `B5_4 CertReport overall is PASS when every step is PASS`() {
        val r = CertReport(
            steps = listOf(
                CertStepResult("fresh_clone", CertStepResult.Status.PASS, 100L),
                CertStepResult("dist_tests", CertStepResult.Status.PASS, 50L),
                CertStepResult("mcp_v2", CertStepResult.Status.PASS, 200L),
                CertStepResult("browser_offline", CertStepResult.Status.PASS, 300L),
                CertStepResult("runner_isolated", CertStepResult.Status.PASS, 500L),
                CertStepResult("metadata_sqlite_restore", CertStepResult.Status.PASS, 100L),
            ),
        )
        assertEquals(CertStepResult.Status.PASS, r.overall)
    }

    @Test
    fun `B5_4 CertStepResult JSON shape is the wire contract`() {
        val r = CertStepResult("fresh_clone", CertStepResult.Status.PASS, 100L, "ok")
        val text = kotlinx.serialization.json.Json { prettyPrint = true }
            .encodeToString(CertStepResult.serializer(), r)
        // kotlinx.serialization with prettyPrint produces a JSON object with these keys.
        assertTrue("\"name\"" in text)
        assertTrue("\"status\"" in text)
        assertTrue("\"durationMs\"" in text)
        assertTrue("\"detail\"" in text)
    }

    @Test
    fun `B5_4 protocol JSON shape is the wire contract`() {
        val text = CertProtocol.CURRENT.toJson()
        assertTrue("\"steps\"" in text)
        assertTrue("\"name\"" in text)
        assertTrue("\"description\"" in text)
        // Every step name appears in the JSON.
        for (step in CertProtocol.CURRENT.steps) {
            assertTrue(
                step.name in text,
                "step name '${step.name}' must appear in the protocol JSON",
            )
        }
    }

    private fun sha256(path: Path): String {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        Files.newInputStream(path).use { input ->
            val buf = ByteArray(8192)
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}
