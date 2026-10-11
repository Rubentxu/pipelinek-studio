package dev.pipelinek.studio.application.ports

import java.time.Instant

/**
 * R2 item 3 (2026-10-11): explicit admission lifecycle for plugin JARs.
 *
 * Per the S2 mandate item 3: 'Distinguir expresamente `DISCOVERED`,
 * `COMPATIBLE`, `ADMITTED`, `REFUSED` y `UNAVAILABLE`'. The pre-R2
 * adapter only carried the 4-state `TrustClass` (TrustedInternal /
 * TrustedPinned / UntrustedNew / UntrustedTampered) which classifies
 * trust but does NOT model the admission lifecycle.
 *
 * Lifecycle semantics (canonical):
 *
 *   DISCOVERED     — the plugin JAR was found on disk; its manifest
 *                    was read without class-loading; no admission
 *                    decision has been made yet.
 *   COMPATIBLE     — the plugin's manifest matches the installed SDK's
 *                    declared vendor + version range; it COULD be
 *                    admitted but the user has not yet accepted.
 *   ADMITTED       — the user explicitly accepted the plugin; its
 *                    sha256 is pinned in the plugin lock; the SDK
 *                    may load it.
 *   REFUSED        — the user explicitly rejected, OR the plugin's
 *                    manifest failed admission checks (e.g. vendor
 *                    mismatch with the SDK's internal vendor).
 *   UNAVAILABLE    — the SDK or runtime cannot load the plugin (SDK
 *                    missing, subprocess not alive, OS sandbox
 *                    blocked, etc.).
 *
 * Transitions (TODO; will be implemented when the admission use
 * case lands):
 *   DISCOVERED -> COMPATIBLE     on manifest match (vendor + version)
 *   DISCOVERED -> REFUSED        on vendor mismatch with SDK
 *   COMPATIBLE -> ADMITTED       on user acceptance (persists sha256)
 *   COMPATIBLE -> REFUSED        on user rejection
 *   *          -> UNAVAILABLE    on SDK/runtime failure to load
 *
 * Until the admission use case is wired, the only state that the
 * adapter can produce without user or SDK interaction is
 * `DISCOVERED` (manifest read, no decision). Tests pin that
 * boundary.
 */

sealed interface PluginAdmissionState {
    /** The plugin JAR filename this state applies to. */
    val pluginJar: String

    data class Discovered(
        override val pluginJar: String,
        val implementationTitle: String?,
        val implementationVersion: String?,
        val implementationVendor: String?,
        val sha256: String,
        val stepCapabilities: List<String>,
    ) : PluginAdmissionState

    data class Compatible(
        override val pluginJar: String,
        val sdkVersion: String,
        val vendorMatch: Boolean,
    ) : PluginAdmissionState

    data class Admitted(
        override val pluginJar: String,
        val sha256: String,
        val admittedAt: Instant,
    ) : PluginAdmissionState

    data class Refused(
        override val pluginJar: String,
        val reason: String,
    ) : PluginAdmissionState

    data class Unavailable(
        override val pluginJar: String,
        val reason: String,
    ) : PluginAdmissionState
}

/**
 * R2 item 3 (2026-10-11): read-only port for the admission state of
 * every plugin JAR the inspector has seen. The producer implementation
 * (`PluginAdmissionPortImpl`) is wired in R2 follow-ups; for now
 * the contract is declared so the SPA and the use-case layer can
 * consume it.
 *
 * `null` is NOT a valid state — when the inspector has never seen a
 * JAR, the port MUST return `Unavailable` with a `reason`. The
 * empty/missing case is honest, not silent.
 */
interface PluginAdmissionPort {
    fun stateOf(pluginJar: String): PluginAdmissionState
    fun listAll(): List<PluginAdmissionState>
}