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

// SFW-H2.2c (2026-10-13): the Fabric client side. Composite build
// resolves the Fabric contracts via the sibling PipelinekFabric repo;
// the module itself depends on contracts only (no control-plane, no
// worker, no gRPC). See studio-infra-fabric/build.gradle.kts.
includeBuild("../PipelinekFabric") {
    dependencySubstitution {
        // The composite build substitutes the canonical Maven coordinates
        // (io.jenkins.pipelinek.fabric:run-control etc.) for the included
        // builds' subprojects. The substitution is automatic; this block
        // documents it.
    }
}

include(
    "studio-domain",
    "studio-application",
    "studio-infra-storage",
    "studio-infra-sdk",
    "studio-infra-fabric",
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
