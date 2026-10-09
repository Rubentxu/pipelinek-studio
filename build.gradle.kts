// Root build — all real work happens in module subprojects.
// Per-module Kotlin compiler options are set in each subproject's build.gradle.kts
// because the Kotlin plugin must already be applied there.
plugins {
    id("org.jetbrains.kotlin.jvm") version "2.1.0" apply false
}

allprojects {
    group = "dev.pipelinek.studio"
    version = "0.1.0-alpha.1"
}

tasks.register("cleanAll") {
    group = "build"
    description = "Clean all subprojects build dirs."
    dependsOn(subprojects.map { it.tasks.named("clean") })
}

// S5 E5.7 — wraps scripts/verify-from-clean-clone.sh as a Gradle task so CI
// systems can run the certification with `./gradlew verifyCleanClone` instead
// of invoking the shell script directly. The task depends on `test` so a
// failing test blocks certification; the script's own assertions then
// validate the post-build surfaces.
tasks.register<Exec>("verifyCleanClone") {
    group = "verification"
    description = "Run the from-clean-clone certification harness (5+ checks: tests, distTar, /health, /api/version, /openapi.json, backup, SDK TS, detekt, VS Code scaffold, Monaco, rate limit)."
    dependsOn("test")
    val script = file("scripts/verify-from-clean-clone.sh")
    inputs.file(script)
    inputs.dir("studio-http")
    inputs.dir("studio-cli")
    inputs.dir("studio-app")
    inputs.file("detekt.yml")
    inputs.dir("scripts")
    inputs.dir("apps")
    commandLine = listOf("bash", script.absolutePath)
}
