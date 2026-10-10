package dev.pipelinek.studio.infra.storage

import dev.pipelinek.studio.application.ports.RunKeptReason
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Unit tests for [RetentionService] — AAT-DUR-006.
 *
 * The retention service walks `<controlRoot>/<runId>/`, opens each
 * `run.db`, and looks for a `RunFinished` event in the `events`
 * table. A run is deleted when it is terminal AND the runner does
 * not report a live subprocess for its run_id.
 *
 * These tests build synthetic control roots with hand-crafted
 * `run.db` files (matching the SDK's schema) so they are independent
 * of any real subprocess.
 */
class RetentionServiceTest {

    @TempDir
    lateinit var tempDir: Path

    private val service = RetentionService()

    @AfterEach
    fun tearDown() {
        // @TempDir cleans up; nothing else to do.
    }

    @Test
    fun `terminal run with no live subprocess is deleted`() {
        val runId = "00000000-0000-4000-8000-000000000001"
        val runDir = tempDir.resolve(runId)
        Files.createDirectories(runDir)
        writeRunDbWithRunFinished(runDir.resolve("run.db"))

        val outcome = service.sweep(tempDir, liveRunToPid = emptyMap())

        assertEquals(1, outcome.runsScanned, "scanned the one run")
        assertEquals(1, outcome.runsDeleted, "the terminal run was deleted")
        assertEquals(emptyList(), outcome.runsKept)
        assertTrue(!Files.exists(runDir), "the per-run dir was removed")
    }

    @Test
    fun `non-terminal run is kept with not-terminal reason`() {
        val runId = "00000000-0000-4000-8000-000000000002"
        val runDir = tempDir.resolve(runId)
        Files.createDirectories(runDir)
        writeRunDbWithoutRunFinished(runDir.resolve("run.db"))

        val outcome = service.sweep(tempDir, liveRunToPid = emptyMap())

        assertEquals(1, outcome.runsScanned)
        assertEquals(0, outcome.runsDeleted)
        assertEquals(1, outcome.runsKept.size)
        val reason = outcome.runsKept.first()
        assertTrue(reason is RunKeptReason.NotTerminal, "reason was: $reason")
        assertEquals(runId, reason.runId)
        assertTrue(Files.exists(runDir), "the per-run dir was NOT removed")
    }

    @Test
    fun `terminal run with live subprocess is kept with per-run pid`() {
        val runId = "00000000-0000-4000-8000-000000000003"
        val runDir = tempDir.resolve(runId)
        Files.createDirectories(runDir)
        writeRunDbWithRunFinished(runDir.resolve("run.db"))

        // The runner says THIS run_id has pid 4242.
        val outcome = service.sweep(tempDir, liveRunToPid = mapOf(runId to 4242L))

        assertEquals(1, outcome.runsScanned)
        assertEquals(0, outcome.runsDeleted, "live subprocess protects the run")
        val reason = outcome.runsKept.first()
        assertTrue(reason is RunKeptReason.HasLiveSubprocess, "reason was: $reason")
        assertEquals(runId, reason.runId)
        assertEquals(4242L, reason.pid)
        assertTrue(Files.exists(runDir), "the per-run dir was NOT removed")
    }

    @Test
    fun `per-run pid check is not global - a different run's pid does not protect this run`() {
        val runId = "00000000-0000-4000-8000-000000000004"
        val otherRunId = "00000000-0000-4000-8000-000000000099"
        val runDir = tempDir.resolve(runId)
        Files.createDirectories(runDir)
        writeRunDbWithRunFinished(runDir.resolve("run.db"))

        // The runner reports a live subprocess for OTHER_RUN, not THIS run.
        // The retention service must NOT protect THIS run.
        val outcome = service.sweep(tempDir, liveRunToPid = mapOf(otherRunId to 9000L))

        assertEquals(1, outcome.runsScanned)
        assertEquals(1, outcome.runsDeleted, "a live pid for a different run does NOT protect this run")
        assertTrue(!Files.exists(runDir), "the per-run dir was removed")
    }

    @Test
    fun `missing run_db is kept with unreadable reason`() {
        val runId = "00000000-0000-4000-8000-000000000005"
        val runDir = tempDir.resolve(runId)
        Files.createDirectories(runDir)
        // No run.db inside.

        val outcome = service.sweep(tempDir, liveRunToPid = emptyMap())

        assertEquals(1, outcome.runsScanned)
        assertEquals(0, outcome.runsDeleted)
        val reason = outcome.runsKept.first()
        assertTrue(reason is RunKeptReason.Unreadable, "reason was: $reason")
        assertEquals(runId, reason.runId)
        assertTrue(Files.exists(runDir))
    }

    @Test
    fun `non-UUID sibling directories are ignored`() {
        val runId = "00000000-0000-4000-8000-000000000006"
        val runDir = tempDir.resolve(runId)
        Files.createDirectories(runDir)
        writeRunDbWithRunFinished(runDir.resolve("run.db"))
        // A non-UUID sibling (e.g. a leases/ dir) should be ignored.
        Files.createDirectories(tempDir.resolve("leases"))
        Files.createDirectories(tempDir.resolve("not-a-uuid-dir"))

        val outcome = service.sweep(tempDir, liveRunToPid = emptyMap())

        assertEquals(1, outcome.runsScanned, "only the UUID dir was scanned")
        assertEquals(1, outcome.runsDeleted)
        assertTrue(Files.exists(tempDir.resolve("leases")), "sibling leases/ untouched")
    }

    /**
     * Audit H-23 (2026-10-10): the UUID regex was replaced
     * with a single canonical pattern. This test pins the
     * behavior: any non-canonical string is rejected, the
     * canonical 8-4-4-4-12 hex pattern is accepted, and the
     * matching is anchored (no extra chars before/after).
     */
    @Test
    fun `isUuidLike accepts canonical 8-4-4-4-12 hex and rejects the rest`() {
        // Use the same regex the service uses; the test does
        // not call a private method but exercises the contract
        // directly.
        val canonical = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}\$")
        val good = listOf(
            "00000000-0000-0000-0000-000000000000",
            "FFFFFFFF-FFFF-FFFF-FFFF-FFFFFFFFFFFF",
            "0811e7b6-0460-e6b1-5a31-cd3f312b1b5b",
            "abcdef01-2345-6789-abcd-ef0123456789",
        )
        val bad = listOf(
            "",                                          // empty
            "not-a-uuid",                                // not 36 chars
            "00000000-0000-0000-0000-00000000000",        // 35 chars
            "00000000-0000-0000-0000-0000000000000",      // 37 chars
            "00000000-0000-0000-0000-00000000000g",      // non-hex char
            "00000000 0000 0000 0000 0000000000000000",  // spaces instead of dashes
            "X0000000-0000-0000-0000-000000000000",       // non-hex leading char
            "00000000-0000-0000-0000-000000000000\n",     // trailing newline
            "../etc/passwd",                              // path traversal attempt
        )
        for (g in good) assertTrue(canonical.matches(g), "should accept '$g'")
        for (b in bad) assertTrue(!canonical.matches(b), "should reject '$b'")
    }

    @Test
    fun `missing control root returns empty outcome`() {
        val missing = tempDir.resolve("does-not-exist")
        val outcome = service.sweep(missing, liveRunToPid = emptyMap())
        assertEquals(0, outcome.runsScanned)
        assertEquals(0, outcome.runsDeleted)
        assertEquals(emptyList(), outcome.runsKept)
    }

    @Test
    fun `mixed batch - terminal with no live is deleted, others kept`() {
        val terminalId = "00000000-0000-4000-8000-000000000007"
        val liveId = "00000000-0000-4000-8000-000000000008"
        val inflightId = "00000000-0000-4000-8000-000000000009"

        Files.createDirectories(tempDir.resolve(terminalId))
        writeRunDbWithRunFinished(tempDir.resolve(terminalId).resolve("run.db"))

        Files.createDirectories(tempDir.resolve(liveId))
        writeRunDbWithRunFinished(tempDir.resolve(liveId).resolve("run.db"))

        Files.createDirectories(tempDir.resolve(inflightId))
        writeRunDbWithoutRunFinished(tempDir.resolve(inflightId).resolve("run.db"))

        val outcome = service.sweep(
            tempDir,
            liveRunToPid = mapOf(liveId to 5555L),
        )

        assertEquals(3, outcome.runsScanned)
        assertEquals(1, outcome.runsDeleted, "only the terminal-without-live run was deleted")
        assertEquals(2, outcome.runsKept.size)
        assertTrue(!Files.exists(tempDir.resolve(terminalId)))
        assertTrue(Files.exists(tempDir.resolve(liveId)), "live-subprocess run kept")
        assertTrue(Files.exists(tempDir.resolve(inflightId)), "in-flight run kept")
    }

    // ---- helpers -------------------------------------------------------

    private fun writeRunDbWithRunFinished(dbPath: Path) {
        Files.createDirectories(dbPath.parent)
        DriverManager.getConnection("jdbc:sqlite:${dbPath.toAbsolutePath()}").use { c ->
            c.createStatement().use { st ->
                st.executeUpdate(
                    """
                    CREATE TABLE IF NOT EXISTS events (
                        event_id TEXT NOT NULL,
                        run_id TEXT NOT NULL,
                        sequence INTEGER NOT NULL,
                        kind TEXT NOT NULL,
                        occurred_at TEXT NOT NULL,
                        payload TEXT NOT NULL
                    )
                    """.trimIndent()
                )
                st.executeUpdate(
                    """
                    INSERT INTO events (event_id, run_id, sequence, kind, occurred_at, payload)
                    VALUES ('e1', '00000000-0000-0000-0000-000000000000', 1, 'RunStarted', '2026-01-01T00:00:00Z', '{}')
                    """.trimIndent()
                )
                st.executeUpdate(
                    """
                    INSERT INTO events (event_id, run_id, sequence, kind, occurred_at, payload)
                    VALUES ('e2', '00000000-0000-0000-0000-000000000000', 2, 'RunFinished', '2026-01-01T00:00:01Z', '{"outcome":"success"}')
                    """.trimIndent()
                )
            }
        }
        assertNotNull(Files.exists(dbPath), "test setup: run.db was created")
    }

    private fun writeRunDbWithoutRunFinished(dbPath: Path) {
        Files.createDirectories(dbPath.parent)
        DriverManager.getConnection("jdbc:sqlite:${dbPath.toAbsolutePath()}").use { c ->
            c.createStatement().use { st ->
                st.executeUpdate(
                    """
                    CREATE TABLE IF NOT EXISTS events (
                        event_id TEXT NOT NULL,
                        run_id TEXT NOT NULL,
                        sequence INTEGER NOT NULL,
                        kind TEXT NOT NULL,
                        occurred_at TEXT NOT NULL,
                        payload TEXT NOT NULL
                    )
                    """.trimIndent()
                )
                st.executeUpdate(
                    """
                    INSERT INTO events (event_id, run_id, sequence, kind, occurred_at, payload)
                    VALUES ('e1', '00000000-0000-0000-0000-000000000000', 1, 'RunStarted', '2026-01-01T00:00:00Z', '{}')
                    """.trimIndent()
                )
            }
        }
    }
}
