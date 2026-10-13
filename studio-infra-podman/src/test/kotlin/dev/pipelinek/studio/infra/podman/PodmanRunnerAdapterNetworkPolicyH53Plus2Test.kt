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
 * Falsifiers for the SFW-H5.3+ second
 * follow-up: the configurable network
 * policy on the [PodmanRunnerAdapter].
 *
 * The H5.3+ first follow-up shipped the
 * real `podman run` invocation; the
 * second follow-up adds the network
 * policy as a constructor parameter
 * (`--network=host` by default; `--network=none`
 * is the locked-down alternative).
 */
@DisplayName("PodmanRunnerAdapter (SFW-H5.3+ network policy)")
class PodmanRunnerAdapterNetworkPolicyH53Plus2Test {

    private val request = RunRequest(
        runId = RunId("studio-h53p2-1"),
        snapshotId = SnapshotId("snapshot-h53p2"),
        scenarioId = ScenarioId("scenario-h53p2"),
        profile = dev.pipelinek.studio.application.ports.ExecutionProfile.TrustedLocal,
    )

    // ------------------------------------------------------------------
    // Falsifier 1: default network=host is passed to podman run
    // ------------------------------------------------------------------
    @Test
    fun `default network is host (the container shares the host's network namespace)`() = runBlocking {
        val fake = FakePodmanCommandRunnerForH53P2(
            successResult = PodmanCommandResult.Success(
                stdout = "abc123\n",
                exitCode = 0,
            ),
        )
        val adapter = PodmanRunnerAdapter(commandRunner = fake)
        adapter.runInContainer(request, "image:latest")
        val args = fake.lastArgs ?: error("lastArgs is null")
        assertTrue(
            args.contains("--network") && args[args.indexOf("--network") + 1] == "host",
            "default network must be 'host'; got args=$args",
        )
    }

    // ------------------------------------------------------------------
    // Falsifier 2: opt-in network=none is passed to podman run
    // ------------------------------------------------------------------
    @Test
    fun `opt-in network none is passed when configured`() = runBlocking {
        val fake = FakePodmanCommandRunnerForH53P2(
            successResult = PodmanCommandResult.Success(
                stdout = "abc123\n",
                exitCode = 0,
            ),
        )
        val adapter = PodmanRunnerAdapter(commandRunner = fake, network = "none")
        adapter.runInContainer(request, "image:latest")
        val args = fake.lastArgs ?: error("lastArgs is null")
        assertTrue(
            args.contains("--network") && args[args.indexOf("--network") + 1] == "none",
            "configured network must be 'none'; got args=$args",
        )
    }

    // ------------------------------------------------------------------
    // Falsifier 3: opt-in network=bridge is passed when configured
    // ------------------------------------------------------------------
    @Test
    fun `opt-in network bridge is passed when configured`() = runBlocking {
        val fake = FakePodmanCommandRunnerForH53P2(
            successResult = PodmanCommandResult.Success(
                stdout = "abc123\n",
                exitCode = 0,
            ),
        )
        val adapter = PodmanRunnerAdapter(commandRunner = fake, network = "bridge")
        adapter.runInContainer(request, "image:latest")
        val args = fake.lastArgs ?: error("lastArgs is null")
        assertTrue(
            args.contains("--network") && args[args.indexOf("--network") + 1] == "bridge",
            "configured network must be 'bridge'; got args=$args",
        )
    }

    // ------------------------------------------------------------------
    // Falsifier 4: podman run with network=host still returns Started
    // ------------------------------------------------------------------
    @Test
    fun `podman run with network host returns Started with parsed containerId`() = runBlocking {
        val fake = FakePodmanCommandRunnerForH53P2(
            successResult = PodmanCommandResult.Success(
                stdout = "xyz789\n",
                exitCode = 0,
            ),
        )
        val adapter = PodmanRunnerAdapter(commandRunner = fake, network = "host")
        val result = adapter.runInContainer(request, "image:latest")
        val started = result as? PodmanRunResult.Started
        assertNotNull(started, "expected Started, got $result")
        assertEquals("xyz789", started!!.containerId)
    }

    // ------------------------------------------------------------------
    // Falsifier 5: opt-in network=custom is passed when configured
    // ------------------------------------------------------------------
    @Test
    fun `opt-in network custom (a user-defined network) is passed when configured`() = runBlocking {
        val fake = FakePodmanCommandRunnerForH53P2(
            successResult = PodmanCommandResult.Success(
                stdout = "abc123\n",
                exitCode = 0,
            ),
        )
        val adapter = PodmanRunnerAdapter(commandRunner = fake, network = "studio-net")
        adapter.runInContainer(request, "image:latest")
        val args = fake.lastArgs ?: error("lastArgs is null")
        assertTrue(
            args.contains("--network") && args[args.indexOf("--network") + 1] == "studio-net",
            "configured network must be 'studio-net'; got args=$args",
        )
    }
}

// --------------------------------------------------------------------
// Test fixture
// --------------------------------------------------------------------

private class FakePodmanCommandRunnerForH53P2(
    var successResult: PodmanCommandResult,
) : PodmanCommandRunner {
    var lastArgs: List<String>? = null
        private set

    override suspend fun run(args: List<String>): PodmanCommandResult {
        lastArgs = args
        return successResult
    }
}
