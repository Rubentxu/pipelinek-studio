package dev.pipelinek.studio.cert.domain

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * C0.4 (2026-10-12): the aggregator tests.
 *
 * D-05 fail-closed: a report that lacks required data
 * must NEVER produce PASS. The aggregator is a pure
 * function: it does not run any case. The tests pin
 * every rule in the class doc of [CertReport].
 */
class CertReportTest {

    private val satisfiedEnvelope = IntegrityEnvelope(
        tagMatchesCommit = true,
        commitMatchesRelease = true,
        artifactDigestsMatch = true,
        sdkVersionMatchesLock = true,
        studioVersionPinned = true,
        // R5.2 (2026-10-12): the 6th integrity field
        // (runtime↔manifest) is required when the cert
        // has the inputs in hand. Tests that construct a
        // "satisfied" envelope must declare it true.
        runtimeMatchesManifest = true,
    )

    private fun passedCase(id: String, name: String = "ok"): CaseExecution {
        val cid = CaseId(id)
        return CaseExecution(
            caseId = cid,
            name = name,
            verdict = Verdict.Passed,
            startedAt = 0,
            durationMs = 1,
            assertions = listOf(
                Assertion(
                    name = "exists",
                    kind = Assertion.Assert.PathExists,
                    expected = "/x",
                    actual = "/x",
                    held = true,
                ),
            ),
            evidenceRefs = listOf(
                EvidenceRef(kind = EvidenceRef.Kind.ResponseBody, path = "resp/$id.json"),
            ),
        )
    }

    private fun failedCase(id: String): CaseExecution =
        passedCase(id).copy(
            verdict = Verdict.Failed(Failure.DigestMismatch),
            errorMessage = "x",
        )

    private fun blockedCase(id: String): CaseExecution =
        passedCase(id).copy(verdict = Verdict.Blocked(Failure.RuntimeUnavailable))

    private fun notMeasuredCase(id: String): CaseExecution =
        passedCase(id).copy(verdict = Verdict.NotMeasured)

    private fun baseReport(cases: List<CaseExecution> = listOf(passedCase("UAT-001"))): CertReport =
        CertReport(
            studioTag = "v1.0.0-rc.16",
            studioCommit = "a".repeat(40),
            studioVersion = "1.0.0-rc.16",
            sdkVersion = "0.48.0-rc1",
            sdkDigest = "b".repeat(64),
            startedAt = 0,
            finishedAt = 1000,
            cases = cases,
            integrity = satisfiedEnvelope,
            environmentFingerprint = "env-1",
        )

    @Test
    fun `D-05 rule 1 - empty report cannot be PASS`() {
        val r = baseReport(emptyList())
        val v = r.verdict()
        assertTrue(v is Verdict.Failed, "empty report must be FAIL, got $v")
        assertEquals(Failure.ReportIncomplete, (v as Verdict.Failed).reason)
    }

    @Test
    fun `D-05 rule 2 - missing required case is FAIL`() {
        val r = baseReport(cases = listOf(passedCase("UAT-001")))
        val v = r.verdict(requiredCases = listOf(CaseId("UAT-001"), CaseId("UAT-002")))
        assertTrue(v is Verdict.Failed)
        assertEquals(Failure.CaseMissing, (v as Verdict.Failed).reason)
    }

    @Test
    fun `D-05 rule 3 - duplicate case id is FAIL`() {
        val r = baseReport(cases = listOf(passedCase("UAT-001"), passedCase("UAT-001")))
        val v = r.verdict()
        assertTrue(v is Verdict.Failed)
        assertEquals(Failure.DuplicateCaseId, (v as Verdict.Failed).reason)
    }

    @Test
    fun `D-05 rule 4 - unsatisfied integrity envelope is FAIL`() {
        val r = baseReport().copy(integrity = satisfiedEnvelope.copy(sdkVersionMatchesLock = false))
        val v = r.verdict()
        assertTrue(v is Verdict.Failed)
        assertEquals(Failure.VersionMismatch, (v as Verdict.Failed).reason)
    }

    @Test
    fun `D-05 rule 5 - any case FAIL yields overall FAIL`() {
        val r = baseReport(cases = listOf(passedCase("UAT-001"), failedCase("UAT-002")))
        val v = r.verdict()
        assertTrue(v is Verdict.Failed)
        assertEquals(Failure.DigestMismatch, (v as Verdict.Failed).reason)
    }

    @Test
    fun `D-05 rule 7 - required case BLOCKED yields overall FAIL`() {
        val r = baseReport(cases = listOf(passedCase("UAT-001"), blockedCase("UAT-002")))
        val v = r.verdict(requiredCases = listOf(CaseId("UAT-002")))
        assertTrue(v is Verdict.Failed)
        assertEquals(Failure.CaseMissing, (v as Verdict.Failed).reason)
    }

    @Test
    fun `D-05 rule 8 - required case NOT_MEASURED yields overall FAIL`() {
        val r = baseReport(cases = listOf(passedCase("UAT-001"), notMeasuredCase("UAT-002")))
        val v = r.verdict(requiredCases = listOf(CaseId("UAT-002")))
        assertTrue(v is Verdict.Failed)
    }

    @Test
    fun `D-05 rule 6 - optional case BLOCKED does NOT block overall PASS`() {
        val r = baseReport(cases = listOf(passedCase("UAT-001"), blockedCase("UAT-002")))
        // UAT-002 is optional (not in requiredCases); overall must be PASS
        val v = r.verdict(requiredCases = listOf(CaseId("UAT-001")))
        assertEquals(Verdict.Passed, v)
    }

    @Test
    fun `D-05 rule 9 - all required cases PASS + envelope satisfied = PASS`() {
        val r = baseReport(
            cases = listOf(
                passedCase("UAT-001"),
                passedCase("UAT-002"),
                passedCase("UAT-013"),
            ),
        )
        val v = r.verdict(
            requiredCases = listOf(CaseId("UAT-001"), CaseId("UAT-002"), CaseId("UAT-013")),
        )
        assertEquals(Verdict.Passed, v)
    }

    @Test
    fun `D-05 CaseExecution rejects PASS with no assertions`() {
        val cid = CaseId("UAT-001")
        assertFailsWith<IllegalArgumentException> {
            CaseExecution(
                caseId = cid,
                name = "x",
                verdict = Verdict.Passed,
                startedAt = 0,
                durationMs = 1,
                assertions = emptyList(),
                evidenceRefs = listOf(EvidenceRef(EvidenceRef.Kind.ResponseBody, "p")),
            )
        }
    }

    @Test
    fun `D-05 CaseExecution rejects PASS with no evidence refs`() {
        val cid = CaseId("UAT-001")
        assertFailsWith<IllegalArgumentException> {
            CaseExecution(
                caseId = cid,
                name = "x",
                verdict = Verdict.Passed,
                startedAt = 0,
                durationMs = 1,
                assertions = listOf(
                    Assertion("a", Assertion.Assert.PathExists, "/x", "/x", true),
                ),
                evidenceRefs = emptyList(),
            )
        }
    }

    @Test
    fun `D-05 CaseExecution accepts PASS with assertions + evidence`() {
        val cid = CaseId("UAT-001")
        CaseExecution(
            caseId = cid,
            name = "x",
            verdict = Verdict.Passed,
            startedAt = 0,
            durationMs = 1,
            assertions = listOf(
                Assertion("a", Assertion.Assert.PathExists, "/x", "/x", true),
            ),
            evidenceRefs = listOf(EvidenceRef(EvidenceRef.Kind.ResponseBody, "p")),
        )
    }

    @Test
    fun `D-05 CaseExecution accepts FAIL with empty assertions (failure case is the evidence)`() {
        val cid = CaseId("UAT-001")
        CaseExecution(
            caseId = cid,
            name = "x",
            verdict = Verdict.Failed(Failure.ArtifactNotFound),
            startedAt = 0,
            durationMs = 1,
            assertions = emptyList(),
            evidenceRefs = emptyList(),
        )
    }
}
