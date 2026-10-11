package dev.pipelinek.studio.infra.storage

import dev.pipelinek.studio.application.ports.AdmittedPlugin
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * R2.3 (2026-10-11): adversarial tests for the file-backed
 * [FilePluginAdmissionLock]. The contract is:
 *  1. Missing file = empty lock.
 *  2. Empty list passed to save() deletes the file.
 *  3. Roundtrip: save([entry]) + load() returns the same entry
 *     with all fields preserved (sha256, actor, timestamp).
 *  4. Corruption: a non-parseable file or unknown format_version
 *     is treated as an empty lock (not a hard failure), so the
 *     studio can boot even if the lock was hand-edited.
 */
class FilePluginAdmissionLockTest {

    @Test
    fun `missing lock file loads as empty list`(@TempDir tmp: Path) {
        val lock = FilePluginAdmissionLock(tmp.resolve("plugin.lock.json"))
        assertEquals(emptyList(), lock.load())
    }

    @Test
    fun `save with empty list deletes the file`(@TempDir tmp: Path) {
        val path = tmp.resolve("plugin.lock.json")
        val lock = FilePluginAdmissionLock(path)
        lock.save(
            listOf(
                AdmittedPlugin(
                    pluginJar = "x.jar",
                    sha256 = "a".repeat(64),
                    admittedAt = Instant.parse("2026-10-11T18:00:00Z"),
                    admittedBy = "ops:test",
                ),
            ),
        )
        assertTrue(Files.isRegularFile(path), "save must create the file when non-empty")
        lock.save(emptyList())
        assertFalse(Files.exists(path), "save(empty) must delete the file (no plugins admitted state)")
    }

    @Test
    fun `roundtrip preserves all fields exactly`(@TempDir tmp: Path) {
        val path = tmp.resolve("plugin.lock.json")
        val lock = FilePluginAdmissionLock(path)
        val original = listOf(
            AdmittedPlugin(
                pluginJar = "valid-plugin-0.1.0.jar",
                sha256 = "abc123def456".padEnd(64, '0'),
                admittedAt = Instant.parse("2026-10-11T18:30:00Z"),
                admittedBy = "ops:alice",
            ),
            AdmittedPlugin(
                pluginJar = "other-plugin-2.0.0.jar",
                sha256 = "fedcba987654".padEnd(64, '0'),
                admittedAt = Instant.parse("2026-10-12T09:15:30Z"),
                admittedBy = "ops:bob",
            ),
        )
        lock.save(original)
        val reloaded = lock.load()
        assertEquals(original, reloaded, "roundtrip must preserve every field, in order")
    }

    @Test
    fun `non-parseable file is treated as empty lock`(@TempDir tmp: Path) {
        val path = tmp.resolve("plugin.lock.json")
        Files.writeString(path, "this is not JSON {{{ broken")
        val lock = FilePluginAdmissionLock(path)
        assertEquals(emptyList(), lock.load(), "corruption must NOT crash; the lock is treated as empty")
    }

    @Test
    fun `unknown format_version is treated as empty lock`(@TempDir tmp: Path) {
        val path = tmp.resolve("plugin.lock.json")
        Files.writeString(
            path,
            """{"format_version": 99, "admitted": []}""",
        )
        val lock = FilePluginAdmissionLock(path)
        assertEquals(emptyList(), lock.load(), "format_version=99 must be refused; treated as empty")
    }
}
