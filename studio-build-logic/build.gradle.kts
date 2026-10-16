// =============================================================================
// studio-build-logic — precompiled build logic for SFW H1 Phase A.2
// =============================================================================
//
// The publish-verify helper used by `build.gradle.kts` (root) lives here as
// a precompiled-script-plugin so the logic is testable in isolation. The
// `buildSrc/` convention is not used because Studio's build cannot import
// project classes (per [[build-script-cant-import-project-classes]]) and
// the helper has no project-class dependencies — only stdlib + JUnit.
//
// # What this module ships
//
// One Kotlin object: [FabricContractsVerifier]. The verifier checks the
// local Maven repo for the 6 Fabric contract artifacts and reports which
// are present, which are stale, and which are missing. The root
// `build.gradle.kts` wraps it in a Gradle task (`publishFabricContractsLocal`).
//
// # Why a precompiled-script-plugin (and not just a top-level Kotlin file in build.gradle.kts)
//
// The root `build.gradle.kts` is a 200+ line file with cross-cutting
// concerns (toolchain, byte-reproducible jars, the verify task, the detekt
// task). Adding a 100+ line helper inline couples Fabric-verify to the
// rest of the root script. A precompiled-script-plugin is `apply`d into
// the root build; the helper is explicit and unit-testable.
//
// # The Kotlin version dance
//
// The `kotlin-dsl` plugin auto-applies its own Kotlin version
// (`embeddedKotlinVersion`, currently 1.8) which is no longer supported.
// The override below uses `tasks.withType<KotlinCompile>` to set the
// language version AFTER `kotlin-dsl` has done its thing, so the
// override sticks. The `kotlin { }` block alone is too early — it's
// evaluated before the plugin's defaults are set, and the plugin's
// defaults win.
plugins {
    `kotlin-dsl`
    `java-test-fixtures`
}

// Register the plugin id so the parent composite build can
// resolve `id("dev.pipelinek.studio.fabric-contracts")` from
// this composite build's `gradlePlugin` namespace.
gradlePlugin {
    plugins {
        create("fabricContracts") {
            id = "dev.pipelinek.studio.fabric-contracts"
            implementationClass = "dev.pipelinek.studio.build.logic.FabricContractsPlugin"
        }
    }
}

repositories {
    gradlePluginPortal()
    mavenCentral()
    mavenLocal()
}

dependencies {
    // The verifier uses only the JDK + stdlib (java.io.File, java.util.Properties).
    // No project deps, no third-party deps.

    // The composite build does NOT share the root `gradle/libs.versions.toml`
    // (only the root project has `libs`). Hardcoded versions are
    // intentional: this is a build-logic module, not a runtime module.
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    testImplementation("org.junit.jupiter:junit-jupiter-api:5.10.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.10.2")
}

tasks.withType<Test> { useJUnitPlatform() }

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    compilerOptions {
        // Override the kotlin-dsl
        // embeddedKotlinVersion
        // (1.8, no longer supported).
        // The `kotlin { }` block is
        // too early — `kotlin-dsl`
        // registers its defaults
        // first; tasks.withType runs
        // after, so the override
        // wins.
        languageVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_2)
        apiVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_2)
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
    }
}
