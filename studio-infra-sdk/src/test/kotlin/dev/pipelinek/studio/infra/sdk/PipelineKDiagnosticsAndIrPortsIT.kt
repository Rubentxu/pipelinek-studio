package dev.pipelinek.studio.infra.sdk

import dev.pipelinek.studio.application.ports.DiagnosticsReadResult
import dev.pipelinek.studio.application.ports.DiagnosticPhase
import dev.pipelinek.studio.application.ports.IrReadResult
import dev.pipelinek.studio.domain.RunId
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class PipelineKDiagnosticsAndIrPortsIT {

    @Test
    fun `diagnostics translates CompilationFinished events`(@TempDir tmp: Path) {
        val runId = RunId.new()
        val runDir = tmp.resolve(runId.value)
        Files.createDirectories(runDir)
        val db = runDir.resolve("run.db")
        java.sql.DriverManager.getConnection("jdbc:sqlite:$db").use { c ->
            c.createStatement().use { it.executeUpdate("CREATE TABLE events (event_id TEXT, run_id TEXT, sequence INTEGER, kind TEXT, occurred_at TEXT, payload TEXT)") }
            c.prepareStatement("INSERT INTO events(event_id, run_id, sequence, kind, occurred_at, payload) VALUES(?,?,?,?,?,?)").use { ps ->
                ps.setString(1, "e1"); ps.setString(2, runId.value); ps.setLong(3, 1)
                ps.setString(4, "CompilationFinished"); ps.setString(5, "2026-10-09T20:00:00Z")
                ps.setString(6, """{"diagnostics":[{"severity":"ERROR","message":"Unresolved reference 'foo'","line":3,"column":5,"path":"echo.kts"}]}""")
                ps.executeUpdate()
            }
        }
        val port = PipelineKDiagnosticsAndIrPorts(tmp)
        val res = port.diagnostics(runId)
        assertTrue(res is DiagnosticsReadResult.Ok)
        val ok = res as DiagnosticsReadResult.Ok
        assertEquals(1, ok.diagnostics.size)
        val d = ok.diagnostics[0]
        assertEquals("Unresolved reference 'foo'", d.message)
        assertEquals(3, d.line)
        assertEquals(5, d.column)
        assertEquals("echo.kts", d.path)
        assertEquals(DiagnosticPhase.COMPILATION, d.phase)
    }

    @Test
    fun `ir snapshot includes admitted phase from operation_journal`(@TempDir tmp: Path) {
        val runId = RunId.new()
        val runDir = tmp.resolve(runId.value)
        Files.createDirectories(runDir)
        val db = runDir.resolve("run.db")
        java.sql.DriverManager.getConnection("jdbc:sqlite:$db").use { c ->
            c.createStatement().use {
                it.executeUpdate("CREATE TABLE events (event_id TEXT, run_id TEXT, sequence INTEGER, kind TEXT, occurred_at TEXT, payload TEXT)")
                it.executeUpdate("""
                    CREATE TABLE operation_journal (
                        op_id TEXT PRIMARY KEY,
                        fingerprint TEXT, status TEXT, kind TEXT,
                        attempt INTEGER DEFAULT 1, input TEXT, output TEXT,
                        started_at INTEGER, ended_at INTEGER,
                        created_at INTEGER, updated_at INTEGER,
                        deadline_ms INTEGER, run_id TEXT
                    )
                """.trimIndent())
            }
            c.prepareStatement(
                "INSERT INTO operation_journal(op_id, fingerprint, status, kind, attempt, input, run_id) VALUES(?,?,?,?,?,?,?)",
            ).use { ps ->
                ps.setString(1, "op-1"); ps.setString(2, "com.example.EchoStep")
                ps.setString(3, "OK"); ps.setString(4, "ECHO"); ps.setInt(5, 1)
                ps.setString(6, "{}"); ps.setString(7, runId.value)
                ps.executeUpdate()
            }
            c.prepareStatement("INSERT INTO events(event_id, run_id, sequence, kind, occurred_at, payload) VALUES(?,?,?,?,?,?)").use { ps ->
                ps.setString(1, "e1"); ps.setString(2, runId.value); ps.setLong(3, 1)
                ps.setString(4, "CompilationFinished"); ps.setString(5, "2026-10-09T20:00:00Z")
                ps.setString(6, "{}")
                ps.executeUpdate()
            }
        }
        val port = PipelineKDiagnosticsAndIrPorts(tmp)
        val res = port.snapshot(runId)
        assertTrue(res is IrReadResult.Ok)
        val snap = res as IrReadResult.Ok
        assertNotNull(snap.snapshot)
        assertEquals(1, snap.snapshot.stages.size)
        val steps = snap.snapshot.stages[0].steps
        assertEquals(1, steps.size)
        assertEquals("Echo", steps[0].name)
        assertTrue(snap.snapshot.phasesObserved.contains(dev.pipelinek.studio.application.ports.IRPhase.ADMITTED))
        assertTrue(snap.snapshot.phasesObserved.contains(dev.pipelinek.studio.application.ports.IRPhase.COMPILED))
    }

    @Test
    fun `unknown run-db returns Unknown for both`(@TempDir tmp: Path) {
        val port = PipelineKDiagnosticsAndIrPorts(tmp)
        val d = port.diagnostics(RunId.new())
        assertTrue(d is DiagnosticsReadResult.Unknown)
        val i = port.snapshot(RunId.new())
        assertTrue(i is IrReadResult.Unknown)
    }
}
