package dev.pipelinek.studio.cli

/**
 * S5 polish: the CLI's own version constant, exposed via
 * `studio --version` and the fallback printed by `studio doctor` when
 * the API is unreachable. Kept in sync with the root project's
 * `version = "0.1.0-alpha.1"` in build.gradle.kts; this is a
 * deliberate two-place duplication for the bootstrap phase — when
 * the build grows a VersionCatalog or a release plugin, this file
 * will be the single source of truth and the gradle file will read
 * from it.
 */
object StudioCliVersion {
    const val VERSION: String = "0.1.0-alpha.1"
    const val KOTLIN_VERSION: String = "2.1.0"
    const val KTOR_VERSION: String = "3.0.1"
}
