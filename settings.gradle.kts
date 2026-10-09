rootProject.name = "pipelinek-studio"

pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
    plugins {
        id("org.jetbrains.kotlin.plugin.serialization") version "2.1.0"
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
