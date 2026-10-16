package dev.pipelinek.studio.cli

/**
 * S5 polish: the CLI's own version constant, exposed via
 * `studio --version` and the fallback printed by `studio doctor` when
 * the API is unreachable. Kept in sync with the root project's
 * `version` (driven by `gradle.properties:studio.version`); this is a
 * deliberate two-place duplication for the bootstrap phase — when
 * the build grows a VersionCatalog or a release plugin, this file
 * will be the single source of truth and the gradle file will read
 * from it.
 *
 * R-VER-001 follow-up (2026-10-11): the version literal is now
 * `1.0.0-rc.17` (matching the latest tag). The new
 * `VersionCoherenceB0Test` fails CI if this string diverges from
 * gradle.properties:studio.version. Bump both at the same time.
 */
object StudioCliVersion {
    const val VERSION: String = "1.0.0-rc.17"
    const val KOTLIN_VERSION: String = "2.1.0"
    const val KTOR_VERSION: String = "3.0.1"
}
