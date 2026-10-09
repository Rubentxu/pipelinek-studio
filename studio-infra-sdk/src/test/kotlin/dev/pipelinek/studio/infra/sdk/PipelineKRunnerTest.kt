package dev.pipelinek.studio.infra.sdk

import dev.pipelinek.studio.application.ports.*
import dev.pipelinek.studio.domain.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertIs

class PipelineKRunnerTest {

    @Test
    fun `submit builds SPAWNED child and admits with controlRoot`(@TempDir tmp: Path) {
        val recorded = java.util.concurrent.atomic.AtomicReference<Pair<String, List<String>>>(null)
        val spawner = object : ProcessSpawner {
            override fun spawn(mainClass: String, classpath: List<Path>, argv: List<String>): SpawnedProcess {
                recorded.set(mainClass to argv)
                return object : SpawnedProcess {
                    override fun asyncDetach() { /* noop */ }
                    override val pid: Long? = null
                }
            }
        }
        val runner = PipelineKRunner(
            controlRootBase = tmp,
            sdkClasspath = listOf(tmp.resolve("fake.jar")),
            mainClass = "dev.rubentxu.pipeline.v2.application.MainKt",
            spawner = spawner,
        )
        val req = RunRequest(
            runId = RunId.new(),
            snapshotId = SnapshotId.new(),
            scenarioId = ScenarioId.new(),
            profile = ExecutionProfile.TRUSTED_LOCAL,
        )
        val result = runner.submit(req)
        assertIs<SubmissionResult.Admitted>(result)
        assertTrue(result.controlRoot.toString().contains(req.runId.value))
        val r = recorded.get()
        assertEquals("dev.rubentxu.pipeline.v2.application.MainKt", r.first)
        assertTrue(r.second.contains("--db"), "expected --db flag, got ${r.second}")
        assertTrue(r.second.contains("--control-root"), "expected --control-root flag")
        assertTrue(r.second.contains("run"), "expected 'run' subcommand")
        assertTrue(r.second.any { it.endsWith("snapshot.kts") }, "expected snapshot.kts path")
    }

    @Test
    fun `spawn failure surfaces as Refused not crashed exception`(@TempDir tmp: Path) {
        val spawner = object : ProcessSpawner {
            override fun spawn(mainClass: String, classpath: List<Path>, argv: List<String>): SpawnedProcess {
                throw java.io.IOException("boom")
            }
        }
        val runner = PipelineKRunner(
            controlRootBase = tmp,
            sdkClasspath = listOf(tmp.resolve("fake.jar")),
            mainClass = "Main",
            spawner = spawner,
        )
        val req = RunRequest(
            runId = RunId.new(),
            snapshotId = SnapshotId.new(),
            scenarioId = ScenarioId.new(),
            profile = ExecutionProfile.TRUSTED_LOCAL,
        )
        val r = runner.submit(req)
        assertIs<SubmissionResult.Refused>(r)
        assertTrue(r.reason.contains("runner-error"))
    }

    @Test
    fun `cancel returns Unknown in bootstrap`(@TempDir tmp: Path) {
        val runner = PipelineKRunner(
            controlRootBase = tmp,
            sdkClasspath = emptyList(),
            mainClass = "Main",
            spawner = object : ProcessSpawner {
                override fun spawn(mainClass: String, classpath: List<Path>, argv: List<String>): SpawnedProcess {
                    error("not used")
                }
            },
        )
        val res = runner.cancel(RunId.new())
        assertIs<CancellationResult.Unknown>(res)
    }
}
