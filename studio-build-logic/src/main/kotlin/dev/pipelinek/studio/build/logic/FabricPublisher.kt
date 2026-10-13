// =============================================================================
// FabricPublisher — SFW H1 Phase A.2 (Part 2)
// =============================================================================
//
// The publisher that backs the root `publishFabricContractsLocal` task.
// When the [FabricContractsVerifier] reports STALE or MISSING, the
// publisher invokes `gradle publishToMavenLocal` on the 6 contract
// sub-modules of the Fabric repo.
//
// # Why a separate object (and not a method on the task)
//
// Same as [FabricContractsVerifier]: the publisher is testable in
// isolation. The task that wraps it (in `FabricContractsPlugin`) is a
// thin shell that wires Gradle's lifecycle to the publisher's
// `publish()` call.
//
// # Why a seam (and not a hard-coded `gradlew` invocation)
//
// The publisher takes an [Executor] interface (the thing that
// actually runs `./gradlew publishToMavenLocal`). In production the
// executor is [ProcessExecutor] (which wraps `ProcessBuilder`); in
// tests it's a fake that records the command. This is the same
// pattern as the H5.3+ `PodmanCommandRunner` port: tests inject a
// fake, no real subprocess.
//
// # Why a 3-arm result (and not a Boolean)
//
// "Did the publish succeed?" is a 3-state question: PUBLISHED (all 6
// sub-modules returned exit 0), FAILED (a sub-module returned non-zero),
// REFUSED (a precondition failed — e.g. the Fabric dir does not exist).
// The Result carries the per-sub-module detail so the operator can
// see which module failed.
//
// # Why the contract modules list is shared (and not duplicated)
//
// The list of 6 modules lives in [FABRIC_CONTRACT_MODULES]. The
// publisher uses it; the verifier uses it; the Gradle task that
// builds the `gradle publishToMavenLocal ...` argv uses it. A
// single source of truth keeps the three in sync.
package dev.pipelinek.studio.build.logic

import java.io.File
import java.util.concurrent.TimeUnit

/**
 * A single command execution. Records the command + the
 * captured stdout/stderr + the exit code.
 */
data class CommandExecution(
    val command: List<String>,
    val workingDir: File,
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
)

/**
 * The executor seam. The default impl is [ProcessExecutor] (real
 * subprocess via `ProcessBuilder`); tests inject a fake.
 */
fun interface Executor {
    fun execute(command: List<String>, workingDir: File): CommandExecution
}

/**
 * Real-process executor. Wraps `ProcessBuilder` and captures
 * stdout/stderr.
 */
class ProcessExecutor(
    private val timeoutSeconds: Long = 600L,
) : Executor {
    override fun execute(command: List<String>, workingDir: File): CommandExecution {
        val proc = ProcessBuilder(command)
            .directory(workingDir)
            .redirectErrorStream(false)
            .start()
        val finished = proc.waitFor(timeoutSeconds, TimeUnit.SECONDS)
        if (!finished) {
            proc.destroyForcibly()
            throw RuntimeException(
                "Process timed out after ${timeoutSeconds}s: ${command.joinToString(" ")}"
            )
        }
        return CommandExecution(
            command = command,
            workingDir = workingDir,
            exitCode = proc.exitValue(),
            stdout = proc.inputStream.bufferedReader().readText(),
            stderr = proc.errorStream.bufferedReader().readText(),
        )
    }
}

/**
 * The 3-arm result of a publish attempt.
 */
sealed class PublishResult {
    /** All 6 sub-modules published successfully. */
    data class Published(
        val executions: List<CommandExecution>,
    ) : PublishResult()

    /** A sub-module returned a non-zero exit code. */
    data class Failed(
        val executions: List<CommandExecution>,
        val failedModule: String,
        val stderr: String,
    ) : PublishResult()

    /** A precondition failed (e.g. the Fabric dir does not exist). */
    data class Refused(
        val reason: String,
    ) : PublishResult()
}

/**
 * The publisher. Invokes `./gradlew publishToMavenLocal` on the 6
 * contract sub-modules of the Fabric repo.
 *
 * The publisher does NOT decide whether to publish; the [FabricContractsVerifier]
 * decides that. The publisher is the executor.
 */
class FabricPublisher(
    private val fabricDir: File,
    private val gradleBinary: String = "./gradlew",
) {
    init {
        require(gradleBinary.isNotBlank()) { "gradleBinary must not be blank" }
    }

    /**
     * Publishes the 6 contract modules to the local Maven repo.
     * The publisher runs each module's `publishToMavenLocal` task
     * as a separate `gradle` invocation; this isolates failures
     * (a failure in `model` does NOT prevent `run-control` from
     * being published).
     */
    fun publish(executor: Executor): PublishResult {
        if (!fabricDir.exists()) {
            return PublishResult.Refused(
                reason = "Fabric repo not found at ${fabricDir.absolutePath} " +
                    "(set -Pfabric.dir=/path/to/PipelinekFabric to override)"
            )
        }
        val gradlew = File(fabricDir, gradleBinary)
        if (!gradlew.exists()) {
            return PublishResult.Refused(
                reason = "Gradle wrapper not found at ${gradlew.absolutePath}"
            )
        }
        val executions = mutableListOf<CommandExecution>()
        for (module in FABRIC_CONTRACT_MODULES) {
            val command = listOf(
                gradleBinary,
                ":contracts:$module:publishToMavenLocal",
                "--no-configuration-cache",
                "--no-daemon",
            )
            val execution = executor.execute(command, fabricDir)
            executions.add(execution)
            if (execution.exitCode != 0) {
                return PublishResult.Failed(
                    executions = executions,
                    failedModule = module,
                    stderr = execution.stderr,
                )
            }
        }
        return PublishResult.Published(executions = executions)
    }

    companion object {
        /**
         * Resolves the Fabric repo dir from a project property
         * (default `../PipelinekFabric`).
         */
        fun resolveFabricDir(projectDir: String): File {
            val fabricDirPath = System.getProperty("fabric.dir")
                ?: System.getenv("FABRIC_DIR")
                ?: "../PipelinekFabric"
            // The default is relative to the Studio project root.
            val base = File(projectDir)
            return if (File(fabricDirPath).isAbsolute) {
                File(fabricDirPath)
            } else {
                File(base, fabricDirPath)
            }
        }
    }
}
