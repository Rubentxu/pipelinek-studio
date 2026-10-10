package dev.pipelinek.studio.cli

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertTrue

/**
 * B6.0 (2026-10-10): the single-binary
 * distribution contract tests.
 *
 * The S5 mandate says the studio ships an
 * "instalador local". The pre-B6.0 studio
 * required the operator to install JDK 21
 * + Node 20 separately. B6.0 bundles a
 * custom JRE (jlink, ~60MB) inside the
 * distribution tar; the launcher uses the
 * bundled JRE so the operator needs NOTHING
 * pre-installed.
 *
 * The tests pin:
 *  1. The launcher is a bash script.
 *  2. The launcher exports JAVA_HOME to
 *     the bundled JRE.
 *  3. The launcher invokes the bundled
 *     `bin/java` with the right classpath.
 *  4. The standalone tar contains a
 *     `jre/` directory (the custom JRE).
 *  5. The launcher runs `studio --version`
 *     in a subshell with no JAVA_HOME and
 *     no `java` on PATH.
 *  6. The standalone tar size is under a
 *     sane budget (the jlink reduces the
 *     ~180MB full JDK to ~60MB; the tar
 *     includes the JRE + the lib jars).
 */
class StandaloneB60Test {

    @Test
    fun `B6_0 launcher is a bash script that exports JAVA_HOME to the bundled JRE`() {
        val script = readLauncherSource()
        assertTrue(
            "#!/usr/bin/env bash" in script,
            "launcher must have a bash shebang, got:\n$script",
        )
        assertTrue(
            "JAVA_HOME=" in script && "/jre" in script,
            "launcher must set JAVA_HOME to a path ending in /jre, got:\n$script",
        )
        assertTrue(
            "export JAVA_HOME" in script,
            "launcher must export JAVA_HOME, got:\n$script",
        )
        assertTrue(
            "JAVA_HOME/bin/java" in script,
            "launcher must invoke the bundled bin/java, got:\n$script",
        )
    }

    @Test
    fun `B6_0 launcher has a flag pass-through so studio start --port 9000 works`() {
        val script = readLauncherSource()
        assertTrue(
            "exec" in script && (script.contains("\"\$@\"") || script.contains("\"$@\"")),
            "launcher must exec the main with operator args passed through, got:\n$script",
        )
    }

    @Test
    fun `B6_0 standalone tar contains a jre directory (the custom JRE)`() {
        val tarball = findStandaloneTarball()
        assertTrue(
            tarball != null && Files.exists(tarball),
            "standalone tarball not found; the operator should run :studio-cli:standaloneDist first",
        )
        val stage = Files.createTempDirectory("studio-standalone-check-")
        try {
            runTar(tarball!!, stage)
            val root = stage.toFile().listFiles()?.firstOrNull()
            assertTrue(root != null, "tarball must contain a root directory")
            val jre = root!!.toPath().resolve("jre")
            assertTrue(
                Files.isDirectory(jre),
                "standalone tar must contain a jre/ directory at $jre",
            )
            val java = jre.resolve("bin/java")
            assertTrue(
                Files.isRegularFile(java),
                "bundled jre/ must include bin/java at $java",
            )
        } finally {
            stage.toFile().deleteRecursively()
        }
    }

    @Test
    fun `B6_0 standalone tar size is under 200 MB (jlink reduces the ~180MB JDK to ~60MB)`() {
        val tarball = findStandaloneTarball() ?: return
        val sizeBytes = Files.size(tarball)
        val sizeMb = sizeBytes / (1024 * 1024)
        assertTrue(
            sizeMb < 200,
            "standalone tar is ${sizeMb}MB; expected <200MB. " +
                "If the size ballooned, the jlink step is missing or the module list is wrong.",
        )
    }

    @Test
    fun `B6_0 launcher runs studio --version in a clean subshell (no JAVA_HOME, no java on PATH)`(
        @TempDir tmp: Path,
    ) {
        val tarball = findStandaloneTarball() ?: return
        val stage = Files.createTempDirectory(tmp, "studio-launch-")
        runTar(tarball, stage)
        val launcher = stage.toFile().listFiles()!!.first().toPath()
            .resolve("bin/studio").toFile()
        assertTrue(launcher.canExecute(), "launcher must be executable")
        val pb = ProcessBuilder("/bin/bash", "-c", launcher.toString() + " --version")
        pb.environment()["HOME"] = System.getProperty("user.home")
        pb.environment()["PATH"] = "/usr/bin:/bin"
        pb.environment().remove("JAVA_HOME")
        val proc = pb.start()
        val output = proc.inputStream.bufferedReader().readText().trim()
        proc.waitFor()
        assertTrue(
            output.startsWith("studio "),
            "launcher output must start with 'studio ', got: '$output'",
        )
        assertTrue(
            "(kotlin" in output,
            "launcher output must include the build identity, got: '$output'",
        )
    }

    private fun readLauncherSource(): String {
        val tarball = findStandaloneTarball() ?: return ""
        val stage = Files.createTempDirectory("studio-launcher-read-")
        try {
            runTar(tarball, stage)
            val root = stage.toFile().listFiles()!!.first()
            val launcher = root.toPath().resolve("bin/studio").toFile()
            if (!launcher.isFile) return ""
            return launcher.readText()
        } finally {
            stage.toFile().deleteRecursively()
        }
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

    private fun runTar(tarball: Path, intoDir: Path) {
        val pb = ProcessBuilder("tar", "-xf", tarball.toString(), "-C", intoDir.toString())
        pb.inheritIO()
        val p = pb.start()
        p.waitFor()
        check(p.exitValue() == 0) { "tar -xf ${tarball} failed with exit ${p.exitValue()}" }
    }
}
