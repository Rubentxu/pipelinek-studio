plugins {
    id("org.jetbrains.kotlin.jvm")
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
    implementation(libs.coroutines.core)
    implementation(libs.logback.classic)

    testImplementation(libs.bundles.junit5)
    testImplementation(libs.kotlin.test)
}

application {
    mainClass.set("dev.pipelinek.studio.app.MainKt")
    applicationDefaultJvmArgs = listOf(
        "-Dpipelinek.studio.runtime.dataDir=\${user.home}/.pipelinek-studio/runtime",
        "-Dpipelinek.studio.runtime.host=127.0.0.1",
        "-Dpipelinek.studio.runtime.port=7777",
    )
}
