package dev.pipelinek.studio.cert.domain

import kotlinx.serialization.Serializable

/**
 * C0.4 (2026-10-12): the verdict taxonomy.
 *
 * D-05 mandates five terminal states; the certifier
 * cannot collapse them into PASS/FAIL.
 *
 *   PASS          — every required assertion held + every
 *                    required evidence is present + the
 *                    integrity envelope is satisfied.
 *   FAIL          — a required assertion was executed and
 *                    failed, or the integrity envelope is
 *                    broken.
 *   BLOCKED       — a prerequisite is missing or no valid
 *                    environment is available; the case was
 *                    NOT executed.
 *   NOT_MEASURED  — the case was not executed at all (no
 *                    evidence produced); the catalog cannot
 *                    claim anything about it.
 *   NOT_APPLICABLE— the case is excluded by an explicit
 *                    product profile; the operator has
 *                    authorised the exclusion.
 *
 * NOT_APPLICABLE cannot be auto-generated for any
 * capability the product advertises. The exclusion must
 * be present in the cert policy before the case runs.
 */
@Serializable
sealed interface Verdict {
    @Serializable
    object Passed : Verdict {
        override fun toString(): String = "PASS"
    }

    @Serializable
    data class Failed(val reason: Failure) : Verdict {
        override fun toString(): String = "FAIL:${reason.code}"
    }

    @Serializable
    data class Blocked(val reason: Failure) : Verdict {
        override fun toString(): String = "BLOCKED:${reason.code}"
    }

    @Serializable
    object NotMeasured : Verdict {
        override fun toString(): String = "NOT_MEASURED"
    }

    @Serializable
    data class NotApplicable(val reason: String) : Verdict {
        override fun toString(): String = "NOT_APPLICABLE:$reason"
    }
}

/**
 * C0.4 (2026-10-12): the structured failure reason
 * taxonomy. Every FAIL and every BLOCKED verdict cites
 * exactly one of these codes. The codes are the wire
 * contract for the cert reports; downstream tools
 * (D-007 gating, dashboards) match on [code] (stable
 * string) and never on the message.
 *
 * The message is operator-facing and may be refined
 * over time. The code never changes.
 */
@Serializable
sealed class Failure(val code: String, val message: String) {
    @Serializable
    object ArtifactNotFound : Failure("ARTIFACT_NOT_FOUND", "required artifact not found")

    @Serializable
    object DigestMismatch : Failure("DIGEST_MISMATCH", "downloaded or installed digest does not match the published one")

    @Serializable
    object ManifestInvalid : Failure("MANIFEST_INVALID", "manifest cannot be parsed or is missing required fields")

    @Serializable
    object PrerequisiteUnavailable : Failure(
        "PREREQUISITE_UNAVAILABLE",
        "a required prerequisite (tool, SDK, network) is not available in this environment",
    )

    @Serializable
    object ProcessTimeout : Failure("PROCESS_TIMEOUT", "a subprocess exceeded the configured timeout")

    @Serializable
    object RuntimeUnavailable : Failure(
        "RUNTIME_UNAVAILABLE",
        "the PipelineK runtime could not be loaded or its subprocess could not be spawned",
    )

    @Serializable
    object ContractMismatch : Failure(
        "CONTRACT_MISMATCH",
        "the product response does not satisfy the published contract",
    )

    @Serializable
    object EvidenceMissing : Failure(
        "EVIDENCE_MISSING",
        "a required evidence artifact (log, file, response) was not produced",
    )

    @Serializable
    object UnsupportedPlatform : Failure(
        "UNSUPPORTED_PLATFORM",
        "the certifier does not support this OS / arch / runtime",
    )

    @Serializable
    object PolicyViolation : Failure(
        "POLICY_VIOLATION",
        "the operator's policy forbids this result (e.g. unsigned release on a signed-only policy)",
    )

    // C0.4 additions for the integrity envelope:
    @Serializable
    object CaseMissing : Failure("CASE_MISSING", "a required case is absent from the run")

    @Serializable
    object DuplicateCaseId : Failure("DUPLICATE_CASE_ID", "two cases share the same id")

    @Serializable
    object FileMissing : Failure("FILE_MISSING", "a required file is missing")

    @Serializable
    object VersionMismatch : Failure(
        "VERSION_MISMATCH",
        "the artifact version does not match the version declared in the catalog",
    )

    @Serializable
    object ExecutionError : Failure("EXECUTION_ERROR", "the case raised an unexpected error during execution")

    @Serializable
    object CaseDisabled : Failure(
        "CASE_DISABLED",
        "the case is in the catalog but disabled by the active profile",
    )

    @Serializable
    object PartialResult : Failure("PARTIAL_RESULT", "the case returned a partial result; the oracles were not all evaluated")

    @Serializable
    object ReportIncomplete : Failure(
        "REPORT_INCOMPLETE",
        "the report is empty or lacks required integrity fields; refusing to produce a verdict",
    )
}
