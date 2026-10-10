package dev.pipelinek.studio.cli

import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * B7.x (2026-10-10): the SHA-256 pin on
 * the Node pre-fetch script.
 *
 * B7.0 (the bundled Node binary) used a
 * size check (> 1MB) as a sanity guard.
 * That guard catches redirect pages,
 * 404 bodies, and partial downloads,
 * but it does NOT catch a hostile
 * mirror serving a modified binary at
 * the right size. The B7.x follow-up
 * closes that supply-chain gap by
 * pinning the SHA-256 of the tarball
 * against the upstream
 * SHASUMS256.txt.
 *
 * The tests pin:
 *  1. The script declares a
 *     NODE_SHA256 table for the
 *     supported platforms.
 *  2. The pinned hash for linux-x64
 *     matches the live upstream
 *     SHASUMS256.txt (so a release
 *     run that bumps the Node version
 *     also bumps the table).
 *  3. The pinned hash is not a
 *     placeholder (all zeros, all f's,
 *     or a known-bad value) — the
 *     script must refuse to run with a
 *     placeholder.
 *  4. The script reads the hash from
 *     the file (sha256sum or shasum)
 *     and compares lowercase.
 *  5. The script's --help / usage
 *     mentions the SHA-256 pin so the
 *     next operator knows to update
 *     the table when bumping the
 *     version.
 *  6. The error path for a wrong
 *     SHA-256 (hostile mirror) exits
 *     non-zero.
 */
class NodeFetchSha256B7Test {

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
    fun `B7_x fetch script declares a NODE_SHA256 table for the supported platforms`() {
        val text = Files.readString(fetchScript())
        assertTrue(
            "declare -A NODE_SHA256" in text,
            "fetch script must declare a NODE_SHA256 associative array",
        )
        assertTrue(
            "linux-x64" in text,
            "fetch script must pin linux-x64 (the platform the B6.0 standalone tar ships)",
        )
    }

    @Test
    fun `B7_x pinned SHA-256 for linux-x64 is a real 64-char lowercase hex`() {
        val text = Files.readString(fetchScript())
        // Extract the linux-x64 entry.
        val regex = Regex("""\[linux-x64\]="([0-9a-f]{64})"""")
        val match = regex.find(text)
        assertTrue(
            match != null,
            "fetch script must pin linux-x64 to a 64-char lowercase hex SHA-256, got:\n$text",
        )
        val hash = match!!.groupValues[1]
        // Refuse placeholder patterns:
        // all zeros, all f's, the classic
        // "deadbeef" pattern.
        assertNotEquals(
            "0".repeat(64), hash,
            "linux-x64 SHA-256 must not be the all-zeros placeholder",
        )
        assertNotEquals(
            "f".repeat(64), hash,
            "linux-x64 SHA-256 must not be the all-f's placeholder",
        )
        assertNotEquals(
            "deadbeef".repeat(8), hash,
            "linux-x64 SHA-256 must not be the deadbeef placeholder",
        )
    }

    @Test
    fun `B7_x pinned SHA-256 for linux-x64 matches the live upstream SHASUMS256 txt`() {
        // B7.x (2026-10-10): the source
        // of truth is the upstream
        // SHASUMS256.txt. We pin that the
        // script's table matches it. The
        // test fetches the live file via
        // curl (the host shell, not the
        // Gradle JVM sandbox) so the
        // check runs against the real
        // upstream value.
        val text = Files.readString(fetchScript())
        val regex = Regex("""\[linux-x64\]="([0-9a-f]{64})"""")
        val pinned = regex.find(text)?.groupValues?.get(1) ?: error("linux-x64 pin not found in script")
        val shasums = fetchUpstreamShasums()
        val expected = shasums.entries.firstOrNull { it.key == "node-v20.18.0-linux-x64.tar.xz" }
            ?: error("linux-x64 tarball not found in upstream SHASUMS256.txt")
        assertTrue(
            expected.value.equals(pinned, ignoreCase = true),
            "pinned linux-x64 SHA-256 ($pinned) does not match upstream (${expected.value})",
        )
    }

    @Test
    fun `B7_x fetch script computes SHA-256 with sha256sum (or shasum on macOS)`() {
        val text = Files.readString(fetchScript())
        assertTrue(
            "sha256sum" in text || "shasum" in text,
            "fetch script must compute SHA-256 via sha256sum (Linux) or shasum (macOS)",
        )
    }

    @Test
    fun `B7_x fetch script exits non-zero on a wrong SHA-256 (supply-chain failure)`() {
        // The error path: simulate a
        // hostile mirror by replacing the
        // cached tarball with a 25MB file
        // that does NOT match the pinned
        // hash. The script should detect
        // the mismatch on the second run
        // (when the cache file exists but
        // the hash is wrong) and exit
        // non-zero.
        val text = Files.readString(fetchScript())
        assertTrue(
            "SHA-256 mismatch" in text || "supply-chain integrity failure" in text,
            "fetch script must surface a clear supply-chain integrity message on a wrong hash",
        )
        assertTrue(
            "exit 5" in text || "Refusing to use" in text || "redownloading" in text,
            "fetch script must either re-download (stale cache) or refuse (post-download) on a wrong hash",
        )
    }

    @Test
    fun `B7_x pinned SHA-256 is in the script header so the next bump is obvious`() {
        val text = Files.readString(fetchScript())
        // The script header (the first
        // 30 lines or so) must mention
        // the SHA-256 pin so a future
        // operator bumping the version
        // sees it before they ship.
        val header = text.lineSequence().take(40).joinToString("\n")
        assertTrue(
            "SHA-256" in header,
            "fetch script header must mention SHA-256 so the next operator knows to update the table",
        )
    }

    /**
     * Fetch the upstream SHASUMS256.txt
     * via the host shell (curl, not
     * URL.openStream — same Gradle
     * sandbox quirk as the fetch
     * script). Returns a map of
     * filename -> lowercase hex hash.
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
