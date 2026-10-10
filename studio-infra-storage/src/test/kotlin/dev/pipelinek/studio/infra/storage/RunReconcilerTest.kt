package dev.pipelinek.studio.infra.storage

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import java.util.jar.JarOutputStream
import java.util.jar.Manifest
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFalse

/**
 * Audit H-25 (2026-10-10): RunReconciler previously trusted
 * whatever directory name sat under `controlRootBase/<dir>/run.db`
 * and exposed it verbatim as a RunId in /api/recovery. A
 * non-UUID-shaped name (`../foo`, `last-run`, `leases`) or a
 * path-traversal attempt would either:
 *
 *   1. surface a path-shaped runId in the recovery endpoint, OR
 *   2. feed a non-RunId string into a downstream component that
 *      assumed the canonical UUID shape.
 *
 * The tests below lock the new contract: directories whose names
 * are not canonical UUIDs are skipped with a log, and any
 * run.db whose resolved path escapes controlRootBase is also
 * skipped.
 */
class RunReconcilerTest {

    @Test
    fun `reconcile skips directories whose names are not canonical UUIDs`(@TempDir tmp: Path) {
        val controlRoot = tmp.resolve("control").also { Files.createDirectories(it) }
        // Non-UUID names that an operator might have left around.
        Files.createDirectory(controlRoot.resolve("last-run"))
        Files.createDirectory(controlRoot.resolve("leases"))
        Files.createDirectory(controlRoot.resolve("not-a-uuid"))
        // Also a real-shaped dir, just to be sure the filter doesn't drop everything.
        val realDir = controlRoot.resolve("11111111-2222-3333-4444-555555555555").also {
            Files.createDirectory(it)
            // No run.db — should be skipped for a different reason.
        }

        val report = RunReconciler(controlRoot).reconcile()
        // Only the real dir is "considered"; the rest are dropped at the UUID gate.
        // The real dir has no run.db so the reconciler skips it for that reason.
        assertEquals(0, report.reconciled.size,
            "non-UUID directories must be skipped; got ${report.reconciled.map { it.runId.value }}")
    }

    @Test
    fun `reconcile reports a valid run when run_db exists under a UUID directory`(@TempDir tmp: Path) {
        val controlRoot = tmp.resolve("control").also { Files.createDirectories(it) }
        val runId = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"
        val runDir = controlRoot.resolve(runId).also { Files.createDirectories(it) }
        writeRunDb(runDir.resolve("run.db"), "RunFinished", "{\"outcome\":\"success\"}")

        val report = RunReconciler(controlRoot).reconcile()
        assertEquals(1, report.reconciled.size)
        val entry = report.reconciled.first()
        assertEquals(runId, entry.runId.value)
        assertEquals("RunFinished", entry.lastEventKind)
    }

    @Test
    fun `reconcile refuses to read run_db that escapes controlRoot via symlink`(@TempDir tmp: Path) {
        val controlRoot = tmp.resolve("control").also { Files.createDirectories(it) }
        val runId = "12345678-1234-1234-1234-123456789012"
        val runDir = controlRoot.resolve(runId).also { Files.createDirectories(it) }
        // Create a real run.db outside controlRoot and symlink it
        // into the per-run dir. A symlink to /tmp or anywhere else
        // would let an attacker read arbitrary SQLite files via
        // the reconciler.
        val outsideDb = tmp.resolve("outside.db").also {
            writeRunDb(it, "RunFinished", "{\"outcome\":\"success\"}")
        }
        runDir.resolve("run.db").let { link ->
            Files.deleteIfExists(link)
            Files.createSymbolicLink(link, outsideDb)
        }
        val report = RunReconciler(controlRoot).reconcile()
        // The defense-in-depth check rejects the out-of-tree run.db.
        assertEquals(0, report.reconciled.size,
            "run.db escaping controlRoot must be refused; got ${report.reconciled.map { it.runId.value }}")
    }

    @Test
    fun `IdFormat isUuidLike matches canonical UUIDs and rejects everything else`() {
        // Valid
        assertTrue(dev.pipelinek.studio.domain.IdFormat.isUuidLike("0811e7b6-0460-e6b1-5a31-cd3f312b1b5b"))
        assertTrue(dev.pipelinek.studio.domain.IdFormat.isUuidLike("AAAAAAAA-BBBB-CCCC-DDDD-EEEEEEEEEEEE"))
        // Invalid
        assertFalse(dev.pipelinek.studio.domain.IdFormat.isUuidLike("not-a-uuid"))
        assertFalse(dev.pipelinek.studio.domain.IdFormat.isUuidLike(""))
        assertFalse(dev.pipelinek.studio.domain.IdFormat.isUuidLike("../../../etc/passwd"))
        assertFalse(dev.pipelinek.studio.domain.IdFormat.isUuidLike("0811e7b60460e6b15a31cd3f312b1b5b3c36f8d1aa6dce7a7fd04193e362eace")) // no dashes
        assertFalse(dev.pipelinek.studio.domain.IdFormat.isUuidLike("0811e7b6-0460-e6b1-5a31-cd3f312b1b5")) // too short
        assertFalse(dev.pipelinek.studio.domain.IdFormat.isUuidLike(null))
    }

    @Test
    fun `reconcile on missing controlRoot yields an empty report`(@TempDir tmp: Path) {
        val missing = tmp.resolve("does-not-exist")
        val report = RunReconciler(missing).reconcile()
        assertEquals(0, report.reconciled.size)
        assertEquals(0, report.totalRuns)
    }

    private fun writeRunDb(path: Path, lastKind: String, lastPayload: String) {
        DriverManager.getConnection("jdbc:sqlite:$path").use { c ->
            c.createStatement().use { st ->
                st.execute("CREATE TABLE events (sequence INTEGER PRIMARY KEY, kind TEXT, occurred_at TEXT, payload TEXT)")
                st.execute("INSERT INTO events(sequence, kind, occurred_at, payload) VALUES (1, '$lastKind', '2026-10-10T00:00:00Z', '$lastPayload')")
            }
        }
    }
}
