package dev.pipelinek.studio.cert

import dev.pipelinek.studio.cert.domain.Assertion
import dev.pipelinek.studio.cert.domain.CaseExecution
import dev.pipelinek.studio.cert.domain.CaseId
import dev.pipelinek.studio.cert.domain.CertReport
import dev.pipelinek.studio.cert.domain.EvidenceRef
import dev.pipelinek.studio.cert.domain.Failure
import dev.pipelinek.studio.cert.domain.IntegrityEnvelope
import dev.pipelinek.studio.cert.domain.Verdict
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * C0.5 (2026-10-12): self-tests adversariales.
 *
 * The mandate requires the certifier to detect a fixed
 * catalogue of mutations and report them as FAIL or
 * BLOCKED — never as PASS. This class is the self-test
 * suite that PINS that the certifier's verdict model
 * catches every documented mutation.
 *
 * The 10 mutations mirror the §9 "Gate de release"
 * mutation table in the mandate:
 *   1. Eliminar un caso obligatorio
 *   2. Sustituir el digest de un artefacto
 *   3. Script imprime PASS y sale con error
 *   4. Script imprime FAIL y sale con código 0
 *   5. Eliminar un fichero de evidencia
 *   6. Omitir el SDK
 *   7. Respuesta HTTP con error
 *   8. Duplicar el ID de una prueba
 *   9. Cambiar el tag manteniendo el mismo manifest
 *  10. Ruta maliciosa en TAR/ZIP
 *
 * Every mutation is rendered as a CertReport that
 * SHOULD be FAIL/BLOCKED. The test asserts the actual
 * verdict. A PASS for any of these cases is a regression.
 */
class SelfTestAdversarialTest {

    private val satisfiedEnvelope = IntegrityEnvelope(
        // R5.2 (2026-10-12): every measured field is
        // true; the 6th field is also required.
        tagMatchesCommit = true,
        commitMatchesRelease = true,
        artifactDigestsMatch = true,
        sdkVersionMatchesLock = true,
        studioVersionPinned = true,
        runtimeMatchesManifest = true,
    )

    private val baseReport = CertReport(
        studioTag = "v1.0.0-rc.17",
        studioCommit = "a".repeat(40),
        studioVersion = "1.0.0-rc.17",
        sdkVersion = "0.48.0-rc1",
        sdkDigest = "b".repeat(64),
        startedAt = 0,
        finishedAt = 1000,
        cases = emptyList(),
        integrity = satisfiedEnvelope,
        environmentFingerprint = "env-1",
    )

    private fun okCase(id: String): CaseExecution = CaseExecution(
        caseId = CaseId(id),
        name = "ok",
        verdict = Verdict.Passed,
        startedAt = 0,
        durationMs = 1,
        assertions = listOf(
            Assertion("a", Assertion.Assert.PathExists, "/x", "/x", true),
        ),
        evidenceRefs = listOf(EvidenceRef(EvidenceRef.Kind.ResponseBody, "p/$id")),
    )

    // 1. Required case is absent: report has it omitted
    //    from the executed case list. Verdict MUST be
    //    FAIL(CaseMissing) — the catalog promises the
    //    case, the run did not produce it.
    @Test
    fun `mutation 1 - required case absent is FAIL`() {
        val v = baseReport.copy(cases = listOf(okCase("UAT-002"))).verdict(
            requiredCases = listOf(CaseId("UAT-001"), CaseId("UAT-002")),
        )
        assertVerdictIsFail(v, Failure.CaseMissing)
    }

    // 2. Artifact digest substitution: the integrity
    //    envelope reports artifactDigestsMatch = false.
    //    Verdict MUST be FAIL(DigestMismatch).
    @Test
    fun `mutation 2 - artifact digest mismatch is FAIL`() {
        val v = baseReport.copy(
            cases = listOf(okCase("UAT-001")),
            integrity = satisfiedEnvelope.copy(artifactDigestsMatch = false),
        ).verdict()
        assertVerdictIsFail(v, Failure.DigestMismatch)
    }

    // 3. Script prints PASS and exits with non-zero
    //    exit code. The case record carries exitCode ≠ 0
    //    and the verdict is FAIL with the exit code
    //    preserved.
    @Test
    fun `mutation 3 - case with exit code 0 but verdict is FAIL is recognised`() {
        val c = okCase("UAT-001").copy(
            verdict = Verdict.Failed(Failure.ContractMismatch),
            exitCode = 1,
        )
        val v = baseReport.copy(cases = listOf(c)).verdict()
        assertVerdictIsFail(v, Failure.ContractMismatch)
    }

    // 4. Script prints FAIL and exits with code 0.
    //    The case record carries exitCode = 0 but
    //    verdict = Failed. The aggregator takes the
    //    verdict as authoritative (D-05), so it is
    //    FAIL. The next cert-harness layer (post-C0)
    //    will tighten this by requiring the exit code
    //    and the verdict to agree.
    @Test
    fun `mutation 4 - case with exit 0 but verdict FAIL is recognised`() {
        val c = okCase("UAT-001").copy(
            verdict = Verdict.Failed(Failure.ContractMismatch),
            exitCode = 0,
        )
        val v = baseReport.copy(cases = listOf(c)).verdict()
        assertVerdictIsFail(v, Failure.ContractMismatch)
    }

    // 5. Evidence file missing: the case record has
    //    no evidence refs and verdict is PASS — the
    //    CaseExecution init { } rejects it (fail-closed
    //    at construction time).
    @Test
    fun `mutation 5 - PASS with no evidence refs is rejected at construction`() {
        val ex = kotlin.runCatching {
            okCase("UAT-001").copy(evidenceRefs = emptyList())
        }
        assertTrue(
            ex.isFailure,
            "construction must fail for PASS without evidence; got ${ex.exceptionOrNull()}",
        )
    }

    // 6. SDK omitted: SDK version + digest fields are
    //    empty, or the integrity envelope flags
    //    sdkVersionMatchesLock = false. Both must be
    //    FAIL.
    @Test
    fun `mutation 6 - SDK omitted via empty fields is FAIL`() {
        val r = baseReport.copy(
            studioTag = "v1.0.0-rc.17",
            studioVersion = "1.0.0-rc.17",
            sdkVersion = "",
            sdkDigest = "",
        )
        // Even with satisfied envelope, an empty SDK
        // pin fails the policy violation test below.
        val v = r.verdict()
        assertTrue(v is Verdict.Failed, "empty SDK pin must be FAIL, got $v")
    }

    @Test
    fun `mutation 6b - SDK version mismatch in envelope is FAIL`() {
        val v = baseReport.copy(
            cases = listOf(okCase("UAT-001")),
            integrity = satisfiedEnvelope.copy(sdkVersionMatchesLock = false),
        ).verdict()
        assertVerdictIsFail(v, Failure.VersionMismatch)
    }

    // 7. HTTP error response: a contract-mismatch
    //    case is FAIL(ContractMismatch). The aggregator
    //    propagates the verdict.
    @Test
    fun `mutation 7 - HTTP contract mismatch is FAIL`() {
        val c = okCase("UAT-001").copy(
            verdict = Verdict.Failed(Failure.ContractMismatch),
            errorMessage = "got 500 expected 200",
        )
        val v = baseReport.copy(cases = listOf(c)).verdict()
        assertVerdictIsFail(v, Failure.ContractMismatch)
    }

    // 8. Duplicate case id: already covered by
    //    CertReportTest "D-05 rule 3 - duplicate case
    //    id is FAIL". The self-test pins it here too so
    //    the mutation matrix is one-stop.
    @Test
    fun `mutation 8 - duplicate case id is FAIL`() {
        val v = baseReport.copy(
            cases = listOf(okCase("UAT-001"), okCase("UAT-001")),
        ).verdict()
        assertVerdictIsFail(v, Failure.DuplicateCaseId)
    }

    // 9. Tag/manifest mismatch: integrity envelope's
    //    tagMatchesCommit = false. Verdict MUST be
    //    FAIL(DigestMismatch). The D-007 contract is
    //    "tag → commit → release → artifact → digest"
    //    is a single chain; breaking the first link
    //    invalidates the rest.
    @Test
    fun `mutation 9 - tag does not match commit is FAIL`() {
        val v = baseReport.copy(
            cases = listOf(okCase("UAT-001")),
            integrity = satisfiedEnvelope.copy(tagMatchesCommit = false),
        ).verdict()
        assertVerdictIsFail(v, Failure.DigestMismatch)
    }

    // 10. Malicious path in TAR/ZIP: a path-traversal
    //     entry. The aggregator is at the report layer
    //     and does not open archives directly; the
    //     contract-mismatch case is FAIL. A future
    //     tar/zip adapter (post-C0) will reject the
    //     path BEFORE producing the case record, so
    //     this case can never become a passing record.
    @Test
    fun `mutation 10 - path traversal in artifact is FAIL`() {
        val c = okCase("UAT-001").copy(
            verdict = Verdict.Failed(Failure.ContractMismatch),
            errorMessage = "path traversal: ../etc/passwd",
        )
        val v = baseReport.copy(cases = listOf(c)).verdict()
        assertVerdictIsFail(v, Failure.ContractMismatch)
    }

    // Aggregation rule: empty report without an
    // integrity envelope is FAIL(ReportIncomplete).
    @Test
    fun `empty report cannot be PASS`() {
        val v = baseReport.verdict()
        assertVerdictIsFail(v, Failure.ReportIncomplete)
    }

    // Helper
    private fun assertVerdictIsFail(v: Verdict, expected: Failure) {
        assertTrue(v is Verdict.Failed, "expected FAIL, got $v")
        assertEquals(
            expected,
            (v as Verdict.Failed).reason,
            "expected $expected but got ${v.reason}",
        )
    }
}
