package dev.pipelinek.studio.infra.podman

/**
 * SFW-H5.3+ (2026-10-13): the abstract seam the
 * [PodmanRunnerAdapter] uses to invoke the
 * `podman` CLI. The production impl
 * ([ProcessPodmanCommandRunner]) shells out via
 * [ProcessBuilder]; the test impl is a fake
 * that records the call + returns a
 * deterministic result.
 *
 * ## Why an interface (and not a direct ProcessBuilder call)
 *
 * The H5.3+ tests want to exercise the
 * `PodmanRunnerAdapter` without a real `podman`
 * binary. The `PodmanCommandRunner` interface
 * lets the tests inject a fake. A future
 * work-unit may add a
 * `DbusPodmanCommandRunner` for systems where
 * the `podman` CLI is not installed but the
 * Podman D-Bus API is available.
 */
interface PodmanCommandRunner {
    suspend fun run(args: List<String>): PodmanCommandResult
}

sealed interface PodmanCommandResult {
    data class Success(val stdout: String, val exitCode: Int) : PodmanCommandResult
    data class Failure(val stderr: String, val exitCode: Int) : PodmanCommandResult
}

/**
 * SFW-H5.3+ (2026-10-13): the production
 * [PodmanCommandRunner] that invokes the
 * `podman` CLI via [ProcessBuilder]. The
 * subprocess is captured synchronously
 * (the [PodmanRunnerAdapter] runs in a
 * `runBlocking` context at the call site;
 * see the H6.2 + H6.4 runBlocking
 * discipline).
 */
class ProcessPodmanCommandRunner(
    private val podmanBinary: String = "podman",
) : PodmanCommandRunner {

    override suspend fun run(args: List<String>): PodmanCommandResult = try {
        val pb = ProcessBuilder(listOf(podmanBinary) + args)
        val process = pb.start()
        val stdout = process.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        val stderr = process.errorStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        val exitCode = process.waitFor()
        if (exitCode == 0) {
            PodmanCommandResult.Success(stdout = stdout, exitCode = exitCode)
        } else {
            PodmanCommandResult.Failure(stderr = stderr, exitCode = exitCode)
        }
    } catch (failure: Exception) {
        PodmanCommandResult.Failure(
            stderr = "process spawn failed: ${failure.javaClass.simpleName}: ${failure.message}",
            exitCode = -1,
        )
    }
}
