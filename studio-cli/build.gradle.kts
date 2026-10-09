plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.kotlin.plugin.serialization")
    application
}


tasks.withType<Test> { useJUnitPlatform() }
kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
    }
}

// CLI build needs ktor client (CIO + content-negotiation + kotlinx-json)
dependencies {
    implementation(project(":studio-domain"))
    implementation(project(":studio-application"))
    implementation(project(":studio-http"))
    implementation(project(":studio-infra-storage"))
    implementation(libs.kotlin.stdlib)
    implementation(libs.bundles.ktor.server)
    implementation("io.ktor:ktor-client-cio:3.0.1")
    implementation("io.ktor:ktor-client-content-negotiation:3.0.1")
    implementation("io.ktor:ktor-serialization-kotlinx-json:3.0.1")
    implementation(libs.logback.classic)
    implementation(libs.coroutines.core)

    testImplementation(libs.bundles.junit5)
    testImplementation(libs.kotlin.test)
}

application {
    mainClass.set("dev.pipelinek.studio.cli.MainKt")
}
