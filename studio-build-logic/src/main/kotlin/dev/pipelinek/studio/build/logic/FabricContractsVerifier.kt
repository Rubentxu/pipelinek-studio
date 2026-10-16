// =============================================================================
// FabricContractsVerifier — SFW H1 Phase A.2
// =============================================================================
//
// The verifier that backs the root `publishFabricContractsLocal` task. It
// inspects the local Maven repo for the 6 Fabric contract artifacts and
// reports which are present, which are stale, and which are missing.
//
// # Why a separate object (and not a method on the task)
//
// The verifier is testable in isolation. The task that wraps it (in
// `build.gradle.kts`) is a thin shell that wires Gradle's lifecycle to
// the verifier's report. Putting the logic in an object keeps the
// surface small and the tests focused.
//
// # Why it returns a Report (and not a Boolean)
//
// "Are the contracts installed?" is a 3-state question, not a 2-state
// one: ALL_OK, STALE, MISSING. The Report carries the per-module detail
// (so the operator can see what's missing) and a single top-level
// `status` (so the task can branch in one place).
//
// # Why the local repo path is hardcoded (~/.m2/repository) — and not
// # configurable
//
// Studio's build resolves contracts from `mavenLocal()` (per
// `settings.gradle.kts`). `mavenLocal()` is the standard Gradle/Maven
// convention; it points at `~/.m2/repository` on every platform
// (WINDOWS: `%USERPROFILE%/.m2/repository`, MAC/LINUX: `$HOME/.m2/repository`).
// The single-source-of-truth is `org.gradle.api.artifacts.repositories.MavenArtifactRepository.mavenLocal()`,
// not a custom path. Operators with non-standard layouts can override
// via the `MAVEN_REPO` env var (which Gradle respects).
package dev.pipelinek.studio.build.logic

import java.io.File
import java.util.Properties

/**
 * The 6 Fabric contract modules. The set is closed: adding a new
 * contract module requires updating this list AND `settings.gradle.kts`'s
 * `pluginManagement`/`dependencyResolutionManagement` Maven coordinate list.
 */
val FABRIC_CONTRACT_MODULES: List<String> = listOf(
    "model",
    "observation",
    "run-control",
    "transport",
    "wire",
    "worker-session",
)

/**
 * The Maven coordinates shared by all 6 contract modules.
 */
const val FABRIC_GROUP: String = "io.jenkins.pipelinek.fabric"

/**
 * Resolves the local Maven repo root. Honours `MAVEN_REPO` env var
 * (set by some CI runners) and falls back to `~/.m2/repository`.
 */
fun resolveLocalMavenRepo(): File {
    val env = System.getenv("MAVEN_REPO")
    return if (!env.isNullOrBlank()) {
        File(env)
    } else {
        File(System.getProperty("user.home"), ".m2/repository")
    }
}

/**
 * The verifier. Reports the state of the 6 Fabric contract artifacts
 * in the local Maven repo against the expected `pkfVersion`.
 *
 * The verifier does NOT publish anything. It only reports. The
 * publishing is a separate code path (`publishMavenPublicationToMavenLocal`
 * on the Fabric side) that the calling Gradle task invokes when the
 * report says "MISSING" or "STALE".
 */
class FabricContractsVerifier(
    private val expectedVersion: String,
    private val repoRoot: File = resolveLocalMavenRepo(),
) {
    /**
     * The 3-arm status of the local Maven repo with respect to the
     * expected version.
     */
    enum class Status {
        /** All 6 modules present at the expected version. */
        ALL_OK,

        /** Some modules present, some missing, or all present at a different version. */
        STALE,

        /** All 6 modules missing. */
        MISSING,
    }

    data class ModuleState(
        val module: String,
        val expectedVersion: String,
        val installedVersion: String?,
        val pomExists: Boolean,
        val jarExists: Boolean,
    ) {
        val isOk: Boolean
            get() = installedVersion == expectedVersion && pomExists && jarExists
    }

    data class Report(
        val expectedVersion: String,
        val repoRoot: File,
        val status: Status,
        val modules: List<ModuleState>,
    ) {
        val missing: List<ModuleState>
            get() = modules.filter { !it.pomExists }
        val stale: List<ModuleState>
            get() = modules.filter { it.pomExists && it.installedVersion != expectedVersion }
        val ok: List<ModuleState>
            get() = modules.filter { it.isOk }
    }

    fun verify(): Report {
        val states = FABRIC_CONTRACT_MODULES.map { module ->
            val moduleDir = File(repoRoot, "${FABRIC_GROUP.replace('.', '/')}/$module")
            // The Maven local layout is:
            //   <group-as-path>/<module>/<version>/<module>-<version>.(pom|jar)
            val versions = moduleDir.listFiles { f -> f.isDirectory }
                ?.map { it.name }
                ?.sorted()
                ?: emptyList()
            val installedVersion = versions.lastOrNull()
            val pomExists = installedVersion != null &&
                File(moduleDir, "$installedVersion/$module-$installedVersion.pom").exists()
            val jarExists = installedVersion != null &&
                File(moduleDir, "$installedVersion/$module-$installedVersion.jar").exists()
            ModuleState(
                module = module,
                expectedVersion = expectedVersion,
                installedVersion = installedVersion,
                pomExists = pomExists,
                jarExists = jarExists,
            )
        }
        val allMissing = states.all { !it.pomExists }
        val allOk = states.all { it.isOk }
        val status = when {
            allMissing -> Status.MISSING
            allOk -> Status.ALL_OK
            else -> Status.STALE
        }
        return Report(
            expectedVersion = expectedVersion,
            repoRoot = repoRoot,
            status = status,
            modules = states,
        )
    }

    companion object {
        /**
         * Resolves the `pkfVersion` from a Fabric repo's `gradle.properties`.
         * Returns `null` when the file is missing or the key is absent.
         */
        fun readPkfVersion(fabricDir: File): String? {
            val props = File(fabricDir, "gradle.properties")
            if (!props.exists()) return null
            val p = Properties()
            props.inputStream().use { p.load(it) }
            return p.getProperty("pkfVersion")
        }
    }
}
