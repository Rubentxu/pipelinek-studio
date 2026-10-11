package dev.pipelinek.studio.domain

import java.time.Instant

/**
 * R0.3.1 (2026-10-11) — Immutable scenario record per
 * docs/01-architecture/DATA_MODEL_AND_PERSISTENCE.md.
 *
 * Pre-R0.3.1 the studio treated `experiment_id` as a substitute for
 * `scenario_id` (S1 anti-pattern, App.tsx body). The mandate R0.3.1
 * is "persistir escenarios auténticos; eliminar `experiment_id` como
 * sustituto de `scenario_id`." A [SourceScenario] is the formal
 * owner of identity: it carries a stable [ScenarioId], a monotonically
 * increasing [version], and the [pluginLockDigest] that pinned the
 * plugin registry at the moment of sealing.
 *
 * The id+version tuple acts as a unique constraint: re-registering
 * the same ScenarioId with the same version is a no-op (the studio
 * will not duplicate the row, mirroring how experiments/snapshots
 * behave).
 */
data class SourceScenario(
    val id: ScenarioId,
    val version: Int,
    val pluginLockDigest: String?,
    val createdAt: Instant,
) {
    init {
        require(version >= 1) { "scenario version must be >= 1, got $version" }
        require(pluginLockDigest == null || pluginLockDigest.length == 64) {
            "pluginLockDigest must be either null or a 64-char sha256 hex, got ${pluginLockDigest?.length}"
        }
    }

    /** Stable content key used by run_bindings for idempotency. */
    fun contentKey(): String = "${id.value}:$version"

    override fun equals(other: Any?): Boolean = other is SourceScenario && other.id == id && other.version == version
    override fun hashCode(): Int = 31 * id.hashCode() + version
}
