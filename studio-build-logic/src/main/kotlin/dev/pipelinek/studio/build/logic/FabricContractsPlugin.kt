// =============================================================================
// dev.pipelinek.studio.fabric-contracts — precompiled script plugin
// =============================================================================
//
// The Gradle plugin id (resolved from the package + filename by the
// `kotlin-dsl` plugin) is `dev.pipelinek.studio.fabric-contracts`.
// The root `build.gradle.kts` `apply`s it once and gains:
//   - A `verifyFabricContracts` task (the 3-arm guard described below).
//   - The helper class [FabricContractsVerifier] in scope (the task
//     uses it; tests use it directly).
//
// # What the task does
//
// The `verifyFabricContracts` task inspects the local Maven repo for
// the 6 Fabric contract artifacts and reports which are present,
// which are stale, and which are missing. The task exits 0 (PASS)
// when all 6 are at the expected version; otherwise it throws a
// `GradleException` with a clear message and a pointer to the
// operator-driven fix (`cd ../PipelinekFabric && ./gradlew
// publishToMavenLocal`).
//
// # Why a plugin (and not a top-level helper in build.gradle.kts)
//
// The root `build.gradle.kts` is a 200+ line file. Adding a 100+ line
// inline helper would couple Fabric-verify to the rest of the root
// build. A plugin isolates the surface; the helper is testable in
// isolation; the build script stays a thin wiring layer.
package dev.pipelinek.studio.build.logic

import dev.pipelinek.studio.build.logic.FabricContractsVerifier.Status
import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalog
import org.gradle.api.artifacts.VersionCatalogsExtension
import java.io.File

class FabricContractsPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        // The version catalog is a Project extension (`VersionCatalogsExtension`);
        // `target.libs` is the syntactic sugar the root build script gets, but
        // inside a plugin we resolve it explicitly.
        val catalogs: VersionCatalogsExtension =
            target.extensions.getByType(VersionCatalogsExtension::class.java)
        val libs: VersionCatalog = catalogs.named("libs")
        val expectedVersion: String = libs.findVersion("fabric").orElseThrow {
            IllegalStateException("gradle/libs.versions.toml must declare [versions] fabric")
        }.requiredVersion

        val fabricDirPath: String =
            (target.findProperty("fabric.dir") as String?) ?: "../PipelinekFabric"
        val fabricDir: File = if (File(fabricDirPath).isAbsolute) {
            File(fabricDirPath)
        } else {
            File(target.rootProject.projectDir, fabricDirPath)
        }

        // R3 SFW H1 Phase B Part 5: capture Project-derived values at
        // configuration time. The `doLast` closures below MUST NOT
        // reference `target` (a `Project`) — config-cache cannot
        // serialize `Project` instances into its cache blob.
        // `org.gradle.configuration-cache=true` in gradle.properties
        // would fail to store the configuration cache, breaking the
        // harness on a clean machine. See MEMORY.md
        // `studio-fabric-composite-build-ccache-conflict.md` (same
        // family — `Project` references in task closures break
        // config-cache).
        val logger = target.logger

        target.tasks.register("verifyFabricContracts") {
            group = "verification"
            description = "Verify that the 6 Fabric contract artifacts are present in the local Maven repo at the version pinned in gradle/libs.versions.toml."
            doLast {
                val verifier = FabricContractsVerifier(expectedVersion = expectedVersion)
                val report = verifier.verify()
                when (report.status) {
                    Status.ALL_OK -> {
                        logger.lifecycle(
                            "verifyFabricContracts OK — all 6 Fabric contracts at $expectedVersion in ${report.repoRoot}"
                        )
                    }
                    Status.MISSING -> {
                        val missing = report.missing.joinToString(", ") { it.module }
                        throw GradleException(
                            "Fabric contracts not found in ${report.repoRoot}.\n" +
                                "  Missing: $missing\n" +
                                "  Expected version: $expectedVersion (from gradle/libs.versions.toml)\n" +
                                "  Auto-fix: ./gradlew publishFabricContractsLocal\n" +
                                "  (or set -Pfabric.dir=/path/to/PipelinekFabric to override the Fabric repo path)"
                        )
                    }
                    Status.STALE -> {
                        val detail = (report.missing + report.stale).joinToString(", ") { state ->
                            if (state.installedVersion == null) "${state.module} (missing)"
                            else "${state.module}@${state.installedVersion} (expected $expectedVersion)"
                        }
                        throw GradleException(
                            "Fabric contracts are STALE in ${report.repoRoot}.\n" +
                                "  Mismatched: $detail\n" +
                                "  Expected version: $expectedVersion (from gradle/libs.versions.toml)\n" +
                                "  Auto-fix: ./gradlew publishFabricContractsLocal\n" +
                                "  (or set -Pfabric.dir=/path/to/PipelinekFabric to override the Fabric repo path)"
                        )
                    }
                }
            }
        }

        // R3 SFW H1 Phase A.2 Part 2: the auto-publish task. Verifies first
        // (idempotent skip if ALL_OK); publishes only when STALE or MISSING.
        // The `force` flag bypasses the verify (escape hatch).
        target.tasks.register("publishFabricContractsLocal") {
            group = "verification"
            description = "Verify-and-publish the 6 Fabric contract artifacts to the local Maven repo. Idempotent (skips when ALL_OK)."
            doLast {
                val verifier = FabricContractsVerifier(expectedVersion = expectedVersion)
                val report = verifier.verify()
                when (report.status) {
                    Status.ALL_OK -> {
                        logger.lifecycle(
                            "publishFabricContractsLocal — nothing to do (all 6 contracts at $expectedVersion)"
                        )
                    }
                    Status.MISSING, Status.STALE -> {
                        logger.lifecycle(
                            "publishFabricContractsLocal — status ${report.status} detected, publishing..."
                        )
                        val publisher = FabricPublisher(fabricDir = fabricDir)
                        val result = publisher.publish(ProcessExecutor())
                        when (result) {
                            is PublishResult.Published -> {
                                logger.lifecycle(
                                    "publishFabricContractsLocal — published ${result.executions.size} contracts to local Maven repo"
                                )
                            }
                            is PublishResult.Failed -> {
                                throw GradleException(
                                    "publishFabricContractsLocal FAILED on ${result.failedModule} " +
                                        "(exit non-zero). Stderr:\n${result.stderr}\n" +
                                        "Re-run with --info for the full Gradle output."
                                )
                            }
                            is PublishResult.Refused -> {
                                throw GradleException(
                                    "publishFabricContractsLocal REFUSED: ${result.reason}"
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

