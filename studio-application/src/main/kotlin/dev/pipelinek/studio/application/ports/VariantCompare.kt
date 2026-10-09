package dev.pipelinek.studio.application.ports

import dev.pipelinek.studio.domain.ExperimentId
import dev.pipelinek.studio.domain.RunId
import dev.pipelinek.studio.domain.SnapshotId

/**
 * S4 (Agent collaboration) — variant + compare ports per ROADMAP §"S4 API/MCP para
 * variantes de snapshot y compare de runs con diferencias de runtime/plugins/env perfil;
 * mutation scenarios controlados, per-run fingerprints/diffs".
 *
 * Variant: clones a snapshot's source bytes into a new snapshot owned by a chosen
 * experiment. The original is never mutated; per ADR-005 snapshot immutability.
 *
 * Compare: takes two RunIds and computes the runtime diff (event sequences that
 * appeared in only one side + terminal outcome + fingerprint). Studio never lies
 * about the diff — equal events collapse to "EVENTS_IDENTICAL" so the response is
 * bounded in size.
 */

data class VariantRequest(
    val sourceSnapshotId: SnapshotId,
    val targetExperimentId: ExperimentId,
    val creator: String,
)

sealed interface VariantResult {
    data class Ok(val newSnapshotId: SnapshotId) : VariantResult
    data class Failure(val reason: String) : VariantResult
}

interface VariantPort {
    fun variant(request: VariantRequest): VariantResult
}

data class RunComparison(
    val baseRunId: RunId,
    val otherRunId: RunId,
    val baseTerminal: String?,
    val otherTerminal: String?,
    val identicalEvents: Int,
    val onlyInBase: Int,
    val onlyInOther: Int,
)

sealed interface RunComparisonResult {
    data class Ok(val comparison: RunComparison) : RunComparisonResult
    data class Failure(val reason: String) : RunComparisonResult
}

interface RunComparisonPort {
    fun compare(base: RunId, other: RunId): RunComparisonResult
}
