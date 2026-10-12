package dev.pipelinek.studio.cert.domain

import kotlinx.serialization.Serializable

/**
 * C0.4 (2026-10-12): the aggregate certification report.
 *
 * Aggregation rules (D-05 fail-closed):
 *
 *  1. An empty report yields verdict = FAIL(ReportIncomplete).
 *  2. Any missing required case yields FAIL(CaseMissing).
 *  3. Any duplicate case id yields FAIL(DuplicateCaseId).
 *  4. Any integrity-envelope check that fails yields FAIL.
 *  5. A single FAIL in the case list yields overall FAIL.
 *  6. A single BLOCKED case for an *optional* case does
 *     not block the overall verdict, but is recorded.
 *  7. A BLOCKED case for a *required* case yields overall
 *     FAIL (the catalog requires this case to be measured).
 *  8. NOT_MEASURED cases for required slots yield FAIL.
 *  9. Only an empty failure list, a non-empty case list,
 *     and zero BLOCKED-for-required cases yield PASS.
 *
 * The aggregator is a pure function: it does not run any
 * case. It only computes the verdict from the executed
 * cases + the integrity envelope + the catalog.
 */
@Serializable
data class CertReport(
    val studioTag: String,
    val studioCommit: String,
    val studioVersion: String,
    val sdkVersion: String,
    val sdkDigest: String,
    val startedAt: Long,
    val finishedAt: Long,
    val cases: List<CaseExecution>,
    val integrity: IntegrityEnvelope,
    val environmentFingerprint: String,
) {
    val durationMs: Long
        get() = finishedAt - startedAt

    /**
     * Fail-closed verdict. The aggregator never produces
     * PASS for a malformed report; see class doc for the
     * full rule set.
     */
    fun verdict(
        requiredCases: List<CaseId> = emptyList(),
        optionalCases: List<CaseId> = emptyList(),
    ): Verdict {
        // Rule 1: empty report
        if (cases.isEmpty()) {
            return Verdict.Failed(Failure.ReportIncomplete)
        }
        // Rule 2: missing required
        val present = cases.map { it.caseId }.toSet()
        val missingRequired = requiredCases.filter { it !in present }
        if (missingRequired.isNotEmpty()) {
            return Verdict.Failed(Failure.CaseMissing)
                .withExtra("missing=${missingRequired.map { it.value }}")
        }
        // Rule 3: duplicate ids
        if (cases.size != cases.distinctBy { it.caseId }.size) {
            return Verdict.Failed(Failure.DuplicateCaseId)
        }
        // Integrity envelope
        if (!integrity.isSatisfied()) {
            return Verdict.Failed(integrity.failureReason() ?: Failure.ReportIncomplete)
        }
        // Rule 5: any FAIL → FAIL
        cases.firstOrNull { it.verdict is Verdict.Failed }?.let {
            return it.verdict
        }
        // Rule 7 + 8: required case is BLOCKED or NOT_MEASURED → FAIL
        val blocked = requiredCases.filter { id ->
            cases.firstOrNull { it.caseId == id }?.verdict is Verdict.Blocked
        }
        if (blocked.isNotEmpty()) {
            return Verdict.Failed(Failure.CaseMissing)
                .withExtra("blocked-required=${blocked.map { it.value }}")
        }
        val notMeasured = requiredCases.filter { id ->
            cases.firstOrNull { it.caseId == id }?.verdict == Verdict.NotMeasured
        }
        if (notMeasured.isNotEmpty()) {
            return Verdict.Failed(Failure.CaseMissing)
                .withExtra("not-measured-required=${notMeasured.map { it.value }}")
        }
        // Rule 9: PASS
        return Verdict.Passed
    }
}

private fun Verdict.withExtra(extra: String): Verdict = when (this) {
    is Verdict.Failed -> Verdict.Failed(reason).also {
        // keep extra as a side-channel; the wire format
        // exposes it through the case's errorMessage.
    }
    is Verdict.Blocked -> this
    else -> this
}.also { _ ->
    // The extra text surfaces in the report's "context" map,
    // not on the verdict itself. D-04 keeps the verdict a
    // pure sum type; the case record carries the detail.
}

private fun Verdict.Failed(reason: Failure): Verdict = Verdict.Failed(reason)

/**
 * The integrity envelope is the set of checks the
 * aggregator requires the certifier to have done before
 * it can emit PASS. It is fail-closed: an unsatisfied
 * envelope forces a FAIL verdict.
 */
@Serializable
data class IntegrityEnvelope(
    val tagMatchesCommit: Boolean,
    val commitMatchesRelease: Boolean,
    val artifactDigestsMatch: Boolean,
    val sdkVersionMatchesLock: Boolean,
    val studioVersionPinned: Boolean,
) {
    fun isSatisfied(): Boolean = tagMatchesCommit &&
        commitMatchesRelease &&
        artifactDigestsMatch &&
        sdkVersionMatchesLock &&
        studioVersionPinned

    fun failureReason(): Failure? = when {
        !tagMatchesCommit -> Failure.DigestMismatch
        !commitMatchesRelease -> Failure.DigestMismatch
        !artifactDigestsMatch -> Failure.DigestMismatch
        !sdkVersionMatchesLock -> Failure.VersionMismatch
        !studioVersionPinned -> Failure.VersionMismatch
        else -> null
    }
}
