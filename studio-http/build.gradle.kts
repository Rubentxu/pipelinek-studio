plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.kotlin.plugin.serialization")
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
    implementation(project(":studio-infra-storage"))
    implementation(libs.kotlin.stdlib)
    implementation(libs.bundles.ktor.server)
    implementation(libs.logback.classic)

    testImplementation(libs.bundles.junit5)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.ktor.server.test)
    testImplementation(libs.mockk)
}
