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
    implementation(project(":studio-application"))
    implementation(project(":studio-http"))
    implementation(project(":studio-infra-storage"))
    implementation(libs.kotlin.stdlib)
    implementation(libs.kotlin.reflect)
    implementation(libs.coroutines.core)
    implementation(libs.ktor.server.core)
    implementation("io.ktor:ktor-server-netty:3.0.1")
    implementation("io.ktor:ktor-client-cio:3.0.1")
    implementation(libs.logback.classic)
    // R3.2 (2026-10-11): reuse the ktor-bundled kotlinx-serialization
    // runtime (already on the project classpath via studio-http) for the
    // structured JSON decoder. No new artifact needed — we read the JSON
    // tree (JsonObject / JsonArray / JsonPrimitive) and avoid the plugin
    // @Serializable path; no compiler-plugin config required.
    implementation(libs.ktor.serialization.kotlinx.json)

    testImplementation(libs.bundles.junit5)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.mockk)
    testImplementation(libs.ktor.server.test)
    // SFW-H3.2 (2026-10-13): H3.2 ships LocalRunDbFactReadSource, which
    // reads from a per-run run.db SQLite file at runtime. The JDBC API
    // must be on the main classpath so the production source compiles
    // against `java.sql` and `org.xerial:sqlite-jdbc`; the runtime path
    // is also correct (the JDBC driver is loaded by the JVM via the
    // standard `META-INF/services` lookup).
    implementation(libs.xerial.sqlite.jdbc)
}
