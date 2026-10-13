package dev.pipelinek.studio.infra.podman

import java.time.Duration
import java.util.concurrent.TimeUnit

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
 *
 * SFW-H5.3+ 17th follow-up: optional
 * [commandTimeout] (the total wall-clock
 * time the subprocess is allowed to run).
 * When set, the runner uses
 * [Process.waitFor] with a timeout; if the
 * process is still running after the
 * timeout, the runner destroys it
 * (`Process.destroyForcibly`) and returns
 * a [PodmanCommandResult.Failure] with
 * a clear timeout reason in `stderr`. When
 * `null` (the default), the runner uses
 * the blocking [Process.waitFor] (no
 * timeout, backwards-compatible).
 *
 * ## Implementation note
 *
 * `process.inputStream.readText()` blocks
 * until the process closes its stdout.
 * For a slow `podman pull` that writes
 * nothing to stdout, the read blocks for
 * the full subprocess duration — which
 * would defeat the timeout. The 17th
 * follow-up reads stdout and stderr in
 * parallel coroutines, so the timeout
 * can fire independently of the stream
 * reads.
 *
 * ## Why a timeout
 *
 * A slow `podman pull` over a saturated
 * network can hang the subprocess
 * indefinitely. Without a timeout, the
 * Studio process can be blocked forever
 * on a single `runInContainer` call.
 */
class ProcessPodmanCommandRunner(
    private val podmanBinary: String = "podman",
    private val commandTimeout: Duration? = null,
) : PodmanCommandRunner {

    override suspend fun run(args: List<String>): PodmanCommandResult = try {
        val pb = ProcessBuilder(listOf(podmanBinary) + args)
        val process = pb.start()

        // Read stdout and stderr in
        // parallel threads. This is
        // critical for the timeout to
        // work: a blocking
        // `process.inputStream.readText()`
        // would wait for the process to
        // close its stdout, which only
        // happens when the process exits
        // (possibly much later than the
        // timeout).
        val stdoutRef = java.util.concurrent.atomic.AtomicReference<String?>(null)
        val stderrRef = java.util.concurrent.atomic.AtomicReference<String?>(null)
        val stdoutThread = Thread {
            stdoutRef.set(process.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() })
        }
        val stderrThread = Thread {
            stderrRef.set(process.errorStream.bufferedReader(Charsets.UTF_8).use { it.readText() })
        }
        stdoutThread.isDaemon = true
        stderrThread.isDaemon = true
        stdoutThread.start()
        stderrThread.start()

        val finished = if (commandTimeout != null) {
            val ok = process.waitFor(commandTimeout.toMillis(), TimeUnit.MILLISECONDS)
            if (!ok) {
                process.destroyForcibly()
                process.waitFor(1, TimeUnit.SECONDS)
            }
            ok
        } else {
            process.waitFor()
            true
        }
        // Wait briefly for the drain
        // threads to finish (they will
        // when the destroyed process
        // closes its streams).
        stdoutThread.join(1000)
        stderrThread.join(1000)

        if (!finished) {
            return PodmanCommandResult.Failure(
                stderr = "process timed out after ${commandTimeout!!.toMillis()}ms " +
                    "(destroyed forcibly); args=${args.joinToString(" ")}",
                exitCode = -1,
            )
        }
        val stdout = stdoutRef.get() ?: ""
        val stderr = stderrRef.get() ?: ""
        val exitCode = process.exitValue()
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
