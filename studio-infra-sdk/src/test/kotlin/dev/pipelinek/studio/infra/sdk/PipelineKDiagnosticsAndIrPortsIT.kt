package dev.pipelinek.studio.infra.sdk

import dev.pipelinek.studio.application.ports.DiagnosticsReadResult
import dev.pipelinek.studio.application.ports.DiagnosticPhase
import dev.pipelinek.studio.application.ports.IRPhase
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

    /**
     * B3.5 (2026-10-10): the IR Explorer
     * no-synthetic-block guarantee. A row in
     * `operation_journal` with a NULL `fingerprint`
     * has NO source span — the SDK admitted the
     * step via reflection or some non-source path.
     * The pre-B3.5 adapter fabricated a StepIR by
     * deriving the name from the `kind` column,
     * which is exactly the synthetic block the S3
     * mandate forbids ("sin duplicar compiler").
     *
     * B3.5: when any row in the journal has a null
     * fingerprint, the snapshot returns
     * [IrReadResult.Unknown] with a reason that
     * names the offending op ids. The SPA can
     * surface "IR incomplete — N steps have no
     * source mapping" rather than render a fake
     * step block.
     */
    @Test
    fun `B3_5 IR returns Unknown when a journal row has no fingerprint (no source span)`(@TempDir tmp: Path) {
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
            // Row 1: real fingerprint (has source span).
            c.prepareStatement(
                "INSERT INTO operation_journal(op_id, fingerprint, status, kind, attempt, input, run_id) VALUES(?,?,?,?,?,?,?)",
            ).use { ps ->
                ps.setString(1, "op-with-fingerprint"); ps.setString(2, "com.example.EchoStep")
                ps.setString(3, "OK"); ps.setString(4, "ECHO"); ps.setInt(5, 1)
                ps.setString(6, "{}"); ps.setString(7, runId.value)
                ps.executeUpdate()
            }
            // Row 2: NULL fingerprint (NO source span).
            // The pre-B3.5 adapter fabricated a StepIR
            // here with name=kind="Step". The B3.5 fix
            // refuses to project this row and returns
            // Unknown at the snapshot level.
            c.prepareStatement(
                "INSERT INTO operation_journal(op_id, fingerprint, status, kind, attempt, input, run_id) VALUES(?,?,?,?,?,?,?)",
            ).use { ps ->
                ps.setString(1, "op-without-fingerprint"); ps.setString(2, null)
                ps.setString(3, "OK"); ps.setString(4, "Step"); ps.setInt(5, 1)
                ps.setString(6, "{}"); ps.setString(7, runId.value)
                ps.executeUpdate()
            }
        }
        val port = PipelineKDiagnosticsAndIrPorts(tmp)
        val res = port.snapshot(runId)
        // B3.5: the snapshot is Unknown, not Ok.
        // The SPA renders this as "IR incomplete —
        // 1 step has no source mapping".
        assertTrue(res is IrReadResult.Unknown, "expected Unknown, got: $res")
        val unknown = res as IrReadResult.Unknown
        // The reason must name the offending op id
        // (the studio never hides what was missing).
        assertTrue(
            unknown.reason.contains("op-without-fingerprint"),
            "expected the reason to name the offending op id, got: ${unknown.reason}",
        )
        assertTrue(
            unknown.reason.contains("no source span"),
            "expected the reason to mention 'no source span', got: ${unknown.reason}",
        )
    }

    /**
     * B3.5 (2026-10-10): the IR Explorer is still
     * honest when ALL journal rows have a real
     * fingerprint. The pre-B3.5 happy path is
     * preserved: every row projects to a StepIR,
     * the snapshot returns Ok.
     */
    @Test
    fun `B3_5 IR returns Ok when all journal rows have a fingerprint`(@TempDir tmp: Path) {
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
        assertTrue(res is IrReadResult.Ok, "expected Ok, got: $res")
        val ok = res as IrReadResult.Ok
        assertNotNull(ok.snapshot)
        assertEquals(1, ok.snapshot.stages.size)
        val steps = ok.snapshot.stages[0].steps
        assertEquals(1, steps.size)
        assertEquals("Echo", steps[0].name)
        assertEquals("com.example.EchoStep", steps[0].className)
    }

    // R3.3 (2026-10-11) — adversarial tests pinning the no-synthetic-IR
    // contract. Pre-R3.3 the adapter returned StageIR(name =
    // "compiled-runtime", steps = emptyList()) when the journal had
    // nothing — a synthetic placeholder the S3 mandate explicitly
    // forbids. R3.3 closes the gap by refusing to project a stage
    // when the journal is empty AND no compilation event has been
    // recorded, and by anchoring the stage name to real journal data
    // (the first operation's fingerprint class name) when at least
    // one operation is present.

    @Test
    fun `R3_3 IR returns Unknown instead of synthetic stage when journal is empty (no operations, no compilation)`(@TempDir tmp: Path) {
        val runId = RunId.new()
        val runDir = tmp.resolve(runId.value)
        Files.createDirectories(runDir)
        val db = runDir.resolve("run.db")
        java.sql.DriverManager.getConnection("jdbc:sqlite:$db").use { c ->
            c.createStatement().use { st ->
                st.executeUpdate("CREATE TABLE events (event_id TEXT, run_id TEXT, sequence INTEGER, kind TEXT, occurred_at TEXT, payload TEXT)")
                st.executeUpdate("CREATE TABLE operation_journal (op_id TEXT PRIMARY KEY, fingerprint TEXT, status TEXT, kind TEXT, attempt INTEGER DEFAULT 1, input TEXT, output TEXT, started_at INTEGER, ended_at INTEGER, created_at INTEGER, updated_at INTEGER, deadline_ms INTEGER, run_id TEXT)")
            }
        }
        val port = PipelineKDiagnosticsAndIrPorts(tmp)
        val res = port.snapshot(runId)
        assertTrue(
            res is IrReadResult.Unknown,
            "R3.3 forbids synthetic IR; empty journal must yield Unknown, got ${res::class.simpleName}",
        )
        val reason = (res as IrReadResult.Unknown).reason
        assertTrue(
            !reason.contains("compiled-runtime", ignoreCase = true),
            "the legacy 'compiled-runtime' literal must NOT appear in Unknown reasons; got '$reason'",
        )
    }

    @Test
    fun `R3_3 IR stage name is derived from first operation fingerprint not hardcoded compiled-runtime (R033)`(@TempDir tmp: Path) {
        val runId = RunId.new()
        val runDir = tmp.resolve(runId.value)
        Files.createDirectories(runDir)
        val db = runDir.resolve("run.db")
        java.sql.DriverManager.getConnection("jdbc:sqlite:$db").use { c ->
            c.createStatement().use {
                it.executeUpdate("CREATE TABLE events (event_id TEXT, run_id TEXT, sequence INTEGER, kind TEXT, occurred_at TEXT, payload TEXT)")
                it.executeUpdate("CREATE TABLE operation_journal (op_id TEXT PRIMARY KEY, fingerprint TEXT, status TEXT, kind TEXT, attempt INTEGER DEFAULT 1, input TEXT, output TEXT, started_at INTEGER, ended_at INTEGER, created_at INTEGER, updated_at INTEGER, deadline_ms INTEGER, run_id TEXT)")
            }
            c.prepareStatement(
                "INSERT INTO operation_journal(op_id, fingerprint, status, kind, attempt, input, run_id) VALUES(?,?,?,?,?,?,?)",
            ).use { ps ->
                ps.setString(1, "op-1")
                ps.setString(2, "com.example.MyPipeline") // SDK's own class name
                ps.setString(3, "OK")
                ps.setString(4, "PIPELINE")
                ps.setInt(5, 1)
                ps.setString(6, "{}")
                ps.setString(7, runId.value)
                ps.executeUpdate()
            }
            c.prepareStatement("INSERT INTO events(event_id, run_id, sequence, kind, occurred_at, payload) VALUES(?,?,?,?,?,?)").use { ps ->
                ps.setString(1, "e1"); ps.setString(2, runId.value); ps.setLong(3, 1)
                ps.setString(4, "CompilationFinished"); ps.setString(5, "2026-10-11T10:00:00Z")
                ps.setString(6, "{}")
                ps.executeUpdate()
            }
        }
        val port = PipelineKDiagnosticsAndIrPorts(tmp)
        val res = port.snapshot(runId)
        assertTrue(res is IrReadResult.Ok, "non-empty journal must produce Ok")
        val snap = (res as IrReadResult.Ok).snapshot
        assertNotNull(snap)
        assertEquals(1, snap.stages.size)
        val stageName = snap.stages[0].name
        assertTrue(
            stageName != "compiled-runtime",
            "R3.3 forbids the legacy 'compiled-runtime' stage name; got '$stageName'",
        )
        assertTrue(
            stageName == "MyPipeline" || stageName == "com.example.MyPipeline",
            "stage name must be derived from the first operation's fingerprint; got '$stageName'",
        )
    }

    // ----- R3.2 adversarial tests: structured decoder contract -----
    // The pre-R3.2 parser scanned the payload with three regexes over a
    // hand-substring'd array body. R3.2 replaced that with a kotlinx
    // serialization JSON tree walk. These tests pin the structured
    // decoder's fail-closed behaviour on malformed or partially-shaped
    // payloads (the regex parser used to silently drop or leak entries on
    // these shapes — that is the 'parsers regex' anti-pattern the S3
    // mandate forbids).

    @Test
    fun `R3_2 structured decoder returns empty list on malformed payload`(@TempDir tmp: Path) {
        val runId = RunId.new()
        val runDir = tmp.resolve(runId.value)
        Files.createDirectories(runDir)
        val db = runDir.resolve("run.db")
        java.sql.DriverManager.getConnection("jdbc:sqlite:$db").use { c ->
            c.createStatement().use { it.executeUpdate("CREATE TABLE events (event_id TEXT, run_id TEXT, sequence INTEGER, kind TEXT, occurred_at TEXT, payload TEXT)") }
            c.prepareStatement("INSERT INTO events(event_id, run_id, sequence, kind, occurred_at, payload) VALUES(?,?,?,?,?,?)").use { ps ->
                ps.setString(1, "e1"); ps.setString(2, runId.value); ps.setLong(3, 1)
                ps.setString(4, "CompilationFinished"); ps.setString(5, "2026-10-09T20:00:00Z")
                ps.setString(6, "not-actually-json {{{ broken")
                ps.executeUpdate()
            }
        }
        val port = PipelineKDiagnosticsAndIrPorts(tmp)
        val res = port.diagnostics(runId)
        assertTrue(res is DiagnosticsReadResult.Ok, "empty decode must still return Ok")
        assertEquals(0, (res as DiagnosticsReadResult.Ok).diagnostics.size)
    }

    @Test
    fun `R3_2 structured decoder drops entries without a message`(@TempDir tmp: Path) {
        val runId = RunId.new()
        val runDir = tmp.resolve(runId.value)
        Files.createDirectories(runDir)
        val db = runDir.resolve("run.db")
        java.sql.DriverManager.getConnection("jdbc:sqlite:$db").use { c ->
            c.createStatement().use { it.executeUpdate("CREATE TABLE events (event_id TEXT, run_id TEXT, sequence INTEGER, kind TEXT, occurred_at TEXT, payload TEXT)") }
            c.prepareStatement("INSERT INTO events(event_id, run_id, sequence, kind, occurred_at, payload) VALUES(?,?,?,?,?,?)").use { ps ->
                ps.setString(1, "e1"); ps.setString(2, runId.value); ps.setLong(3, 1)
                ps.setString(4, "CompilationFinished"); ps.setString(5, "2026-10-09T20:00:00Z")
                // First entry: no message -> must be dropped silently.
                // Second entry: well-formed -> must survive.
                ps.setString(6, """{"diagnostics":[{"severity":"ERROR","line":1,"column":1,"path":"a.kts"},{"severity":"WARNING","message":"orphan semicolon","line":7,"column":2,"path":"b.kts"}]}""")
                ps.executeUpdate()
            }
        }
        val port = PipelineKDiagnosticsAndIrPorts(tmp)
        val res = port.diagnostics(runId)
        assertTrue(res is DiagnosticsReadResult.Ok)
        val ok = res as DiagnosticsReadResult.Ok
        assertEquals(1, ok.diagnostics.size, "entry without 'message' must be dropped, not silently accepted")
        val d = ok.diagnostics[0]
        assertEquals("orphan semicolon", d.message)
        assertEquals(dev.pipelinek.studio.application.ports.DiagnosticSeverity.WARNING, d.severity)
        assertEquals(7, d.line)
        assertEquals(2, d.column)
        assertEquals("b.kts", d.path)
    }

    // ----- R3.4 adversarial tests: explicit phase separation -----
    // The R3 mandate item 4 is "Separar de forma explícita COMPILED,
    // ADMITTED y OBSERVED". The IRPhase set on IrSnapshot is the wire
    // truth the SPA renders. These three tests pin the contract that
    // each phase is computed independently from a distinct journal
    // surface: operation_journal -> ADMITTED, CompilationFinished ->
    // COMPILED, any non-Compilation event -> OBSERVED. A run with
    // only one of the three journal surfaces must surface ONLY the
    // corresponding phase — a phase being smuggled in because the
    // adapter over-eagerly counts events is exactly the 'merging
    // phases' anti-pattern the mandate forbids.

    private fun seedEmpty(@TempDir tmp: Path, runId: RunId): Path {
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
        }
        return db
    }

    @Test
    fun `R3_4 IR phases only ADMITTED when journal has operations and no events`(@TempDir tmp: Path) {
        val runId = RunId.new()
        seedEmpty(tmp, runId)
        java.sql.DriverManager.getConnection("jdbc:sqlite:${tmp.resolve(runId.value).resolve("run.db")}").use { c ->
            c.prepareStatement(
                "INSERT INTO operation_journal(op_id, fingerprint, status, kind, attempt, input, run_id) VALUES(?,?,?,?,?,?,?)",
            ).use { ps ->
                ps.setString(1, "op-1"); ps.setString(2, "com.example.EchoStep")
                ps.setString(3, "OK"); ps.setString(4, "ECHO"); ps.setInt(5, 1)
                ps.setString(6, "{}"); ps.setString(7, runId.value)
                ps.executeUpdate()
            }
        }
        val port = PipelineKDiagnosticsAndIrPorts(tmp)
        val res = port.snapshot(runId)
        assertTrue(res is IrReadResult.Ok)
        val phases = (res as IrReadResult.Ok).snapshot.phasesObserved
        assertTrue(IRPhase.ADMITTED in phases, "ADMITTED must be present (operation_journal row)")
        assertTrue(IRPhase.COMPILED !in phases, "COMPILED must NOT appear without CompilationFinished events")
        assertTrue(IRPhase.OBSERVED !in phases, "OBSERVED must NOT appear without non-Compilation events")
    }

    @Test
    fun `R3_4 IR phases only COMPILED when only CompilationFinished event is present`(@TempDir tmp: Path) {
        val runId = RunId.new()
        seedEmpty(tmp, runId)
        java.sql.DriverManager.getConnection("jdbc:sqlite:${tmp.resolve(runId.value).resolve("run.db")}").use { c ->
            c.prepareStatement("INSERT INTO events(event_id, run_id, sequence, kind, occurred_at, payload) VALUES(?,?,?,?,?,?)").use { ps ->
                ps.setString(1, "e1"); ps.setString(2, runId.value); ps.setLong(3, 1)
                ps.setString(4, "CompilationFinished"); ps.setString(5, "2026-10-09T20:00:00Z")
                ps.setString(6, "{}"); ps.executeUpdate()
            }
        }
        val port = PipelineKDiagnosticsAndIrPorts(tmp)
        val res = port.snapshot(runId)
        // Empty operation_journal + only CompilationFinished: the
        // adapter has zero ADMITTED steps and the derivedStageName
        // helper returns 'stage-unknown'. The snapshot must still
        // project an Ok with COMPILED in phasesObserved, NOT Unknown.
        assertTrue(res is IrReadResult.Ok, "CompilationFinished alone must NOT produce Unknown (only journal-empty does)")
        val snap = (res as IrReadResult.Ok).snapshot
        val phases = snap.phasesObserved
        assertTrue(IRPhase.COMPILED in phases, "COMPILED must be present (CompilationFinished event)")
        assertTrue(IRPhase.ADMITTED !in phases, "ADMITTED must NOT appear without operation_journal rows")
        assertTrue(IRPhase.OBSERVED !in phases, "OBSERVED must NOT appear without non-Compilation events")
    }

    @Test
    fun `R3_4 IR phases only OBSERVED when only non-Compilation event is present`(@TempDir tmp: Path) {
        val runId = RunId.new()
        seedEmpty(tmp, runId)
        java.sql.DriverManager.getConnection("jdbc:sqlite:${tmp.resolve(runId.value).resolve("run.db")}").use { c ->
            c.prepareStatement("INSERT INTO events(event_id, run_id, sequence, kind, occurred_at, payload) VALUES(?,?,?,?,?,?)").use { ps ->
                ps.setString(1, "e1"); ps.setString(2, runId.value); ps.setLong(3, 1)
                ps.setString(4, "StepStarted"); ps.setString(5, "2026-10-09T20:00:00Z")
                ps.setString(6, "{}"); ps.executeUpdate()
            }
        }
        val port = PipelineKDiagnosticsAndIrPorts(tmp)
        val res = port.snapshot(runId)
        // Empty operation_journal + only a StepStarted event: same as
        // the COMPILED-only case — snapshot must still project with
        // OBSERVED in phases, not Unknown.
        assertTrue(res is IrReadResult.Ok, "StepStarted alone must NOT produce Unknown")
        val phases = (res as IrReadResult.Ok).snapshot.phasesObserved
        assertTrue(IRPhase.OBSERVED in phases, "OBSERVED must be present (non-Compilation event)")
        assertTrue(IRPhase.COMPILED !in phases, "COMPILED must NOT appear without CompilationFinished")
        assertTrue(IRPhase.ADMITTED !in phases, "ADMITTED must NOT appear without operation_journal rows")
    }
}
