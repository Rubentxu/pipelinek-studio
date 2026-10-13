package dev.pipelinek.studio.infra.podman

import dev.pipelinek.studio.application.ports.ExecutionProfile
import dev.pipelinek.studio.application.ports.RunRequest
import dev.pipelinek.studio.domain.RunId
import dev.pipelinek.studio.domain.ScenarioId
import dev.pipelinek.studio.domain.SnapshotId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicReference

/**
 * Falsifiers for the SFW-H5.3+ sixteenth
 * follow-up: configurable image pull
 * policy. The 16th follow-up extends the
 * adapter with a `pullPolicy: String`
 * constructor parameter. The valid
 * values are `"always"`, `"missing"`
 * (default, backwards-compatible), and
 * `"never"`.
 *
 * ## Why a pull policy
 *
 * The H5.3+ chain start hardcoded the
 * default `podman run` pull policy
 * (`missing` — pull only when the image
 * is not in the local cache). Some
 * production deployments want a
 * different policy:
 * - **Pre-pulled images** (operators
 *   that pre-warm the local cache via
 *   a separate `podman pull` step):
 *   use `"never"` to avoid the
 *   network round-trip on every
 *   `podman run`.
 * - **Always-fresh images**
 *   (CI/CD environments that want
 *   the latest tag on every run):
 *   use `"always"`.
 *
 * 5/5 falsifier tests verify the
 * `--pull <policy>` flag is passed
 * correctly.
 */
@DisplayName("PodmanRunnerAdapter (SFW-H5.3+ pull policy)")
class PodmanRunnerAdapterPullPolicyH53Plus16Test {

    // ------------------------------------------------------------------
    // Falsifier 1: default policy is "missing" (backwards-compatible)
    // ------------------------------------------------------------------
    @Test
    fun `default pull policy is missing (backwards-compatible)`() = kotlinx.coroutines.runBlocking {
        val captured = AtomicReference<List<String>?>(null)
        val runner = PodmanRunnerAdapter(
            commandRunner = CapturingPodmanCommandRunner(captured),
        )
        runner.runInContainer(
            request = runRequest(),
            image = "alpine:latest",
            hostControlRoot = Path.of("/studio"),
        )
        val args = captured.get()!!
        val pullIndex = args.indexOf("--pull")
        assertTrue(pullIndex >= 0, "expected --pull flag; args=$args")
        assertEquals(
            "missing",
            args[pullIndex + 1],
            "default pull policy must be 'missing'",
        )
    }

    // ------------------------------------------------------------------
    // Falsifier 2: "always" policy pulls every time
    // ------------------------------------------------------------------
    @Test
    fun `always policy pulls every time`() = kotlinx.coroutines.runBlocking {
        val captured = AtomicReference<List<String>?>(null)
        val runner = PodmanRunnerAdapter(
            commandRunner = CapturingPodmanCommandRunner(captured),
            pullPolicy = "always",
        )
        runner.runInContainer(
            request = runRequest(),
            image = "alpine:latest",
            hostControlRoot = Path.of("/studio"),
        )
        val args = captured.get()!!
        val pullIndex = args.indexOf("--pull")
        assertEquals("always", args[pullIndex + 1])
    }

    // ------------------------------------------------------------------
    // Falsifier 3: "never" policy never pulls
    // ------------------------------------------------------------------
    @Test
    fun `never policy never pulls (uses local cache)`() = kotlinx.coroutines.runBlocking {
        val captured = AtomicReference<List<String>?>(null)
        val runner = PodmanRunnerAdapter(
            commandRunner = CapturingPodmanCommandRunner(captured),
            pullPolicy = "never",
        )
        runner.runInContainer(
            request = runRequest(),
            image = "alpine:latest",
            hostControlRoot = Path.of("/studio"),
        )
        val args = captured.get()!!
        val pullIndex = args.indexOf("--pull")
        assertEquals("never", args[pullIndex + 1])
    }

    // ------------------------------------------------------------------
    // Falsifier 4: pull policy + TCP socket compose
    // ------------------------------------------------------------------
    @Test
    fun `pull policy and TCP socket compose correctly`() = kotlinx.coroutines.runBlocking {
        val captured = AtomicReference<List<String>?>(null)
        val runner = PodmanRunnerAdapter(
            commandRunner = CapturingPodmanCommandRunner(captured),
            socketPath = "tcp://podman-host.example.com:2375",
            pullPolicy = "never",
        )
        runner.runInContainer(
            request = runRequest(),
            image = "alpine:latest",
            hostControlRoot = Path.of("/studio"),
        )
        val args = captured.get()!!
        assertEquals(
            "tcp://podman-host.example.com:2375",
            args[args.indexOf("--url") + 1],
            "TCP socket URL must be preserved",
        )
        assertEquals(
            "never",
            args[args.indexOf("--pull") + 1],
            "pull policy must be 'never'",
        )
    }

    // ------------------------------------------------------------------
    // Falsifier 5: stopContainer is NOT affected by pullPolicy
    // ------------------------------------------------------------------
    @Test
    fun `stopContainer is not affected by pullPolicy`() = kotlinx.coroutines.runBlocking {
        val captured = AtomicReference<List<String>?>(null)
        val runner = PodmanRunnerAdapter(
            commandRunner = CapturingPodmanCommandRunner(captured),
            pullPolicy = "always",
        )
        runner.stopContainer("container-abc123")
        val args = captured.get()!!
        assertTrue(
            !args.contains("--pull"),
            "stopContainer must NOT include --pull flag; args=$args",
        )
        assertTrue(
            args.contains("rm"),
            "stopContainer must include 'rm' subcommand; args=$args",
        )
    }

    // --------------------------------------------------------------------
    // Test helpers
    // --------------------------------------------------------------------

    private fun runRequest(): RunRequest = RunRequest(
        runId = RunId("run-001"),
        snapshotId = SnapshotId("snap-001"),
        scenarioId = ScenarioId("scn-001"),
        profile = ExecutionProfile.TrustedLocal,
    )

    private class CapturingPodmanCommandRunner(
        private val captured: AtomicReference<List<String>?>,
    ) : PodmanCommandRunner {
        override suspend fun run(args: List<String>): PodmanCommandResult {
            captured.set(args)
            return PodmanCommandResult.Success(
                stdout = "container-abc123\n",
                exitCode = 0,
            )
        }
    }
}
