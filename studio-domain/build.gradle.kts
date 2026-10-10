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
    implementation(libs.kotlin.stdlib)
    implementation(libs.kotlin.reflect)

    testImplementation(libs.bundles.junit5)
    testImplementation(libs.kotlin.test)
}
