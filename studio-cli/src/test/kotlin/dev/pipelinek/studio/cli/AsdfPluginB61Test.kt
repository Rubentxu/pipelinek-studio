package dev.pipelinek.studio.cli

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * B6.1 (2026-10-10): the asdf-vm plugin
 * contract tests.
 *
 * The S5 mandate says the studio ships an
 * "instalador local". B6.0 delivered a
 * single-binary tar via jlink; B6.1 adds
 * the asdf-vm plugin so operators get
 *
 *   asdf install pipelinek-studio latest
 *
 * The asdf-vm standard contract requires
 * four scripts under `bin/`:
 *  - list-all   (list installable versions)
 *  - download   (download a specific version)
 *  - install    (extract into $ASDF_INSTALL_PATH)
 *  - list-bin   (list the binaries the plugin exposes)
 *
 * The tests pin:
 *  1. Each script has a bash shebang and is
 *     executable.
 *  2. `list-all` queries the GitHub Releases
 *     API and filters to `v*` tags.
 *  3. `list-bin` returns `studio` (the single
 *     binary the plugin exposes).
 *  4. `download` reads ASDF_INSTALL_VERSION
 *     and the override env var
 *     PIPELINEK_STUDIO_DOWNLOAD_URL.
 *  5. `install` reads ASDF_INSTALL_VERSION
 *     and ASDF_INSTALL_PATH, extracts the
 *     tar with --strip-components=1, and
 *     chmod +x's the launcher.
 *  6. End-to-end: a fake "release" tarball
 *     (the B6.0 standalone tar) is installed
 *     into a sandbox, the launcher is
 *     executable, and `studio --version`
 *     works.
 */
class AsdfPluginB61Test {

    private fun pluginDir(): Path {
        // The plugin lives at the project
        // root under asdf-pipelinek-studio/.
        // The test executor's cwd is
        // studio-cli/; we walk up.
        var cwd: Path? = Path.of("").toAbsolutePath()
        repeat(5) {
            val candidate = cwd!!.resolve("asdf-pipelinek-studio")
            if (Files.isDirectory(candidate)) return candidate
            cwd = cwd.parent
        }
        throw AssertionError("asdf-pipelinek-studio/ not found from cwd=${Path.of("").toAbsolutePath()}")
    }

    @Test
    fun `B6_1 plugin ships the four asdf-vm contract scripts`() {
        val dir = pluginDir()
        val required = listOf("list-all", "list-bin", "download", "install")
        for (name in required) {
            val script = dir.resolve("bin/$name").toFile()
            assertTrue(
                script.isFile,
                "asdf-vm plugin must ship bin/$name, missing at $script",
            )
            assertTrue(
                script.canExecute(),
                "bin/$name must be executable, got: $script",
            )
            val firstLine = script.readLines().firstOrNull() ?: ""
            assertTrue(
                firstLine.startsWith("#!/usr/bin/env bash"),
                "bin/$name must start with a bash shebang, got: $firstLine",
            )
        }
    }

    @Test
    fun `B6_1 list-bin returns the binary the plugin exposes (studio)`() {
        val out = runScript("list-bin", env = emptyMap())
        assertEquals("studio", out.trim(), "list-bin must return 'studio' on stdout")
    }

    @Test
    fun `B6_1 list-all queries the GitHub Releases API and filters v-prefixed tags`() {
        val src = pluginDir().resolve("bin/list-all").toFile().readText()
        // The script must hit the GitHub API
        // AND filter to v* tags (so test
        // snapshots don't surface RCs or
        // non-version tags).
        assertTrue(
            "api.github.com" in src && "/releases" in src,
            "list-all must query the GitHub Releases API, got:\n$src",
        )
        assertTrue(
            "tag_name" in src,
            "list-all must parse tag_name from the API response, got:\n$src",
        )
        assertTrue(
            src.contains("v0") || src.contains("v[0-9]") || src.contains("v0-9"),
            "list-all must filter to v-prefixed tags, got:\n$src",
        )
    }

    @Test
    fun `B6_1 download reads ASDF_INSTALL_VERSION and the override env var`() {
        val src = pluginDir().resolve("bin/download").toFile().readText()
        assertTrue(
            "ASDF_INSTALL_VERSION" in src,
            "download must read ASDF_INSTALL_VERSION, got:\n$src",
        )
        assertTrue(
            "PIPELINEK_STUDIO_DOWNLOAD_URL" in src,
            "download must support the override env var, got:\n$src",
        )
        assertTrue(
            "curl" in src,
            "download must use curl, got:\n$src",
        )
    }

    @Test
    fun `B6_1 install reads ASDF_INSTALL_PATH and ASDF_INSTALL_VERSION, strips one component, chmods the launcher`() {
        val src = pluginDir().resolve("bin/install").toFile().readText()
        assertTrue(
            "ASDF_INSTALL_PATH" in src,
            "install must read ASDF_INSTALL_PATH, got:\n$src",
        )
        assertTrue(
            "ASDF_INSTALL_VERSION" in src,
            "install must read ASDF_INSTALL_VERSION, got:\n$src",
        )
        assertTrue(
            "strip-components" in src,
            "install must extract with --strip-components=1 (the standalone tar's root prefix), got:\n$src",
        )
        assertTrue(
            "chmod" in src && "bin/studio" in src,
            "install must chmod +x the launcher, got:\n$src",
        )
    }

    @Test
    fun `B6_1 end-to-end — install the B6_0 standalone tar into a sandbox and run --version`(@TempDir tmp: Path) {
        // The integration test: take a real
        // B6.0 standalone tarball, point the
        // download script at it via the
        // override, run install, and assert
        // the launcher is executable + prints
        // a version.
        val tarball = findStandaloneTarball()
        if (tarball == null) {
            // The standalone dist may not be
            // built in some test contexts. We
            // skip the e2e step but keep the
            // contract tests (above) running.
            return
        }
        val sandbox = Files.createTempDirectory(tmp, "asdf-sandbox-")
        val download = pluginDir().resolve("bin/download").toString()
        val install = pluginDir().resolve("bin/install").toString()

        // download: with ASDF_INSTALL_VERSION
        // set, the script prints the tarball
        // path on stdout.
        val dl = ProcessBuilder(download)
        dl.environment()["ASDF_INSTALL_VERSION"] = "1.0.0-rc.14"
        // Use the file:// scheme so curl can
        // fetch a local path. The override
        // env var is the asdf contract.
        dl.environment()["PIPELINEK_STUDIO_DOWNLOAD_URL"] = "file://${tarball.toString()}"
        dl.environment()["ASDF_DOWNLOAD_PATH"] = sandbox.toString()
        val dlProc = dl.start()
        val downloadedPath = dlProc.inputStream.bufferedReader().readText().trim()
        dlProc.waitFor()
        assertEquals(0, dlProc.exitValue(), "download must exit 0, got stderr:\n${dlProc.errorStream.bufferedReader().readText()}")
        assertTrue(
            Files.isRegularFile(Path.of(downloadedPath)),
            "download must produce a file at $downloadedPath",
        )
        // The download script copies the
        // tarball to the cache dir under the
        // standard name. The file size must
        // match the source.
        assertEquals(
            Files.size(tarball),
            Files.size(Path.of(downloadedPath)),
            "downloaded file size must match the source",
        )

        // install: extract the tar into the
        // sandbox, strip one component, chmod
        // the launcher.
        val sandboxInstall = Files.createTempDirectory(tmp, "asdf-install-")
        val inProc = ProcessBuilder(install)
        inProc.environment()["ASDF_INSTALL_VERSION"] = "1.0.0-rc.14"
        inProc.environment()["ASDF_INSTALL_PATH"] = sandboxInstall.toString()
        inProc.environment()["ASDF_DOWNLOAD_PATH"] = sandbox.toString()
        inProc.environment()["HOME"] = System.getProperty("user.home")
        inProc.environment()["PATH"] = "/usr/bin:/bin"
        val inStart = inProc.start()
        inStart.waitFor()
        assertEquals(0, inStart.exitValue(), "install must exit 0, got stderr:\n${inStart.errorStream.bufferedReader().readText()}")
        // The launcher must be present and
        // executable.
        val launcher = sandboxInstall.resolve("bin/studio").toFile()
        assertTrue(launcher.isFile, "install must produce bin/studio at $launcher")
        assertTrue(launcher.canExecute(), "install must chmod +x the launcher")
        // The launcher must run with no
        // JAVA_HOME + no java on PATH.
        val run = ProcessBuilder(launcher.absolutePath, "--version")
        run.environment()["HOME"] = System.getProperty("user.home")
        run.environment()["PATH"] = "/usr/bin:/bin"
        run.environment().remove("JAVA_HOME")
        val runProc = run.start()
        val out = runProc.inputStream.bufferedReader().readText().trim()
        runProc.waitFor()
        assertTrue(
            out.startsWith("studio "),
            "asdf-installed studio --version must start with 'studio ', got: '$out'",
        )
    }

    @Test
    fun `B6_1 README documents the asdf install + mise compatibility + the move-out note`() {
        val readme = pluginDir().resolve("README.md").toFile().readText()
        assertTrue(
            "asdf install pipelinek-studio" in readme,
            "README must document the asdf install command",
        )
        assertTrue(
            "mise" in readme.lowercase() || "mise.jdx.dev" in readme,
            "README must mention mise compatibility (mise is asdf-compatible since 2024)",
        )
        assertTrue(
            "PIPELINEK_STUDIO_REPO" in readme && "PIPELINEK_STUDIO_DOWNLOAD_URL" in readme,
            "README must document the override env vars",
        )
        assertTrue(
            "move-out" in readme.lowercase() || "separate repo" in readme.lowercase(),
            "README must document the move-out to a separate repo (asdf-vm convention)",
        )
    }

    private fun runScript(name: String, env: Map<String, String>): String {
        val pb = ProcessBuilder(pluginDir().resolve("bin/$name").toString())
        for ((k, v) in env) pb.environment()[k] = v
        // Force a clean PATH so we exercise
        // the script without inherited env
        // noise (the asdf env vars are added
        // explicitly).
        pb.environment()["HOME"] = System.getProperty("user.home")
        pb.environment()["PATH"] = "/usr/bin:/bin"
        val proc = pb.start()
        val out = proc.inputStream.bufferedReader().readText()
        proc.waitFor()
        return out
    }

    private fun findStandaloneTarball(): Path? {
        var cwd = Path.of("").toAbsolutePath()
        repeat(5) {
            val candidate = cwd.resolve("studio-cli/build/standalone")
            if (Files.isDirectory(candidate)) {
                return Files.list(candidate).use { stream ->
                    stream.filter { it.fileName.toString().endsWith(".tar") }
                        .findFirst().orElse(null)
                }
            }
            cwd = cwd.parent ?: return null
        }
        return null
    }
}
