// Root build — all real work happens in module subprojects.
// Per-module Kotlin compiler options are set in each subproject's build.gradle.kts
// because the Kotlin plugin must already be applied there.
plugins {
    kotlin("jvm") version "2.4.20" apply false
}

allprojects {
    group = "dev.pipelinek.studio"
    // R-VER-001: read the version from gradle.properties so a release
    // bump is a one-line change. Fallback kept only as a safety net
    // for environments where the property is somehow not loaded
    // (the value MUST match gradle.properties:studio.version).
    version = (project.findProperty("studio.version") as String?)
        ?: error("gradle.properties:studio.version is missing")
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

// R0.1.3 (2026-10-11): the CI workflow invokes `./gradlew detekt`
// but no such task existed in the build; the step was effectively
// a no-op that silently passed on every push. This task wraps
// `scripts/detekt-run.sh` so the Gradle invocation matches the
// script's own behavior — Detekt 1.23.8 against the Kotlin sources,
// using the configuration at `detekt.yml`, with the same posture
// the script defaults to.
//
// Posture: the script defaults to `DETEKT_FAIL_ON_FINDINGS=1`
// (any finding exits non-zero). At HEAD the baseline is 25
// findings (LongMethod/NestedBlockDepth/ReturnCount on
// controllers — real refactors, deferred to R5 per the R0
// mandate). Until R5 closes the baseline, this task will fail
// in CI. That is the honest signal — the previous 'no task
// present' state was a false PASS.
//
// Override to opt into the legacy baseline posture:
//   ./gradlew detekt -Pdetekt.failOnFindings=0
// (or DETEKT_FAIL_ON_FINDINGS=0 bash scripts/detekt-run.sh).
val detektScript = file("scripts/detekt-run.sh")
val detektFailOnFindings: String =
    (project.findProperty("detekt.failOnFindings") as String?) ?: "1"

tasks.register<Exec>("detekt") {
    group = "verification"
    description = "Run Detekt against the Kotlin sources via scripts/detekt-run.sh (fail-on-findings posture; override with -Pdetekt.failOnFindings=0)."
    // Note: we intentionally do NOT `dependsOn("test")` — there
    // is no `test` task at the root project (only in subprojects).
    // The detekt script itself does not require tests to be run
    // first; it analyses sources directly. The release-harness
    // workflow runs `test` separately on its own.
    inputs.file(detektScript)
    inputs.file("detekt.yml")
    inputs.dir("studio-domain")
    inputs.dir("studio-application")
    inputs.dir("studio-infra-storage")
    inputs.dir("studio-infra-sdk")
    inputs.dir("studio-http")
    inputs.dir("studio-cli")
    inputs.dir("studio-app")
    environment("DETEKT_FAIL_ON_FINDINGS", detektFailOnFindings)
    commandLine = listOf("bash", detektScript.absolutePath)
}
