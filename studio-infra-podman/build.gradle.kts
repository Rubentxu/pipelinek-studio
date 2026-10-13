// =============================================================================
// studio-infra-podman — Studio's Podman container adapter (SFW-H5.3)
// =============================================================================
// The Studio-side adapter that bridges the [PodmanRunnerPort] abstract
// surface (declared in studio-application/ports/Ports.kt) to a real
// `podman run` invocation. H5.3 ships the abstract surface + a stub
// adapter that is honest about the unwired state; the actual
// `podman run` invocation (the H5 UAT "podman-container run") is a
// follow-up H5.3+ unit.
//
// # Why a separate module
//
// Podman is a different infra from Fabric. The H2.2c discipline
// ("a Studio installation that talks to a Fabric server is a
// Studio installation that does not stop being a Studio installation")
// applies here too: a Studio installation that runs in a Podman
// container is a Studio installation that does not stop being a
// Studio installation. The module keeps the seams narrow.

plugins {
    kotlin("jvm")
}

dependencies {
    implementation(project(":studio-domain"))
    implementation(project(":studio-application"))
    // B6.0: the standalone dist bundles a custom JRE; the test
    // container in CI must include the same JDK 21 baseline. The
    // `kotlin.stdlib` is the minimum; the H5.3 chain-start does
    // not pull in the Podman Java bindings (the H5.3+ follow-up
    // will, when the actual `podman run` invocation is wired).
}

tasks.withType<Test> {
    useJUnitPlatform()
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
    }
}
