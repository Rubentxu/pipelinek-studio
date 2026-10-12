package dev.pipelinek.studio.cert

import dev.pipelinek.studio.cert.domain.CaseId

/**
 * R5.3 (2026-10-12): the contract catalog.
 *
 * The aggregator must derive its required-cases matrix from
 * an INDEPENDENT catalog, not from the executed set. This
 * file is the single source of truth for "which cases are
 * required for a v1.0.0-rc.15 production cert".
 *
 * R5.3 forbids `requiredCases = cases.map { it.caseId }`
 * (R5.3 verbatim: "El universo de pruebas debe proceder de
 * un catálogo independiente del conjunto ejecutado"). The
 * catalog here is that independent universe.
 *
 * R5.3 also requires that `NOT_APPLICABLE` only be valid
 * via an explicit, authorized exclusion. The
 * [authorizedExclusions] list captures the surface ×
 * capability × reason tuples the operator approves in
 * advance; everything else is FAIL.
 *
 * To add a new required case: append to the appropriate
 * surface list AND add its expected behavior to the run-cert
 * / run-studio-cert / run-mcp-cert / run-all-cert
 * implementations. The aggregator will FAIL until both
 * sides are in place (R5.3 "Una prueba obligatoria omitida
 * provoca fallo").
 */
object ContractCatalog {

    /**
     * C1: SDK subprocess UAT fixtures. The 8 canonical
     * golden fixtures (UAT-001 / 006 / 007 / 008 / 010 /
     * 017 / 018 / 019) are required for the cert.
     */
    val C1_REQUIRED: List<CaseId> = listOf(
        CaseId("UAT-001"), // hello
        CaseId("UAT-006"), // fail (non-zero exit)
        CaseId("UAT-007"), // two-stages
        CaseId("UAT-008"), // silent
        CaseId("UAT-010"), // long (cancel)
        CaseId("UAT-017"), // unicode
        CaseId("UAT-018"), // channels (stdout+stderr)
        CaseId("UAT-019"), // malicious-text
    )

    /**
     * C2: studio HTTP cert surface. The 8 documented routes
     * are required.
     */
    val C2_REQUIRED: List<CaseId> = listOf(
        CaseId("AAT-STUDIO-001"), // studio-boot
        CaseId("AAT-STUDIO-002"), // studio-version
        CaseId("AAT-STUDIO-003"), // studio-experiments-create
        CaseId("AAT-STUDIO-004"), // studio-openapi
        CaseId("AAT-STUDIO-005"), // studio-metrics
        CaseId("AAT-STUDIO-006"), // studio-scenarios
        CaseId("AAT-STUDIO-007"), // studio-experiments-list
        CaseId("AAT-STUDIO-008"), // studio-recovery
    )

    /**
     * C3: MCP v2 stdio surface. The 3 documented cases.
     */
    val C3_REQUIRED: List<CaseId> = listOf(
        CaseId("AAT-MCP-001"), // mcp-initialize
        CaseId("AAT-MCP-002"), // mcp-tools-list
        CaseId("AAT-MCP-003"), // mcp-tools-call-studio-health
    )

    /**
     * Aggregated required set for `run-all-cert`. The
     * aggregator is fed this list, NOT the executed set.
     * Per R5.3, any missing case here is a hard FAIL.
     */
    val ALL_REQUIRED: List<CaseId> =
        C1_REQUIRED + C2_REQUIRED + C3_REQUIRED

    /**
     * R5.3 authorized exclusions. The only `NOT_APPLICABLE`
     * verdict the aggregator accepts is a case that
     * matches a tuple here. R5.3: "`NOT_APPLICABLE` solo
     * será válido mediante una exclusión explícita y
     * autorizada para el perfil correspondiente."
     *
     * Per-rc.15 catalog: zero authorized exclusions. Every
     * required case MUST be measured; if a future release
     * needs to skip a case (e.g. a third-party API the
     * sandbox can't reach), the exclusion must be added
     * here with a documented reason. The aggregator will
     * FAIL any NOT_APPLICABLE that does not match an
     * entry.
     */
    val authorizedExclusions: List<AuthorizedExclusion> = emptyList()

    data class AuthorizedExclusion(
        val caseId: CaseId,
        val reason: String,
        val approvedBy: String,
        val approvedAt: String,
    )

    /**
     * Returns the exclusion entry for the given caseId, or
     * null when no exclusion is authorized. R5.3: the
     * aggregator consults this when a case surfaces a
     * NOT_APPLICABLE verdict; an unauthorized
     * NOT_APPLICABLE is a FAIL.
     */
    fun exclusionFor(caseId: CaseId): AuthorizedExclusion? =
        authorizedExclusions.firstOrNull { it.caseId == caseId }
}
