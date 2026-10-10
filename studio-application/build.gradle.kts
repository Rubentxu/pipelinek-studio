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
