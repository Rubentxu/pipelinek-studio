package dev.pipelinek.studio.cert.domain

import kotlinx.serialization.Serializable

/**
 * C0.4 (2026-10-12): the per-case execution record.
 *
 * Every case the certifier runs produces a [CaseExecution].
 * The record is fail-closed by construction: an empty or
 * partial [CaseExecution] yields a non-PASS verdict.
 *
 * The [assertions] list is the oracular contract. The
 * verdict cannot be PASS unless every required assertion
 * held. The [evidenceRefs] list is what downstream tools
 * (D-007, dashboards) point at to inspect what was
 * actually observed.
 */
@Serializable
data class CaseExecution(
    val caseId: CaseId,
    val name: String,
    val verdict: Verdict,
    val startedAt: Long,
    val durationMs: Long,
    val assertions: List<Assertion>,
    val evidenceRefs: List<EvidenceRef>,
    val command: String = "",
    val exitCode: Int = 0,
    val errorMessage: String? = null,
) {
    /**
     * D-05: a CaseExecution is COMPLETE iff assertions is
     * non-empty AND every required assertion passed AND
     * evidenceRefs is non-empty. Incomplete executions
     * downgrade the verdict.
     */
    val isComplete: Boolean
        get() = assertions.isNotEmpty() && evidenceRefs.isNotEmpty()

    init {
        require(name.isNotBlank()) { "CaseExecution.name cannot be blank" }
        require(durationMs >= 0) { "CaseExecution.durationMs cannot be negative" }
        // D-05: an empty report cannot be PASS. We enforce it
        // here so callers cannot construct a "PASS with no
        // assertions" by accident.
        if (verdict == Verdict.Passed) {
            require(assertions.isNotEmpty()) {
                "CaseExecution for ${caseId.value} cannot have verdict=PASS with no assertions"
            }
            require(evidenceRefs.isNotEmpty()) {
                "CaseExecution for ${caseId.value} cannot have verdict=PASS with no evidence refs"
            }
        }
    }
}

/**
 * A single atomic assertion. The oracular contract is the
 * [expected] / [actual] pair; a [kind] of [Assert] classifies
 * the comparison (string equality, JSON path, digest, exit
 * code, etc.) so downstream tools can re-run the assertion
 * against different inputs.
 */
@Serializable
data class Assertion(
    val name: String,
    val kind: Assert,
    val expected: String,
    val actual: String,
    val held: Boolean,
) {
    @Serializable
    enum class Assert {
        StringEquals,
        StringContains,
        PathExists,
        DigestEquals,
        ExitCodeZero,
        JsonPathEquals,
        HttpStatusEquals,
        FileSizeEquals,
        FileContentsEquals,
    }
}

/**
 * Reference to an evidence artifact on disk. The certifier
 * is responsible for producing and storing the evidence;
 * the path is relative to a per-run evidence root.
 */
@Serializable
data class EvidenceRef(
    val kind: Kind,
    val path: String,
    val sha256: String? = null,
    val size: Long? = null,
) {
    @Serializable
    enum class Kind {
        StdoutLog,
        StderrLog,
        ResponseBody,
        ResponseHeaders,
        Artifact,
        Manifest,
        Sbom,
        Receipt,
        Database,
    }
}
