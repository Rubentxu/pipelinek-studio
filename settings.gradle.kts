rootProject.name = "pipelinek-studio"

pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
    plugins {
        id("org.jetbrains.kotlin.plugin.serialization") version "2.4.20"
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
    }
    // No proxy artifacts of PipelineK under test cycles; SDK pinned via libs.versions.toml.
}

include(
    "studio-domain",
    "studio-application",
    "studio-infra-storage",
    "studio-infra-sdk",
    "studio-http",
    "studio-cli",
    "studio-app",
)

// R2.1 (2026-10-11): external SDK fixture project. This is a
// STANDALONE Gradle subproject with NO dependency on the studio
// modules. Its only purpose is to produce three JARs (valid-plugin,
// incompatible-api-plugin, canary-initializer-plugin) whose static
// manifest + META-INF/services + canary marker are read by the
// studio catalog/admission tests. See:
//   examples/sdk-external-execution/build.gradle.kts
//   docs/02-specifications/STEP_PLUGIN_LAB.md
include("examples:sdk-external-execution")
