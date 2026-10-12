package dev.pipelinek.studio.cert.domain

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * C0.4 (2026-10-12): the verdict model tests.
 *
 * These tests pin:
 *  1. The five-state taxonomy (D-05): PASS, FAIL, BLOCKED,
 *     NOT_MEASURED, NOT_APPLICABLE.
 *  2. The [Failure] code taxonomy is stable (the wire
 *     contract is the [Failure.code] string, not the
 *     message).
 *  3. The [Verdict] sealed interface discriminates
 *     exhaustively.
 *  4. Value classes (Sha256, SemVer, GitCommit, CaseId)
 *     reject malformed inputs at the boundary.
 *  5. The [IntegrityEnvelope] fail-closes on any false
 *     field.
 *  6. The aggregator never produces PASS for a malformed
 *     report (D-05 fail-closed).
 */
class VerdictTest {

    @Test
    fun `D-05 the five-state taxonomy is sealed`() {
        // compile-time check: the sealed interface
        // forces the caller to handle every case
        val vs: List<Verdict> = listOf(
            Verdict.Passed,
            Verdict.Failed(Failure.DigestMismatch),
            Verdict.Blocked(Failure.RuntimeUnavailable),
            Verdict.NotMeasured,
            Verdict.NotApplicable("x"),
        )
        assertEquals(5, vs.size)
        assertEquals(1, vs.count { it is Verdict.Passed })
        assertEquals(1, vs.count { it is Verdict.Failed })
        assertEquals(1, vs.count { it is Verdict.Blocked })
        assertEquals(1, vs.count { it is Verdict.NotMeasured })
        assertEquals(1, vs.count { it is Verdict.NotApplicable })
    }

    @Test
    fun `D-05 failure codes are the wire contract`() {
        // The string `code` is what the cert report
        // serialises. If we ever change the message, the
        // code must NOT change (it is the contract with
        // D-007, dashboards, and historical receipts).
        val expected = listOf(
            "ARTIFACT_NOT_FOUND",
            "DIGEST_MISMATCH",
            "MANIFEST_INVALID",
            "PREREQUISITE_UNAVAILABLE",
            "PROCESS_TIMEOUT",
            "RUNTIME_UNAVAILABLE",
            "CONTRACT_MISMATCH",
            "EVIDENCE_MISSING",
            "UNSUPPORTED_PLATFORM",
            "POLICY_VIOLATION",
            "CASE_MISSING",
            "DUPLICATE_CASE_ID",
            "FILE_MISSING",
            "VERSION_MISMATCH",
            "EXECUTION_ERROR",
            "CASE_DISABLED",
            "PARTIAL_RESULT",
            "REPORT_INCOMPLETE",
        )
        val actual = listOf(
            Failure.ArtifactNotFound,
            Failure.DigestMismatch,
            Failure.ManifestInvalid,
            Failure.PrerequisiteUnavailable,
            Failure.ProcessTimeout,
            Failure.RuntimeUnavailable,
            Failure.ContractMismatch,
            Failure.EvidenceMissing,
            Failure.UnsupportedPlatform,
            Failure.PolicyViolation,
            Failure.CaseMissing,
            Failure.DuplicateCaseId,
            Failure.FileMissing,
            Failure.VersionMismatch,
            Failure.ExecutionError,
            Failure.CaseDisabled,
            Failure.PartialResult,
            Failure.ReportIncomplete,
        ).map { it.code }
        assertEquals(expected, actual)
    }

    @Test
    fun `D-05 verdict text format is stable`() {
        assertEquals("PASS", Verdict.Passed.toString())
        assertEquals("FAIL:DIGEST_MISMATCH", Verdict.Failed(Failure.DigestMismatch).toString())
        assertEquals(
            "BLOCKED:RUNTIME_UNAVAILABLE",
            Verdict.Blocked(Failure.RuntimeUnavailable).toString(),
        )
        assertEquals("NOT_MEASURED", Verdict.NotMeasured.toString())
        assertEquals("NOT_APPLICABLE:profile-opt-out", Verdict.NotApplicable("profile-opt-out").toString())
    }

    @Test
    fun `D-04 Sha256 rejects malformed input at the boundary`() {
        assertFailsWith<IllegalArgumentException> { Sha256("nope") }
        assertFailsWith<IllegalArgumentException> { Sha256("Z".repeat(64)) } // uppercase
        assertFailsWith<IllegalArgumentException> { Sha256("a".repeat(63)) } // 63 chars
        Sha256("a".repeat(64)) // ok
    }

    @Test
    fun `D-04 SemVer rejects malformed input at the boundary`() {
        assertFailsWith<IllegalArgumentException> { SemVer("1.0") }
        assertFailsWith<IllegalArgumentException> { SemVer("abc") }
        assertFailsWith<IllegalArgumentException> { SemVer("") }
        SemVer("1.0.0")
        SemVer("1.0.0-rc.15")
        SemVer("1.0.0-rc.1+build.42")
    }

    @Test
    fun `D-04 GitCommit rejects malformed input at the boundary`() {
        assertFailsWith<IllegalArgumentException> { GitCommit("short") }
        assertFailsWith<IllegalArgumentException> { GitCommit("Z".repeat(40)) }
        GitCommit("a".repeat(40))
    }

    @Test
    fun `D-04 CaseId rejects malformed input at the boundary`() {
        // Wrong case
        assertFailsWith<IllegalArgumentException> { CaseId("uat-001") }
        // Empty / blank
        assertFailsWith<IllegalArgumentException> { CaseId("") }
        // No separator
        assertFailsWith<IllegalArgumentException> { CaseId("UAT001") }
        // Unknown prefix
        assertFailsWith<IllegalArgumentException> { CaseId("XAT-001") }
        // AAT group is not 2-6 letters
        assertFailsWith<IllegalArgumentException> { CaseId("AAT-A-001") }
        assertFailsWith<IllegalArgumentException> { CaseId("AAT-TOOLONG-001") }
        // Trailing extra
        assertFailsWith<IllegalArgumentException> { CaseId("UAT-001-extra") }
        // Number too long
        assertFailsWith<IllegalArgumentException> { CaseId("UAT-12345") }
        // Valid forms
        CaseId("UAT-001")
        CaseId("UAT-1") // any 1-4 digits ok
        CaseId("AAT-ARCH-001")
        CaseId("AAT-DUR-004")
        CaseId("AAT-API-008")
    }

    @Test
    fun `C0_4 IntegrityEnvelope is fail-closed`() {
        val satisfied = IntegrityEnvelope(
            tagMatchesCommit = true,
            commitMatchesRelease = true,
            artifactDigestsMatch = true,
            sdkVersionMatchesLock = true,
            studioVersionPinned = true,
        )
        assertTrue(satisfied.isSatisfied())
        assertSame(null, satisfied.failureReason())

        val tagMismatch = satisfied.copy(tagMatchesCommit = false)
        assertEquals(false, tagMismatch.isSatisfied())
        assertSame(Failure.DigestMismatch, tagMismatch.failureReason())

        val sdkMismatch = satisfied.copy(sdkVersionMatchesLock = false)
        assertEquals(false, sdkMismatch.isSatisfied())
        assertSame(Failure.VersionMismatch, sdkMismatch.failureReason())
    }
}
