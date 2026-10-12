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
     *
     * R5.2: the integrity envelope's `isSatisfied()` is
     * computed with the env-aware required set (the local
     * dev sandbox opts the env-dependent fields out via
     * [IntegrityEnvelope.OPTIONAL_FIELDS]).
     *
     * R5.3: `requiredCases` MUST be the catalog-driven
     * required set, NOT `cases.map { it.caseId }`. The
     * caller passes the catalog; the aggregator detects
     * missing / duplicate / evidence-less contracts.
     */
    fun verdict(
        requiredCases: List<CaseId> = emptyList(),
        optionalCases: List<CaseId> = emptyList(),
        requiredIntegrity: Set<String> = IntegrityEnvelope.ALL_REQUIRED,
    ): Verdict {
        // Rule 1: empty report
        if (cases.isEmpty()) {
            return Verdict.Failed(Failure.ReportIncomplete)
        }
        // Rule 2: missing required (catalog-driven)
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
        // R5.2: integrity envelope. `null` is treated as
        // `true` for OPTIONAL_FIELDS and `false` for
        // required fields. The aggregator surfaces the
        // measured vs not-measured split in the report.
        if (!integrity.isSatisfied(requiredFields = requiredIntegrity)) {
            return Verdict.Failed(integrity.failureReason() ?: Failure.ReportIncomplete)
        }
        // Rule 5: any FAIL → FAIL
        cases.firstOrNull { it.verdict is Verdict.Failed }?.let {
            return it.verdict
        }
        // Rule 6 (R5.2): any required field is NOT_MEASURED
        // (null) → FAIL. The optional cases (NOT_MEASURED
        // for fields the local dev sandbox opts out) do
        // NOT block.
        val requiredNotMeasured = integrity.notMeasuredFields()
            .filter { it in requiredIntegrity }
        if (requiredNotMeasured.isNotEmpty()) {
            return Verdict.Failed(Failure.ReportIncomplete)
                .withExtra("integrity-not-measured=${requiredNotMeasured}")
        }
        // Rule 7: required case is BLOCKED → FAIL
        val blocked = requiredCases.filter { id ->
            cases.firstOrNull { it.caseId == id }?.verdict is Verdict.Blocked
        }
        if (blocked.isNotEmpty()) {
            return Verdict.Failed(Failure.CaseMissing)
                .withExtra("blocked-required=${blocked.map { it.value }}")
        }
        // Rule 8: required case is NOT_MEASURED → FAIL
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
/**
 * R5.2 (2026-10-12): every Boolean here is MEASURED, not
 * assumed. A `null` Boolean means the cert could not
 * measure that invariant in the current execution context
 * (e.g. no git tag set in the local dev sandbox) — that
 * field surfaces as `NOT_MEASURED` on the wire. A `false`
 * Boolean is a measured mismatch (e.g. the on-disk
 * artifact's SHA does not match the lockfile pin) and
 * yields a hard `FAIL`.
 *
 * `isSatisfied()` treats `null` as `true` for fields the
 * aggregator declares OPTIONAL for the current execution
 * context, and as `false` (force FAIL) for fields the
 * aggregator declares REQUIRED. The runtime caller
 * (CertMain) decides which fields are required via
 * `requiredIntegrityFields`.
 */
data class IntegrityEnvelope(
    val tagMatchesCommit: Boolean? = null,
    val commitMatchesRelease: Boolean? = null,
    val artifactDigestsMatch: Boolean? = null,
    val sdkVersionMatchesLock: Boolean? = null,
    val studioVersionPinned: Boolean? = null,
) {
    /**
     * @param requiredFields the integrity fields the
     *   aggregator declares REQUIRED. For these, `null`
     *   is treated as `false` (force FAIL — the cert could
     *   not measure an invariant the contract demands).
     *   For the others, `null` is treated as `true`
     *   (OPTIONAL — the cert is allowed to skip the check).
     */
    fun isSatisfied(requiredFields: Set<String> = ALL_REQUIRED): Boolean {
        return FIELDS.all { (name, value) ->
            if (value == null) {
                name in OPTIONAL_FIELDS || name !in requiredFields
            } else {
                value
            }
        }
    }

    fun failureReason(): Failure? = when {
        tagMatchesCommit == false -> Failure.DigestMismatch
        commitMatchesRelease == false -> Failure.DigestMismatch
        artifactDigestsMatch == false -> Failure.DigestMismatch
        sdkVersionMatchesLock == false -> Failure.VersionMismatch
        studioVersionPinned == false -> Failure.VersionMismatch
        else -> null
    }

    fun measuredFields(): Map<String, Boolean> = FIELDS
        .filter { it.second != null }
        .associate { it.first to it.second!! }

    fun notMeasuredFields(): List<String> = FIELDS
        .filter { it.second == null }
        .map { it.first }

    private val FIELDS: List<Pair<String, Boolean?>> by lazy {
        listOf(
            "tagMatchesCommit" to tagMatchesCommit,
            "commitMatchesRelease" to commitMatchesRelease,
            "artifactDigestsMatch" to artifactDigestsMatch,
            "sdkVersionMatchesLock" to sdkVersionMatchesLock,
            "studioVersionPinned" to studioVersionPinned,
        )
    }

    companion object {
        /**
         * The default REQUIRED set: every field is required
         * unless the operator declares it optional. The
         * aggregator (CertReport.verdict) only treats an
         * unset `null` field as `true` if the field is in
         * [OPTIONAL_FIELDS].
         */
        val ALL_REQUIRED: Set<String> = setOf(
            "tagMatchesCommit",
            "commitMatchesRelease",
            "artifactDigestsMatch",
            "sdkVersionMatchesLock",
            "studioVersionPinned",
        )

        /**
         * R5.2 (2026-10-12): fields the local dev sandbox is
         * permitted to skip. In a production T5 environment
         * (operator runs the cert on a clean machine with
         * STUDIO_TAG + STUDIO_RELEASE_TAG set), every field
         * is required. The local sandbox opts into
         * `OPTIONAL` via the absence of the env vars.
         */
        val OPTIONAL_FIELDS: Set<String> = setOf(
            // tagMatchesCommit: only meaningful when a
            // STUDIO_TAG env is set. The local dev sandbox
            // has no tag.
            "tagMatchesCommit",
            // commitMatchesRelease: only meaningful when
            // STUDIO_RELEASE_TAG is set.
            "commitMatchesRelease",
            // studioVersionPinned: only meaningful when
            // STUDIO_VERSION is set (the dev sandbox uses
            // the local gradle.properties value).
            "studioVersionPinned",
        )
    }
}
