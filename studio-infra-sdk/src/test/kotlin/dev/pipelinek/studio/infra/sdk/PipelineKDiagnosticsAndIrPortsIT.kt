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

    /**
     * B3.1 (2026-10-10): source-map exactness. The
     * pre-existing `diagnostics translates
     * CompilationFinished events` test asserts that
     * a single diagnostic at line 3 column 5 reads
     * back as line=3, column=5. B3.1 strengthens
     * the contract:
     *  - a single event with 3 diagnostics at lines
     *    1, 5, 10 round-trips with the exact same
     *    line numbers — no off-by-one drift across
     *    the 1..10 range;
     *  - column 5 at line 3 reads back as 5 (no
     *    off-by-one in the column dimension either);
     *  - the path field is preserved verbatim, so
     *    the Monaco marker click navigates to the
     *    right file.
     *
     * The test uses a synthetic event payload
     * (not a real SDK run) so the line/column
     * fidelity is checked at the adapter boundary
     * without coupling to the SDK's compile output.
     */
    @Test
    fun `B3_1 source-map exactness — three diagnostics at lines 1 5 10 round-trip with exact line and column`(@TempDir tmp: Path) {
        val runId = RunId.new()
        val runDir = tmp.resolve(runId.value)
        Files.createDirectories(runDir)
        val db = runDir.resolve("run.db")
        java.sql.DriverManager.getConnection("jdbc:sqlite:$db").use { c ->
            c.createStatement().use { it.executeUpdate("CREATE TABLE events (event_id TEXT, run_id TEXT, sequence INTEGER, kind TEXT, occurred_at TEXT, payload TEXT)") }
            c.prepareStatement("INSERT INTO events(event_id, run_id, sequence, kind, occurred_at, payload) VALUES(?,?,?,?,?,?)").use { ps ->
                ps.setString(1, "e1"); ps.setString(2, runId.value); ps.setLong(3, 1)
                ps.setString(4, "CompilationFinished"); ps.setString(5, "2026-10-09T20:00:00Z")
                ps.setString(
                    6,
                    """
                    {
                      "diagnostics": [
                        {"severity":"ERROR","message":"line 1 error","line":1,"column":3,"path":"echo.kts"},
                        {"severity":"ERROR","message":"line 5 error","line":5,"column":7,"path":"echo.kts"},
                        {"severity":"ERROR","message":"line 10 error","line":10,"column":2,"path":"echo.kts"}
                      ]
                    }
                    """.trimIndent(),
                )
                ps.executeUpdate()
            }
        }
        val port = PipelineKDiagnosticsAndIrPorts(tmp)
        val res = port.diagnostics(runId)
        assertTrue(res is DiagnosticsReadResult.Ok)
        val ok = res as DiagnosticsReadResult.Ok
        assertEquals(3, ok.diagnostics.size, "expected 3 diagnostics, got ${ok.diagnostics.size}")
        // B3.1: line numbers must round-trip exactly.
        // A future refactor that introduced an
        // off-by-one (e.g. +1 because the SDK uses
        // 1-based but the adapter projected to
        // 0-based) would be caught here.
        assertEquals(1, ok.diagnostics[0].line, "line 1 must round-trip as 1, not ${ok.diagnostics[0].line}")
        assertEquals(5, ok.diagnostics[1].line, "line 5 must round-trip as 5, not ${ok.diagnostics[1].line}")
        assertEquals(10, ok.diagnostics[2].line, "line 10 must round-trip as 10, not ${ok.diagnostics[2].line}")
        // B3.1: column numbers must round-trip exactly.
        assertEquals(3, ok.diagnostics[0].column)
        assertEquals(7, ok.diagnostics[1].column)
        assertEquals(2, ok.diagnostics[2].column)
        // B3.1: path must round-trip verbatim, so the
        // Monaco marker click navigates to the right
        // file. A refactor that nulled or replaced
        // the path would break the SPA's click-to-line.
        for (d in ok.diagnostics) {
            assertEquals("echo.kts", d.path, "path must round-trip verbatim, got: ${d.path}")
        }
        // B3.1: messages are preserved in the
        // diagnostic order. The wire contract is
        // "diagnostics in source order"; a future
        // refactor that sorted by severity would
        // break the SPA's gutter markers.
        assertEquals("line 1 error", ok.diagnostics[0].message)
        assertEquals("line 5 error", ok.diagnostics[1].message)
        assertEquals("line 10 error", ok.diagnostics[2].message)
    }

    /**
     * B3.1 (2026-10-10): a diagnostic at line 1
     * reads back as line 1, NOT line 0 (the
     * off-by-one trap when the SDK's 1-based
     * source maps are projected to a 0-based
     * internal representation). The pre-existing
     * test caught line 3 → line 3; this catches
     * the boundary line 1.
     */
    @Test
    fun `B3_1 line 1 round-trips as 1 not 0`(@TempDir tmp: Path) {
        val runId = RunId.new()
        val runDir = tmp.resolve(runId.value)
        Files.createDirectories(runDir)
        val db = runDir.resolve("run.db")
        java.sql.DriverManager.getConnection("jdbc:sqlite:$db").use { c ->
            c.createStatement().use { it.executeUpdate("CREATE TABLE events (event_id TEXT, run_id TEXT, sequence INTEGER, kind TEXT, occurred_at TEXT, payload TEXT)") }
            c.prepareStatement("INSERT INTO events(event_id, run_id, sequence, kind, occurred_at, payload) VALUES(?,?,?,?,?,?)").use { ps ->
                ps.setString(1, "e1"); ps.setString(2, runId.value); ps.setLong(3, 1)
                ps.setString(4, "CompilationFinished"); ps.setString(5, "2026-10-09T20:00:00Z")
                ps.setString(6, """{"diagnostics":[{"severity":"ERROR","message":"first-line error","line":1,"column":1,"path":"echo.kts"}]}""")
                ps.executeUpdate()
            }
        }
        val port = PipelineKDiagnosticsAndIrPorts(tmp)
        val res = port.diagnostics(runId)
        assertTrue(res is DiagnosticsReadResult.Ok)
        val ok = res as DiagnosticsReadResult.Ok
        assertEquals(1, ok.diagnostics.size)
        // B3.1 boundary: line 1 stays line 1, NOT
        // line 0 (the off-by-one trap).
        assertEquals(1, ok.diagnostics[0].line, "line 1 must round-trip as 1, not 0 or 2")
        assertEquals(1, ok.diagnostics[0].column, "column 1 must round-trip as 1, not 0 or 2")
    }
}
