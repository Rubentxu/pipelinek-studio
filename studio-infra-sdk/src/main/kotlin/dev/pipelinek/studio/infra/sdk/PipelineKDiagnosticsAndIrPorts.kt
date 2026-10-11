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
        // R0.3.2 (2026-10-11): open via SqliteConnection so
        // PRAGMA foreign_keys=ON is set on the run.db read handle.
        else dev.pipelinek.studio.infra.storage.SqliteConnection.openReadOnly(db)
    }

    override fun diagnostics(runId: RunId): DiagnosticsReadResult {
        val conn = openReadonly(runId) ?: return DiagnosticsReadResult.Unknown(
            "run.db not found at ${runDbPath(runId)}",
        )
        conn.use { c ->
            val diagnostics = mutableListOf<Diagnostic>()
            c.prepareStatement(
                "SELECT sequence, kind, occurred_at, payload FROM events WHERE kind = 'CompilationFinished' ORDER BY sequence",
            ).use { ps ->
                ps.executeQuery().use { rs ->
                    while (rs.next()) {
                        val payload = rs.getString("payload") ?: "{}"
                        val sequence = rs.getLong("sequence")
                        // S1 source-location diagnostic: when the SDK's
                        // CompilationFinished payload carries a non-empty
                        // `diagnostics` array, expand it into one Diagnostic
                        // per entry — the inner entries carry severity,
                        // message, line, column, and path which are the
                        // data the SPA needs to render. We only scan
                        // CompilationFinished (not CompilationStarted) because
                        // started has no real diagnostic data — surfaced
                        // placeholders would be noise.
                        val inner = parseCompilationDiagnostics(payload)
                        diagnostics += inner.map { d ->
                            Diagnostic(
                                id = "$runId:$sequence:${d.message.hashCode()}",
                                severity = d.severity,
                                phase = DiagnosticPhase.COMPILATION,
                                message = d.message,
                                source = "pipelinek:finished",
                                line = d.line,
                                column = d.column,
                                path = d.path,
                                runId = runId,
                                actions = emptyList(),
                            )
                        }
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
            // B3.5 (2026-10-10): the no-synthetic-block
            // guarantee. A row in operation_journal
            // with a null/blank `fingerprint` has NO
            // source span — the SDK admitted the
            // step via reflection or some non-source
            // path. The pre-B3.5 adapter fabricated a
            // StepIR by deriving the name from the
            // `kind` column (e.g. "Step" for a kind
            // column of "Step"). That fabrication is
            // exactly what the S3 mandate forbids:
            // "sin duplicar compiler". When ANY row
            // in the journal has a null/blank
            // fingerprint, the snapshot returns
            // Unknown with a clear reason listing the
            // offending op ids, so the SPA can
            // surface "IR incomplete — N steps have
            // no source mapping" rather than render
            // a fabricated block.
            val missingFingerprintOpIds = mutableListOf<String>()
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
                        // B3.5: a null/blank fingerprint is
                        // the "no source span" signal. We
                        // collect the op id and continue
                        // to the next row — the row is
                        // NOT projected into a StepIR.
                        if (fingerprint.isNullOrBlank()) {
                            missingFingerprintOpIds += opId
                            continue
                        }
                        val name = fingerprint.substringAfterLast('.').removeSuffix("Step").ifBlank { kind }
                        steps += StepIR(
                            opId = opId,
                            name = name,
                            className = fingerprint,
                            capabilities = inferCapabilities(fingerprint, input),
                            inputSchemaPresent = input.isNotBlank(),
                            outputSchemaPresent = rs.getString("output") != null,
                            phase = IRPhase.ADMITTED,
                        )
                        phases += IRPhase.ADMITTED
                    }
                }
            }
            if (missingFingerprintOpIds.isNotEmpty()) {
                val reason = buildString {
                    append("IR Explorer refuses to project ")
                    append(missingFingerprintOpIds.size)
                    append(" step(s) with no source span (no fingerprint in operation_journal): ")
                    append(missingFingerprintOpIds.joinToString(", "))
                    append(". The S3 mandate is 'sin duplicar compiler' — fabricating a")
                    append(" StepIR from the kind column would be a synthetic block.")
                }
                log.warn(reason)
                return IrReadResult.Unknown(reason)
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
            // R3.3 (2026-10-11): refuse to project a synthetic stage when
            // there is no journal data. Pre-R3.3 the adapter returned
            // `StageIR(name="compiled-runtime", steps=emptyList())`
            // even when operation_journal had zero rows — a synthetic
            // placeholder the S3 mandate explicitly forbids.
            if (steps.isEmpty() && phases.isEmpty()) {
                return IrReadResult.Unknown(
                    "IR not projected: run.db has no operation_journal rows and no compilation events. " +
                        "The R3.3 mandate forbids synthesizing a stage in this state; the SDK subprocess " +
                        "has either not started or has not yet written to its journal.",
                )
            }
            val stage = StageIR(name = derivedStageName(steps), steps = steps)
            return IrReadResult.Ok(IrSnapshot(runId = runId, stages = listOf(stage), phasesObserved = phases))
        }
    }

    /**
     * R3.3 (2026-10-11): derive the stage name from real journal data
     * instead of the legacy hardcoded literal "compiled-runtime". The
     * pre-R3.3 code returned the same string regardless of what the
     * SDK subprocess actually emitted, which the S3 mandate forbids
     * ("Eliminar el IR sintético compiled-runtime como IR canónico").
     *
     *  - When the journal has operations, the stage name is anchored
     *    on the first operation's fingerprint (the SDK's own
     *    class-name identity, e.g. `com.example.Pipeline`).
     *  - When the journal is empty but compilation events exist, the
     *    stage name is anchored on the CompilationFinished event.
     *  - When neither is present, return Unknown rather than
     *    fabricating a placeholder stage.
     */
    private fun derivedStageName(steps: List<StepIR>): String {
        if (steps.isNotEmpty()) {
            val first = steps.first()
            return first.className.substringAfterLast('.').ifBlank { first.name }
        }
        return "stage-unknown"
    }

    // Note: translateEvent + parsePayload + deriveStepName were
    // removed in S3 — the diagnostics flow now expands the SDK's
    // nested `diagnostics` array via parseCompilationDiagnostics
    // above. The old single-message placeholders were noise.

    private data class Translated(val severity: DiagnosticSeverity, val message: String, val line: Int?, val column: Int?, val path: String?)

    /**
     * Extract every individual diagnostic from the SDK's
     * CompilationFinished payload's nested `diagnostics` array. Each
     * inner object carries the real source location data:
     *   {"severity":"ERROR","message":"...","line":1,"column":5,"path":"..."}
     *
     * Returns an empty list when the payload is not a JSON object, has no
     * nested `diagnostics` array, the array is empty (clean compile), or
     * no inner entry carries a parseable `message`. Callers MUST treat
     * the empty result as "no individual diagnostic to surface" and may
     * fall back to a phase marker.
     *
     * R3.2 (2026-10-11): structured decoder. Pre-R3.2 the parser
     * scanned the payload with three regexes on a manually-substring'd
     * array body; the S3 mandate ('decodificadores estructurados contra
     * el contrato auténtico del SDK') forbids regex-on-JSON when the
     * payload has a real contract. This implementation walks the
     * kotlinx.serialization JSON tree (the same parser used by
     * studio-http for OpenAPI payloads) so any future contract field
     * adds without a parser rewrite, and a malformed payload fails
     * closed to an empty list instead of leaking a partial regex
     * match.
     */
    private fun parseCompilationDiagnostics(payload: String): List<Translated> {
        val root = runCatching { kotlinx.serialization.json.Json.parseToJsonElement(payload) }
            .getOrNull() as? kotlinx.serialization.json.JsonObject ?: return emptyList()
        val array = root["diagnostics"] as? kotlinx.serialization.json.JsonArray ?: return emptyList()
        return array.mapNotNull { el ->
            val obj = el as? kotlinx.serialization.json.JsonObject ?: return@mapNotNull null
            val msg = (obj["message"] as? kotlinx.serialization.json.JsonPrimitive)?.content
                ?: return@mapNotNull null
            val sev = when ((obj["severity"] as? kotlinx.serialization.json.JsonPrimitive)?.content) {
                "WARNING" -> DiagnosticSeverity.WARNING
                "INFO" -> DiagnosticSeverity.INFO
                else -> DiagnosticSeverity.ERROR
            }
            val line = (obj["line"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toIntOrNull()
            val col = (obj["column"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toIntOrNull()
            val path = (obj["path"] as? kotlinx.serialization.json.JsonPrimitive)?.content
            Translated(sev, msg, line, col, path)
        }
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
