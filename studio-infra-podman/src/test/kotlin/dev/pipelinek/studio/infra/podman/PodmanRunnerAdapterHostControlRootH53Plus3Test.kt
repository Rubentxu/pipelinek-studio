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
 * Falsifiers for the SFW-H5.3+ third
 * follow-up: the host control root
 * parameter on the
 * [PodmanRunnerAdapter.runInContainer].
 *
 * The H5.3+ third follow-up changes the
 * `runInContainer` signature to take a
 * `hostControlRoot: Path` parameter; the
 * adapter binds the path into the
 * container at `/studio` via
 * `--mount type=bind`.
 */
@DisplayName("PodmanRunnerAdapter (SFW-H5.3+ host control root)")
class PodmanRunnerAdapterHostControlRootH53Plus3Test {

    private val request = RunRequest(
        runId = RunId("studio-h53p3-1"),
        snapshotId = SnapshotId("snapshot-h53p3"),
        scenarioId = ScenarioId("scenario-h53p3"),
        profile = dev.pipelinek.studio.application.ports.ExecutionProfile.TrustedLocal,
    )

    // ------------------------------------------------------------------
    // Falsifier 1: hostControlRoot is bound into the container
    // ------------------------------------------------------------------
    @Test
    fun `hostControlRoot is bound into the container at slash studio`(@TempDir tempDir: Path) = runBlocking {
        val fake = FakePodmanCommandRunnerForH53P3(
            successResult = PodmanCommandResult.Success(
                stdout = "abc123\n",
                exitCode = 0,
            ),
        )
        val adapter = PodmanRunnerAdapter(commandRunner = fake)
        adapter.runInContainer(request, "image:latest", hostControlRoot = tempDir)
        val args = fake.lastArgs ?: error("lastArgs is null")
        // Find --mount and verify it's a bind
        // mount of the host path to /studio.
        val mountIdx = args.indexOf("--mount")
        assertTrue(mountIdx >= 0, "args must include --mount; got $args")
        val mountValue = args[mountIdx + 1]
        assertTrue(
            mountValue.startsWith("type=bind,"),
            "mount must be type=bind; got '$mountValue'",
        )
        assertTrue(
            mountValue.contains("destination=/studio"),
            "mount must bind to /studio; got '$mountValue'",
        )
        assertTrue(
            mountValue.contains(tempDir.toAbsolutePath().toString()),
            "mount must bind the host path; got '$mountValue'",
        )
    }

    // ------------------------------------------------------------------
    // Falsifier 2: the started result echoes the hostControlRoot
    // ------------------------------------------------------------------
    @Test
    fun `Started result echoes the hostControlRoot back to the caller`(@TempDir tempDir: Path) = runBlocking {
        val fake = FakePodmanCommandRunnerForH53P3(
            successResult = PodmanCommandResult.Success(
                stdout = "abc123\n",
                exitCode = 0,
            ),
        )
        val adapter = PodmanRunnerAdapter(commandRunner = fake)
        val result = adapter.runInContainer(request, "image:latest", hostControlRoot = tempDir)
        val started = result as? PodmanRunResult.Started
        assertNotNull(started, "expected Started, got $result")
        assertEquals(tempDir, started!!.hostControlRoot)
    }

    // ------------------------------------------------------------------
    // Falsifier 3: relative host path is converted to absolute
    // ------------------------------------------------------------------
    @Test
    fun `relative host path is converted to absolute in the bind mount`() = runBlocking {
        val fake = FakePodmanCommandRunnerForH53P3(
            successResult = PodmanCommandResult.Success(
                stdout = "abc123\n",
                exitCode = 0,
            ),
        )
        val adapter = PodmanRunnerAdapter(commandRunner = fake)
        // Use a relative path
        val relativePath = Path.of("relative-host-root")
        adapter.runInContainer(request, "image:latest", hostControlRoot = relativePath)
        val args = fake.lastArgs ?: error("lastArgs is null")
        val mountValue = args[args.indexOf("--mount") + 1]
        // The mount value must be an absolute path
        // (Path.toAbsolutePath() always returns
        // an absolute path).
        assertTrue(
            mountValue.contains("source=") && !mountValue.contains("source=relative-host-root,") || mountValue.contains(relativePath.toAbsolutePath().toString()),
            "relative path must be converted to absolute; got '$mountValue'",
        )
    }

    // ------------------------------------------------------------------
    // Falsifier 4: podman run failure still returns Refused (not Started)
    // ------------------------------------------------------------------
    @Test
    fun `podman run failure with hostControlRoot still returns Refused`(@TempDir tempDir: Path) = runBlocking {
        val fake = FakePodmanCommandRunnerForH53P3(
            successResult = PodmanCommandResult.Failure(
                stderr = "Error: invalid bind mount source",
                exitCode = 125,
            ),
        )
        val adapter = PodmanRunnerAdapter(commandRunner = fake)
        val result = adapter.runInContainer(request, "image:latest", hostControlRoot = tempDir)
        val refused = result as? PodmanRunResult.Refused
        assertNotNull(refused, "expected Refused, got $result")
    }

    // ------------------------------------------------------------------
    // Falsifier 5: started.hostControlRoot is the SAME path that was passed in
    // ------------------------------------------------------------------
    @Test
    fun `started hostControlRoot is the same path that was passed in (not normalized)`(@TempDir tempDir: Path) = runBlocking {
        val fake = FakePodmanCommandRunnerForH53P3(
            successResult = PodmanCommandResult.Success(
                stdout = "abc123\n",
                exitCode = 0,
            ),
        )
        val adapter = PodmanRunnerAdapter(commandRunner = fake)
        val result = adapter.runInContainer(request, "image:latest", hostControlRoot = tempDir)
        val started = result as? PodmanRunResult.Started
        assertNotNull(started, "expected Started, got $result")
        // The host path returned to the caller
        // must be the same path the caller
        // passed in (no normalization; the
        // caller may want to compare paths
        // for equality).
        assertEquals(tempDir, started!!.hostControlRoot)
    }
}

// --------------------------------------------------------------------
// Test fixture
// --------------------------------------------------------------------

private class FakePodmanCommandRunnerForH53P3(
    var successResult: PodmanCommandResult,
) : PodmanCommandRunner {
    var lastArgs: List<String>? = null
        private set

    override suspend fun run(args: List<String>): PodmanCommandResult {
        lastArgs = args
        return successResult
    }
}
