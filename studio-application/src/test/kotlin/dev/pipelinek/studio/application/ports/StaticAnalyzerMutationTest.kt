package dev.pipelinek.studio.application.ports

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * B3.4 (2026-10-10): the mutation test for the
 * custom DSL rule.
 *
 * The S3 mandate says "mutation tests de reglas
 * custom". A mutation test asserts: when the rule
 * is removed (or disabled), the verdict for a
 * matching source flips. The test here is the
 * minimal mutation: construct the analyzer with
 * the [ForbiddenSystemExitRule] removed from the
 * rule list, then feed the same source that the
 * enabled analyzer flagged. The mutation passes
 * when the source no longer produces a diagnostic
 * (the verdict flipped from "diagnostic" to "no
 * diagnostic").
 *
 * The test ALSO asserts the positive case (rule
 * enabled) so a regression that flipped the
 * rule's polarity (e.g. `!pattern.containsMatchIn`
 * instead of `pattern.containsMatchIn`) is caught.
 *
 * The pair of tests — enabled-analyzer-flags,
 * disabled-analyzer-does-not-flag — is the
 * AAT-AN-001 discriminator.
 */
class StaticAnalyzerMutationTest {

    @Test
    fun `B3_4 mutation — rule enabled, source flagged`() {
        val analyzer = TextOnlyStaticAnalyzer(
            rules = listOf(ForbiddenSystemExitRule()),
        )
        val source = "val exitCode = System.exit(0)"
        val diags = analyzer.analyze(source)
        assertTrue(
            diags.any { it.message.contains("forbidden-call-system-exit") },
            "expected the rule to flag System.exit, got: ${diags.map { it.message }}",
        )
    }

    @Test
    fun `B3_4 mutation — rule removed, source NOT flagged (verdict flips)`() {
        // The mutation: an analyzer with NO rules.
        // The same source that the enabled analyzer
        // flagged must now produce an empty
        // diagnostic list. The verdict flip is the
        // AAT-AN-001 discriminator.
        val analyzer = TextOnlyStaticAnalyzer(rules = emptyList())
        val source = "val exitCode = System.exit(0)"
        val diags = analyzer.analyze(source)
        assertEquals(
            emptyList(),
            diags,
            "mutation did NOT flip the verdict: rule removed but the analyzer still produced ${diags.map { it.message }}",
        )
    }

    @Test
    fun `B3_4 mutation — only the matching rule fires (no cross-talk)`() {
        // A second rule that does NOT match the
        // source. The forbidden-call rule still
        // fires; the no-match rule does not. The
        // test asserts the analyzer reports exactly
        // one diagnostic, attributable to the
        // matching rule.
        val noMatchRule = object : StaticAnalyzerRule {
            override val id: String = "no-match"
            override fun matches(line: String): Boolean = line.contains("definitely-not-in-the-source")
        }
        val analyzer = TextOnlyStaticAnalyzer(
            rules = listOf(ForbiddenSystemExitRule(), noMatchRule),
        )
        val source = "val exitCode = System.exit(0)"
        val diags = analyzer.analyze(source)
        assertEquals(1, diags.size, "expected exactly one diagnostic, got ${diags.map { it.message }}")
        assertTrue(
            diags[0].message.contains("forbidden-call-system-exit"),
            "expected the forbidden-call rule to fire, got: ${diags[0].message}",
        )
    }

    @Test
    fun `B3_4 mutation — rule id is unique per rule`() {
        val rule1 = ForbiddenSystemExitRule(id = "rule-one")
        val rule2 = ForbiddenSystemExitRule(id = "rule-two")
        assertFalse(
            rule1.id == rule2.id,
            "rule ids must be unique so the wire diagnostic is traceable back to the rule that fired",
        )
    }

    @Test
    fun `B3_4 mutation — ForbiddenSystemExitRule matches System_exit but not exit`() {
        // Boundary: a partial token like `Runtime.exit`
        // (which is not a thing) must NOT match.
        // The rule's regex is word-bounded to
        // `System.exit` exactly; a future refactor
        // that loosened the regex (e.g. removed the
        // word boundary) would surface here.
        val rule = ForbiddenSystemExitRule()
        assertTrue(rule.matches("val x = System.exit(0)"))
        assertTrue(rule.matches("System.exit(0)"))
        // Plain `exit` (no System. prefix) must
        // not match — the rule is named for the
        // `System.exit` anti-pattern, not exit-by-
        // any-name.
        assertFalse(rule.matches("val x = exit"))
        assertFalse(rule.matches("Runtime.exit(0)"))
    }
}
