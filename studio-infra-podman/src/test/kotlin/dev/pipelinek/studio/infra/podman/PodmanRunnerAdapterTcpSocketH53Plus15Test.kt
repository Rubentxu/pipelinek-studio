package dev.pipelinek.studio.infra.podman

import dev.pipelinek.studio.application.ports.ExecutionProfile
import dev.pipelinek.studio.application.ports.PodmanRunResult
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
 * Falsifiers for the SFW-H5.3+ fifteenth
 * follow-up: TCP socket for remote
 * Podman. The H5.3+ 4th follow-up shipped
 * the Unix socket path; the 15th follow-up
 * extends it to TCP for remote Podman
 * (a common production deployment pattern
 * for distributed Studio boots).
 *
 * ## API
 *
 * The 15th follow-up extends the
 * `socketPath: String?` parameter: a bare
 * path (no scheme) gets the `unix://`
 * prefix (backwards-compatible); a value
 * starting with `tcp://` or `unix://`
 * is passed as-is.
 *
 * 5/5 falsifier tests verify the URL
 * construction (the actual remote
 * Podman round-trip is a production
 * boot concern).
 */
@DisplayName("PodmanRunnerAdapter (SFW-H5.3+ TCP socket)")
class PodmanRunnerAdapterTcpSocketH53Plus15Test {

    // ------------------------------------------------------------------
    // Falsifier 1: bare path is unix:// (backwards-compatible)
    // ------------------------------------------------------------------
    @Test
    fun `bare socket path is unix scheme (backwards-compatible)`() = kotlinx.coroutines.runBlocking {
        val captured = AtomicReference<List<String>?>(null)
        val runner = PodmanRunnerAdapter(
            commandRunner = CapturingPodmanCommandRunner(captured),
            socketPath = "/var/run/podman/podman.sock",
        )
        runner.runInContainer(
            request = runRequest(),
            image = "alpine:latest",
            hostControlRoot = Path.of("/studio"),
        )
        val args = captured.get()!!
        val urlIndex = args.indexOf("--url")
        assertTrue(urlIndex >= 0, "expected --url flag; args=$args")
        assertEquals(
            "unix:///var/run/podman/podman.sock",
            args[urlIndex + 1],
            "bare path must be prefixed with unix://",
        )
    }

    // ------------------------------------------------------------------
    // Falsifier 2: tcp:// scheme is passed as-is
    // ------------------------------------------------------------------
    @Test
    fun `tcp scheme is passed as-is for remote Podman`() = kotlinx.coroutines.runBlocking {
        val captured = AtomicReference<List<String>?>(null)
        val runner = PodmanRunnerAdapter(
            commandRunner = CapturingPodmanCommandRunner(captured),
            socketPath = "tcp://podman-host.example.com:2375",
        )
        runner.runInContainer(
            request = runRequest(),
            image = "alpine:latest",
            hostControlRoot = Path.of("/studio"),
        )
        val args = captured.get()!!
        val urlIndex = args.indexOf("--url")
        assertTrue(urlIndex >= 0, "expected --url flag; args=$args")
        assertEquals(
            "tcp://podman-host.example.com:2375",
            args[urlIndex + 1],
            "explicit tcp:// scheme must be passed as-is",
        )
    }

    // ------------------------------------------------------------------
    // Falsifier 3: explicit unix:// scheme is passed as-is
    // ------------------------------------------------------------------
    @Test
    fun `explicit unix scheme is passed as-is`() = kotlinx.coroutines.runBlocking {
        val captured = AtomicReference<List<String>?>(null)
        val runner = PodmanRunnerAdapter(
            commandRunner = CapturingPodmanCommandRunner(captured),
            socketPath = "unix:///custom/path/podman.sock",
        )
        runner.runInContainer(
            request = runRequest(),
            image = "alpine:latest",
            hostControlRoot = Path.of("/studio"),
        )
        val args = captured.get()!!
        val urlIndex = args.indexOf("--url")
        assertTrue(urlIndex >= 0, "expected --url flag; args=$args")
        assertEquals(
            "unix:///custom/path/podman.sock",
            args[urlIndex + 1],
            "explicit unix:// scheme must be passed as-is (no double prefix)",
        )
    }

    // ------------------------------------------------------------------
    // Falsifier 4: null socketPath omits --url (backwards-compatible)
    // ------------------------------------------------------------------
    @Test
    fun `null socketPath omits url flag (backwards-compatible)`() = kotlinx.coroutines.runBlocking {
        val captured = AtomicReference<List<String>?>(null)
        val runner = PodmanRunnerAdapter(
            commandRunner = CapturingPodmanCommandRunner(captured),
            socketPath = null,
        )
        runner.runInContainer(
            request = runRequest(),
            image = "alpine:latest",
            hostControlRoot = Path.of("/studio"),
        )
        val args = captured.get()!!
        assertTrue(
            !args.contains("--url"),
            "no --url flag when socketPath is null; args=$args",
        )
    }

    // ------------------------------------------------------------------
    // Falsifier 5: stopContainer also uses TCP URL
    // ------------------------------------------------------------------
    @Test
    fun `stopContainer uses TCP URL for remote Podman`() = kotlinx.coroutines.runBlocking {
        val captured = AtomicReference<List<String>?>(null)
        val runner = PodmanRunnerAdapter(
            commandRunner = CapturingPodmanCommandRunner(captured),
            socketPath = "tcp://podman-host.example.com:2375",
        )
        val result = runner.stopContainer("container-abc123")
        assertTrue(
            result is PodmanRunResult.Started,
            "stopContainer must succeed; got $result",
        )
        val args = captured.get()!!
        val urlIndex = args.indexOf("--url")
        assertTrue(urlIndex >= 0, "stopContainer must also pass --url; args=$args")
        assertEquals(
            "tcp://podman-host.example.com:2375",
            args[urlIndex + 1],
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

    /**
     * A [PodmanCommandRunner] that captures
     * the args and returns a fake success.
     * The containerId is returned as the
     * "container-abc123" string so the
     * adapter's parser can extract it.
     */
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
