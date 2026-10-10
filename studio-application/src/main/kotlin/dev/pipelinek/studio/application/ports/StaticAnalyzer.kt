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
 * B3.4 (2026-10-10): a single static-analyzer rule.
 *
 * A rule is a small, named, testable predicate over
 * a single line of source. The default analyzer
 * composes a list of rules; each rule is registered
 * with a unique [id] so the wire diagnostic is
 * traceable back to the rule that fired.
 *
 * The contract is "the rule is total and pure": a
 * rule never throws, never calls into the script
 * engine, never reads more than the single line.
 * The canary test in [StaticAnalyzerZeroEffectsTest]
 * proves the analyzer (not the rules) never evals;
 * a rule that introduced a side effect would be
 * caught by the canary in addition to the unit test
 * of the rule itself.
 */
interface StaticAnalyzerRule {
    /** Stable identifier for the rule; surfaced in [Diagnostic.id]. */
    val id: String
    /** Match a single line. Return true if the line is suspicious. */
    fun matches(line: String): Boolean
}

/**
 * B3.4 (2026-10-10): the rule that flags any call
 * to `System.exit` in a user script. The rule is a
 * real anti-pattern: a `System.exit` in a script
 * top level would crash the parent JVM if the
 * analyzer ever eval'd the source. The default
 * analyzer DOES NOT eval; this rule is the canary
 * that surfaces the anti-pattern to the operator
 * before the SDK's runtime eval ever sees it.
 *
 * The rule is exposed as a top-level object (not
 * nested) so a mutation test can construct an
 * analyzer with the rule removed from the list
 * (see [StaticAnalyzerMutationTest]). The mutation
 * proves the diagnostic truly came from this rule
 * — if the rule is removed, the diagnostic
 * disappears.
 */
data class ForbiddenSystemExitRule(
    override val id: String = "forbidden-call-system-exit",
    private val pattern: Regex = Regex("""\bSystem\.exit\s*\("""),
) : StaticAnalyzerRule {
    override fun matches(line: String): Boolean = pattern.containsMatchIn(line)
}

/**
 * B3.2 / B3.4 (2026-10-10): the default, eval-free
 * text-only static analyzer. This is the
 * conservative baseline the studio ships with; a
 * production wiring replaces it with a Detekt-backed
 * impl. The impl never calls into
 * `kotlin.scripting`, never compiles the source,
 * never spawns a child process. The only effect is
 * scanning the source as a string against the
 * supplied [rules] (default: a single rule that
 * flags `System.exit` calls).
 *
 * Why this is enough for B3.2: the 0-effects canary
 * is the discriminator. The default impl's contract
 * is "I only read text"; the canary test proves
 * that contract. A future Detekt-backed impl has
 * to add its own 0-effects test (the child JVM is
 * the isolation boundary).
 *
 * Why this is enough for B3.4: the analyzer takes
 * a list of [StaticAnalyzerRule]s. The default rule
 * list contains the [ForbiddenSystemExitRule]; a
 * mutation test can construct an analyzer with an
 * empty rule list and assert the diagnostic
 * disappears. The mutation is the AAT-AN-001
 * discriminator.
 */
class TextOnlyStaticAnalyzer(
    private val rules: List<StaticAnalyzerRule> = listOf(ForbiddenSystemExitRule()),
    private val analyzerVersion: String = "studio-static-analyzer:1.0.0",
) : StaticAnalyzerPort {
    override fun analyze(source: String): List<Diagnostic> {
        val out = mutableListOf<Diagnostic>()
        for ((idx, line) in source.lines().withIndex()) {
            val lineNumber = idx + 1
            for (rule in rules) {
                if (rule.matches(line)) {
                    out += Diagnostic(
                        id = "static-analyzer:${rule.id}:$lineNumber",
                        severity = DiagnosticSeverity.ERROR,
                        phase = DiagnosticPhase.DETEKT,
                        message = "${rule.id}: ${line.trim()}",
                        source = analyzerVersion,
                        line = lineNumber,
                        column = (line.indexOf("System.exit") + 1).takeIf { it > 0 },
                        path = "snapshot.pipeline.kts",
                    )
                }
            }
        }
        return out
    }

    companion object {
        /**
         * Backward-compat default instance for the
         * pre-B3.4 callers. The single-rule default
         * is the [ForbiddenSystemExitRule].
         */
        val Default: StaticAnalyzerPort = TextOnlyStaticAnalyzer()
    }
}
