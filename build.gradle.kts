// Root build — all real work happens in module subprojects.
// Per-module Kotlin compiler options are set in each subproject's build.gradle.kts
// because the Kotlin plugin must already be applied there.
plugins {
    kotlin("jvm") version "2.4.20" apply false
    // R3 SFW H1 Phase A.2: apply the precompiled-script-plugin
    // that registers `verifyFabricContracts`. The plugin id
    // matches the file name
    // (`FabricContractsPlugin.kt` in package
    // `dev.pipelinek.studio.build.logic` resolves to
    // `dev.pipelinek.studio.fabric-contracts`).
    id("dev.pipelinek.studio.fabric-contracts")
}

allprojects {
    group = "dev.pipelinek.studio"
    // R-VER-001: read the version from gradle.properties so a release
    // bump is a one-line change. Fallback kept only as a safety net
    // for environments where the property is somehow not loaded
    // (the value MUST match gradle.properties:studio.version).
    version = (project.findProperty("studio.version") as String?)
        ?: error("gradle.properties:studio.version is missing")

    // R6.3 follow-up (2026-10-12): byte-reproducible jars via
    // SOURCE_DATE_EPOCH. Two `clean installDist distTar` runs
    // now produce byte-identical artifacts when
    // SOURCE_DATE_EPOCH is set (e.g. 1700000000). The fix
    // pins `isReproducibleFileOrder` + `isPreserveFileTimestamps`
    // (Gradle 5+ official API) and rewrites the jar's
    // `lastModified` to a fixed epoch so the published SHA
    // is a reproducible artifact of (source, version, env).
    afterEvaluate {
        tasks.withType<Jar>().configureEach {
            isReproducibleFileOrder = true
            isPreserveFileTimestamps = false
            // The Jar task's manifest is the only place the
            // build timestamp appears. Rewrite the manifest's
            // `Build-Time` attribute from the SOURCE_DATE_EPOCH
            // env var (default 2023-11-14T22:13:20Z if unset).
            // Future: a Gradle property to opt-out of the
            // manifest rewrite (currently the manifest is always
            // present; this is additive and small).
            val sde = System.getenv("SOURCE_DATE_EPOCH")
                ?: "1700000000"
            manifest {
                attributes(
                    "Build-Time" to sde,
                )
            }
            doLast {
                val epochMs = (sde.toLongOrNull() ?: 1700000000L) * 1000L
                outputs.files.forEach { f ->
                    if (f.exists()) f.setLastModified(epochMs)
                }
            }
        }
    }
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

// R3.5 (2026-10-11): exercise the +/− Detekt fixtures.
// Runs Detekt twice via the SAME pinned CLI jar that the main
// `detekt` task uses (1.23.8 from `libs.versions.toml`):
//   1. Positive fixture (`FixturePositive.kt`) must report 0 findings.
//   2. Negative fixture (`FixtureNegativeLongMethod.kt`) must report
//      at least one `LongMethod` finding.
// Any deviation (rule deactivated, threshold changed, fixture
// refactored silently) makes this task fail — that is the
// regression guard the R3.5 mandate requires.
tasks.register("detektFixtures") {
    group = "verification"
    description = "Run Detekt against the R3.5 positive/negative fixtures and assert the expected outcomes."
    inputs.file(detektScript)
    inputs.file("detekt.yml")
    inputs.dir("detekt-fixtures")
    // The doLast closure uses ProcessBuilder + project-relative paths;
    // Gradle's configuration cache cannot serialise Project-references
    // from a task action, so we opt this task out of the cache. The
    // release-harness workflow runs the task fresh every time anyway.
    notCompatibleWithConfigurationCache("R3.5 fixtures task runs ProcessBuilder with project paths; opt out of cache")
    doLast {
        val cache = System.getenv("DETEKT_CACHE") ?: "${System.getProperty("user.home")}/.cache/detekt"
        val detektVersion = System.getenv("DETEKT_VERSION") ?: "1.23.8"
        val jar = "$cache/detekt-cli-$detektVersion-all.jar"
        require(java.io.File(jar).exists()) {
            "detekt-cli jar missing at $jar; run `./gradlew detekt` first to populate the cache"
        }
        val fixturesRoot = file("detekt-fixtures/src/main/kotlin")

        fun runDetektOn(label: String, srcRel: String): Int {
            val out = file("build/reports/detekt-fixtures-$label.txt")
            out.parentFile.mkdirs()
            val proc = ProcessBuilder(
                "java", "-jar", jar,
                "--input", fixturesRoot.resolve(srcRel).absolutePath,
                "--config", file("detekt.yml").absolutePath,
                "--report", "txt:${out.absolutePath}",
            ).redirectErrorStream(true).start()
            val log = proc.inputStream.bufferedReader().readText()
            file("build/reports/detekt-fixtures-$label.log").writeText(log)
            return proc.waitFor()
        }

        // Positive: must be clean (exit 0).
        val posExit = runDetektOn("positive", "dev/pipelinek/detekt/fixtures/FixturePositive.kt")
        require(posExit == 0) {
            "FixturePositive.kt must NOT trigger any rule (exit 0); got exit=$posExit; see build/reports/detekt-fixtures-positive.log"
        }
        // Negative: must fail AND contain 'LongMethod' in the report.
        val negExit = runDetektOn("negative", "dev/pipelinek/detekt/fixtures/FixtureNegativeLongMethod.kt")
        val negReport = file("build/reports/detekt-fixtures-negative.txt").readText()
        require(negExit != 0) {
            "FixtureNegativeLongMethod.kt MUST trigger at least one rule (exit != 0); got exit=$negExit"
        }
        require("LongMethod" in negReport) {
            "FixtureNegativeLongMethod.kt must fire 'LongMethod'; report missing the rule. Report:\n$negReport"
        }
        logger.lifecycle("detektFixtures OK — positive clean, negative fires LongMethod")
    }
}
