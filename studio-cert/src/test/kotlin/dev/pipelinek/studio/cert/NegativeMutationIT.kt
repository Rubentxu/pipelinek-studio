package dev.pipelinek.studio.cert

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertTrue
import kotlin.test.assertFalse

/**
 * R5.4 + R5.5 (2026-10-12): executable negative
 * mutations. The mandate says: "Cada mutación debe
 * impedir una certificación PASS." These tests run
 * the actual `run-all-cert` CLI against a real
 * studio + SDK + MCP distribution, but with one byte
 * (or one file) mutated. Every mutation MUST produce
 * a non-zero exit code AND a FAIL verdict in the
 * report JSON.
 *
 * The tests are slow (they spawn a real cert
 * subprocess) so they are guarded by the env var
 * CERT_NEGATIVE_MUTATION_IT=1. CI sets the env var
 * to enable the gate; local dev runs without it for
 * fast feedback.
 *
 * Required inputs (paths):
 *  - CERT_STUDIO_TAR: studio-cli tarball path
 *  - CERT_SDK_ZIP: PipelineK SDK zip path
 *  - CERT_MCP_JS: MCP server.js path
 *  - CERT_FIXTURES: directory with .pipeline.kts files
 */
class NegativeMutationIT {

    private val enabled: Boolean
        get() = System.getenv("CERT_NEGATIVE_MUTATION_IT") == "1" ||
            System.getProperty("cert.negative.mutation.it") == "1"

    private fun requireInputs(): Quad? {
        val tar = System.getenv("CERT_STUDIO_TAR") ?: return null
        val zip = System.getenv("CERT_SDK_ZIP") ?: return null
        val mcp = System.getenv("CERT_MCP_JS") ?: return null
        val fix = System.getenv("CERT_FIXTURES") ?: return null
        return Quad(Path.of(tar), Path.of(zip), Path.of(mcp), Path.of(fix))
    }

    private data class Quad(val tar: Path, val zip: Path, val mcp: Path, val fixtures: Path)

    private fun skip(reason: String): Boolean {
        if (!enabled) {
            println("SKIP: $reason (set CERT_NEGATIVE_MUTATION_IT=1 to enable)")
            return true
        }
        return false
    }

    /**
     * Find the install dir of the cert CLI. The test
     * runs after `gradlew :studio-cert:installDist`,
     * so the binary lives at
     * studio-cert/build/install/studio-cert/bin/studio-cert.
     */
    private fun certBinary(): Path {
        val cwd = Path.of("").toAbsolutePath()
        // Walk up to find studio-cert/build/install.
        var probe: Path? = cwd
        repeat(6) {
            val candidate = probe?.resolve("studio-cert/build/install/studio-cert/bin/studio-cert")
            if (candidate != null && Files.isRegularFile(candidate)) return candidate
            probe = probe?.parent
        }
        error("studio-cert CLI not found under studio-cert/build/install; run :studio-cert:installDist first")
    }

    private data class RunResult(val exitCode: Int, val reportJson: String)

    private fun runAllCert(
        tar: Path,
        zip: Path,
        mcp: Path,
        fixtures: Path,
        outReport: Path,
    ): RunResult {
        val cert = certBinary()
        val cmd = listOf(
            cert.toString(),
            "run-all-cert",
            tar.toString(),
            zip.toString(),
            mcp.toString(),
            fixtures.toString(),
            outReport.toString(),
        )
        val proc = ProcessBuilder(cmd)
            .redirectErrorStream(true)
            .start()
        val out = proc.inputStream.bufferedReader().use { it.readText() }
        val rc = proc.waitFor()
        val report = if (Files.isRegularFile(outReport)) {
            Files.readString(outReport)
        } else ""
        return RunResult(rc, report)
    }

    private fun assertFails(res: RunResult, mutation: String) {
        assertTrue(
            res.exitCode != 0,
            "mutation '$mutation' must produce non-zero exit; got 0. Report: ${res.reportJson.take(200)}",
        )
        assertTrue(
            res.reportJson.contains("\"verdict\": \"FAIL\"") ||
                res.reportJson.contains("\"verdict\":\"FAIL\""),
            "mutation '$mutation' must produce verdict=FAIL; got report: ${res.reportJson.take(400)}",
        )
    }

    @Test
    fun `mutation - tar byte flipped fails cert`(@TempDir tmp: Path) {
        if (skip("negative-mutation")) return
        val q = requireInputs() ?: run { println("SKIP: env vars missing"); return }
        val mutatedTar = tmp.resolve("studio-cli-mutated.tar")
        Files.copy(q.tar, mutatedTar)
        // Flip one byte in the middle of the tar.
        val bytes = Files.readAllBytes(mutatedTar)
        val mid = bytes.size / 2
        bytes[mid] = (bytes[mid].toInt() xor 0x01).toByte()
        Files.write(mutatedTar, bytes)
        val outReport = tmp.resolve("report.json")
        val res = runAllCert(mutatedTar, q.zip, q.mcp, q.fixtures, outReport)
        assertFails(res, "tar-byte-flipped")
    }

    @Test
    fun `mutation - sdk zip replaced by garbage fails cert`(@TempDir tmp: Path) {
        if (skip("negative-mutation")) return
        val q = requireInputs() ?: run { println("SKIP: env vars missing"); return }
        val garbageZip = tmp.resolve("garbage-sdk.zip")
        Files.writeString(garbageZip, "this is not a zip, this is not a zip\n")
        val outReport = tmp.resolve("report.json")
        val res = runAllCert(q.tar, garbageZip, q.mcp, q.fixtures, outReport)
        assertFails(res, "sdk-zip-garbage")
    }

    @Test
    fun `mutation - mcp server js deleted fails cert`(@TempDir tmp: Path) {
        if (skip("negative-mutation")) return
        val q = requireInputs() ?: run { println("SKIP: env vars missing"); return }
        val outReport = tmp.resolve("report.json")
        // Pass a path that does NOT exist; the cert must
        // surface FAIL on the MCP cases, not a fake PASS.
        val res = runAllCert(
            q.tar,
            q.zip,
            Path.of("/nonexistent/mcp-server.js"),
            q.fixtures,
            outReport,
        )
        assertFails(res, "mcp-deleted")
    }

    @Test
    fun `mutation - fixtures dir empty fails cert`(@TempDir tmp: Path) {
        if (skip("negative-mutation")) return
        val q = requireInputs() ?: run { println("SKIP: env vars missing"); return }
        val emptyFixtures = tmp.resolve("empty-fixtures")
        Files.createDirectories(emptyFixtures)
        val outReport = tmp.resolve("report.json")
        val res = runAllCert(q.tar, q.zip, q.mcp, emptyFixtures, outReport)
        assertFails(res, "fixtures-empty")
    }

    @Test
    fun `mutation - studioVersionPinned wrong value fails cert`(@TempDir tmp: Path) {
        if (skip("negative-mutation")) return
        val q = requireInputs() ?: run { println("SKIP: env vars missing"); return }
        // The cert reads STUDIO_VERSION from the env if
        // set, falling back to gradle.properties. We set
        // STUDIO_VERSION=9.9.9-rc.99 (a deliberately
        // wrong value) and expect the cert to either
        // (a) report a non-PASS integrity, or (b) fail
        // because the running studio does not match.
        val outReport = tmp.resolve("report.json")
        val cert = certBinary()
        val cmd = listOf(
            cert.toString(),
            "run-all-cert",
            q.tar.toString(),
            q.zip.toString(),
            q.mcp.toString(),
            q.fixtures.toString(),
            outReport.toString(),
        )
        val proc = ProcessBuilder(cmd)
            .redirectErrorStream(true)
            .apply {
                environment()["STUDIO_VERSION"] = "9.9.9-rc.99"
            }
            .start()
        proc.waitFor()
        val report = if (Files.isRegularFile(outReport)) Files.readString(outReport) else ""
        assertTrue(
            report.contains("\"verdict\": \"FAIL\"") ||
                report.contains("\"verdict\":\"FAIL\""),
            "STUDIO_VERSION=9.9.9-rc.99 must produce verdict=FAIL; got: ${report.take(400)}",
        )
    }
}
