package dev.pipelinek.studio.cli

import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * B6.3 (2026-10-10): the Scoop bucket
 * contract tests.
 *
 * The S5 mandate says the studio ships an
 * "instalador local". B6.0 delivered a
 * single-binary tar via jlink (bash
 * launcher for Linux/macOS). B6.3 adds a
 * Windows .bat shim + the Scoop bucket so
 * Windows users get
 *
 *   scoop bucket add Rubentxu
 *   scoop install studio
 *   studio --version
 *
 * The Scoop manifest's standard contract
 * is a JSON file at `bucket/<name>.json`.
 * The manifest must declare:
 *  - version
 *  - description
 *  - homepage
 *  - license
 *  - url (download URL)
 *  - hash (SHA-256)
 *  - bin (the binaries to expose on PATH)
 *  - extract_dir (the directory inside the
 *    tar that holds the files)
 *
 * The tests pin:
 *  1. The manifest file exists at the
 *     standard location.
 *  2. The manifest declares all the
 *     required fields.
 *  3. The hash is a 64-char hex string
 *     (SHA-256 wire contract).
 *  4. The url + version + extract_dir are
 *     consistent.
 *  5. The bin entries expose both the
 *     bash launcher (Linux/macOS) and the
 *     .bat shim (Windows).
 *  6. The B6.0 standalone tar now includes
 *     a bin/studio.bat shim (the Windows
 *     side of the cross-platform contract).
 *  7. The README documents the scoop
 *     bucket add + the move-out note.
 */
class ScoopBucketB63Test {

    private fun bucketDir(): Path {
        var cwd: Path? = Path.of("").toAbsolutePath()
        repeat(5) {
            val candidate = cwd!!.resolve("scoop-bucket")
            if (Files.isDirectory(candidate)) return candidate
            cwd = cwd.parent
        }
        throw AssertionError("scoop-bucket/ not found from cwd=${Path.of("").toAbsolutePath()}")
    }

    @Test
    fun `B6_3 manifest file exists at the standard bucket location`() {
        val manifest = bucketDir().resolve("bucket/studio.json").toFile()
        assertTrue(
            manifest.isFile,
            "Scoop manifest must live at bucket/studio.json, missing at $manifest",
        )
        assertTrue(
            manifest.length() > 0,
            "Scoop manifest must not be empty",
        )
    }

    @Test
    fun `B6_3 manifest declares all the required Scoop fields`() {
        val text = bucketDir().resolve("bucket/studio.json").toFile().readText()
        val required = listOf("version", "description", "homepage", "license", "url", "hash", "bin", "extract_dir")
        for (field in required) {
            assertTrue(
                "\"$field\"" in text,
                "Scoop manifest must include '$field', got:\n$text",
            )
        }
    }

    @Test
    fun `B6_3 hash is a 64-char hex string (SHA-256 wire contract)`() {
        val text = bucketDir().resolve("bucket/studio.json").toFile().readText()
        // The hash must be 64 lowercase hex
        // chars (Scoop rejects other formats).
        val regex = Regex("\"hash\"\\s*:\\s*\"([0-9a-f]{64})\"")
        val match = regex.find(text)
        assertNotNull(
            match,
            "Scoop hash must be a 64-char lowercase hex SHA-256, got:\n$text",
        )
    }

    @Test
    fun `B6_3 manifest's url, version, extract_dir, and bin are consistent`() {
        val text = bucketDir().resolve("bucket/studio.json").toFile().readText()
        val version = Regex("\"version\"\\s*:\\s*\"([^\"]+)\"").find(text)?.groupValues?.get(1)
        assertNotNull(version, "manifest must declare a version")
        // The url must include the same
        // version in both the release tag
        // (v<version>) and the tarball
        // filename (studio-cli-standalone-<version>.tar).
        val url = Regex("\"url\"\\s*:\\s*\"([^\"]+)\"").find(text)?.groupValues?.get(1) ?: ""
        assertTrue(
            url.contains("v$version") && url.contains("studio-cli-standalone-$version.tar"),
            "url must include v<version> and the standalone tar name, got: $url vs version=$version",
        )
        // The extract_dir must be the tar's
        // root prefix.
        val extractDir = Regex("\"extract_dir\"\\s*:\\s*\"([^\"]+)\"").find(text)?.groupValues?.get(1) ?: ""
        assertEquals(
            "studio-cli-standalone-$version",
            extractDir,
            "extract_dir must be the tar's root prefix",
        )
    }

    @Test
    fun `B6_3 manifest bin entries expose both bash launcher and Windows bat shim`() {
        val text = bucketDir().resolve("bucket/studio.json").toFile().readText()
        // Scoop's "bin" can be a list of
        // [src, dest] tuples. The manifest
        // exposes:
        //  - bin/studio (bash) on PATH as 'studio'
        //  - bin/studio.bat (Windows) on PATH as 'studio.bat'
        assertTrue(
            "bin/studio" in text,
            "bin must include bin/studio (bash launcher) for Linux/macOS, got:\n$text",
        )
        assertTrue(
            "bin/studio.bat" in text,
            "bin must include bin/studio.bat (Windows shim), got:\n$text",
        )
    }

    @Test
    fun `B6_3 standalone tar now includes the Windows bat shim (cross-platform contract)`() {
        // The B6.3 cross-platform contract
        // requires the standalone tar to
        // ship a Windows .bat shim. The
        // shim wraps the bundled jlink JRE
        // (jre\\bin\\java.exe) with the
        // lib\\* classpath.
        val tarball = findStandaloneTarball()
        if (tarball == null) return // skip when standalone is not built
        val stage = Files.createTempDirectory("studio-scoop-")
        try {
            runTar(tarball, stage)
            val root = stage.toFile().listFiles()!!.first()
            val bat = root.toPath().resolve("bin/studio.bat").toFile()
            assertTrue(bat.isFile, "standalone tar must include bin/studio.bat at $bat")
            val text = bat.readText()
            assertTrue(
                "@echo off" in text,
                "studio.bat must start with @echo off, got:\n$text",
            )
            assertTrue(
                "java.exe" in text,
                "studio.bat must invoke java.exe, got:\n$text",
            )
            assertTrue(
                "JAVA_HOME" in text && "jre" in text,
                "studio.bat must set JAVA_HOME to the bundled jre, got:\n$text",
            )
            assertTrue(
                "lib\\" in text || "lib/" in text,
                "studio.bat must include the lib classpath, got:\n$text",
            )
        } finally {
            stage.toFile().deleteRecursively()
        }
    }

    @Test
    fun `B6_3 README documents the scoop bucket add + the move-out note`() {
        val readme = bucketDir().resolve("README.md").toFile().readText()
        assertTrue(
            "scoop bucket add Rubentxu" in readme,
            "README must document the scoop bucket add command",
        )
        assertTrue(
            "scoop install studio" in readme,
            "README must document the scoop install command",
        )
        assertTrue(
            "move-out" in readme.lowercase() || "separate repo" in readme.lowercase(),
            "README must document the move-out to a separate repo (Scoop convention)",
        )
        assertTrue(
            "shasum" in readme || "Get-FileHash" in readme,
            "README must explain how to compute the new hash for a release",
        )
    }

    private fun findStandaloneTarball(): Path? {
        var cwd: Path? = Path.of("").toAbsolutePath()
        repeat(5) {
            val candidate = cwd!!.resolve("studio-cli/build/standalone")
            if (Files.isDirectory(candidate)) {
                return Files.list(candidate).use { stream ->
                    stream.filter { it.fileName.toString().endsWith(".tar") }
                        .findFirst().orElse(null)
                }
            }
            cwd = cwd.parent
        }
        return null
    }

    private fun runTar(tarball: Path, intoDir: Path) {
        val pb = ProcessBuilder("tar", "-xf", tarball.toString(), "-C", intoDir.toString())
        pb.inheritIO()
        val p = pb.start()
        p.waitFor()
        check(p.exitValue() == 0) { "tar -xf $tarball failed with exit ${p.exitValue()}" }
    }
}
