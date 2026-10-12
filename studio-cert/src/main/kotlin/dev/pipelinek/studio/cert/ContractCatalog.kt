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
     * R6 (2026-10-12): the authentic E2E surface. The 8 cases
     * are the R6.1 golden path + the 7 R6.2 normative
     * assertions (UAT-010 cancel, UAT-011 restart, UAT-012
     * kill-9, UAT-014 idempotency, UAT-017 UTF-8 split,
     * UAT-018 stream authority, UAT-019 output safety,
     * UAT-040 SQLite migration). The case ids use the AAT-INT
     * prefix because the surface is "integration: studio
     * driving the full chain end-to-end" — not the same
     * surface as the per-process UAT or per-route AAT-STUDIO
     * cases. The aggregator treats an omitted INT case the
     * same way as any other omitted required case (hard
     * FAIL per R5.3).
     */
    val R6_REQUIRED: List<CaseId> = listOf(
        CaseId("AAT-INT-001"), // e2e-hello-full-chain (R6.1)
        CaseId("AAT-INT-010"), // e2e-cancel-while-running (R6.2 UAT-010)
        CaseId("AAT-INT-011"), // e2e-restart-persistence (R6.1 closing half)
        CaseId("AAT-INT-012"), // e2e-kill9-recovery (R6.2 UAT-012)
        CaseId("AAT-INT-014"), // e2e-no-double-execution (R6.2 UAT-014)
        CaseId("AAT-INT-017"), // e2e-utf8-paginated-roundtrip (R6.2 UAT-017)
        CaseId("AAT-INT-018"), // e2e-stream-authority (R6.2 UAT-018)
        CaseId("AAT-INT-019"), // e2e-output-safety (R6.2 UAT-019)
        CaseId("AAT-INT-040"), // e2e-sqlite-migration (R6.2 UAT-040)
    )

    /**
     * Aggregated required set for `run-all-cert`. The
     * aggregator is fed this list, NOT the executed set.
     * Per R5.3, any missing case here is a hard FAIL.
     */
    val ALL_REQUIRED: List<CaseId> =
        C1_REQUIRED + C2_REQUIRED + C3_REQUIRED + R6_REQUIRED

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
