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

/**
 * Falsifiers for the SFW-H5.3+
 * [PodmanRunnerAdapter] — the H5.3 chain's
 * real `podman run` invocation.
 *
 * The tests use a [FakePodmanCommandRunner]
 * (instead of a real `podman` binary) so they
 * run on a host without `podman` installed.
 * The production impl
 * ([ProcessPodmanCommandRunner]) is a
 * [ProcessBuilder]-based subprocess.
 */
@DisplayName("PodmanRunnerAdapter (SFW-H5.3+ real podman run)")
class PodmanRunnerAdapterH53PlusTest {

    private val request = RunRequest(
        runId = RunId("studio-h53p-1"),
        snapshotId = SnapshotId("snapshot-h53p"),
        scenarioId = ScenarioId("scenario-h53p"),
        profile = dev.pipelinek.studio.application.ports.ExecutionProfile.TrustedLocal,
    )

    // ------------------------------------------------------------------
    // Falsifier 1: podman run success -> Started
    // ------------------------------------------------------------------
    @Test
    fun `podman run success returns Started with parsed containerId`() = runBlocking {
        val fake = FakePodmanCommandRunner(
            successResult = PodmanCommandResult.Success(
                stdout = "abc123def456\n",
                exitCode = 0,
            ),
        )
        val adapter = PodmanRunnerAdapter(commandRunner = fake)
        val result = adapter.runInContainer(request, "studio-h53p-image:latest", hostControlRoot = java.nio.file.Path.of("/tmp/h53p"))
        val started = result as? PodmanRunResult.Started
        assertNotNull(started, "expected Started, got $result")
        assertEquals("abc123def456", started!!.containerId)
        // Verify the args passed to podman
        // (H5.3+ second follow-up adds --network=host;
        // H5.3+ third follow-up changes --mount
        // from type=tmpfs to type=bind with the
        // hostControlRoot as the source).
        assertEquals(
            listOf("run", "--rm", "-d", "--network", "host", "--mount", "type=bind,source=/tmp/h53p,destination=/studio", "studio-h53p-image:latest"),
            fake.lastArgs,
            "podman run args must include --rm, -d, --network=host, --mount=bind, and the image",
        )
    }

    // ------------------------------------------------------------------
    // Falsifier 2: podman run failure -> Refused
    // ------------------------------------------------------------------
    @Test
    fun `podman run failure returns Refused with stderr`() = runBlocking {
        val fake = FakePodmanCommandRunner(
            successResult = PodmanCommandResult.Failure(
                stderr = "Error: image not known",
                exitCode = 125,
            ),
        )
        val adapter = PodmanRunnerAdapter(commandRunner = fake)
        val result = adapter.runInContainer(request, "nonexistent:latest", hostControlRoot = java.nio.file.Path.of("/tmp/h53p"))
        val refused = result as? PodmanRunResult.Refused
        assertNotNull(refused, "expected Refused, got $result")
        assertTrue(
            refused!!.reason.contains("image not known"),
            "reason should reference stderr: ${refused.reason}",
        )
        assertTrue(
            refused.reason.contains("125"),
            "reason should reference exit code 125: ${refused.reason}",
        )
    }

    // ------------------------------------------------------------------
    // Falsifier 3: podman run success but empty stdout -> Unknown
    // ------------------------------------------------------------------
    @Test
    fun `podman run success with empty stdout returns Unknown`() = runBlocking {
        val fake = FakePodmanCommandRunner(
            successResult = PodmanCommandResult.Success(
                stdout = "",
                exitCode = 0,
            ),
        )
        val adapter = PodmanRunnerAdapter(commandRunner = fake)
        val result = adapter.runInContainer(request, "image:latest", hostControlRoot = java.nio.file.Path.of("/tmp/h53p"))
        val unknown = result as? PodmanRunResult.Unknown
        assertNotNull(unknown, "expected Unknown, got $result")
        assertTrue(
            unknown!!.reason.contains("empty"),
            "reason should mention empty stdout: ${unknown.reason}",
        )
    }

    // ------------------------------------------------------------------
    // Falsifier 4: podman rm success -> Started (cleanup)
    // ------------------------------------------------------------------
    @Test
    fun `podman rm success returns Started (idempotent cleanup)`() = runBlocking {
        val fake = FakePodmanCommandRunner(
            successResult = PodmanCommandResult.Success(
                stdout = "abc123def456\n",
                exitCode = 0,
            ),
        )
        val adapter = PodmanRunnerAdapter(commandRunner = fake)
        val result = adapter.stopContainer("abc123def456")
        val started = result as? PodmanRunResult.Started
        assertNotNull(started, "expected Started, got $result")
        assertEquals("abc123def456", started!!.containerId)
        // Verify the args
        assertEquals(
            listOf("rm", "-f", "abc123def456"),
            fake.lastArgs,
            "podman rm args must be [rm, -f, containerId]",
        )
    }

    // ------------------------------------------------------------------
    // Falsifier 5: podman rm 'no such container' is treated as success
    // ------------------------------------------------------------------
    @Test
    fun `podman rm 'no such container' is treated as idempotent success`() = runBlocking {
        val fake = FakePodmanCommandRunner(
            successResult = PodmanCommandResult.Failure(
                stderr = "Error: no such container: abc123",
                exitCode = 1,
            ),
        )
        val adapter = PodmanRunnerAdapter(commandRunner = fake)
        val result = adapter.stopContainer("abc123")
        val started = result as? PodmanRunResult.Started
        assertNotNull(
            started,
            "expected Started (idempotent cleanup of absent container), got $result",
        )
    }
}

// --------------------------------------------------------------------
// Test fixture
// --------------------------------------------------------------------

private class FakePodmanCommandRunner(
    var successResult: PodmanCommandResult,
) : PodmanCommandRunner {
    var callCount: Int = 0
        private set
    var lastArgs: List<String>? = null
        private set

    override suspend fun run(args: List<String>): PodmanCommandResult {
        callCount += 1
        lastArgs = args
        return successResult
    }
}
