@file:Suppress("TooGenericExceptionCaught")

package dev.pipelinek.studio.infra.sdk

import dev.pipelinek.studio.application.ports.*
import dev.pipelinek.studio.application.ports.ExecutionProfile
import dev.pipelinek.studio.domain.RunId
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
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

    // R0.3.6 (2026-10-11): maximum time we wait for the subprocess to
    // emit its RunStarted event with the upstream_id field. Production
    // subprocesses emit within milliseconds; the timeout is the hard
    // bound for refusing a submit rather than fabricating a UUID.
    private val UPSTREAM_ID_POLL_TIMEOUT: Duration = Duration.ofMillis(500)

    // Live subprocess registry. cancel(runId) consults this map; entries are
    // pruned when the SDK subprocess exits (best-effort, see drainer below).
    // ConcurrentHashMap because the HTTP thread that handles /runs/{id}/cancel
    // is not the same as the thread that submitted the run.
    private val liveProcs = java.util.concurrent.ConcurrentHashMap<String, Process>()
    // Audit F-P0-3 + R0.3.6: parallel registry of upstream run ids. status(runId)
    // reads both maps to return RunHandleStatus.Live(upstreamRunId, pid)
    // when the supervisor knows about the run. The map is pruned by the
    // same drainer thread that prunes liveProcs.
    private val liveUpstreamIds = java.util.concurrent.ConcurrentHashMap<String, String>()
    // R0.3.6 (2026-10-11): liveness marker keyed by runId. The supervisor
    // (status) needs to know the runId is alive even when the spawner is
    // a test stub that doesn't expose a raw [Process] (the `liveProcs`
    // map is only populated when the spawner is a [JavaProcessSpawner]).
    // The set tracks "this runId has been admitted with a journal-emitted
    // upstream id and has not yet exited" — independent of whether a real
    // [Process] handle is available for cancel().
    private val liveMarkers = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    // Hook the Process exit so we can prune the map. We pass this through
    // the spawner's asyncDetach callback (S0 didn't plumb it, so for now we
    // use a waitFor() on a daemon thread per process to clean up).
    private val lifecycleDrainers = java.util.concurrent.ConcurrentHashMap<String, Thread>()

    override fun submit(req: RunRequest): SubmissionResult {
        // B2.4 (2026-10-10): the runner enforces the profile
        // contract. Per the B2 mandate, "Si se necesita un
        // perfil no confiable, admitirlo solo cuando haya un
        // mecanismo OS concreto y un test de aislamiento
        // superado. En otro caso, denegarlo." The only
        // currently accepted profile is TrustedLocal. The
        // Untrusted seam exists in the request envelope so
        // the contract is explicit, but the runner MUST
        // refuse it until an OS-level sandbox + isolation
        // test exist. This is the B2.4 invariant: a clear
        // Refused with a documented reason, not a silent
        // fall-through to a non-sandboxed execution.
        if (req.profile !is ExecutionProfile.TrustedLocal) {
            return SubmissionResult.Refused(
                "PROFILE_NOT_SUPPORTED: profile ${req.profile::class.simpleName} " +
                    "requires an OS-level sandbox that the studio does not yet implement. " +
                    "Only ExecutionProfile.TrustedLocal is admitted (D-005, B2 mandate § 6.6).",
            )
        }
        val controlRoot = controlRootBase.resolve(req.runId.value).also { Files.createDirectories(it) }
        // B2.3 (2026-10-10): every per-run path the runner
        // resolves MUST stay under controlRootBase. The check
        // is the same shape as RunReconciler's H-25 defense:
        // refuse the submit with a clear reason if a future
        // change accidentally lets a per-run path escape the
        // boundary. Defense in depth, not a fix to a current
        // bug — the runner today only resolves
        // controlRootBase/<runId>/<file>, which always
        // starts with controlRootBase. The test locks the
        // invariant.
        when (val v = PathConfinement.check(controlRootBase, controlRoot, "controlRoot")) {
            is PathConfinement.Verdict.Escaped ->
                return SubmissionResult.Refused("PATH_ESCAPE: ${v.reason}")
            PathConfinement.Verdict.Inside -> { /* ok */ }
        }
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
            log.info("submitting run={} upstream-run-id will be read from run.db", req.runId.value)
            // Capture subprocess stdout+stderr to disk so OutputReadPort can serve real bytes.
            val stdoutLog = controlRoot.resolve("stdout.log")
            val proc = spawner.spawnWithCapture(mainClass, sdkClasspath, argv, stdoutSink(stdoutLog))
            // Register the live Process so /cancel can find + destroy it.
            // The SpawnedProcess wrapper does not expose the underlying Process
            // in the S0 port; we ask the spawner for the raw Process via
            // processFor(proc) — JavaProcessSpawner provides this. The default
            // spawn() (no capture) returns null for the raw handle.
            val raw = (spawner as? JavaProcessSpawner)?.processFor(proc)
            // R0.3.6 (2026-10-11): the upstream run id MUST come from the
            // subprocess's own journal — never a UUID fabricated at spawn
            // time. The SDK subprocess writes a `RunStarted` event whose
            // payload carries `upstream_id`. We poll run.db for that event
            // with a short timeout; if the subprocess has not emitted it
            // yet, we refuse the submit rather than fabricate a fake id.
            // The HTTP layer maps this Refused into a 503 + clear reason
            // so the operator can retry once the subprocess has settled.
            val upstreamRunId = readUpstreamIdFromJournal(runDb, timeout = UPSTREAM_ID_POLL_TIMEOUT)
            if (upstreamRunId == null) {
                // No RunStarted event arrived within the poll window. Stop
                // the subprocess (best-effort) so we don't leak it; the
                // contract is honest about the missing authority.
                runCatching { raw?.destroyForcibly() }
                return SubmissionResult.Refused(
                    "UPSTREAM_ID_PENDING: subprocess started but no RunStarted event with upstream_id " +
                        "was emitted to run.db within ${UPSTREAM_ID_POLL_TIMEOUT.toMillis()} ms; refusing to " +
                        "fabricate a fake upstream id (R0.3.6 mandate)",
                )
            }
            if (raw != null) {
                liveProcs[req.runId.value] = raw
                // Audit F-P0-3 + R0.3.6: the status supervisor needs
                // both the upstream runId (now from the subprocess's
                // own journal, not a UUID generator — see R0.3.6) and
                // the liveness marker keyed off runId.
                liveUpstreamIds[req.runId.value] = upstreamRunId
                liveMarkers.add(req.runId.value)
                val drainer = Thread({
                    try { raw.waitFor() } catch (_: InterruptedException) { /* expected on cancel */ }
                    liveProcs.remove(req.runId.value)
                    liveUpstreamIds.remove(req.runId.value)
                    liveMarkers.remove(req.runId.value)
                    lifecycleDrainers.remove(req.runId.value)
                }, "studio-runner-drain-${proc.pid}").apply {
                    isDaemon = true
                    start()
                }
                lifecycleDrainers[req.runId.value] = drainer
            } else {
                // Spawner without a raw Process handle (test stubs):
                // still mark the runId as alive AND record the
                // journal-emitted upstream id so the supervisor can
                // answer status(runId). cancel() will return Unknown
                // in this case (no process to destroy), which is honest.
                liveUpstreamIds[req.runId.value] = upstreamRunId
                liveMarkers.add(req.runId.value)
            }
            // We do not wait here synchronously; the supervisor flips state in background.
            // For the S0 minimal happy path, we admit and let the supervisor poll.
            proc.asyncDetach()
            SubmissionResult.Admitted(
                upstreamRunId = upstreamRunId,
                controlRoot = controlRoot,
            )
        } catch (e: Exception) {
            log.error("runner submit failed", e)
            SubmissionResult.Refused("runner-error: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    /**
     * R0.3.6 (2026-10-11): poll run.db for the first `RunStarted` event
     * whose payload carries an `upstream_id` field. Returns the id, or
     * null if [timeout] passes without the subprocess emitting the event.
     *
     * The poll is short and bounded — production subprocesses write the
     * `RunStarted` event within milliseconds of fork. The runner refuses
     * rather than fabricate if the wait times out, per the R0.3.6 mandate.
     */
    private fun readUpstreamIdFromJournal(runDb: Path, timeout: Duration): String? {
        val deadline = System.nanoTime() + timeout.toNanos()
        while (System.nanoTime() < deadline) {
            try {
                if (!Files.isRegularFile(runDb)) {
                    Thread.sleep(20)
                    continue
                }
                dev.pipelinek.studio.infra.storage.SqliteConnection.openReadOnly(runDb).use { c ->
                    c.prepareStatement(
                        "SELECT payload FROM events WHERE kind = 'RunStarted' ORDER BY sequence ASC LIMIT 1",
                    ).use { ps ->
                        ps.executeQuery().use { rs ->
                            if (!rs.next()) {
                                Thread.sleep(20)
                                return@use null
                            }
                            val payload = rs.getString(1) ?: return@use null
                            val id = extractUpstreamId(payload) ?: return@use null
                            return id
                        }
                    }
                }
            } catch (_: Exception) {
                Thread.sleep(20)
            }
        }
        return null
    }

    /**
     * Tiny hand-rolled `upstream_id` extractor for the `RunStarted`
     * payload. Mirrors [dev.pipelinek.studio.domain.RunOutcome.extractOutcomeField]
     * in shape (no regex, no JSON parser, length-bounded scan). The
     * payload is the SDK's `RunStarted` event body, e.g.
     * `{"upstream_id":"abc-123","started_at":"..."}`. If the field is
     * missing or the payload is malformed, we return null so the caller
     * (the submit path) refuses.
     */
    private fun extractUpstreamId(payload: String): String? {
        val max = 4096
        if (payload.length > max) return null
        val needle = "\"upstream_id\""
        var i = 0
        val n = payload.length
        var depth = 0
        while (i < n) {
            val c = payload[i]
            if (c == '{') { depth++; i++; continue }
            if (c == '}') { depth--; i++; continue }
            if (c == '"' && depth == 1 &&
                i + needle.length <= n &&
                payload.regionMatches(i, needle, 0, needle.length)
            ) {
                var p = i + needle.length
                while (p < n && payload[p].isWhitespace()) p++
                if (p < n && payload[p] == ':') {
                    p++
                    while (p < n && payload[p].isWhitespace()) p++
                    if (p < n && payload[p] == '"') {
                        p++
                        val sb = StringBuilder()
                        while (p < n) {
                            val ch = payload[p]
                            if (ch == '\\' && p + 1 < n) {
                                sb.append(payload[p + 1])
                                p += 2
                                continue
                            }
                            if (ch == '"') break
                            sb.append(ch)
                            p++
                        }
                        if (p < n && payload[p] == '"') {
                            return sb.toString().take(max)
                        }
                        return null
                    }
                }
            }
            i++
        }
        return null
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

    /**
     * Audit F-P0-3 (2026-10-10): the previous implementation was a hard-
     * coded `Unknown("status supervisor not implemented in S0 bootstrap")`
     * that masked the real state. The fix is hybrid:
     *
     *  - If the supervisor has a live Process registered for this runId,
     *    return [RunHandleStatus.Live] with the upstreamRunId and pid.
     *  - If the supervisor has a Process that has just exited (the
     *    drainer has not yet removed it), return [RunHandleStatus.Unknown]
     *    with a clear reason pointing the caller to the run.db.
     *  - If the supervisor has no record of the runId, return
     *    [RunHandleStatus.Unknown] with an explicit reason. The runner
     *    cannot tell whether the run is "still booting", "already
     *    finished", or "never existed" — the contract is honest about
     *    that limit; the run.db is the canonical source of truth.
     */
    override fun status(runId: RunId): RunHandleStatus {
        // R0.3.6 (2026-10-11): the supervisor must check the liveness
        // marker first. The marker is set on admit (after journal-
        // emitted upstream_id is captured) and cleared when the
        // subprocess exits. Test stubs without a raw Process still
        // populate the marker so status() returns Live when the
        // runId was admitted with a real upstream id.
        if (!liveMarkers.contains(runId.value)) {
            val proc = liveProcs[runId.value]
            return if (proc == null) {
                RunHandleStatus.Unknown(
                    "no live subprocess registered for runId ${runId.value}; " +
                        "consult run.db for terminal state (RUNNING / RunFinished / RunCancelled / RunLost)",
                )
            } else {
                RunHandleStatus.Unknown(
                    "process for runId ${runId.value} has exited; consult run.db for terminal state",
                )
            }
        }
        // R0.3.6: only return Live when we have the journal-emitted
        // upstream id. Without it, status must honestly report Unknown
        // — never a UUID-prefixed string.
        val upstream = liveUpstreamIds[runId.value]
            ?: return RunHandleStatus.Unknown(
                "subprocess is alive but no upstream_id was captured from run.db; " +
                    "the supervisor refuses to project a fabricated id (R0.3.6)",
            )
        val pid = liveProcs[runId.value]?.let { runCatching { it.pid() }.getOrNull() }
        return RunHandleStatus.Live(upstreamRunId = upstream, pid = pid)
    }

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
    // [Process] to implement cancel(). Entries are auto-pruned via
    // [Process.onExit] when the subprocess terminates, so the map never
    // grows unbounded across the JVM's lifetime (audit C-6).
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
        }.also { wrapper ->
            spawnedHandles[wrapper] = proc
            // Audit C-6: auto-prune the spawnedHandles entry when the
            // subprocess exits. Process.onExit() is JDK 9+ and returns a
            // CompletableFuture that completes when the process terminates
            // (normally or forcibly). This replaces the previous design
            // where entries accumulated until JVM shutdown, leaking
            // Process references and pinning file descriptors. The default
            // executor for onExit is the common ForkJoinPool, which is
            // daemon-safe and won't block JVM shutdown.
            proc.onExit().whenComplete { _, _ -> spawnedHandles.remove(wrapper) }
        }
    }

    /**
     * Expose the raw [Process] for a previously-spawned [SpawnedProcess].
     * The runner uses this to register the live subprocess for cancel();
     * the default [ProcessSpawner] returns null (no cancel support).
     */
    fun processFor(spawned: SpawnedProcess): Process? = spawnedHandles[spawned]
}
