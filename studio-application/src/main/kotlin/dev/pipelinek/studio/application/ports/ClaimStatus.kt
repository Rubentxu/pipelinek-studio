package dev.pipelinek.studio.application.ports

/**
 * UAT-046 — Agent prompt genera escenario sin claims de prueba →
 *           Sin ejecutar: muestra NOT_EXECUTED; después distingue
 *           PASS/FAIL según test.
 *
 * A "test claim" is any source construct that asserts an expected
 * outcome: a step whose kind contains 'assert', 'shouldEqual',
 * 'expect', or any non-trivial predicate expression. Scenarios
 * with claims can be unambiguously marked PASS/FAIL when they
 * run. Scenarios without claims have no test authority, so the
 * studio records the run as `NOT_EXECUTED` until the user adds
 * a claim and explicitly re-runs.
 *
 * The detector is intentionally conservative: a step whose name
 * ONLY contains 'echo' or 'pwd' is NOT a claim (no assertion).
 * A step whose name contains 'assert' / 'shouldEqual' / 'expect'
 * is a claim. A source with no steps at all is also NOT_EXECUTED
 * (the scenario is empty).
 */
enum class ClaimStatus {
    /** The source declares an assertion (assert/shouldEqual/expect). */
    CLAIM_VERIFIED,

    /** The source has no test authority — recorded as not yet executed. */
    NOT_EXECUTED,
}

object ClaimStatusDetector {
    private val CLAIM_PATTERNS = listOf(
        Regex("""\bassert[A-Z_]?"""),       // assert, assertEquals, assertTrue, ...
        Regex("""\bshould(Equal|Be|Have|Not|Contain|Throw)?"""),  // shouldEqual, shouldBe, ...
        Regex("""\bexpect[A-Z_]?"""),       // expect, expectEquals, ...
    )

    /**
     * Compute the claim status for a snapshot source. The source
     * is the .pipeline.kts bytes the user sealed; we inspect them
     * for assertion-shaped step kinds. Returns CLAIM_VERIFIED if
     * any step kind matches a claim pattern, NOT_EXECUTED otherwise.
     */
    fun detect(sourceBytes: ByteArray?): ClaimStatus {
        if (sourceBytes == null || sourceBytes.isEmpty()) return ClaimStatus.NOT_EXECUTED
        val text = String(sourceBytes, Charsets.UTF_8)
        for (p in CLAIM_PATTERNS) {
            if (p.containsMatchIn(text)) return ClaimStatus.CLAIM_VERIFIED
        }
        return ClaimStatus.NOT_EXECUTED
    }
}
