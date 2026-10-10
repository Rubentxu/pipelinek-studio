plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
}


tasks.withType<Test> {
    useJUnitPlatform()
    // S5 E5.4: tests that exercise the full StudioHttpModule (PerfBaselineTest
    // makes 100+ calls in seconds) opt out of the production rate limit.
    environment("STUDIO_RATE_LIMIT_DISABLED", "1")
}
kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
    }
}

dependencies {
    implementation(project(":studio-domain"))
    implementation(project(":studio-application"))
    implementation(project(":studio-infra-storage"))
    implementation(libs.kotlin.stdlib)
    implementation(libs.bundles.ktor.server)
    implementation(libs.logback.classic)

    testImplementation(libs.bundles.junit5)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.ktor.server.test)
    testImplementation(libs.mockk)
}
