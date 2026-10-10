package dev.pipelinek.studio.cli

import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * B6.2 (2026-10-10): the Homebrew tap
 * contract tests.
 *
 * The S5 mandate says the studio ships an
 * "instalador local". B6.0 delivered a
 * single-binary tar via jlink. B6.2 adds
 * the Homebrew tap so operators get
 *
 *   brew tap Rubentxu/pipelinek-studio
 *   brew install studio
 *   studio --version
 *
 * Homebrew's standard contract is a Ruby
 * formula at `Formula/<name>.rb`. The
 * formula must declare:
 *  - desc (description)
 *  - homepage
 *  - url (download URL)
 *  - sha256 (checksum)
 *  - version
 *  - install (Ruby method that installs
 *    files into Homebrew's prefix)
 *  - test (Ruby method that runs a smoke
 *    check)
 *
 * The tests pin:
 *  1. The formula file exists and has the
 *     right Ruby class structure.
 *  2. The formula downloads from the
 *     Rubentxu/pipelinek-studio GitHub
 *     releases URL (the B6.0 standalone tar).
 *  3. The sha256 is a 64-char hex string
 *     (the wire contract).
 *  4. The install method uses
 *     `libexec.install` (self-contained
 *     convention) and `bin.install_symlink`
 *     (PATH exposure).
 *  5. The test method runs `studio --version`
 *     and asserts the output starts with
 *     "studio".
 *  6. The README documents the brew install
 *     flow + the move-out note.
 */
class HomebrewTapB62Test {

    private fun tapDir(): Path {
        var cwd: Path? = Path.of("").toAbsolutePath()
        repeat(5) {
            val candidate = cwd!!.resolve("homebrew-pipelinek-studio")
            if (Files.isDirectory(candidate)) return candidate
            cwd = cwd.parent
        }
        throw AssertionError("homebrew-pipelinek-studio/ not found from cwd=${Path.of("").toAbsolutePath()}")
    }

    @Test
    fun `B6_2 formula file exists at the standard location`() {
        val formula = tapDir().resolve("Formula/studio.rb").toFile()
        assertTrue(
            formula.isFile,
            "Homebrew formula must live at Formula/studio.rb, missing at $formula",
        )
        val text = formula.readText()
        // The class declaration is the
        // first non-comment, non-blank line.
        assertTrue(
            text.contains("class Studio < Formula"),
            "formula must declare 'class Studio < Formula', got:\n$text",
        )
    }

    @Test
    fun `B6_2 formula has the required desc, homepage, url, sha256, version fields`() {
        val text = tapDir().resolve("Formula/studio.rb").toFile().readText()
        // desc — short description (used in
        // `brew info`).
        assertTrue(
            Regex("""desc\s+["']""").containsMatchIn(text),
            "formula must declare desc, got:\n$text",
        )
        // homepage — project URL.
        assertTrue(
            Regex("""homepage\s+["']https://github\.com/Rubentxu/pipelinek-studio["']""")
                .containsMatchIn(text),
            "formula must declare homepage pointing to the studio repo, got:\n$text",
        )
        // url — GitHub release tarball URL.
        assertTrue(
            Regex("""url\s+["']https://github\.com/Rubentxu/pipelinek-studio/releases/download/[^"']+\.tar["']""")
                .containsMatchIn(text),
            "formula must declare url pointing to a GitHub release tarball, got:\n$text",
        )
        // sha256 — 64 hex chars.
        val sha = Regex("""sha256\s+["']([0-9a-fA-F]{64})["']""").find(text)
        assertTrue(
            sha != null,
            "formula must declare a 64-hex-char sha256, got:\n$text",
        )
        // version.
        assertTrue(
            Regex("""version\s+["']""").containsMatchIn(text),
            "formula must declare version, got:\n$text",
        )
    }

    @Test
    fun `B6_2 formula's install method uses libexec + bin install_symlink (self-contained convention)`() {
        val text = tapDir().resolve("Formula/studio.rb").toFile().readText()
        // The install method is a Ruby
        // def block. We extract it for
        // focused assertions.
        val installBody = extractRubyMethod(text, "def install")
        assertTrue(
            installBody != null,
            "formula must define a `def install` method, got:\n$text",
        )
        assertTrue(
            "libexec.install" in (installBody ?: ""),
            "install must use libexec.install (self-contained tools convention), got:\n$installBody",
        )
        assertTrue(
            "bin.install_symlink" in (installBody ?: ""),
            "install must use bin.install_symlink to expose the launcher on PATH, got:\n$installBody",
        )
    }

    @Test
    fun `B6_2 formula's test block runs studio --version and asserts the output`() {
        val text = tapDir().resolve("Formula/studio.rb").toFile().readText()
        // Homebrew's test is a `test do ... end`
        // block, not a `def test` method.
        val testBlock = extractRubyBlock(text, "test do")
        assertTrue(
            testBlock != null,
            "formula must define a `test do ... end` block, got:\n$text",
        )
        // The test must run the launcher
        // with --version and assert the
        // output starts with 'studio'.
        assertTrue(
            "studio" in (testBlock ?: "") && "--version" in (testBlock ?: ""),
            "test must run 'studio --version', got:\n$testBlock",
        )
        assertTrue(
            "assert_match" in (testBlock ?: "") && "shell_output" in (testBlock ?: ""),
            "test must use Homebrew's assert_match + shell_output helpers, got:\n$testBlock",
        )
    }

    @Test
    fun `B6_2 formula's install method strips the standalone tar's root prefix`() {
        // The standalone tar's root is
        // `studio-cli-standalone-<version>/`.
        // The install must handle this
        // — Homebrew's `libexec.install Dir["*"]`
        // does it automatically because the
        // tar's first entry is the root dir
        // and Homebrew's libexec.install copies
        // every entry. The test pins that the
        // install uses libexec.install (not
        // bin.install or a manual extraction).
        val text = tapDir().resolve("Formula/studio.rb").toFile().readText()
        val installBody = extractRubyMethod(text, "def install") ?: ""
        assertTrue(
            "Dir[\"*\"]" in installBody || "Dir['*']" in installBody,
            "install must extract Dir[\"*\"] (handles the tar's root prefix automatically), got:\n$installBody",
        )
    }

    @Test
    fun `B6_2 README documents the brew install flow + the move-out note`() {
        val readme = tapDir().resolve("README.md").toFile().readText()
        assertTrue(
            "brew tap Rubentxu/pipelinek-studio" in readme,
            "README must document the brew tap command",
        )
        assertTrue(
            "brew install studio" in readme,
            "README must document the brew install command",
        )
        assertTrue(
            "move-out" in readme.lowercase() || "separate repo" in readme.lowercase(),
            "README must document the move-out to a separate repo (Homebrew convention)",
        )
        assertTrue(
            "shasum" in readme || "sha256sum" in readme,
            "README must explain how to compute the new sha256 for a release",
        )
    }

    @Test
    fun `B6_2 formula's url + sha256 + version are consistent (same version string everywhere)`() {
        val text = tapDir().resolve("Formula/studio.rb").toFile().readText()
        // Extract the version from the `version` line.
        val version = Regex("""version\s+["']([^"']+)["']""").find(text)?.groupValues?.get(1)
        assertTrue(
            version != null && version.isNotEmpty(),
            "formula must declare a non-empty version, got: $version",
        )
        // The url must include the same
        // version in both the release tag
        // (v<version>) and the tarball
        // filename (studio-cli-standalone-<version>.tar).
        val url = Regex("""url\s+["']([^"']+)["']""").find(text)?.groupValues?.get(1) ?: ""
        assertTrue(
            url.contains("v$version") && url.contains("studio-cli-standalone-$version.tar"),
            "url must include v<version> and the standalone tar name, got: $url vs version=$version",
        )
    }

    // Extract a Ruby method body by name. The
    // body is everything between `def <name>`
    // and the next `def` at the same indent
    // (or end of class).
    private fun extractRubyMethod(text: String, defLine: String): String? {
        val startIdx = text.indexOf(defLine)
        if (startIdx < 0) return null
        val fromStart = text.substring(startIdx)
        // Find the next "def " at column 0
        // (top-level method declaration).
        val lines = fromStart.lines()
        val out = StringBuilder()
        var started = false
        for (line in lines) {
            if (!started && line.trim().startsWith("def ")) {
                started = true
                out.appendLine(line)
                continue
            }
            if (started) {
                if (line.startsWith("  def ") || line.startsWith("def ")) {
                    break
                }
                out.appendLine(line)
            }
        }
        return out.toString()
    }

    // Extract a Ruby `block do ... end` body
    // by the opening line. Tracks the indent
    // level: the body ends when a line at
    // the same or lower indent closes the
    // `do` with `end`.
    private fun extractRubyBlock(text: String, openLine: String): String? {
        val startIdx = text.indexOf(openLine)
        if (startIdx < 0) return null
        val fromStart = text.substring(startIdx)
        val openIndent = fromStart.lineSequence().first().takeWhile { it == ' ' }.length
        val out = StringBuilder()
        var depth = 0
        for (line in fromStart.lineSequence()) {
            if (out.isEmpty()) {
                // First line: the open line.
                out.appendLine(line)
                if ("do" in line.trim() || "do |" in line || "do |" in line) depth++
                continue
            }
            val trimmed = line.trim()
            // A line at the open indent that
            // starts with `end` closes the
            // block.
            val lineIndent = line.takeWhile { it == ' ' }.length
            if (lineIndent == openIndent && trimmed == "end") {
                out.appendLine(line)
                return out.toString()
            }
            // Track do/end inside the block.
            // This is a rough heuristic; the
            // test is forgiving.
            if (trimmed.startsWith("do ") || trimmed == "do" || trimmed.endsWith(" do") || trimmed.endsWith(" do |")) {
                depth++
            }
            if (trimmed == "end" || trimmed.startsWith("end ") || trimmed.startsWith("end,")) {
                if (depth > 0) depth--
                else {
                    out.appendLine(line)
                    return out.toString()
                }
            }
            out.appendLine(line)
        }
        return out.toString()
    }
}
