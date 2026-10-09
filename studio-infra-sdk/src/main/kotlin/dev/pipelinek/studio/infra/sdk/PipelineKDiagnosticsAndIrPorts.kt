package dev.pipelinek.studio.infra.sdk

import dev.pipelinek.studio.application.ports.DiagnosticsReadPort
import dev.pipelinek.studio.application.ports.DiagnosticsReadResult
import dev.pipelinek.studio.application.ports.Diagnostic
import dev.pipelinek.studio.application.ports.DiagnosticPhase
import dev.pipelinek.studio.application.ports.DiagnosticSeverity
import dev.pipelinek.studio.application.ports.IrExplorerReadPort
import dev.pipelinek.studio.application.ports.IrReadResult
import dev.pipelinek.studio.application.ports.IrSnapshot
import dev.pipelinek.studio.application.ports.IRPhase
import dev.pipelinek.studio.application.ports.StageIR
import dev.pipelinek.studio.application.ports.StepIR
import dev.pipelinek.studio.domain.RunId
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import java.time.Instant

/**
 * S3 adapter — reads run.db to build normalised Diagnostics + IR Snapshot.
 *
 * Diagnostics: pulls events with `kind` matching `Compilation*` and normalises
 * the SDK-provided severity / line / column / path into Studio's Diagnostic shape.
 *
 * IR: pulls `operation_journal` rows as `StepIR` entries (phase=ADMITTED) plus
 * events with `kind` in the runtime family for phase=OBSERVED. Compilation
 * events drive phase=COMPILED.
 *
 * We deliberately do NOT compile in this module — the SDK owns compilation.
 * This adapter is a pure read-only projection.
 */
class PipelineKDiagnosticsAndIrPorts(
    private val controlRootBase: Path,
) : DiagnosticsReadPort, IrExplorerReadPort {

    private val log = LoggerFactory.getLogger(javaClass)
    private val inspector = StaticManifestInspector()

    private fun runDbPath(runId: RunId): Path =
        controlRootBase.resolve(runId.value).resolve("run.db")

    private fun openReadonly(runId: RunId) = run {
        val db = runDbPath(runId)
        if (!Files.isRegularFile(db)) null
        else DriverManager.getConnection("jdbc:sqlite:${db.toAbsolutePath()}")
    }

    override fun diagnostics(runId: RunId): DiagnosticsReadResult {
        val conn = openReadonly(runId) ?: return DiagnosticsReadResult.Unknown(
            "run.db not found at ${runDbPath(runId)}",
        )
        conn.use { c ->
            val diagnostics = mutableListOf<Diagnostic>()
            c.prepareStatement(
                "SELECT sequence, kind, occurred_at, payload FROM events WHERE kind LIKE 'Compilation%' ORDER BY sequence",
            ).use { ps ->
                ps.executeQuery().use { rs ->
                    while (rs.next()) {
                        val payload = rs.getString("payload") ?: "{}"
                        val kind = rs.getString("kind") ?: ""
                        val sequence = rs.getLong("sequence")
                        diagnostics += translateEvent(runId, sequence, kind, payload)
                    }
                }
            }
            return DiagnosticsReadResult.Ok(diagnostics)
        }
    }

    override fun snapshot(runId: RunId): IrReadResult {
        val conn = openReadonly(runId) ?: return IrReadResult.Unknown(
            "run.db not found at ${runDbPath(runId)}",
        )
        conn.use { c ->
            val steps = mutableListOf<StepIR>()
            val phases = mutableSetOf<IRPhase>()
            c.prepareStatement(
                "SELECT op_id, fingerprint, kind, attempt, input, output FROM operation_journal ORDER BY op_id",
            ).use { ps ->
                ps.executeQuery().use { rs ->
                    while (rs.next()) {
                        val kind = rs.getString("kind") ?: "Step"
                        val opId = rs.getString("op_id")
                        val fingerprint = rs.getString("fingerprint")
                        val attempt = rs.getInt("attempt")
                        val input = rs.getString("input") ?: ""
                        val name = deriveStepName(fingerprint, kind)
                        steps += StepIR(
                            opId = opId,
                            name = name,
                            className = fingerprint ?: kind,
                            capabilities = inferCapabilities(fingerprint, input),
                            inputSchemaPresent = input.isNotBlank(),
                            outputSchemaPresent = rs.getString("output") != null,
                            phase = IRPhase.ADMITTED,
                        )
                        phases += IRPhase.ADMITTED
                    }
                }
            }
            c.prepareStatement(
                "SELECT sequence, kind FROM events WHERE kind LIKE 'Compilation%'",
            ).use { ps ->
                ps.executeQuery().use { rs ->
                    while (rs.next()) {
                        val kind = rs.getString("kind") ?: ""
                        if (kind == "CompilationFinished") phases += IRPhase.COMPILED
                    }
                }
            }
            c.prepareStatement(
                "SELECT COUNT(*) FROM events WHERE kind NOT LIKE 'Compilation%'",
            ).use { ps ->
                ps.executeQuery().use { rs ->
                    if (rs.next() && rs.getInt(1) > 0) phases += IRPhase.OBSERVED
                }
            }
            val stage = StageIR(name = "compiled-runtime", steps = steps)
            return IrReadResult.Ok(IrSnapshot(runId = runId, stages = listOf(stage), phasesObserved = phases))
        }
    }

    private fun translateEvent(runId: RunId, sequence: Long, kind: String, payload: String): Diagnostic {
        // Minimal payload shape picked up from `bin/pipelinek` emit:
        //   {"diagnostics":[{"severity":"ERROR","message":"...","line":1,"column":5,"path":"..."}, ...]}
        // Fallback: synthesize a diagnostic carrying the kind.
        val (severity, message, line, column, path) = parsePayload(payload, kind)
        return Diagnostic(
            id = "$runId:$sequence",
            severity = severity,
            phase = DiagnosticPhase.COMPILATION,
            message = message,
            source = "pipelinek:${kind.replace("Compilation", "").lowercase()}",
            line = line,
            column = column,
            path = path,
            runId = runId,
            actions = emptyList(),
        )
    }

    private data class Translated(val severity: DiagnosticSeverity, val message: String, val line: Int?, val column: Int?, val path: String?)

    private fun parsePayload(payload: String, kind: String): Translated {
        val msgMatch = Regex(""""message"\s*:\s*"([^"]+)"""").find(payload)
        val lineMatch = Regex(""""line"\s*:\s*(\d+)""").find(payload)
        val colMatch = Regex(""""column"\s*:\s*(\d+)""").find(payload)
        val pathMatch = Regex(""""path"\s*:\s*"([^"]+)"""").find(payload)
        val sevMatch = Regex(""""severity"\s*:\s*"([^"]+)"""").find(payload)
        val sev = when (sevMatch?.groupValues?.get(1)) {
            "ERROR" -> DiagnosticSeverity.ERROR
            "WARNING" -> DiagnosticSeverity.WARNING
            "INFO" -> DiagnosticSeverity.INFO
            else -> if (kind == "CompilationFinished") DiagnosticSeverity.INFO else DiagnosticSeverity.ERROR
        }
        return Translated(
            severity = sev,
            message = msgMatch?.groupValues?.get(1) ?: "unknown diagnostic",
            line = lineMatch?.groupValues?.get(1)?.toIntOrNull(),
            column = colMatch?.groupValues?.get(1)?.toIntOrNull(),
            path = pathMatch?.groupValues?.get(1),
        )
    }

    private fun deriveStepName(fingerprint: String?, kind: String): String {
        if (fingerprint.isNullOrBlank()) return kind
        return fingerprint.substringAfterLast('.').removeSuffix("Step").ifBlank { kind }
    }

    private fun inferCapabilities(fingerprint: String?, input: String): List<String> {
        if (fingerprint.isNullOrBlank()) return emptyList()
        // Static plugin JAR metadata is unavailable in operation_journal;
        // best-effort heuristic — emit at least the runtime kind.
        return listOfNotNull(
            "runtime:${if (input.isBlank()) "no-input" else "with-input"}",
        )
    }
}
