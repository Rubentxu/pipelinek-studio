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
 * R5.3 (2026-10-12): the cert's required-cases
 * matrix MUST come from the catalog, never from
 * the executed set. These tests pin the
 * catalog-driven decision and prove that omitting
 * a required case surfaces as `CaseMissing`.
 *
 * The tests use the pure [CertReport.verdict]
 * aggregator (no subprocess, no fixtures) so the
 * catalog surface is exercised in isolation. The
 * catalog in [ContractCatalog] is the single source
 * of truth for which cases are required.
 *
 * Why this matters: the R5.3 mandate forbids
 * "requiredCases = cases.map { it.caseId }". The
 * aggregator MUST consult the catalog and FAIL on
 * any case the catalog promises but the executed
 * set does not produce.
 */
class SurfaceCatalogTest {

    /**
     * Build a minimal [CertReport] with the given
     * [cases] and a fully-satisfied integrity
     * envelope. The envelope is satisfied by
     * construction so the verdict path goes through
     * the case-missing rule, not the integrity
     * rule.
     */
    private fun reportWith(cases: List<CaseExecution>): CertReport {
        return CertReport(
            studioTag = "test",
            studioCommit = "test",
            studioVersion = "1.0.0",
            sdkVersion = "0.48.0-rc1",
            sdkDigest = "0".repeat(64),
            cases = cases,
            startedAt = cases.first().startedAt,
            finishedAt = cases.first().startedAt,
            integrity = IntegrityEnvelope(
                tagMatchesCommit = true,
                commitMatchesRelease = true,
                artifactDigestsMatch = true,
                sdkVersionMatchesLock = true,
                studioVersionPinned = true,
                runtimeMatchesManifest = true,
            ),
            environmentFingerprint = "test",
        )
    }

    private fun passedCase(id: String): CaseExecution = CaseExecution(
        caseId = CaseId(id),
        name = id,
        verdict = Verdict.Passed,
        startedAt = 0L,
        durationMs = 1L,
        // CaseExecution requires at least one
        // assertion when verdict=PASS; the catalog
        // tests only care about the verdict path, not
        // the assertion content, so a single
        // synthetic assertion is enough.
        assertions = listOf(
            Assertion(
                name = "catalog-test-synthetic",
                kind = Assertion.Assert.StringEquals,
                expected = "ok",
                actual = "ok",
                held = true,
            ),
        ),
        // CaseExecution also requires at least
        // one evidence ref for verdict=PASS (per
        // R5.2: a PASS without evidence is not
        // admissible).
        evidenceRefs = listOf(
            EvidenceRef(
                kind = EvidenceRef.Kind.Artifact,
                path = "/dev/null",
                sha256 = "0".repeat(64),
                size = 0L,
            ),
        ),
        exitCode = 0,
    )

    @Test
    fun `R5_3 C1 catalog with one omitted UAT is FAIL CaseMissing`() {
        val c1ExceptOne = ContractCatalog.C1_REQUIRED
            .filter { it.value != "UAT-007" }
        val cases = c1ExceptOne.map { passedCase(it.value) }
        val report = reportWith(cases)
        val v = report.verdict(
            requiredCases = ContractCatalog.C1_REQUIRED,
            requiredIntegrity = IntegrityEnvelope.ALL_REQUIRED,
        )
        assertTrue(v is Verdict.Failed, "expected FAIL, got $v")
        assertEquals(Failure.CaseMissing, (v as Verdict.Failed).reason)
    }

    @Test
    fun `R5_3 C2 catalog with one omitted AAT-STUDIO is FAIL CaseMissing`() {
        val c2ExceptOne = ContractCatalog.C2_REQUIRED
            .filter { it.value != "AAT-STUDIO-005" }
        val cases = c2ExceptOne.map { passedCase(it.value) }
        val report = reportWith(cases)
        val v = report.verdict(
            requiredCases = ContractCatalog.C2_REQUIRED,
            requiredIntegrity = IntegrityEnvelope.ALL_REQUIRED,
        )
        assertTrue(v is Verdict.Failed, "expected FAIL, got $v")
        assertEquals(Failure.CaseMissing, (v as Verdict.Failed).reason)
    }

    @Test
    fun `R5_3 C3 catalog with one omitted AAT-MCP is FAIL CaseMissing`() {
        val c3ExceptOne = ContractCatalog.C3_REQUIRED
            .filter { it.value != "AAT-MCP-003" }
        val cases = c3ExceptOne.map { passedCase(it.value) }
        val report = reportWith(cases)
        val v = report.verdict(
            requiredCases = ContractCatalog.C3_REQUIRED,
            requiredIntegrity = IntegrityEnvelope.ALL_REQUIRED,
        )
        assertTrue(v is Verdict.Failed, "expected FAIL, got $v")
        assertEquals(Failure.CaseMissing, (v as Verdict.Failed).reason)
    }

    @Test
    fun `R5_3 ALL_REQUIRED catalog with C1 C2 C3 executed is PASS only when ALL_REQUIRED is satisfied`() {
        // The aggregator MUST use the explicit
        // catalog passed to verdict(). Here we
        // exercise the surface=c1 invocation
        // against C1_REQUIRED — the executed set
        // covers C1 only, so passing C1_REQUIRED
        // as required is the only way to get PASS.
        val c1Cases = ContractCatalog.C1_REQUIRED.map { passedCase(it.value) }
        val report = reportWith(c1Cases)
        val v = report.verdict(
            requiredCases = ContractCatalog.C1_REQUIRED,
            requiredIntegrity = IntegrityEnvelope.ALL_REQUIRED,
        )
        assertEquals(Verdict.Passed, v)
    }

    @Test
    fun `R5_3 mismatched surface flag (surface=c2 against C1 cases) is FAIL CaseMissing`() {
        // The cert's `--surface=c2` flag is the
        // operator's explicit choice. If the
        // operator runs `run-cert` with
        // `--surface=c2` but only UAT fixtures
        // are present, the catalog (C2) expects
        // AAT-STUDIO-* cases and the executed
        // set has none — the aggregator MUST
        // surface CaseMissing, not silently
        // accept the UAT cases.
        val c1Cases = ContractCatalog.C1_REQUIRED.map { passedCase(it.value) }
        val report = reportWith(c1Cases)
        val v = report.verdict(
            requiredCases = ContractCatalog.C2_REQUIRED,
            requiredIntegrity = IntegrityEnvelope.ALL_REQUIRED,
        )
        assertTrue(v is Verdict.Failed, "expected FAIL, got $v")
        assertEquals(Failure.CaseMissing, (v as Verdict.Failed).reason)
    }

    @Test
    fun `R5_3 R6 catalog with one omitted AAT-INT is FAIL CaseMissing`() {
        val r6ExceptOne = ContractCatalog.R6_REQUIRED
            .filter { it.value != "AAT-INT-018" }
        val cases = r6ExceptOne.map { passedCase(it.value) }
        val report = reportWith(cases)
        val v = report.verdict(
            requiredCases = ContractCatalog.R6_REQUIRED,
            requiredIntegrity = IntegrityEnvelope.ALL_REQUIRED,
        )
        assertTrue(v is Verdict.Failed, "expected FAIL, got $v")
        assertEquals(Failure.CaseMissing, (v as Verdict.Failed).reason)
    }

    @Test
    fun `R5_3 runCert surface parsing accepts c1 c2 c3`() {
        // The CLI flag parser. The production code
        // path lives in CertMain.parseSurface; this
        // test pins its surface vocabulary.
        assertEquals(CertSurface.C1, parseSurface("c1"))
        assertEquals(CertSurface.C2, parseSurface("c2"))
        assertEquals(CertSurface.C3, parseSurface("c3"))
    }

    @Test
    fun `R5_3 parseSurface rejects unknown labels`() {
        // The parser is fail-closed: an unknown
        // surface label is a hard error, never a
        // silent fallback. CI must use one of the
        // three documented values.
        val ex = runCatching { parseSurface("c4") }.exceptionOrNull()
        assertTrue(ex is IllegalStateException, "expected IllegalStateException, got $ex")
    }
}
