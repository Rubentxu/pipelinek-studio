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
 * increasing [version], the [pluginLockDigest] that pinned the
 * plugin registry at the moment of sealing, and the
 * [environmentProfile] that pinned the execution profile.
 *
 * R0.3.9 (2026-10-11) — added [environmentProfile] to close the
 * profile-identity gap surfaced by [RunBindingIdentityR03BTest].
 * Pre-R0.3.9 the SQLite schema declared
 * `scenarios.environment_profile TEXT NOT NULL` but the
 * [SourceScenario] data class and `saveScenario` INSERT omitted
 * it, so a fresh scenario registration against the real
 * repository would have failed. Default value matches the only
 * profile the runner currently accepts (per B2.5 / ADR-002:
 * `TrustedLocal`).
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
    val environmentProfile: String = DEFAULT_PROFILE,
) {
    init {
        require(version >= 1) { "scenario version must be >= 1, got $version" }
        require(pluginLockDigest == null || pluginLockDigest.length == 64) {
            "pluginLockDigest must be either null or a 64-char sha256 hex, got ${pluginLockDigest?.length}"
        }
        require(environmentProfile.isNotBlank()) {
            "environmentProfile must be non-blank, got '$environmentProfile'"
        }
    }

    /** Stable content key used by run_bindings for idempotency. */
    fun contentKey(): String = "${id.value}:$version"

    override fun equals(other: Any?): Boolean = other is SourceScenario && other.id == id && other.version == version
    override fun hashCode(): Int = 31 * id.hashCode() + version

    companion object {
        /**
         * R0.3.9: the default execution profile a scenario is
         * sealed with. Matches [dev.pipelinek.studio.application.ports.ExecutionProfile.TrustedLocal]
         * — the only profile the runner accepts per B2.5 / ADR-002.
         * Future profiles (Untrusted etc.) will require an OS
         * sandbox and an isolation test, per the same mandate.
         */
        const val DEFAULT_PROFILE: String = "trusted_local"
    }
}
