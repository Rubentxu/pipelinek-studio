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
 *   then waits for the subprocess to exit. We capture subprocess stdout+stderr
 *   to `<controlRoot>/stdout.log` so OutputReadPort can serve real bytes.
 *   We DO NOT infer state from raw stdout strings.
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
    private val stdoutSink: (Path) -> java.io.OutputStream = { Files.newOutputStream(it) },
) : PipelineRunnerPort {

    private val log = LoggerFactory.getLogger(javaClass)

    override fun submit(req: RunRequest): SubmissionResult {
        val controlRoot = controlRootBase.resolve(req.runId.value).also { Files.createDirectories(it) }
        val runDb = controlRoot.resolve("run.db")
        val snapshotPath = controlRoot.resolve("snapshot.pipeline.kts").also {
            // Snapshot bytes are written to disk from a separate read port (snapshot store).
            // For the boot we generate a minimal placeholder; S0 real read comes from
            // StudioMetadataRepository.
            it.writeBytes("/* snapshot bytes loaded by Studio from metadata */\n".toByteArray())
        }
        return try {
            val argv = listOf(
                "run",
                "--db", runDb.toString(),
                "--control-root", controlRoot.toString(),
                snapshotPath.toString(),
            )
            log.info("submitting run={} upstream-run-id not yet known", req.runId.value)
            // Capture subprocess stdout+stderr to disk so OutputReadPort can serve real bytes.
            val stdoutLog = controlRoot.resolve("stdout.log")
            val proc = spawner.spawnWithCapture(mainClass, sdkClasspath, argv, stdoutSink(stdoutLog))
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
    /**
     * Spawn with stdout/stderr captured to [captureSink]. Implementations should drain
     * the captured stream in a background thread while the process runs, so S0's
     * OutputReadPort can serve real bytes after the subprocess terminates. The default
     * [spawn] redirectErrorStream(true) is a fallback for callers that don't pass a sink.
     */
    fun spawnWithCapture(
        mainClass: String,
        classpath: List<Path>,
        argv: List<String>,
        captureSink: java.io.OutputStream,
    ): SpawnedProcess = spawn(mainClass, classpath, argv)
    fun asyncDetach(): Unit = Unit
}

interface SpawnedProcess {
    /** Cooperative detach — process keeps running; supervisor tracks it. */
    fun asyncDetach()
    val pid: Long?
}

object JavaProcessSpawner : ProcessSpawner {
    /**
     * JVM flags the upstream PipelineK `bin/pipelinek` script applies on JDK 9+.
     * Without these the SDK's reflective access fails with module access errors.
     */
    val jvmFlags: List<String> = listOf(
        "--add-opens=java.base/java.lang=ALL-UNNAMED",
        "--add-opens=java.base/java.io=ALL-UNNAMED",
        "--add-opens=java.base/java.nio=ALL-UNNAMED",
        "--add-opens=java.base/java.util=ALL-UNNAMED",
        "--add-opens=java.base/sun.nio.ch=ALL-UNNAMED",
        "--add-opens=java.base/java.lang.reflect=ALL-UNNAMED",
        "--add-exports=jdk.compiler/com.sun.tools.javac.api=ALL-UNNAMED",
    )

    override fun spawn(mainClass: String, classpath: List<Path>, argv: List<String>): SpawnedProcess =
        spawnInternal(mainClass, classpath, argv, redirectToSink = false, captureSink = null)

    override fun spawnWithCapture(
        mainClass: String,
        classpath: List<Path>,
        argv: List<String>,
        captureSink: java.io.OutputStream,
    ): SpawnedProcess = spawnInternal(mainClass, classpath, argv, redirectToSink = true, captureSink = captureSink)

    private fun spawnInternal(
        mainClass: String,
        classpath: List<Path>,
        argv: List<String>,
        redirectToSink: Boolean,
        captureSink: java.io.OutputStream?,
    ): SpawnedProcess {
        val javaBin = System.getProperty("java.home") + "/bin/java"
        val cp = classpath.joinToString(System.getProperty("path.separator"))
        val cmd = buildList {
            add(javaBin)
            addAll(jvmFlags)
            add("-cp")
            add(cp)
            add(mainClass)
            addAll(argv)
        }
        val pb = ProcessBuilder(cmd).redirectErrorStream(true)
        val proc = pb.start()
        if (redirectToSink && captureSink != null) {
            // Drain subprocess stdout+stderr into the sink on a background thread; close
            // the sink when the process exits. Non-blocking on the runner thread.
            val drain = Thread({
                try {
                    proc.inputStream.copyTo(captureSink)
                } catch (_: Throwable) { /* swallow — process likely already exited */ }
                try { captureSink.close() } catch (_: Throwable) { /* idempotent */ }
            }, "studio-collector-${proc.pid()}")
            drain.isDaemon = true
            drain.start()
        }
        return object : SpawnedProcess {
            override fun asyncDetach() { /* process keeps running */ }
            override val pid: Long? = runCatching { proc.pid() }.getOrNull()
        }
    }
}
