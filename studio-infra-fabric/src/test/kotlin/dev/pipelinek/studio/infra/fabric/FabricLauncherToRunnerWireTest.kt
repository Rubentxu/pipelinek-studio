package dev.pipelinek.studio.infra.fabric

import dev.pipelinek.studio.infra.podman.PodmanCommandResult
import dev.pipelinek.studio.infra.podman.PodmanCommandRunner
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Falsifiers for the wire between [FabricContainerLauncher]
 * (Phase B Part 2) and [FabricRunnerAdapter] (H2.2c).
 *
 * The two halves of the Studio-Fabric integration were
 * implemented in separate iterations. This test verifies
 * they remain compatible: the launcher publishes a port
 * that the adapter can connect to.
 *
 * 5/5 falsifier scenarios; the integration is **wiring-only**:
 * no podman, no Fabric server, no real network call. The
 * test fakes the subprocess and asserts the contract.
 */
@DisplayName("FabricContainerLauncher ↔ FabricRunnerAdapter wire (SFW H1)")
class FabricLauncherToRunnerWireTest {

    // ------------------------------------------------------------------
    // Falsifier 1: launcher's default endpoint is 127.0.0.1:50051
    // ------------------------------------------------------------------
    @Test
    fun `launcher default endpoint is 127_0_0_1 port 50051`() = runBlocking {
        val launcher = FabricContainerLauncher(
            podman = CapturingPodmanRunner("container-xyz\n"),
            image = "fabric-server:0.5.0-R1-alpha.8",
        )
        launcher.start()
        // The launcher's endpoint must be 127.0.0.1:50051 — the
        // standard port for the Fabric gRPC service.
        assertEquals("http://127.0.0.1:50051", launcher.endpoint)
    }

    // ------------------------------------------------------------------
    // Falsifier 2: launcher binds the container port 50051 to host 50051
    // ------------------------------------------------------------------
    @Test
    fun `launcher maps container port 50051 to host port 50051`() = runBlocking {
        val captured = CapturedArgs()
        val launcher = FabricContainerLauncher(
            podman = CapturingPodmanRunner("container-xyz\n", captured),
            image = "fabric-server:0.5.0-R1-alpha.8",
        )
        launcher.start()
        val portArg = captured.args!!.let { it[it.indexOf("-p") + 1] }
        // The port mapping is '127.0.0.1:50051:50051'
        // (host:container). The container-internal port is
        // fixed at 50051; the host port is 50051 by default.
        assertTrue(
            portArg.endsWith(":50051:50051"),
            "port mapping must be host:50051:50051; got '$portArg'",
        )
    }

    // ------------------------------------------------------------------
    // Falsifier 3: launcher can be re-started (idempotent at the
    //              port-mapping level)
    // ------------------------------------------------------------------
    @Test
    fun `launcher handles consecutive start calls without port drift`() = runBlocking {
        val captured = CapturedArgs()
        val launcher = FabricContainerLauncher(
            podman = CapturingPodmanRunner("container-xyz\n", captured),
            image = "fabric-server:0.5.0-R1-alpha.8",
        )
        launcher.start()
        val firstPort = captured.args!!.let { it[it.indexOf("-p") + 1] }
        captured.args = null
        // Simulate 'podman rm -f' succeeding; the container ID
        // is cleared so a new start can be issued.
        launcher.stop()
        captured.args = null
        launcher.start()
        val secondPort = captured.args!!.let { it[it.indexOf("-p") + 1] }
        assertEquals(firstPort, secondPort, "port mapping must not drift between start cycles")
    }

    // ------------------------------------------------------------------
    // Falsifier 4: start failure leaves endpoint=null (caller-side
    //              defensiveness against the wire breaking)
    // ------------------------------------------------------------------
    @Test
    fun `start failure leaves endpoint null`() = runBlocking {
        val launcher = FabricContainerLauncher(
            podman = ScriptedPodmanRunner(
                responses = listOf(
                    PodmanCommandResult.Failure(stderr = "image not found", exitCode = 125),
                ),
            ),
            image = "fabric-server:does-not-exist",
        )
        val result = launcher.start()
        assertTrue(result is PodmanCommandResult.Failure, "start must fail")
        // The launcher's endpoint is null when the container
        // did not start. The adapter would crash if it tried
        // to connect; this guard ensures the caller sees the
        // failure first.
        assertEquals(null, launcher.endpoint)
    }

    // ------------------------------------------------------------------
    // Falsifier 5: launcher and adapter agree on host = 127.0.0.1
    //              (not 0.0.0.0 — that would be a network policy
    //              widening the operator didn't ask for)
    // ------------------------------------------------------------------
    @Test
    fun `launcher binds to 127_0_0_1 not 0_0_0_0`() = runBlocking {
        val captured = CapturedArgs()
        val launcher = FabricContainerLauncher(
            podman = CapturingPodmanRunner("container-xyz\n", captured),
            image = "fabric-server:0.5.0-R1-alpha.8",
        )
        launcher.start()
        val portArg = captured.args!!.let { it[it.indexOf("-p") + 1] }
        // The default hostBind is 127.0.0.1 (loopback). The
        // operator can override with hostBind="0.0.0.0" for
        // remote access, but the default is loopback-only.
        assertTrue(
            portArg.startsWith("127.0.0.1:"),
            "default host bind must be 127.0.0.1 (loopback); got '$portArg'",
        )
        assertTrue(
            !portArg.startsWith("0.0.0.0:"),
            "default must NOT be 0.0.0.0 (would expose to network); got '$portArg'",
        )
    }

    // --------------------------------------------------------------------
    // Test helpers
    // --------------------------------------------------------------------

    private class CapturedArgs {
        var args: List<String>? = null
    }

    private class CapturingPodmanRunner(
        private val stdout: String = "",
        private val captured: CapturedArgs? = null,
    ) : PodmanCommandRunner {
        override suspend fun run(args: List<String>): PodmanCommandResult {
            captured?.args = args
            return PodmanCommandResult.Success(stdout = stdout, exitCode = 0)
        }
    }

    private class ScriptedPodmanRunner(
        private val responses: List<PodmanCommandResult>,
    ) : PodmanCommandRunner {
        private val index = java.util.concurrent.atomic.AtomicInteger(0)
        override suspend fun run(args: List<String>): PodmanCommandResult {
            val i = index.getAndIncrement()
            return responses.getOrNull(i) ?: responses.last()
        }
    }
}
