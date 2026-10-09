package dev.pipelinek.studio.infra.sdk

import dev.pipelinek.studio.application.ports.EventCursor
import dev.pipelinek.studio.application.ports.RunReadResult
import dev.pipelinek.studio.domain.RunId
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Reads events from a real PipelineK run.db produced by `bin/pipelinek run`.
 * Uses the SDK from SDK_PATH env var when present; otherwise uses a tiny synthetic
 * run.db built inline so the test stays informative without network/setup.
 */
class PipelineKRunReadPortIT {

    private var sdkClasspath: List<Path> = emptyList()

    /** Optional: when SDK_PATH is set, we are ready to use the real SDK distribution
     *  elsewhere. For these tests we always work against synthetic run.db files so
     *  this is a no-op when SDK_PATH is missing. */
    private fun discoverSdk() {
        val sdkStr = System.getenv("SDK_PATH") ?: return
        val sdkDir = Path.of(sdkStr)
        if (!Files.isDirectory(sdkDir)) return
        sdkClasspath = Files.list(sdkDir.resolve("lib")).use { stream ->
            stream.filter { it.fileName.toString().endsWith(".jar") }
                .filter { !it.fileName.toString().endsWith("-sources.jar") }
                .toList()
        }
    }

    @Test
    fun `reads events from synthetic run-db without SDK`(@TempDir tmp: Path) {
        discoverSdk()
        discoverSdk()
        val port = PipelineKRunReadPort(tmp)
        val runId = RunId.new()
        val runDir = tmp.resolve(runId.value)
        Files.createDirectories(runDir)
        val db = runDir.resolve("run.db")
        java.sql.DriverManager.getConnection("jdbc:sqlite:$db").use { c ->
            c.createStatement().use { st ->
                st.executeUpdate(
                    """
                    CREATE TABLE events (event_id TEXT, run_id TEXT, sequence INTEGER,
                                          kind TEXT, occurred_at TEXT, payload TEXT)
                    """.trimIndent(),
                )
            }
            c.prepareStatement(
                "INSERT INTO events(event_id, run_id, sequence, kind, occurred_at, payload) VALUES(?,?,?,?,?,?)",
            ).use { ps ->
                for (i in 1..3) {
                    ps.setString(1, "evt-$i")
                    ps.setString(2, runId.value)
                    ps.setLong(3, i.toLong())
                    ps.setString(4, "TestEventKind-$i")
                    ps.setString(5, "2026-10-09T20:00:00Z")
                    ps.setString(6, """{"step":"stage-$i","line":"${i}"}""")
                    ps.executeUpdate()
                }
            }
        }

        val res: RunReadResult = port.events(runId, null, 10)
        assertTrue(res is RunReadResult.Ok, "expected Ok, got $res")
        val ok = res as RunReadResult.Ok
        assertEquals(3, ok.events.size)
        assertEquals("TestEventKind-1", ok.events[0].kind)
        assertEquals("stage-1", ok.events[0].payload["step"])
        assertEquals(3, ok.events.size)
        assertEquals(null, ok.nextSequence) // fewer rows than limit → EOF
    }

    @Test
    fun `returns Unknown when run-db missing`(@TempDir tmp: Path) {
        discoverSdk()
        val port = PipelineKRunReadPort(tmp)
        val res = port.events(RunId.new(), null, 50)
        assertTrue(res is RunReadResult.Unknown, "expected Unknown, got $res")
    }

    @Test
    fun `pagination via EventCursor`(@TempDir tmp: Path) {
        discoverSdk()
        val port = PipelineKRunReadPort(tmp)
        val runId = RunId.new()
        val runDir = tmp.resolve(runId.value)
        Files.createDirectories(runDir)
        val db = runDir.resolve("run.db")
        java.sql.DriverManager.getConnection("jdbc:sqlite:$db").use { c ->
            c.createStatement().use { it.executeUpdate("CREATE TABLE events (event_id TEXT, run_id TEXT, sequence INTEGER, kind TEXT, occurred_at TEXT, payload TEXT)") }
            c.prepareStatement("INSERT INTO events(event_id, run_id, sequence, kind, occurred_at, payload) VALUES(?,?,?,?,?,?)").use { ps ->
                for (i in 1..5) {
                    ps.setString(1, "e$i"); ps.setString(2, runId.value); ps.setLong(3, i.toLong())
                    ps.setString(4, "K$i"); ps.setString(5, "2026-10-09T20:00:00Z")
                    ps.setString(6, "{}")
                    ps.executeUpdate()
                }
            }
        }
        val first = port.events(runId, null, 2) as RunReadResult.Ok
        assertEquals(2, first.events.size)
        assertNotNull(first.nextSequence)
        val second = port.events(runId, first.nextSequence, 10) as RunReadResult.Ok
        assertEquals(3, second.events.size)
        assertEquals(null, second.nextSequence)
    }

    @Test
    fun `state returns synthetic hint based on last event`(@TempDir tmp: Path) {
        discoverSdk()
        val port = PipelineKRunReadPort(tmp)
        val runId = RunId.new()
        val runDir = tmp.resolve(runId.value)
        Files.createDirectories(runDir)
        val db = runDir.resolve("run.db")
        java.sql.DriverManager.getConnection("jdbc:sqlite:$db").use { c ->
            c.createStatement().use { it.executeUpdate("CREATE TABLE events (event_id TEXT, run_id TEXT, sequence INTEGER, kind TEXT, occurred_at TEXT, payload TEXT)") }
            c.prepareStatement("INSERT INTO events(event_id, run_id, sequence, kind, occurred_at, payload) VALUES(?,?,?,?,?,?)").use { ps ->
                ps.setString(1, "e1"); ps.setString(2, runId.value); ps.setLong(3, 1)
                ps.setString(4, "PipelineSucceeded"); ps.setString(5, "2026-10-09T20:00:00Z")
                ps.setString(6, "{}")
                ps.executeUpdate()
            }
        }
        val res = port.state(runId)
        assertTrue(res is RunReadResult.Ok)
        val ok = res as RunReadResult.Ok
        assertEquals("SyntheticStateHint", ok.events[0].kind)
        assertEquals("PipelineSucceeded", ok.events[0].payload["lastKind"])
    }
}
