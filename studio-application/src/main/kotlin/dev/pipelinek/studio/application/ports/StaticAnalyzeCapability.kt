package dev.pipelinek.studio.application.ports

/**
 * B3.0 / B3.3 (2026-10-10): default implementation of
 * [StaticAnalyzeCapabilityPort] that always reports
 * [StaticAnalyzeCapability.Unavailable]. Production
 * wiring overrides this default with a real
 * Detekt-backed implementation that returns
 * [StaticAnalyzeCapability.Available] when the
 * Detekt child JVM is reachable.
 *
 * The default is intentionally pessimistic: when
 * nothing is wired, the studio says "static
 * analysis is not available" rather than returning
 * an empty list (which the SPA would render as
 * "no problems found"). The [B3.3] test asserts
 * the env-var flip (`STUDIO_STATIC_ANALYZE_DISABLED=1`
 * → Unavailable("disabled by env")).
 */
object UnavailableStaticAnalyzeCapability : StaticAnalyzeCapabilityPort {
    override fun capability(): StaticAnalyzeCapability =
        StaticAnalyzeCapability.Unavailable("not configured")
}
