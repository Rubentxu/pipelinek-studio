plugins {
    kotlin("jvm")
    application
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
    implementation(project(":studio-infra-sdk"))
    implementation(project(":studio-http"))
    implementation(libs.kotlin.stdlib)
    implementation(libs.ktor.server.core)
    implementation("io.ktor:ktor-server-netty:3.0.1")
    implementation(libs.ktor.network.tls.certificates)
    implementation(libs.coroutines.core)
    implementation(libs.logback.classic)

    testImplementation(libs.bundles.junit5)
    testImplementation(libs.kotlin.test)
}

tasks.withType<Test> {
    useJUnitPlatform()
}

application {
    mainClass.set("dev.pipelinek.studio.app.MainKt")
    // S2 (2026-10-16): the launcher used to pin
    //   -Dpipelinek.studio.runtime.dataDir=\${user.home}/... and
    //   -Dpipelinek.studio.runtime.port=7777,
    // which forced the *legacy plain-HTTP* port on every distribution boot and
    // beat the in-code HTTPS default (7778). No JVM defaults are shipped now:
    // `resolveRuntimeConfig` owns the canonical defaults (HTTPS 7778 / plain
    // HTTP 7777 only with --insecure-http, loopback host, ${user.home} data
    // dir), and an explicit -D override still wins. Keeping the defaults in
    // one place is what prevents the scheme/port pair from drifting again.
}
