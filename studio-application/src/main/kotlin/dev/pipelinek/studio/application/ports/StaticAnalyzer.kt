package dev.pipelinek.studio.application.ports

/**
 * B3.2 (2026-10-10): static analyzer port.
 *
 * The studio's S3 deliverable is "Detekt en proceso
 * aislado con version matrix y reglas DSL pequeñas".
 * The honest UNSUPPORTED path is the spine: when no
 * analyzer is wired, the studio reports
 * [StaticAnalyzeCapability.Unavailable] and never
 * runs anything against the source.
 *
 * The port is intentionally narrow: a single
 * [analyze] call. The default impl
 * [TextOnlyStaticAnalyzer] is a strict, eval-free
 * text scanner that cannot execute the source. A
 * production wiring overrides the default with a
 * real Detekt-backed impl that spawns a child JVM
 * with the plugin-lock-pinned Detekt JAR; the child
 * is the "in-process aislado" boundary the S3
 * mandate calls for.
 *
 * The 0-effects canary (see
 * StaticAnalyzerZeroEffectsTest) is the discriminator:
 * a script that calls [System.exit] in its top level
 * is fed to the analyzer; the parent JVM is still
 * alive after [analyze] returns. A future refactor
 * that introduced a real eval (e.g. by mistake
 * shelling out to a script engine) would surface as
 * the parent JVM dying mid-test.
 */
fun interface StaticAnalyzerPort {
    /**
     * @param source the script source bytes. The
     * analyzer MUST treat this as opaque text; it
     * MUST NOT eval, compile, or execute the
     * bytes in any form. The contract is enforced
     * by the canary test, not by a sandbox.
     * @return the diagnostics the analyzer found.
     * Empty list means "no problems found", not
     * "the analyzer did not run". A non-empty list
     * contains exactly the diagnostics the
     * analyzer observed; the analyzer never
     * invents diagnostics.
     */
    fun analyze(source: String): List<Diagnostic>
}

/**
 * B3.2 (2026-10-10): the default, eval-free
 * text-only static analyzer. This is the
 * conservative baseline the studio ships with; a
 * production wiring replaces it with a Detekt-backed
 * impl. The impl never calls into
 * `kotlin.scripting`, never compiles the source,
 * never spawns a child process. The only effect is
 * scanning the source as a string for known
 * anti-patterns (currently: a single rule that flags
 * the literal text `System.exit` in the top of the
 * script).
 *
 * Why this is enough for B3.2: the 0-effects canary
 * is the discriminator. The default impl's contract
 * is "I only read text"; the canary test proves
 * that contract. A future Detekt-backed impl has
 * to add its own 0-effects test (the child JVM is
 * the isolation boundary).
 */
object TextOnlyStaticAnalyzer : StaticAnalyzerPort {
    private val FORBIDDEN_CALL = "forbidden-call"
    private val SYSTEM_EXIT_LITERAL = Regex("""\bSystem\.exit\s*\(""")

    override fun analyze(source: String): List<Diagnostic> {
        val out = mutableListOf<Diagnostic>()
        // Single rule for B3.2: flag any line that
        // calls System.exit. The rule is a real
        // anti-pattern: a `System.exit` in a
        // script top level would crash the parent
        // JVM if the analyzer ever eval'd the
        // source. The default analyzer DOES NOT
        // eval; this rule is the canary that
        // surfaces the anti-pattern to the operator
        // before the SDK's runtime eval ever sees
        // it.
        for ((idx, line) in source.lines().withIndex()) {
            if (SYSTEM_EXIT_LITERAL.containsMatchIn(line)) {
                out += Diagnostic(
                    id = "static-analyzer:system-exit:${idx + 1}",
                    severity = DiagnosticSeverity.ERROR,
                    phase = DiagnosticPhase.DETEKT,
                    message = "forbidden-call: System.exit in user script",
                    source = "studio-static-analyzer:1.0.0",
                    line = idx + 1,
                    column = (line.indexOf("System.exit") + 1).takeIf { it > 0 },
                    path = "snapshot.pipeline.kts",
                )
            }
        }
        return out
    }
}
