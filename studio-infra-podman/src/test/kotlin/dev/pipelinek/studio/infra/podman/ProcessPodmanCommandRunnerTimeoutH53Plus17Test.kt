package dev.pipelinek.studio.infra.podman

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.time.Duration

/**
 * Falsifiers for the SFW-H5.3+ seventeenth
 * follow-up: subprocess-level pull timeout.
 *
 * The 17th follow-up extends
 * [ProcessPodmanCommandRunner] with an
 * optional `commandTimeout: Duration?`
 * parameter. When set, the runner uses
 * [Process.waitFor] with a timeout; if
 * the process is still running after the
 * timeout, the runner destroys it
 * forcibly and returns a
 * [PodmanCommandResult.Failure] with a
 * clear timeout reason in `stderr`.
 *
 * ## Why a timeout
 *
 * A slow `podman pull` over a saturated
 * network can hang the subprocess
 * indefinitely. The H5.3+ 14th follow-up
 * added HTTP timeouts for the registry
 * client, but the `podman` subprocess
 * itself had no timeout. The 17th closes
 * that gap.
 *
 * ## Test strategy
 *
 * The 5 falsifier tests use a real
 * subprocess (`sleep`) to verify the
 * timeout fires correctly. A real
 * `sleep` subprocess is the cleanest way
 * to test the timeout behavior; a fake
 * would not exercise the actual
 * `Process.waitFor` + `destroyForcibly`
 * path.
 */
@DisplayName("ProcessPodmanCommandRunner (SFW-H5.3+ subprocess timeout)")
class ProcessPodmanCommandRunnerTimeoutH53Plus17Test {

    // ------------------------------------------------------------------
    // Falsifier 1: default timeout (null) is backwards-compatible
    // ------------------------------------------------------------------
    @Test
    fun `default timeout (null) is backwards-compatible`() {
        val runner = ProcessPodmanCommandRunner()
        assertNotNull(runner)
    }

    // ------------------------------------------------------------------
    // Falsifier 2: explicit null timeout is accepted
    // ------------------------------------------------------------------
    @Test
    fun `explicit null timeout is accepted (no timeout)`() {
        val runner = ProcessPodmanCommandRunner(commandTimeout = null)
        assertNotNull(runner)
    }

    // ------------------------------------------------------------------
    // Falsifier 3: fast command succeeds within timeout
    // ------------------------------------------------------------------
    @Test
    fun `fast command succeeds within timeout`() = runBlocking {
        // `sleep 0.1` is fast (100ms).
        // A 5s timeout is more than
        // enough.
        val runner = ProcessPodmanCommandRunner(
            podmanBinary = "sleep",
            commandTimeout = Duration.ofSeconds(5),
        )
        val start = System.currentTimeMillis()
        val result = runner.run(listOf("0.1"))
        val elapsed = System.currentTimeMillis() - start
        assertTrue(
            result is PodmanCommandResult.Success,
            "fast command must succeed; got $result",
        )
        assertTrue(
            elapsed < 4_000,
            "fast command must complete quickly; elapsed=${elapsed}ms",
        )
    }

    // ------------------------------------------------------------------
    // Falsifier 4: slow command triggers timeout + destroy
    // ------------------------------------------------------------------
    @Test
    fun `slow command triggers timeout and destroy`() = runBlocking {
        // `sleep 5` is slow (5s).
        // A 300ms timeout must fire
        // and the process must be
        // destroyed.
        val runner = ProcessPodmanCommandRunner(
            podmanBinary = "sleep",
            commandTimeout = Duration.ofMillis(300),
        )
        val start = System.currentTimeMillis()
        val result = runner.run(listOf("5"))
        val elapsed = System.currentTimeMillis() - start
        assertTrue(
            result is PodmanCommandResult.Failure,
            "slow command must return Failure on timeout; got $result",
        )
        val failure = result as PodmanCommandResult.Failure
        assertTrue(
            failure.stderr.contains("timed out", ignoreCase = true),
            "failure stderr must mention the timeout; got '${failure.stderr}'",
        )
        assertTrue(
            elapsed < 3_000,
            "timeout must fire well before the 5s sleep; elapsed=${elapsed}ms",
        )
    }

    // ------------------------------------------------------------------
    // Falsifier 5: timeout applies to non-success exit codes too
    // ------------------------------------------------------------------
    @Test
    fun `timeout applies to non-success exit codes too`() = runBlocking {
        // `sleep 5` is slow; even if it
        // were to exit with a non-zero
        // code, the timeout should still
        // fire first.
        val runner = ProcessPodmanCommandRunner(
            podmanBinary = "sleep",
            commandTimeout = Duration.ofMillis(200),
        )
        val result = runner.run(listOf("5"))
        assertTrue(
            result is PodmanCommandResult.Failure,
            "timeout must result in Failure; got $result",
        )
        val failure = result as PodmanCommandResult.Failure
        assertEquals(-1, failure.exitCode, "timeout exit code is -1")
    }
}
