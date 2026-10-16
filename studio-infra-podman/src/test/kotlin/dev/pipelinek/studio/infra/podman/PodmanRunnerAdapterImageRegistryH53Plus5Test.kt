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
 * Falsifiers for the SFW-H5.3+ fifth
 * follow-up: the [PodmanImageRegistry]
 * seam on the [PodmanRunnerAdapter].
 *
 * The H5.3+ fifth follow-up adds a
 * `PodmanImageRegistry` constructor
 * parameter; the adapter calls
 * `imageRegistry.resolve(request, image)`
 * BEFORE invoking `podman run`. The
 * default is [LocalPodmanImageRegistry]
 * (a no-op: the input is already
 * fully-qualified).
 */
@DisplayName("PodmanRunnerAdapter (SFW-H5.3+ image registry)")
class PodmanRunnerAdapterImageRegistryH53Plus5Test {

    private val request = RunRequest(
        runId = RunId("studio-h53p5-1"),
        snapshotId = SnapshotId("snapshot-h53p5"),
        scenarioId = ScenarioId("scenario-h53p5"),
        profile = dev.pipelinek.studio.application.ports.ExecutionProfile.TrustedLocal,
    )

    // ------------------------------------------------------------------
    // Falsifier 1: LocalPodmanImageRegistry is a no-op (returns input)
    // ------------------------------------------------------------------
    @Test
    fun `LocalPodmanImageRegistry returns the input image unchanged`(@TempDir tempDir: Path) = runBlocking {
        val fake = FakePodmanCommandRunnerForH53P5(
            successResult = PodmanCommandResult.Success(
                stdout = "abc123\n",
                exitCode = 0,
            ),
        )
        val adapter = PodmanRunnerAdapter(
            commandRunner = fake,
            imageRegistry = LocalPodmanImageRegistry(),
        )
        adapter.runInContainer(request, "studio:latest", hostControlRoot = tempDir)
        val args = fake.lastArgs ?: error("lastArgs is null")
        assertTrue(
            args.last() == "studio:latest",
            "last arg must be the unresolved image; got $args",
        )
    }

    // ------------------------------------------------------------------
    // Falsifier 2: custom registry resolves short name to full name
    // ------------------------------------------------------------------
    @Test
    fun `custom registry resolves short name to fully-qualified name`(@TempDir tempDir: Path) = runBlocking {
        val fake = FakePodmanCommandRunnerForH53P5(
            successResult = PodmanCommandResult.Success(
                stdout = "abc123\n",
                exitCode = 0,
            ),
        )
        val adapter = PodmanRunnerAdapter(
            commandRunner = fake,
            imageRegistry = FakeImageRegistryForH53P5(
                resolveTo = "registry.local/studio:0.48.0-rc1",
            ),
        )
        adapter.runInContainer(request, "studio:latest", hostControlRoot = tempDir)
        val args = fake.lastArgs ?: error("lastArgs is null")
        assertEquals(
            "registry.local/studio:0.48.0-rc1",
            args.last(),
            "last arg must be the resolved image; got $args",
        )
    }

    // ------------------------------------------------------------------
    // Falsifier 3: registry is called once per runInContainer call
    // ------------------------------------------------------------------
    @Test
    fun `registry is called once per runInContainer call`(@TempDir tempDir: Path) = runBlocking {
        val fake = FakePodmanCommandRunnerForH53P5(
            successResult = PodmanCommandResult.Success(
                stdout = "abc123\n",
                exitCode = 0,
            ),
        )
        val registry = FakeImageRegistryForH53P5(resolveTo = "registry.local/studio:0.48.0-rc1")
        val adapter = PodmanRunnerAdapter(
            commandRunner = fake,
            imageRegistry = registry,
        )
        adapter.runInContainer(request, "studio:latest", hostControlRoot = tempDir)
        assertEquals(1, registry.resolveCalls, "registry must be called once per runInContainer")
        assertEquals("studio:latest", registry.lastInput, "registry must receive the input image")
    }

    // ------------------------------------------------------------------
    // Falsifier 4: stopContainer does NOT call the registry
    // ------------------------------------------------------------------
    @Test
    fun `stopContainer does NOT call the registry`(@TempDir tempDir: Path) = runBlocking {
        val fake = FakePodmanCommandRunnerForH53P5(
            successResult = PodmanCommandResult.Success(
                stdout = "abc123\n",
                exitCode = 0,
            ),
        )
        val registry = FakeImageRegistryForH53P5(resolveTo = "registry.local/studio:0.48.0-rc1")
        val adapter = PodmanRunnerAdapter(
            commandRunner = fake,
            imageRegistry = registry,
        )
        adapter.stopContainer("abc123")
        assertEquals(0, registry.resolveCalls, "registry must NOT be called by stopContainer")
    }

    // ------------------------------------------------------------------
    // Falsifier 5: registry receives the request (for request-aware resolution)
    // ------------------------------------------------------------------
    @Test
    fun `registry receives the request (for request-aware resolution)`(@TempDir tempDir: Path) = runBlocking {
        val fake = FakePodmanCommandRunnerForH53P5(
            successResult = PodmanCommandResult.Success(
                stdout = "abc123\n",
                exitCode = 0,
            ),
        )
        val registry = FakeImageRegistryForH53P5(resolveTo = "registry.local/studio:0.48.0-rc1")
        val adapter = PodmanRunnerAdapter(
            commandRunner = fake,
            imageRegistry = registry,
        )
        adapter.runInContainer(request, "studio:latest", hostControlRoot = tempDir)
        assertEquals(
            request,
            registry.lastRequest,
            "registry must receive the request for request-aware resolution",
        )
    }
}

// --------------------------------------------------------------------
// Test fixture
// --------------------------------------------------------------------

private class FakePodmanCommandRunnerForH53P5(
    var successResult: PodmanCommandResult,
) : PodmanCommandRunner {
    var lastArgs: List<String>? = null
        private set

    override suspend fun run(args: List<String>): PodmanCommandResult {
        lastArgs = args
        return successResult
    }
}

private class FakeImageRegistryForH53P5(
    val resolveTo: String,
) : PodmanImageRegistry {
    var resolveCalls: Int = 0
        private set
    var lastInput: String? = null
        private set
    var lastRequest: RunRequest? = null
        private set

    override suspend fun resolve(request: RunRequest, image: String): String {
        resolveCalls += 1
        lastInput = image
        lastRequest = request
        return resolveTo
    }
}
