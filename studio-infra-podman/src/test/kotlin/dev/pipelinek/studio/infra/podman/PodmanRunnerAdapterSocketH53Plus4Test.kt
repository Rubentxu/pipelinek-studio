package dev.pipelinek.studio.infra.podman

import dev.pipelinek.studio.application.ports.PodmanRunResult
import dev.pipelinek.studio.application.ports.RunRequest
import dev.pipelinek.studio.domain.RunId
import dev.pipelinek.studio.domain.ScenarioId
import dev.pipelinek.studio.domain.SnapshotId
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

/**
 * Falsifiers for the SFW-H5.3+ fourth
 * follow-up: the rootful/rootless
 * Podman socket config on the
 * [PodmanRunnerAdapter].
 *
 * The H5.3+ fourth follow-up adds a
 * `socketPath: String?` constructor
 * parameter. When set, the adapter
 * passes `--url=unix://<socketPath>` to
 * every `podman` invocation. Operators
 * that need rootful Podman pass
 * `"/var/run/podman/podman.sock"`.
 */
@DisplayName("PodmanRunnerAdapter (SFW-H5.3+ rootful/rootless socket)")
class PodmanRunnerAdapterSocketH53Plus4Test {

    private val request = RunRequest(
        runId = RunId("studio-h53p4-1"),
        snapshotId = SnapshotId("snapshot-h53p4"),
        scenarioId = ScenarioId("scenario-h53p4"),
        profile = dev.pipelinek.studio.application.ports.ExecutionProfile.TrustedLocal,
    )

    // ------------------------------------------------------------------
    // Falsifier 1: socketPath=null does NOT add --url (default rootless)
    // ------------------------------------------------------------------
    @Test
    fun `socketPath null does NOT add the --url flag (default rootless)`(@TempDir tempDir: Path) = runBlocking {
        val fake = FakePodmanCommandRunnerForH53P4(
            successResult = PodmanCommandResult.Success(
                stdout = "abc123\n",
                exitCode = 0,
            ),
        )
        val adapter = PodmanRunnerAdapter(commandRunner = fake, socketPath = null)
        adapter.runInContainer(request, "image:latest", hostControlRoot = tempDir)
        val args = fake.lastArgs ?: error("lastArgs is null")
        assertTrue(
            !args.contains("--url"),
            "args must NOT include --url when socketPath is null; got $args",
        )
    }

    // ------------------------------------------------------------------
    // Falsifier 2: socketPath is passed as --url=unix://<socketPath>
    // ------------------------------------------------------------------
    @Test
    fun `socketPath is passed as --url=unix plus slash plus socketPath`(@TempDir tempDir: Path) = runBlocking {
        val fake = FakePodmanCommandRunnerForH53P4(
            successResult = PodmanCommandResult.Success(
                stdout = "abc123\n",
                exitCode = 0,
            ),
        )
        val adapter = PodmanRunnerAdapter(
            commandRunner = fake,
            socketPath = "/var/run/podman/podman.sock",
        )
        adapter.runInContainer(request, "image:latest", hostControlRoot = tempDir)
        val args = fake.lastArgs ?: error("lastArgs is null")
        val urlIdx = args.indexOf("--url")
        assertTrue(
            urlIdx >= 0 && args[urlIdx + 1] == "unix:///var/run/podman/podman.sock",
            "args must include --url unix://<socketPath>; got $args",
        )
    }

    // ------------------------------------------------------------------
    // Falsifier 3: stopContainer also passes --url when socketPath is set
    // ------------------------------------------------------------------
    @Test
    fun `stopContainer also passes --url when socketPath is set`() = runBlocking {
        val fake = FakePodmanCommandRunnerForH53P4(
            successResult = PodmanCommandResult.Success(
                stdout = "abc123\n",
                exitCode = 0,
            ),
        )
        val adapter = PodmanRunnerAdapter(
            commandRunner = fake,
            socketPath = "/var/run/podman/podman.sock",
        )
        adapter.stopContainer("abc123")
        val args = fake.lastArgs ?: error("lastArgs is null")
        val urlIdx = args.indexOf("--url")
        assertTrue(
            urlIdx >= 0 && args[urlIdx + 1] == "unix:///var/run/podman/podman.sock",
            "stopContainer must also include --url when socketPath is set; got $args",
        )
    }

    // ------------------------------------------------------------------
    // Falsifier 4: stopContainer with socketPath=null does NOT add --url
    // ------------------------------------------------------------------
    @Test
    fun `stopContainer with socketPath null does NOT add the --url flag`() = runBlocking {
        val fake = FakePodmanCommandRunnerForH53P4(
            successResult = PodmanCommandResult.Success(
                stdout = "abc123\n",
                exitCode = 0,
            ),
        )
        val adapter = PodmanRunnerAdapter(commandRunner = fake, socketPath = null)
        adapter.stopContainer("abc123")
        val args = fake.lastArgs ?: error("lastArgs is null")
        assertTrue(
            !args.contains("--url"),
            "stopContainer must NOT include --url when socketPath is null; got $args",
        )
    }

    // ------------------------------------------------------------------
    // Falsifier 5: rootless socket (XDG_RUNTIME_DIR style) is also passed
    // ------------------------------------------------------------------
    @Test
    fun `rootless socket path is also passed when configured`(@TempDir tempDir: Path) = runBlocking {
        val fake = FakePodmanCommandRunnerForH53P4(
            successResult = PodmanCommandResult.Success(
                stdout = "abc123\n",
                exitCode = 0,
            ),
        )
        val adapter = PodmanRunnerAdapter(
            commandRunner = fake,
            socketPath = "/run/user/1000/podman/podman.sock",
        )
        val result = adapter.runInContainer(request, "image:latest", hostControlRoot = tempDir)
        val started = result as? PodmanRunResult.Started
        assertNotNull(started, "expected Started, got $result")
        assertEquals("abc123", started!!.containerId)
        val args = fake.lastArgs ?: error("lastArgs is null")
        val urlIdx = args.indexOf("--url")
        assertTrue(
            urlIdx >= 0 && args[urlIdx + 1] == "unix:///run/user/1000/podman/podman.sock",
            "rootless socket path must be passed as --url; got $args",
        )
    }
}

// --------------------------------------------------------------------
// Test fixture
// --------------------------------------------------------------------

private class FakePodmanCommandRunnerForH53P4(
    var successResult: PodmanCommandResult,
) : PodmanCommandRunner {
    var lastArgs: List<String>? = null
        private set

    override suspend fun run(args: List<String>): PodmanCommandResult {
        lastArgs = args
        return successResult
    }
}
