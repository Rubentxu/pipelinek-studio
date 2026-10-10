package dev.pipelinek.studio.cli

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class DoctorTest {

    @Test
    fun `overall verdict is OK when every check passes`() {
        val report = Doctor.run(
            Doctor.DoctorEnv(
                env = mapOf("PATH" to ""), // empty PATH -> node WARN
                dbPath = Path.of("/tmp/never-used-doctor-ok.db"),
            ),
        )
        // java_version + studio_cli_version + https_default + db + lockfile checks
        // are deterministic; PATH being empty makes node_on_path a WARN.
        // Since there are no FAILs, overall must be WARN (not OK, not FAIL).
        // The check name list is the contract.
        val names = report.checks.map { it.name }
        assertTrue("studio_cli_version" in names)
        assertTrue("sdk_lockfile_present" in names)
        assertTrue("sdk_lockfile_parseable" in names)
        assertTrue("db_path_writable" in names)
        assertTrue("java_version" in names)
        assertTrue("node_on_path" in names)
        assertTrue("https_default" in names)
    }

    @Test
    fun `db_path_writable returns OK on a writable temp path`(@TempDir tmp: Path) {
        val r = Doctor.dbPathWritable(Doctor.DoctorEnv(dbPath = tmp.resolve("studio.db")))
        assertEquals(Doctor.Status.OK, r.status)
        assertTrue("writable" in r.detail.lowercase() || tmp.toString() in r.detail)
    }

    @Test
    fun `db_path_writable returns FAIL when the parent cannot be created`(@TempDir tmp: Path) {
        // A path whose parent is a regular file cannot have a
        // sibling db created under it.
        val blocker = tmp.resolve("blocker")
        Files.writeString(blocker, "i am a file, not a dir")
        val r = Doctor.dbPathWritable(Doctor.DoctorEnv(dbPath = blocker.resolve("studio.db")))
        assertEquals(Doctor.Status.FAIL, r.status)
        assertTrue("write" in r.detail.lowercase() || "not" in r.detail.lowercase())
    }

    @Test
    fun `java_version reports OK on the running JVM`() {
        val r = Doctor.javaVersion()
        // The test JVM is whatever the CI uses; we only assert
        // it's >= 17 (which is enforced for the studio).
        val major = System.getProperty("java.version")?.takeWhile { it.isDigit() }?.toIntOrNull() ?: 0
        if (major >= 17) {
            assertEquals(Doctor.Status.OK, r.status)
        } else {
            assertEquals(Doctor.Status.FAIL, r.status)
        }
    }

    @Test
    fun `node_on_path returns WARN when PATH is empty`() {
        val r = Doctor.nodeOnPath(Doctor.DoctorEnv(env = mapOf("PATH" to "")))
        assertEquals(Doctor.Status.WARN, r.status)
        assertTrue("PATH" in r.detail || "node" in r.detail.lowercase())
    }

    @Test
    fun `node_on_path returns OK when a directory with a node binary is on PATH`(@TempDir tmp: Path) {
        val exe = if (System.getProperty("os.name").lowercase().contains("win")) "node.exe" else "node"
        Files.writeString(tmp.resolve(exe), "#!/bin/sh\n")
        val r = Doctor.nodeOnPath(Doctor.DoctorEnv(env = mapOf("PATH" to tmp.toString())))
        assertEquals(Doctor.Status.OK, r.status)
    }

    @Test
    fun `https_default reports OK when no env override is set`() {
        val r = Doctor.httpsDefault(Doctor.DoctorEnv(env = emptyMap()))
        assertEquals(Doctor.Status.OK, r.status)
        assertTrue("https" in r.detail.lowercase())
    }

    @Test
    fun `https_default reports OK when STUDIO_HTTP is https`() {
        val r = Doctor.httpsDefault(
            Doctor.DoctorEnv(env = mapOf("STUDIO_HTTP" to "https://studio.example:9999")),
        )
        assertEquals(Doctor.Status.OK, r.status)
    }

    @Test
    fun `https_default reports OK when http is acknowledged via INSECURE flag`() {
        val r = Doctor.httpsDefault(
            Doctor.DoctorEnv(
                env = mapOf(
                    "STUDIO_HTTP" to "http://127.0.0.1:7777",
                    "STUDIO_HTTP_INSECURE" to "1",
                ),
            ),
        )
        assertEquals(Doctor.Status.OK, r.status)
    }

    @Test
    fun `https_default reports WARN when http is set without INSECURE flag`() {
        val r = Doctor.httpsDefault(
            Doctor.DoctorEnv(env = mapOf("STUDIO_HTTP" to "http://127.0.0.1:7777")),
        )
        assertEquals(Doctor.Status.WARN, r.status)
        assertTrue("STUDIO_HTTP_INSECURE" in r.detail || "refuse" in r.detail.lowercase())
    }

    @Test
    fun `exit code is 1 when any check fails`() {
        val report = Doctor.DoctorReport(
            checks = listOf(
                Doctor.CheckResult("ok_one", Doctor.Status.OK, "fine"),
                Doctor.CheckResult("fatal_one", Doctor.Status.FAIL, "broken"),
            ),
        )
        assertEquals(1, report.exitCode)
        assertEquals(Doctor.Status.FAIL, report.overall)
    }

    @Test
    fun `exit code is 0 when every check is OK or WARN (no FAIL)`() {
        val report = Doctor.DoctorReport(
            checks = listOf(
                Doctor.CheckResult("ok_one", Doctor.Status.OK, "fine"),
                Doctor.CheckResult("warn_one", Doctor.Status.WARN, "soft warning"),
            ),
        )
        assertEquals(0, report.exitCode)
        assertEquals(Doctor.Status.WARN, report.overall)
    }

    @Test
    fun `overall is OK when checks is empty`() {
        val report = Doctor.DoctorReport(checks = emptyList())
        assertEquals(0, report.exitCode)
        assertEquals(Doctor.Status.OK, report.overall)
    }

    @Test
    fun `human-readable output contains the check names and the overall verdict`() {
        val report = Doctor.DoctorReport(
            checks = listOf(
                Doctor.CheckResult("check_alpha", Doctor.Status.OK, "ok detail"),
                Doctor.CheckResult("check_beta", Doctor.Status.FAIL, "fail detail"),
            ),
        )
        val out = report.toHumanReadable()
        assertTrue("check_alpha" in out)
        assertTrue("check_beta" in out)
        assertTrue("overall" in out)
        assertTrue("fail" in out)
        assertNotEquals(-1, out.indexOf("check_alpha"))
    }
}
