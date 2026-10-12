plugins {
    kotlin("jvm")
}


tasks.withType<Test> { useJUnitPlatform() }
kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
    }
}

dependencies {
    implementation(project(":studio-domain"))
    implementation(libs.kotlin.stdlib)
    implementation(libs.kotlin.reflect)
    implementation(libs.coroutines.core)
    implementation(libs.logback.classic)

    testImplementation(libs.bundles.junit5)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.mockk)
}

// C0.1 (2026-10-12): the StepTestHarnessR28Test needs the
// three fixture jars produced by the examples:sdk-external-execution
// module. Per the mandate, the test must fail visibly if the
// fixtures are missing — not be skipped, not produce a soft-PASS.
//
// We wire the jar paths via Gradle properties derived from
// the build graph (NOT a relative path) so:
//   * The test :test task depends on the upstream :assemble,
//     so the jars exist before the test starts.
//   * The absolute jar paths are passed as system properties
//     to JUnit, so the test resolves them deterministically.
//   * If the dependency is removed or the upstream is skipped,
//     Gradle fails the test task — there is no way to run the
//     StepTestHarnessR28Test without the fixtures.
val externalFixturesRoot: org.gradle.api.file.Directory =
    rootProject.layout.projectDirectory.dir("examples/sdk-external-execution/build/libs")

tasks.test {
    dependsOn(":examples:sdk-external-execution:assemble")
    systemProperty(
        "studio.test.fixture.validPlugin",
        externalFixturesRoot.file("valid-plugin-0.1.0.jar").asFile.absolutePath,
    )
    systemProperty(
        "studio.test.fixture.canaryInitializerPlugin",
        externalFixturesRoot.file("canary-initializer-plugin-0.1.0.jar").asFile.absolutePath,
    )
    systemProperty(
        "studio.test.fixture.incompatibleApiPlugin",
        externalFixturesRoot.file("incompatible-api-plugin-99.0.0.jar").asFile.absolutePath,
    )
}
