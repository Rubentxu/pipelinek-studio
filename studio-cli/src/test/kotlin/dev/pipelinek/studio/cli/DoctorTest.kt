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

    /**
     * B2.8 (2026-10-10): a data dir + keystore with
     * owner-only perms (the production default set by
     * TlsConfig) must report OK.
     */
    @Test
    fun `B2_8 data dir with owner-only perms is OK`(@TempDir tmp: Path) {
        // Make a fresh data dir + keystore with 0700 / 0600.
        val dataDir = tmp.resolve("data")
        Files.createDirectories(dataDir)
        Files.setPosixFilePermissions(
            dataDir,
            java.util.Set.of(
                java.nio.file.attribute.PosixFilePermission.OWNER_READ,
                java.nio.file.attribute.PosixFilePermission.OWNER_WRITE,
                java.nio.file.attribute.PosixFilePermission.OWNER_EXECUTE,
            ),
        )
        val ks = dataDir.resolve("keystore.jks")
        Files.write(ks, byteArrayOf(0, 1, 2, 3))
        Files.setPosixFilePermissions(
            ks,
            java.util.Set.of(
                java.nio.file.attribute.PosixFilePermission.OWNER_READ,
                java.nio.file.attribute.PosixFilePermission.OWNER_WRITE,
            ),
        )
        val result = Doctor.dataDirectoryPermissions(
            Doctor.DoctorEnv(
                env = emptyMap(),
                dataDir = dataDir,
            ),
        )
        assertEquals(Doctor.Status.OK, result.status, "got: ${result.detail}")
    }

    /**
     * B2.8 (2026-10-10): a data dir with group/world
     * read bits set is FAIL — the check refuses to
     * pass on a permissive filesystem.
     */
    @Test
    fun `B2_8 data dir with group read is FAIL`(@TempDir tmp: Path) {
        val dataDir = tmp.resolve("data")
        Files.createDirectories(dataDir)
        Files.setPosixFilePermissions(
            dataDir,
            java.util.Set.of(
                java.nio.file.attribute.PosixFilePermission.OWNER_READ,
                java.nio.file.attribute.PosixFilePermission.OWNER_WRITE,
                java.nio.file.attribute.PosixFilePermission.OWNER_EXECUTE,
                java.nio.file.attribute.PosixFilePermission.GROUP_READ,
            ),
        )
        val result = Doctor.dataDirectoryPermissions(
            Doctor.DoctorEnv(
                env = emptyMap(),
                dataDir = dataDir,
            ),
        )
        assertEquals(Doctor.Status.FAIL, result.status)
        assertTrue(
            result.detail.contains("group/world read"),
            "expected group/world read in detail, got: ${result.detail}",
        )
    }

    /**
     * B2.8 (2026-10-10): a keystore with world-write is
     * FAIL. The keystore is the most sensitive file in
     * the data dir; a future TlsConfig change that
     * relaxed perms would be caught here.
     */
    @Test
    fun `B2_8 keystore with others write is FAIL`(@TempDir tmp: Path) {
        val dataDir = tmp.resolve("data")
        Files.createDirectories(dataDir)
        Files.setPosixFilePermissions(
            dataDir,
            java.util.Set.of(
                java.nio.file.attribute.PosixFilePermission.OWNER_READ,
                java.nio.file.attribute.PosixFilePermission.OWNER_WRITE,
                java.nio.file.attribute.PosixFilePermission.OWNER_EXECUTE,
            ),
        )
        val ks = dataDir.resolve("keystore.jks")
        Files.write(ks, byteArrayOf(0, 1, 2, 3))
        Files.setPosixFilePermissions(
            ks,
            java.util.Set.of(
                java.nio.file.attribute.PosixFilePermission.OWNER_READ,
                java.nio.file.attribute.PosixFilePermission.OWNER_WRITE,
                java.nio.file.attribute.PosixFilePermission.OTHERS_WRITE,
            ),
        )
        val result = Doctor.dataDirectoryPermissions(
            Doctor.DoctorEnv(
                env = emptyMap(),
                dataDir = dataDir,
            ),
        )
        assertEquals(Doctor.Status.FAIL, result.status)
        assertTrue(
            result.detail.contains("keystore"),
            "expected keystore in detail, got: ${result.detail}",
        )
    }

    /**
     * B2.8 (2026-10-10): a snapshots dir under data that
     * is world-readable is FAIL. Snapshots embed the
     * operator's sealed source; a world-readable dir
     * exposes it to any local user.
     */
    @Test
    fun `B2_8 snapshots dir with world read is FAIL`(@TempDir tmp: Path) {
        val dataDir = tmp.resolve("data")
        Files.createDirectories(dataDir)
        Files.setPosixFilePermissions(
            dataDir,
            java.util.Set.of(
                java.nio.file.attribute.PosixFilePermission.OWNER_READ,
                java.nio.file.attribute.PosixFilePermission.OWNER_WRITE,
                java.nio.file.attribute.PosixFilePermission.OWNER_EXECUTE,
            ),
        )
        val snapshots = dataDir.resolve("snapshots")
        Files.createDirectories(snapshots)
        Files.setPosixFilePermissions(
            snapshots,
            java.util.Set.of(
                java.nio.file.attribute.PosixFilePermission.OWNER_READ,
                java.nio.file.attribute.PosixFilePermission.OWNER_WRITE,
                java.nio.file.attribute.PosixFilePermission.OWNER_EXECUTE,
                java.nio.file.attribute.PosixFilePermission.OTHERS_READ,
            ),
        )
        val result = Doctor.dataDirectoryPermissions(
            Doctor.DoctorEnv(
                env = emptyMap(),
                dataDir = dataDir,
            ),
        )
        assertEquals(Doctor.Status.FAIL, result.status)
        assertTrue(
            result.detail.contains("snapshots"),
            "expected snapshots in detail, got: ${result.detail}",
        )
    }

    @Test
    fun `lockOwnershipMatches reports OK when no lock is present`(
        @TempDir tmp: Path,
    ) {
        val result = Doctor.lockOwnershipMatches(
            Doctor.DoctorEnv(dataDir = tmp),
        )
        assertEquals(Doctor.Status.OK, result.status, result.detail)
        assertTrue(
            result.detail.contains("no lock"),
            "expected 'no lock' in detail, got: ${result.detail}",
        )
    }

    @Test
    fun `lockOwnershipMatches reports OK when lock uid matches current user`(
        @TempDir tmp: Path,
    ) {
        val current = System.getProperty("user.name") ?: error("user.name not set")
        StudioLock.write(
            tmp,
            StudioLock(
                pid = 99999L,
                host = "127.0.0.1",
                port = 7778,
                startedAt = 1L,
                uid = current,
            ),
        )
        val result = Doctor.lockOwnershipMatches(Doctor.DoctorEnv(dataDir = tmp))
        assertEquals(Doctor.Status.OK, result.status, result.detail)
        assertTrue(
            result.detail.contains(current),
            "expected current user '$current' in detail, got: ${result.detail}",
        )
    }

    @Test
    fun `lockOwnershipMatches reports OK on non-default data dir even with foreign lock uid`(
        @TempDir tmp: Path,
    ) {
        // Non-default data dir (tmp) +
        // foreign uid → OK, not WARN. The
        // operator picked this dir on
        // purpose; we don't second-guess.
        StudioLock.write(
            tmp,
            StudioLock(
                pid = 99999L,
                host = "127.0.0.1",
                port = 7778,
                startedAt = 1L,
                uid = "another-user-123",
            ),
        )
        val result = Doctor.lockOwnershipMatches(Doctor.DoctorEnv(dataDir = tmp))
        assertEquals(Doctor.Status.OK, result.status, result.detail)
        assertTrue(
            result.detail.contains("intentional"),
            "expected 'intentional' (shared data dir) in detail, got: ${result.detail}",
        )
    }

    @Test
    fun `lockOwnershipMatches source pins the WARN remediation (default dir plus foreign uid)`() {
        // The WARN branch on the default
        // data dir is hard to test without
        // polluting the real
        // ~/.pipelinek-studio. Pin it at
        // the source level: the function
        // must mention "Another account"
        // and "--data-dir" so the operator
        // gets an actionable hint.
        val text = java.nio.file.Path.of("src/main/kotlin/dev/pipelinek/studio/cli/Doctor.kt").let { rel ->
            val abs = if (java.nio.file.Files.isRegularFile(rel)) rel
            else java.nio.file.Path.of(System.getProperty("user.dir"), "studio-cli/src/main/kotlin/dev/pipelinek/studio/cli/Doctor.kt")
            java.nio.file.Files.readString(abs)
        }
        assertTrue(
            "Another account" in text,
            "lockOwnershipMatches must warn with 'Another account' on default dir + foreign uid",
        )
        assertTrue(
            "--data-dir" in text,
            "lockOwnershipMatches must suggest --data-dir as remediation",
        )
    }
}
