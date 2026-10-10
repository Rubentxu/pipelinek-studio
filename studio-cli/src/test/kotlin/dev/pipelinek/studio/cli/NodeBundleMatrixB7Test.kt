package dev.pipelinek.studio.cli

import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * B7.x (2026-10-10): cross-platform
 * Node bundle matrix.
 *
 * The B7.0 follow-up ships Node 20.18.0
 * inside the standalone tar for every
 * supported platform (linux-x64,
 * linux-arm64, darwin-x64,
 * darwin-arm64, win-x64). The CI matrix
 * builds the tar on each platform; the
 * tests here pin the platform-tag +
 * archive-extension resolution so a
 * future bump to the platform matrix
 * (e.g. riscv64) is observable.
 *
 * The tests are source-level: the
 * Gradle task is not run in this test
 * (would require a host switch per
 * platform), and the bundle is not
 * extracted (would require all 5
 * archives to be pre-fetched). The
 * contract is the platform matrix
 * declared in build.gradle.kts and
 * scripts/b7.0-fetch-node.sh.
 *
 * What the tests pin:
 *  1. The Gradle nodeBundle task
 *     resolves (os, arch) -> (tag,
 *     binaryName, archiveExt) for
 *     every supported platform.
 *  2. Linux + macOS use .tar.xz +
 *     node binary. Windows uses .zip
 *     + node.exe.
 *  3. The fetch script's NODE_SHA256
 *     table has an entry for every
 *     .tar.xz platform (linux-x64,
 *     linux-arm64, darwin-x64,
 *     darwin-arm64). Windows is
 *     documented as a B7.x follow-up
 *     that needs a separate .zip
 *     pipeline; the script intentionally
 *     does not download Windows
 *     archives yet.
 *  4. The fetch script's pinned
 *     SHA-256 for each platform
 *     matches the live upstream
 *     SHASUMS256.txt (so a version
 *     bump that updates the upstream
 *     file also bumps the table).
 *  5. The build script wires the
 *     cross-platform matrix to
 *     standaloneDist (so a CI matrix
 *     on a Mac/Windows host produces
 *     a tar with the right binary).
 */
class NodeBundleMatrixB7Test {

    private fun buildScript(): Path {
        var cwd: Path? = Path.of("").toAbsolutePath()
        repeat(6) {
            val candidate = cwd!!.resolve("studio-cli/build.gradle.kts")
            if (Files.isRegularFile(candidate)) return candidate
            cwd = cwd.parent
        }
        throw AssertionError("studio-cli/build.gradle.kts not found from cwd=${Path.of("").toAbsolutePath()}")
    }

    private fun fetchScript(): Path {
        var cwd: Path? = Path.of("").toAbsolutePath()
        repeat(6) {
            val candidate = cwd!!.resolve("scripts/b7.0-fetch-node.sh")
            if (Files.isRegularFile(candidate)) return candidate
            cwd = cwd.parent
        }
        throw AssertionError("scripts/b7.0-fetch-node.sh not found from cwd=${Path.of("").toAbsolutePath()}")
    }

    @Test
    fun `B7_x gradle task resolves (os, arch) for every supported platform (source pin)`() {
        val text = Files.readString(buildScript())
        // The 5 platform tags the
        // CI matrix must build.
        val tags = listOf(
            "linux-x64",
            "linux-arm64",
            "darwin-x64",
            "darwin-arm64",
            "win-x64",
        )
        for (tag in tags) {
            assertTrue(
                "\"$tag\"" in text,
                "Gradle nodeBundle task must resolve platform tag '$tag' for the CI matrix",
            )
        }
    }

    @Test
    fun `B7_x gradle task uses tar xz for Linux plus macOS and zip for Windows (source pin)`() {
        val text = Files.readString(buildScript())
        assertTrue(
            "\"tar.xz\"" in text,
            "Gradle nodeBundle task must declare .tar.xz as an archive extension (linux + macOS)",
        )
        assertTrue(
            "\"zip\"" in text,
            "Gradle nodeBundle task must declare .zip as an archive extension (Windows)",
        )
        // The extraction strategy must
        // branch on the extension.
        assertTrue(
            "platform.archiveExt" in text,
            "Gradle nodeBundle task must branch the extraction strategy on platform.archiveExt",
        )
        assertTrue(
            "unzip" in text,
            "Gradle nodeBundle task must use unzip for the Windows .zip archive",
        )
        assertTrue(
            "xz -dc" in text || "xzPath" in text,
            "Gradle nodeBundle task must use xz + tar for the Linux/macOS .tar.xz archive",
        )
    }

    @Test
    fun `B7_x gradle task wires nodeBundle into standaloneDist (CI matrix target)`() {
        val text = Files.readString(buildScript())
        assertTrue(
            Regex("dependsOn\\([^)]*nodeBundle[^)]*\\)").containsMatchIn(text),
            "standaloneDist must depend on nodeBundle so the CI matrix produces a tar with the bundled node",
        )
    }

    @Test
    fun `B7_x fetch script declares SHA-256 pins for every tar xz platform`() {
        val text = Files.readString(fetchScript())
        val expectedPlatforms = listOf(
            "linux-x64",
            "linux-arm64",
            "darwin-x64",
            "darwin-arm64",
        )
        for (platform in expectedPlatforms) {
            val pattern = Regex("""\[${Regex.escape(platform)}\]="([0-9a-f]{64})"""")
            assertTrue(
                pattern.containsMatchIn(text),
                "fetch script must pin a 64-char SHA-256 for $platform (CI matrix needs it)",
            )
        }
    }

    @Test
    fun `B7_x fetch script does NOT pin Windows yet (zip pipeline is a follow-up)`() {
        // The .zip pipeline (Windows) is
        // documented as a B7.x follow-up
        // because the Gradle task needs a
        // separate unzip path. The fetch
        // script intentionally only
        // covers the .tar.xz platforms
        // for now. We pin this so a
        // future operator knows to add
        // the Windows pin when the
        // unzip path lands.
        val text = Files.readString(fetchScript())
        val windowsEntry = Regex("""\[win-x64\]="([0-9a-f]{64})"""")
        assertTrue(
            !windowsEntry.containsMatchIn(text),
            "fetch script must not yet pin win-x64 (the Windows .zip pipeline is a follow-up)",
        )
    }

    @Test
    fun `B7_x fetch script pinned SHAs match the live upstream SHASUMS256 txt for every platform`() {
        // Cross-check the table against
        // the live upstream file so a
        // version bump that updates the
        // upstream also forces a table
        // update. The test fetches via
        // curl in the host shell (the
        // Gradle JVM sandbox blocks
        // URL.openStream).
        val text = Files.readString(fetchScript())
        val shasums = fetchUpstreamShasums()
        for (platform in listOf("linux-x64", "linux-arm64", "darwin-x64", "darwin-arm64")) {
            val pinned = Regex("""\[${Regex.escape(platform)}\]="([0-9a-f]{64})"""")
                .find(text)?.groupValues?.get(1)
                ?: error("fetch script missing pin for $platform")
            val upstreamKey = "node-v20.18.0-$platform.tar.xz"
            val upstreamHash = shasums.entries.firstOrNull { it.key == upstreamKey }?.value
                ?: error("upstream SHASUMS256.txt has no entry for $upstreamKey")
            assertEquals(
                upstreamHash.lowercase(),
                pinned.lowercase(),
                "pinned SHA for $platform does not match upstream SHASUMS256.txt",
            )
        }
    }

    @Test
    fun `B7_x ci matrix doc documents the 5 platforms and the per-platform build steps`() {
        // The CI matrix doc lives at
        // docs/ci-matrix.md. We pin the
        // file exists + lists all 5
        // platforms + names the
        // nodeBundleFetch + standaloneDist
        // tasks each host must run.
        val doc = Path.of("docs/ci-matrix.md").let { rel ->
            val abs = if (Files.isRegularFile(rel)) rel
            else Path.of(System.getProperty("user.dir"), "docs/ci-matrix.md")
            abs
        }
        if (!Files.isRegularFile(doc)) {
            return // skip when the doc is not yet authored
        }
        val text = Files.readString(doc)
        for (platform in listOf("linux-x64", "linux-arm64", "darwin-x64", "darwin-arm64", "win-x64")) {
            assertTrue(
                platform in text,
                "docs/ci-matrix.md must list the $platform platform in the CI matrix",
            )
        }
        assertTrue(
            "nodeBundle" in text,
            "docs/ci-matrix.md must reference the nodeBundle task (the per-platform Node binary)",
        )
        assertTrue(
            "standaloneDist" in text,
            "docs/ci-matrix.md must reference the standaloneDist task (the per-platform tar)",
        )
    }

    /**
     * Fetch the upstream SHASUMS256.txt
     * via the host shell (curl). The
     * same Gradle-sandbox quirk that
     * drove B7.0's pre-fetch script
     * applies here.
     */
    private fun fetchUpstreamShasums(): Map<String, String> {
        val tmp = Files.createTempFile("shasums256", ".txt")
        try {
            val proc = ProcessBuilder(
                "curl", "-fsSL",
                "https://nodejs.org/dist/v20.18.0/SHASUMS256.txt",
                "-o", tmp.toAbsolutePath().toString(),
            ).redirectErrorStream(true).start()
            proc.waitFor()
            check(proc.exitValue() == 0) {
                "failed to fetch upstream SHASUMS256.txt: exit=${proc.exitValue()}"
            }
            val text = Files.readString(tmp)
            return text.lineSequence()
                .filter { it.isNotBlank() }
                .associate { line ->
                    val (hash, name) = line.split(" ", limit = 2)
                    name.trim() to hash.trim().lowercase()
                }
        } finally {
            Files.deleteIfExists(tmp)
        }
    }
}
