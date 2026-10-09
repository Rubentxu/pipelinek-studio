plugins {
    id("org.jetbrains.kotlin.jvm")
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
    implementation(libs.kotlin.stdlib)
    implementation(libs.bundles.storage)
    implementation(libs.logback.classic)

    testImplementation(libs.bundles.junit5)
    testImplementation(libs.kotlin.test)
    testRuntimeOnly(libs.xerial.sqlite.jdbc)
}
