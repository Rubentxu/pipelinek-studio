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
    private val snapshotSource: dev.pipelinek.studio.application.ports.StudioMetadataPort,
    private val spawner: ProcessSpawner = JavaProcessSpawner,
    private val timeout: Duration = Duration.ofMinutes(15),
    private val stdoutSink: (Path) -> java.io.OutputStream = { Files.newOutputStream(it) },
) : PipelineRunnerPort {

    private val log = LoggerFactory.getLogger(javaClass)

    // Live subprocess registry. cancel(runId) consults this map; entries are
    // pruned when the SDK subprocess exits (best-effort, see drainer below).
    // ConcurrentHashMap because the HTTP thread that handles /runs/{id}/cancel
    // is not the same as the thread that submitted the run.
    private val liveProcs = java.util.concurrent.ConcurrentHashMap<String, Process>()
    // Hook the Process exit so we can prune the map. We pass this through
    // the spawner's asyncDetach callback (S0 didn't plumb it, so for now we
    // use a waitFor() on a daemon thread per process to clean up).
    private val lifecycleDrainers = java.util.concurrent.ConcurrentHashMap<String, Thread>()

    override fun submit(req: RunRequest): SubmissionResult {
        val controlRoot = controlRootBase.resolve(req.runId.value).also { Files.createDirectories(it) }
        val runDb = controlRoot.resolve("run.db")
        // S1 E1.5: read the real source bytes the user sealed. The runner
        // never falls back to a placeholder — a missing snapshot is a hard
        // error, because running a different script than the user sealed
        // would silently corrupt the experiment contract. (The S0 boot
        // placeholder was only valid for the boot path; it is removed in
        // this sprint so the contract is enforced for every submit.)
        val sourceBytes: ByteArray = snapshotSource.loadSnapshotSource(req.snapshotId)
            ?: return SubmissionResult.Refused(
                "SNAPSHOT_MISSING: snapshot ${req.snapshotId.value} not found in studio.db; " +
                    "the seal flow must persist bytes via saveSnapshot() before submit.",
            )
        val snapshotPath = controlRoot.resolve("snapshot.pipeline.kts").also {
            it.writeBytes(sourceBytes)
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
            // Register the live Process so /cancel can find + destroy it.
            // The SpawnedProcess wrapper does not expose the underlying Process
            // in the S0 port; we ask the spawner for the raw Process via
            // processFor(proc) — JavaProcessSpawner provides this. The default
            // spawn() (no capture) returns null for the raw handle.
            val raw = (spawner as? JavaProcessSpawner)?.processFor(proc)
            if (raw != null) {
                liveProcs[req.runId.value] = raw
                val drainer = Thread({
                    try { raw.waitFor() } catch (_: InterruptedException) { /* expected on cancel */ }
                    liveProcs.remove(req.runId.value)
                    lifecycleDrainers.remove(req.runId.value)
                }, "studio-runner-drain-${proc.pid}").apply {
                    isDaemon = true
                    start()
                }
                lifecycleDrainers[req.runId.value] = drainer
            }
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

    override fun cancel(runId: RunId): CancellationResult {
        val proc = liveProcs[runId.value]
            ?: return CancellationResult.Unknown("no live subprocess for runId ${runId.value}")
        // destroyForcibly is the JVM-blessed way to send SIGKILL on POSIX.
        // The subprocess will exit; the drainer thread above then prunes
        // the liveProcs entry and the run.db will reflect the abrupt exit
        // (the SDK writes a RunFinished(outcome=cancelled) event in its
        // own teardown; if it does not, the next read on run.db will
        // surface the process-exit state honestly).
        return try {
            proc.destroyForcibly()
            // The port's CancellationResult only exposes CancelRequested /
            // AlreadyTerminal / Unknown; the runner's contract is "best
            // effort" (we asked the OS to kill the subprocess), which is
            // best modelled as CancelRequested even when the OS may not
            // have delivered SIGKILL yet. The next /runs/{id} call (or
            // the drainer thread) reflects the eventual exit.
            CancellationResult.CancelRequested
        } catch (e: Exception) {
            log.warn("cancel for runId={} raised {}", runId.value, e.toString())
            CancellationResult.Unknown("destroy failed: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    override fun status(runId: RunId): RunHandleStatus =
        RunHandleStatus.Unknown("status supervisor not implemented in S0 bootstrap")

    /**
     * Snapshot of currently-live subprocess bindings: run_id -> pid.
     *
     * Used by the retention service (AAT-DUR-006) to do per-run pid
     * checks instead of a global /proc scan. The retention service
     * never deletes a run that the runner still considers live; this
     * is the only authoritative source of that truth.
     *
     * Returns a defensive copy: callers can't mutate the live map.
     */
    fun liveRunToPid(): Map<String, Long> {
        val out = HashMap<String, Long>(liveProcs.size)
        for ((runId, proc) in liveProcs) {
            // Defensive: skip entries whose Process object no longer
            // exposes a usable pid (shouldn't happen but guard anyway).
            try {
                val pid = proc.pid()
                if (pid > 0) out[runId] = pid
            } catch (_: Exception) { /* skip */ }
        }
        return out
    }
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

    // Backing store for [processFor] — the [SpawnedProcess] wrappers the
    // factory returns are stateless, but the runner needs the underlying
    // [Process] to implement cancel(). The runner also prunes the
    // liveProcs map when its drainer thread observes waitFor() returning.
    private val spawnedHandles: java.util.concurrent.ConcurrentHashMap<SpawnedProcess, Process> =
        java.util.concurrent.ConcurrentHashMap()

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
        }.also { spawnedHandles[it] = proc }
    }

    /**
     * Expose the raw [Process] for a previously-spawned [SpawnedProcess].
     * The runner uses this to register the live subprocess for cancel();
     * the default [ProcessSpawner] returns null (no cancel support).
     */
    fun processFor(spawned: SpawnedProcess): Process? = spawnedHandles[spawned]
}
