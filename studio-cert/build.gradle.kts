// B5.4 (2026-10-10): the independent
// certification harness.
//
// The harness is a standalone Gradle project
// that lives OUTSIDE the main pipelinek-studio
// build graph. It exercises the *installed*
// distribution, not the src. The build is
// intentionally minimal: just enough to
// compile + run the CertProtocol unit tests.

plugins {
    id("org.jetbrains.kotlin.jvm") version "2.4.20"
    id("org.jetbrains.kotlin.plugin.serialization") version "2.4.20"
    application
}

repositories {
    mavenCentral()
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
    }
}

dependencies {
    implementation("org.jetbrains.kotlin:kotlin-stdlib:2.4.20")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    testImplementation(platform("org.junit:junit-bom:5.11.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5:2.4.20")
}

tasks.withType<Test> { useJUnitPlatform() }
