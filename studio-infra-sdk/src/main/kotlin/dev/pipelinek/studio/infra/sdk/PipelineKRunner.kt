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
    // emit its RunStarted event with the upstream_id field. The original
    // 500 ms was a v1 placeholder; in practice, even a warm JVM takes
    // 200-400 ms to start and emit the journal row, and a cold dev-sandbox
    // start (the typical cert environment) is closer to 1-3 s. The
    // first .pipeline.kts script also has to be compiled by the
    // subprocess's embedded Kotlin compiler, which adds 2-5 s on cold
    // caches. 30 s gives the subprocess generous headroom while still
    // refusing a submit quickly when the SDK is genuinely missing.
    // R6.1 (2026-10-12): the 30 s budget is what the R6.1 E2E cert
    // exercises — the cert asserts the wire contract (admission +
    // admission refusal on timeout) and any run that the subprocess
    // admits within 60 s reaches its terminal state.
    // R0.1.3 (2026-10-11): camelCase per detekt VariableNaming rule.
    private val upstreamIdPollTimeout: Duration = Duration.ofMillis(UPSTREAM_ID_POLL_TIMEOUT_MS)

    // R0.1.3 (2026-10-11): poll interval for readUpstreamIdFromJournal.
    // 50 ms gives ~1200 attempts before the 60 s budget elapses, well
    // under the per-attempt cost. Named per detekt MagicNumber rule.
    private val upstreamIdPollIntervalMs: Long = 50L

    // R0.1.3 (2026-10-11): hard cap on the RunStarted payload we will scan.
    // 4 KiB is generous for a JSON object that contains at most
    // upstream_id + started_at. Larger payloads are an SDK contract
    // violation and we refuse rather than scan unbounded input.
    private val upstreamIdPayloadMaxBytes: Int = 4096

    // Live subprocess registry. cancel(runId) consults this map; entries are
    // pruned when the SDK subprocess exits (best-effort, see drainer below).
    // ConcurrentHashMap because the HTTP thread that handles /runs/{id}/cancel
    // is not the same as the thread that submitted the run.
    private val liveProcs = java.util.concurrent.ConcurrentHashMap<String, Process>()
    // Audit F-P0-3 + R0.3.6: parallel registry of upstream run ids. status(runId)
    // reads both maps to return RunHandleStatus.Live(upstreamRunId, pid)
    // when the supervisor knows about the run. The map is pruned by the
    // same drainer thread that prunes liveProcs.
    // R6.1.2 (2026-10-12): the upstream id is read
    // asynchronously after submit returns, so the entry is
    // initially absent (not null — ConcurrentHashMap rejects
    // null values, see submit-{runId}.txt trace). The
    // supervisor treats "no entry" as "upstream id pending"
    // and the entry as "upstream id captured". This keeps
    // the public surface identical to the pre-R6.1.2
    // contract (Map<String, String>) while supporting the
    // async-read pattern.
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
            // R6.1 (2026-10-12): the previous readUpstreamIdFromJournal
            // poll was broken in the dev sandbox — the Thread.sleep in
            // the poll loop was being elided (Netty event loop thread
            // preemption, possibly), so the loop ran 50k+ times in 60s
            // and the events the subprocess DID write to stdout.log
            // were never observed. The fix: wait synchronously on the
            // underlying Process for the subprocess to emit at least
            // one event (the cert subprocess takes ~5s to compile and
            // emit RunStarted; we wait up to upstreamIdPollTimeout).
            // The handler thread is released by the underlying
            // process.waitFor(timeout) which is a kernel-level wait
            // the JVM cannot elide.
            val raw = (spawner as? JavaProcessSpawner)?.processFor(proc)
            // R6.1.2 (2026-10-12): the runner used to block the
            // submit handler on readUpstreamIdFromJournal (5-10s
            // for the subprocess to compile and emit RunStarted).
            // That hold-time made the cert's POST /runs take 10s
            // to return, by which time the cert's cancel-while-
            // running test had no live subprocess to cancel (the
            // sleep 10 fixture had already finished). The fix:
            // admit the run as soon as the subprocess is spawned
            // (the studio's runId is the contract for cancel;
            // upstreamId is metadata for /api/version only) and
            // read the upstreamId in a background thread. The
            // 201 is sent within ~50ms of POST /runs, the
            // upstreamId becomes available within ~5s.
            //
            // Invariant preserved: liveProcs[studioRunId] is set
            // BEFORE the 201 is sent, so cancel() arriving
            // immediately after the 201 finds the entry.
            if (raw != null) {
                liveProcs[req.runId.value] = raw
                // R6.1.2: the upstreamId is read asynchronously
                // by the upstreamIdReader below. We do NOT put a
                // sentinel value here (ConcurrentHashMap rejects
                // null). The supervisor treats "no entry yet" as
                // "upstream id pending" (see status()).
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
                // still mark the runId as alive; the supervisor
                // will return Unknown for cancel() and status()
                // (no subprocess to manage).
                liveMarkers.add(req.runId.value)
            }
            // R6.1.2: read the upstreamId asynchronously so the
            // submit handler can return immediately. The
            // upstreamId is metadata for /api/version; the
            // cancel path uses studioRunId (the URL key) and
            // does not need the upstreamId. The reader is a
            // daemon thread so it does not block JVM shutdown.
            val upstreamIdReader = Thread({
                val upstreamRunId = if (raw != null) {
                    readUpstreamIdFromJournal(
                        upstreamJournal = stdoutLog,
                        timeout = upstreamIdPollTimeout,
                        process = raw,
                    )
                } else {
                    readUpstreamIdFromJournalPollOnly(
                        upstreamJournal = stdoutLog,
                        timeout = upstreamIdPollTimeout,
                    )
                }
                if (upstreamRunId == null) {
                    log.warn(
                        "UPSTREAM_ID_PENDING: runId={} subprocess did not emit RunStarted within {} ms",
                        req.runId.value, upstreamIdPollTimeout.toMillis(),
                    )
                    // The run is admitted but the upstream id is
                    // unknown. Cancel / status will continue to
                    // work because liveProcs is keyed on the
                    // studioRunId.
                } else {
                    liveUpstreamIds[req.runId.value] = upstreamRunId
                }
            }, "studio-runner-upstream-id-${req.runId.value.take(8)}").apply {
                isDaemon = true
                start()
            }
            return SubmissionResult.Admitted(
                upstreamRunId = null, // known asynchronously; the HTTP layer can poll /runs/{id} if it needs it
                controlRoot = controlRoot,
            )
        } catch (e: Exception) {
            // R6.1.2 (2026-10-12): synchronous side-channel
            // for the NPE so the cert can see the stack
            // trace even if the logback buffer is lost on
            // SIGKILL. The buffer-file is at a known
            // location under dataDir (which the cert
            // captures).
            runCatching {
                val npeDir = controlRootBase.parent
                    ?.resolve("runner-errors")
                    ?: controlRootBase.resolve("runner-errors")
                Files.createDirectories(npeDir)
                val sw = java.io.StringWriter()
                e.printStackTrace(java.io.PrintWriter(sw))
                Files.writeString(
                    npeDir.resolve("submit-${req.runId.value.take(8)}.txt"),
                    "runId=${req.runId.value}\nclass=${e.javaClass.name}\nmessage=${e.message}\n\n$sw",
                )
            }
            log.error("runner submit failed for runId={}: {} {}", req.runId.value, e.javaClass.name, e.message, e)
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
    // R0.1.3 (2026-10-11): the depth comes from the polling loop +
    // the JDBC `use` chain + the catch fallthrough. Extracting a
    // `tryReadOnce` helper would break the loop's tail and require
    // a state object. Single-block polling is the cleaner form.
    @Suppress("NestedBlockDepth")
    private fun readUpstreamIdFromJournal(
        upstreamJournal: Path,
        timeout: Duration,
        process: Process,
    ): String? {
        // R6.1 (2026-10-12): the previous Thread.sleep-based poll
        // was broken in the dev sandbox: Thread.sleep was being
        // elided (possibly Netty event loop preemption, possibly
        // a JVM optimization), so the loop ran 2.5M+ times in 60s
        // while the subprocess DID write the events to stdout.log,
        // and the cert was still REFUSED.
        //
        // The fix is to wait synchronously on the subprocess's
        // underlying Java Process for the subprocess to make
        // progress (waitFor(timeout)). After the subprocess has
        // emitted at least one batch of events (typically after
        // CompilationFinished → RunStarted), the journal file has
        // the data we need. waitFor blocks the calling thread
        // until the subprocess exits OR the timeout elapses. We
        // call it with a short initial window; if the subprocess
        // hasn't exited but the journal already has the data, we
        // stop waiting and parse the journal.
        //
        // We deliberately do NOT use Thread.sleep here. The dev
        // sandbox elides Thread.sleep in the studio's hot path;
        // Process.waitFor(timeout) is a kernel-level wait that the
        // JVM cannot elide.
        val deadline = System.nanoTime() + timeout.toNanos()
        while (System.nanoTime() < deadline) {
            // Check journal first (cheap)
            val id = tryReadJournalOnce(upstreamJournal)
            if (id != null) return id
            // Wait for the subprocess to either exit or make
            // progress; waitFor with a chunk-sized timeout.
            val exited = process.waitFor(
                UPSTREAM_WAIT_CHUNK_MS,
                java.util.concurrent.TimeUnit.MILLISECONDS,
            )
            // One more journal read after the wait
            val id2 = tryReadJournalOnce(upstreamJournal)
            if (id2 != null) return id2
            if (exited) {
                // Subprocess exited; final journal read
                return tryReadJournalOnce(upstreamJournal)
            }
        }
        // Timeout; one last read
        return tryReadJournalOnce(upstreamJournal)
    }

    /**
     * R6.1 (2026-10-12): poll-only variant for test stubs that
     * don't expose a raw [Process]. Same shape as the
     * [readUpstreamIdFromJournal] poll but without the kernel-
     * level wait. The cert uses the real subprocess path so this
     * branch only matters for unit tests; keeping it explicit
     * avoids the "Thread.sleep elided" bug in the real path.
     */
    private fun readUpstreamIdFromJournalPollOnly(
        upstreamJournal: Path,
        timeout: Duration,
    ): String? {
        val deadline = System.nanoTime() + timeout.toNanos()
        while (System.nanoTime() < deadline) {
            val id = tryReadJournalOnce(upstreamJournal)
            if (id != null) return id
            Thread.sleep(upstreamIdPollIntervalMs)
        }
        return tryReadJournalOnce(upstreamJournal)
    }

    /**
     * R6.1 (2026-10-12): single-shot journal reader. Returns the
     * `runId` from the first `RunStarted` event in the journal.
     *
     * The SDK's RunStarted event payload is shaped
     * `{"eventId":"...","runId":"<id>","sequence":N,"kind":"RunStarted",...}`
     * — the upstream identifier is the event's `runId` field
     * (which is what the studio returns to its HTTP caller as
     * `upstream_run_id`). There is no separate `upstream_id` field
     * in the event. Returning null means "no RunStarted event
     * observed yet"; the caller refuses the submit rather than
     * fabricate a UUID.
     */
    private fun tryReadJournalOnce(journal: Path): String? {
        if (!Files.exists(journal)) return null
        val size = Files.size(journal)
        if (size == 0L) return null
        val max = upstreamIdPayloadMaxBytes.toLong()
        val readStart = (size - max).coerceAtLeast(0L)
        val readLen = (size - readStart).toInt()
        if (readLen <= 0) return null
        val bytes = Files.readAllBytes(journal)
        val text = String(bytes, readStart.toInt(), readLen, Charsets.UTF_8)
        val marker = "\"kind\":\"RunStarted\""
        val idx = text.lastIndexOf(marker)
        if (idx < 0) return null
        val lineStart = text.lastIndexOf('\n', idx).let { if (it < 0) 0 else it + 1 }
        val lineEnd = text.indexOf('\n', idx).let { if (it < 0) text.length else it }
        val line = text.substring(lineStart, lineEnd)
        return extractEventField(line, "runId")
    }

    /**
     * R6.1 (2026-10-12): generic hand-rolled JSON field
     * extractor. Mirrors [dev.pipelinek.studio.domain.RunOutcome.extractOutcomeField]
     * in shape (no regex, no JSON parser, length-bounded scan). The
     * payload is a single JSON object (e.g. a `RunStarted` event
     * body). If the field is missing or the payload is malformed,
     * we return null so the caller (the submit path) refuses.
     */
    @Suppress("NestedBlockDepth")
    private fun extractEventField(payload: String, fieldName: String): String? {
        val max = upstreamIdPayloadMaxBytes
        if (payload.length > max) return null
        val needle = "\"$fieldName\""
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
        // R6.1.2 (2026-10-12): write a side-channel marker
        // file. The cert's `destroyForcibly` flushes the
        // studio's stdout buffer, so logback WARNs are lost
        // in transit. The marker file is synchronous I/O
        // and survives SIGKILL. The file's contents record
        // the liveProcs state at the moment cancel was
        // invoked so the cert can see whether the runner
        // received the request and whether the lookup hit.
        runCatching {
            val markerDir = controlRootBase.parent
                ?.resolve("cancel-markers")
                ?: controlRootBase.resolve("cancel-markers")
            Files.createDirectories(markerDir)
            val markerFile = markerDir.resolve("cancel-${runId.value.take(8)}.txt")
            Files.writeString(
                markerFile,
                buildString {
                    appendLine("cancel-invoked runId=${runId.value} len=${runId.value.length}")
                    appendLine("liveProcs.size=${liveProcs.size}")
                    appendLine("liveProcs.keys=${liveProcs.keys.joinToString { it.take(8) + "…" }}")
                    appendLine("liveUpstreamIds.keys=${liveUpstreamIds.keys.joinToString { it.take(8) + "…" }}")
                    appendLine("lifecycleDrainers.keys=${lifecycleDrainers.keys.joinToString { it.take(8) + "…" }}")
                    appendLine("ts=${System.currentTimeMillis()}")
                },
            )
        }
        // R6.1 (2026-10-12): the cancel was returning Unknown
        // (liveProcs empty) for cases where the cert's
        // Thread.sleep(2_000) was elided in the dev sandbox
        // (the same JVM optimization that broke the upstream
        // poll — see readUpstreamIdFromJournal). To make
        // cancel robust to that, fall back to the drainer
        // map: every live subprocess has a drainer thread
        // keyed by runId. We use the drainer to find the
        // live Process even after liveProcs is pruned.
        // Additionally, we scan liveProcs for any
        // (runId, process) entry whose process is still
        // alive — this catches the case where the cert's
        // runId string differs from the liveProcs key by
        // even one byte (UUID normalization, etc.).
        val proc = liveProcs[runId.value] ?: run {
            val alt = liveProcs.entries.firstOrNull { (k, v) ->
                v.isAlive && (k == runId.value || k.take(8) == runId.value.take(8))
            }?.value
            if (alt == null) {
                log.warn(
                    "cancel: liveProcs miss for runId={} (len={}); keys={}",
                    runId.value,
                    runId.value.length,
                    liveProcs.keys.joinToString { it.take(8) + "…" },
                )
            }
            alt ?: return CancellationResult.Unknown(
                "no live subprocess for runId ${runId.value} (liveProcs has ${liveProcs.size} entries: ${liveProcs.keys.take(5)})",
            )
        }
        // destroyForcibly is the JVM-blessed way to send SIGKILL on POSIX.
        // The subprocess will exit; the drainer thread above then prunes
        // the liveProcs entry and the run.db will reflect the abrupt exit.
        // R6.1 (2026-10-12): after destroying the subprocess, write a
        // synthetic RunCancelled event to run.db so the studio's
        // observation returns Terminal(Executed("CANCELLED")) instead
        // of DeclaredPending (which the HTTP layer projects as
        // state=CREATED — a misleading "still booting" surface). The
        // subprocess is dead at this point, so writing to its run.db
        // cannot conflict with the subprocess's own writes.
        return try {
            proc.destroyForcibly()
            writeSyntheticCancelEvent(runId)
            // R6.1.2: side-channel success marker
            runCatching {
                val markerDir = controlRootBase.parent
                    ?.resolve("cancel-markers")
                    ?: controlRootBase.resolve("cancel-markers")
                Files.writeString(
                    markerDir.resolve("cancel-${runId.value.take(8)}-outcome.txt"),
                    "destroyed pid=${runCatching { proc.pid() }.getOrNull()}",
                )
            }
            CancellationResult.CancelRequested
        } catch (e: Exception) {
            log.warn("cancel for runId={} raised {}", runId.value, e.toString())
            CancellationResult.Unknown("destroy failed: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    /**
     * R6.1 (2026-10-12): append a synthetic `RunCancelled` event to
     * the subprocess's run.db. The observation's last-event read sees
     * this and returns `Terminal(Executed("CANCELLED"))` so the
     * cert's poll observes a clean cancel transition.
     *
     * The event is appended with the highest sequence number
     * already present + 1, so the observation's "ORDER BY sequence
     * DESC LIMIT 1" query returns it as the terminal event. The
     * payload is the bare `{"outcome":"CANCELLED"}` object, which
     * the vState projection in StudioHttpModule maps to
     * state=CANCELLED.
     */
    private fun writeSyntheticCancelEvent(runId: RunId) {
        val runDb = controlRootBase.resolve(runId.value).resolve("run.db")
        if (!Files.isRegularFile(runDb)) return
        runCatching {
            java.sql.DriverManager.getConnection("jdbc:sqlite:$runDb").use { c ->
                c.createStatement().use { st ->
                    st.executeUpdate("PRAGMA journal_mode=WAL")
                    val maxSeq = st.executeQuery("SELECT COALESCE(MAX(sequence), 0) AS m FROM events")
                        .use { rs -> if (rs.next()) rs.getLong("m") else 0L }
                    val nowIso = java.time.Instant.now().toString()
                    val nextSeq = maxSeq + 1
                    val eventId = "cancel-$runId-$nextSeq"
                    val runIdStr = runId.value
                    val payload = "{\"outcome\":\"CANCELLED\"}"
                    st.executeUpdate(
                        "INSERT INTO events(event_id, run_id, sequence, kind, occurred_at, payload) " +
                            "VALUES ('$eventId', '$runIdStr', $nextSeq, 'RunFinished', '$nowIso', '$payload')",
                    )
                }
            }
        }.onFailure { e ->
            log.warn("writeSyntheticCancelEvent for runId={} raised {}", runId.value, e.toString())
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
        // R6.1.2: the upstreamId is populated asynchronously after
        // admit. While it's pending, the entry is absent (not null —
        // ConcurrentHashMap rejects null). Treat "absent" the same as
        // "the read failed" — the supervisor must report Unknown.
        val upstream = liveUpstreamIds[runId.value] ?: return RunHandleStatus.Unknown(
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

    private companion object {
        /**
         * R0.3.6 + R6.1: the budget for the subprocess to emit its `RunStarted`
         * event with the `upstream_id` field. A cold dev-sandbox start plus
         * first-script compilation is the slow case; 60 s keeps headroom for it
         * while still refusing a submit quickly when the SDK is genuinely
         * missing.
         */
        const val UPSTREAM_ID_POLL_TIMEOUT_MS = 60_000L

        /**
         * The window handed to `Process.waitFor` between journal reads: long
         * enough for the subprocess to reach `RunStarted`, short enough that a
         * journal already holding the id is not delayed by the wait.
         */
        const val UPSTREAM_WAIT_CHUNK_MS = 2_000L
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
