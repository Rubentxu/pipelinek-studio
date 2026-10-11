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
    /**
     * B3.0 (2026-10-10): toolchain provenance. The
     * analyzer (Detekt / kotlinc / etc.) that
     * produced this diagnostic. The SPA can use
     * the version + digest to render a "diagnostic
     * produced by Detekt 1.23.1 @ sha256:..." line
     * in the diagnostic panel. Defaults to null
     * when the source did not provide provenance
     * (e.g. the SDK adapter for an older SDK that
     * did not capture it); the studio never
     * invents a value.
     */
    val toolchainVersion: String? = null,
    val toolchainDigest: String? = null,
    /**
     * R3.1 (2026-10-11): source digest for the bytes the
     * diagnostic was emitted against. Anchors the diagnostic
     * to the exact source snapshot; the SPA uses it to mark
     * diagnostics stale after a snapshot edit. Defaults to
     * null when the source did not carry one (e.g. an SDK
     * that pre-dates source-digest capture); Studio never
     * invents a value.
     *
     * Carries the same Sha256 value class as
     * [dev.pipeline.source.SourceSnapshot.sha256] so a caller
     * can compare against the run's source-snapshot digest
     * without converting hex strings.
     */
    val sourceDigest: dev.pipelinek.studio.domain.Sha256? = null,
)

sealed interface DiagnosticsReadResult {
    data class Ok(val diagnostics: List<Diagnostic>) : DiagnosticsReadResult
    data class Unknown(val reason: String) : DiagnosticsReadResult
}

/** S3 E3.1 — normalizes compilation/admission/Detekt diagnostics. */
interface DiagnosticsReadPort {
    fun diagnostics(runId: RunId): DiagnosticsReadResult
}

/**
 * B3.0 / B3.3 (2026-10-10): static-analysis capability
 * port. The studio's S3 deliverable is "Detekt real +
 * IR de fuente upstream, con evidencia de lo que
 * funciona/lo que no del LSP; no liberar función
 * unsafe etiquetada compile-only." The honest
 * UNSUPPORTED path is the spine of that mandate:
 * when the studio cannot do static analysis
 * safely, it MUST say so on the wire, not return
 * an empty diagnostic list (which the SPA would
 * render as "no problems").
 *
 * The port is intentionally narrow: a single
 * [capability] call. The default impl is
 * [Unavailable] with the documented reason
 * "not configured". A production wiring overrides
 * the default with [Available] when the
 * Detekt child JVM is reachable + the plugin-lock
 * digest matches. The [B3.3] test asserts the
 * env-var flip (STUDIO_STATIC_ANALYZE_DISABLED=1
 * → Unavailable("disabled by env")).
 */
/** B3.0 / B3.3: static-analysis capability port (fun interface for SAM). */
fun interface StaticAnalyzeCapabilityPort {
    fun capability(): StaticAnalyzeCapability
}

sealed interface StaticAnalyzeCapability {
    /**
     * Static analysis is available. The version
     * + digest are the analyzer's identity
     * (Detekt 1.23.1 + the plugin-set lock digest).
     * The SPA can show a green "Detekt enabled"
     * badge with the identity.
     */
    data class Available(
        val analyzerVersion: String,
        val analyzerDigest: String,
        val rulesetVersion: String? = null,
    ) : StaticAnalyzeCapability

    /**
     * Static analysis is NOT available. The
     * [reason] is a human-readable string the
     * SPA can surface in the diagnostic panel
     * (e.g. "Detekt 1.23.1 not on classpath",
     * "STUDIO_STATIC_ANALYZE_DISABLED=1 is set",
     * "Detekt child JVM spawn failed").
     */
    data class Unavailable(val reason: String) : StaticAnalyzeCapability
}
