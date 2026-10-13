rootProject.name = "pipelinek-studio"

pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        // The Fabric contracts are resolved as Maven coordinates. The
        // contracts are published to the local Maven repo (`mavenLocal()`,
        // i.e. `~/.m2/repository`) by `./gradlew publishToMavenLocal` in
        // the sibling PipelinekFabric repo. The standard Gradle convention
        // for "consume locally-published artifacts" is `mavenLocal()`; it
        // sits in `pluginManagement` because the contracts plugin (none
        // today, but future-proof) would resolve from there too.
        mavenLocal()
    }
    plugins {
        id("org.jetbrains.kotlin.plugin.serialization") version "2.4.20"
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
        // Fabric contracts (the only
        // thing Studio imports from
        // Fabric). The contracts are
        // published as Maven artifacts
        // by PipelinekFabric's
        // `gradle/contracts-publish.gradle.kts`
        // convention, into the local
        // Maven repo.
        //
        // The order matters: `mavenLocal()`
        // is checked first. If the
        // contracts are not present
        // locally (e.g. a fresh clone),
        // the build fails with a clear
        // "I forgot to publish" error
        // — the trade-off documented in
        // the previous composite-build
        // section. To refresh: `cd
        // ../PipelinekFabric && ./gradlew
        // publishToMavenLocal`.
        mavenLocal()
    }
    // No proxy artifacts of PipelineK under test cycles; SDK pinned via libs.versions.toml.
}

include(
    "studio-domain",
    "studio-application",
    "studio-infra-storage",
    "studio-infra-sdk",
    "studio-infra-fabric",
    "studio-infra-podman",
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
