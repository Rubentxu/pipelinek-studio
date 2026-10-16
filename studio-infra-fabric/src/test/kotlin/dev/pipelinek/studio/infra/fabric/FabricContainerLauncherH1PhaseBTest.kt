package dev.pipelinek.studio.infra.fabric

import dev.pipelinek.studio.infra.podman.PodmanCommandResult
import dev.pipelinek.studio.infra.podman.PodmanCommandRunner
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicReference

/**
 * Falsifiers for the SFW H1 Phase B
 * (Studio side): the
 * [FabricContainerLauncher]. The
 * launcher wraps a [PodmanCommandRunner]
 * to start/stop the Fabric server
 * container.
 *
 * 5/5 falsifier tests verify the
 * start/stop protocol.
 */
@DisplayName("FabricContainerLauncher (SFW H1 Phase B)")
class FabricContainerLauncherH1PhaseBTest {

    // ------------------------------------------------------------------
    // Falsifier 1: start invokes podman run with the right args
    // ------------------------------------------------------------------
    @Test
    fun `start invokes podman run with the right args`() = runBlocking {
        val captured = AtomicReference<List<String>?>(null)
        val launcher = FabricContainerLauncher(
            podman = CapturingPodmanCommandRunner(captured, "container-abc123\n"),
            image = "fabric-server:0.5.0-R1-alpha.8",
        )
        val result = launcher.start()
        assertTrue(result is PodmanCommandResult.Success, "start must succeed; got $result")
        val args = captured.get()!!
        // Verify the podman run args.
        assertTrue(args.contains("run"), "must invoke 'run'; args=$args")
        assertTrue(args.contains("-d"), "must be detached; args=$args")
        assertTrue(args.contains("--rm"), "must auto-remove on exit; args=$args")
        assertTrue(args.contains("--name"), "must have a name; args=$args")
        assertTrue(args.contains("fabric-server:0.5.0-R1-alpha.8"), "must include the image; args=$args")
        assertTrue(args.contains("-p"), "must map the port; args=$args")
        // Default port is 50051.
        val portArg = args[args.indexOf("-p") + 1]
        assertTrue(portArg.contains("50051"), "port must be 50051; got '$portArg'")
    }

    // ------------------------------------------------------------------
    // Falsifier 2: start stores the container ID
    // ------------------------------------------------------------------
    @Test
    fun `start stores the container ID from stdout`() = runBlocking {
        val launcher = FabricContainerLauncher(
            podman = CapturingPodmanCommandRunner(
                capturedArgs = AtomicReference(null),
                stdout = "deadbeefcafe\n",
            ),
            image = "fabric-server:0.5.0-R1-alpha.8",
        )
        launcher.start()
        assertEquals("deadbeefcafe", launcher.containerId)
        assertEquals("http://127.0.0.1:50051", launcher.endpoint)
    }

    // ------------------------------------------------------------------
    // Falsifier 3: stop invokes podman rm with the right args
    // ------------------------------------------------------------------
    @Test
    fun `stop invokes podman rm with the right args`() = runBlocking {
        val captured = AtomicReference<List<String>?>(null)
        val launcher = FabricContainerLauncher(
            podman = CapturingPodmanCommandRunner(
                capturedArgs = captured,
                stdout = "container-xyz\n",
            ),
            image = "fabric-server:0.5.0-R1-alpha.8",
        )
        launcher.start()
        captured.set(null)  // reset to capture the stop args
        val result = launcher.stop()
        assertTrue(result is PodmanCommandResult.Success, "stop must succeed; got $result")
        val args = captured.get()!!
        assertTrue(args.contains("rm"), "must invoke 'rm'; args=$args")
        assertTrue(args.contains("-f"), "must force-remove; args=$args")
        assertTrue(
            args.contains("container-xyz"),
            "must reference the container ID; args=$args",
        )
        // The container ID is cleared after a successful stop.
        assertNull(launcher.containerId)
    }

    // ------------------------------------------------------------------
    // Falsifier 4: stop on a non-existent container is a no-op (idempotent)
    // ------------------------------------------------------------------
    @Test
    fun `stop on non-existent container is a no-op`() = runBlocking {
        val launcher = FabricContainerLauncher(
            podman = ScriptedPodmanCommandRunner(
                responses = listOf(
                    // stop (no prior start)
                    PodmanCommandResult.Failure(
                        stderr = "Error: no such container: fabric-server",
                        exitCode = 1,
                    ),
                ),
            ),
            image = "fabric-server:0.5.0-R1-alpha.8",
        )
        val result = launcher.stop()
        assertTrue(result is PodmanCommandResult.Success, "stop must be idempotent; got $result")
        assertNull(launcher.containerId)
    }

    // ------------------------------------------------------------------
    // Falsifier 5: start failure leaves the launcher in a clean state
    // ------------------------------------------------------------------
    @Test
    fun `start failure leaves the launcher in a clean state`() = runBlocking {
        val launcher = FabricContainerLauncher(
            podman = ScriptedPodmanCommandRunner(
                responses = listOf(
                    // First call (start): failure
                    PodmanCommandResult.Failure(
                        stderr = "Error: image not found",
                        exitCode = 125,
                    ),
                ),
            ),
            image = "fabric-server:does-not-exist",
        )
        val result = launcher.start()
        assertTrue(result is PodmanCommandResult.Failure, "start must fail; got $result")
        assertNull(launcher.containerId, "containerId must remain null on failure")
        assertNull(launcher.endpoint, "endpoint must remain null on failure")
    }

    // --------------------------------------------------------------------
    // Test helpers
    // --------------------------------------------------------------------

    private class CapturingPodmanCommandRunner(
        private val capturedArgs: AtomicReference<List<String>?>,
        private val stdout: String = "",
    ) : PodmanCommandRunner {
        override suspend fun run(args: List<String>): PodmanCommandResult {
            capturedArgs.set(args)
            return PodmanCommandResult.Success(stdout = stdout, exitCode = 0)
        }
    }

    private class ScriptedPodmanCommandRunner(
        private val responses: List<PodmanCommandResult>,
    ) : PodmanCommandRunner {
        private val index = java.util.concurrent.atomic.AtomicInteger(0)
        override suspend fun run(args: List<String>): PodmanCommandResult {
            val i = index.getAndIncrement()
            return responses.getOrNull(i) ?: responses.last()
        }
    }
}
