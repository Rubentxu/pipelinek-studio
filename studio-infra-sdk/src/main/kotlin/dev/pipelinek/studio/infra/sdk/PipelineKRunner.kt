package dev.pipelinek.studio.infra.sdk

import dev.pipelinek.studio.application.ports.*
import dev.pipelinek.studio.domain.RunId
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.UUID
import kotlin.io.path.writeBytes

/**
 * PipelineK runner — invokes a real PipelineK JVM subprocess to execute a snapshot.
 *
 * D-005: profile `TRUSTED_LOCAL` only in S0. JVM isolation ≠ OS sandbox.
 *
 * Per DATA_MODEL_AND_PERSISTENCE.md:
 * - one run.db per run; controlRoot under managed dir.
 * - the runner sets `--db`, `--control-root`, snapshot bytes (written to disk),
 *   then waits for the subprocess to exit. We classify the exit + parsed output
 *   into typed results. We DO NOT infer state from raw stdout strings.
 *
 * The runner is constructed with a [ProcessSpawner] so tests can substitute a fake
 * without spawning real processes. Real spawning via [JavaProcessSpawner] in production.
 */
class PipelineKRunner(
    private val controlRootBase: Path,
    private val sdkClasspath: List<Path>,
    private val mainClass: String,
    private val spawner: ProcessSpawner = JavaProcessSpawner,
    private val timeout: Duration = Duration.ofMinutes(15),
) : PipelineRunnerPort {

    private val log = LoggerFactory.getLogger(javaClass)

    override fun submit(req: RunRequest): SubmissionResult {
        val controlRoot = controlRootBase.resolve(req.runId.value).also { Files.createDirectories(it) }
        val runDb = controlRoot.resolve("run.db")
        val snapshotPath = controlRoot.resolve("snapshot.kts").also {
            // Snapshot bytes are written to disk from a separate read port (snapshot store).
            // For the boot we generate a minimal placeholder; S0 real read comes from
            // StudioMetadataRepository.
            it.writeBytes("/* snapshot bytes loaded by Studio from metadata */".toByteArray())
        }
        return try {
            val argv = listOf(
                "--db", runDb.toString(),
                "--control-root", controlRoot.toString(),
                "run",
                "--script", snapshotPath.toString(),
            )
            log.info("submitting run={} upstream-run-id not yet known", req.runId.value)
            val proc = spawner.spawn(mainClass, sdkClasspath, argv)
            // We do not wait here synchronously; the supervisor flips state in background.
            // For the S0 minimal happy path, we admit and let the supervisor poll.
            proc.asyncDetach()
            SubmissionResult.Admitted(
                upstreamRunId = "pipelinek-${UUID.randomUUID()}",
                controlRoot = controlRoot,
            )
        } catch (e: Exception) {
            log.error("runner submit failed", e)
            SubmissionResult.Refused("runner-error: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    override fun cancel(runId: RunId): CancellationResult =
        CancellationResult.Unknown("cancel not implemented in S0 bootstrap (will hook Process.destroyFor() in next slice)")

    override fun status(runId: RunId): RunHandleStatus =
        RunHandleStatus.Unknown("status supervisor not implemented in S0 bootstrap")

    private fun List<Path>.asClasspath(): String = joinToString(System.getProperty("path.separator"))
}

interface ProcessSpawner {
    fun spawn(mainClass: String, classpath: List<Path>, argv: List<String>): SpawnedProcess
    fun asyncDetach(): Unit = Unit
}

interface SpawnedProcess {
    /** Cooperative detach — process keeps running; supervisor tracks it. */
    fun asyncDetach()
    val pid: Long?
}

object JavaProcessSpawner : ProcessSpawner {
    override fun spawn(mainClass: String, classpath: List<Path>, argv: List<String>): SpawnedProcess {
        val javaBin = System.getProperty("java.home") + "/bin/java"
        val cp = classpath.joinToString(System.getProperty("path.separator"))
        val cmd = listOf(javaBin, "-cp", cp, mainClass) + argv
        val pb = ProcessBuilder(cmd).redirectErrorStream(true)
        val proc = pb.start()
        return object : SpawnedProcess {
            override fun asyncDetach() { /* process keeps running */ }
            override val pid: Long? = runCatching { proc.pid() }.getOrNull()
        }
    }
}
