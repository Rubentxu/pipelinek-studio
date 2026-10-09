package dev.pipelinek.studio.application.ports

import dev.pipelinek.studio.domain.RunId

/**
 * Diagnostic model per S3 (Diagnostics, Detekt & IR Explorer) ROADMAP §"E3.1
 * Diagnosticos normalizados por fases y fuente, markers Monaco".
 *
 * Studio aggregates diagnostics from MULTIPLE sources: compilation (RunReadPort),
 * admission (StaticManifestInspector trust class), Detekt (rules S3), and the
 * SDK's own diagnostic records persisted in run.db events.
 *
 * Normalized Diagnostic — never RAW upstream string — so Monaco markers render
 * consistently regardless of source. `actions` lists existing affordances Studio
 * can offer; absent affordances are NOT invented.
 */

enum class DiagnosticSeverity { ERROR, WARNING, INFO }
enum class DiagnosticPhase { COMPILATION, ADMISSION, EXECUTION, RECOVERY, DETEKT, OTHER }

data class Diagnostic(
    val id: String,
    val severity: DiagnosticSeverity,
    val phase: DiagnosticPhase,
    val message: String,
    val source: String,
    val line: Int? = null,
    val column: Int? = null,
    val path: String? = null,
    val runId: RunId? = null,
    val actions: List<String> = emptyList(),
)

sealed interface DiagnosticsReadResult {
    data class Ok(val diagnostics: List<Diagnostic>) : DiagnosticsReadResult
    data class Unknown(val reason: String) : DiagnosticsReadResult
}

/** S3 E3.1 — normalizes compilation/admission/Detekt diagnostics. */
interface DiagnosticsReadPort {
    fun diagnostics(runId: RunId): DiagnosticsReadResult
}
