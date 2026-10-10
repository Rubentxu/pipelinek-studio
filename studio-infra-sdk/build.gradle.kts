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

    testImplementation(libs.bundles.junit5)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.mockk)
    testImplementation(libs.ktor.server.test)
    testRuntimeOnly(libs.xerial.sqlite.jdbc)
}
