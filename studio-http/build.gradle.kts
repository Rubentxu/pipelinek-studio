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
    // R0.2.2 (2026-10-11): TlsConfig (relocated from studio-app to make it
    // reachable from studio-cli's `studio start`) needs the cert-builder
    // DSL. Netty's io.netty.handler.ssl.SslContextBuilder arrives
    // transitively from ktor-server-netty.
    implementation(libs.ktor.network.tls.certificates)
    implementation(libs.logback.classic)

    testImplementation(libs.bundles.junit5)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.ktor.server.test)
    testImplementation(libs.mockk)
}
