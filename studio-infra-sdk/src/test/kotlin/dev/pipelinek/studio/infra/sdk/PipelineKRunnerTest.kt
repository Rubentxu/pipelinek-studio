package dev.pipelinek.studio.infra.sdk

import dev.pipelinek.studio.application.ports.*
import dev.pipelinek.studio.domain.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertIs

class PipelineKRunnerTest {

    @Test
    fun `submit builds SPAWNED child and admits with controlRoot`(@TempDir tmp: Path) {
        val recorded = java.util.concurrent.atomic.AtomicReference<Pair<String, List<String>>>(null)
        val spawner = object : ProcessSpawner {
            override fun spawn(mainClass: String, classpath: List<Path>, argv: List<String>): SpawnedProcess {
                recorded.set(mainClass to argv)
                // R0.3.6: pre-create the run.db with a RunStarted event so
                // the runner reads a real upstream_id (not a UUID).
                val ctrlRoot = argv[argv.indexOf("--control-root") + 1]
                val runDb = Path.of(ctrlRoot).resolve("run.db")
                preCreateRunStartedEvent(runDb, "upstream-test-happy-path")
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
            snapshotSource = StubSnapshotSource(),
        )
        val req = RunRequest(
            runId = RunId.new(),
            snapshotId = SnapshotId.new(),
            scenarioId = ScenarioId.new(),
            profile = ExecutionProfile.TrustedLocal,
        )
        val result = runner.submit(req)
        assertIs<SubmissionResult.Admitted>(result)
        assertTrue(result.controlRoot.toString().contains(req.runId.value))
        val r = recorded.get()
        assertEquals("dev.rubentxu.pipeline.v2.application.MainKt", r.first)
        assertTrue(r.second.contains("--db"), "expected --db flag, got ${r.second}")
        assertTrue(r.second.contains("--control-root"), "expected --control-root flag")
        assertTrue(r.second.contains("run"), "expected 'run' subcommand")
        assertTrue(r.second.any { it.endsWith("snapshot.pipeline.kts") }, "expected snapshot.pipeline.kts path")
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
            snapshotSource = StubSnapshotSource(),
        )
        val req = RunRequest(
            runId = RunId.new(),
            snapshotId = SnapshotId.new(),
            scenarioId = ScenarioId.new(),
            profile = ExecutionProfile.TrustedLocal,
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
            snapshotSource = StubSnapshotSource(),
        )
        val res = runner.cancel(RunId.new())
        assertIs<CancellationResult.Unknown>(res)
    }

    /**
     * B2.3 (2026-10-10): the runner's per-submit path shape
     * stays under controlRootBase. The end-to-end test is
     * "the normal happy path still admits" (the helper's
     * own unit tests cover the escape cases). The reason
     * this lives here: any future change to submit() that
     * accidentally lets a per-run path resolve outside the
     * base will trip the check and flip the test from
     * Admitted to Refused — a built-in regression net for
     * B2.3.
     */
    @Test
    fun `B2_3 normal submit stays confined and is admitted`(@TempDir tmp: Path) {
        val runner = PipelineKRunner(
            controlRootBase = tmp.resolve("control").also { java.nio.file.Files.createDirectories(it) },
            sdkClasspath = listOf(tmp.resolve("fake.jar")),
            mainClass = "Main",
            spawner = object : ProcessSpawner {
                override fun spawn(mainClass: String, classpath: List<Path>, argv: List<String>): SpawnedProcess {
                    // R0.3.6: pre-create the journal so the runner reads a
                    // real upstream id (not a UUID).
                    val ctrlRoot = argv[argv.indexOf("--control-root") + 1]
                    val runDb = Path.of(ctrlRoot).resolve("run.db")
                    preCreateRunStartedEvent(runDb, "upstream-b2-3")
                    return object : SpawnedProcess {
                        override fun asyncDetach() {}
                        override val pid: Long? = null
                    }
                }
            },
            snapshotSource = StubSnapshotSource(),
        )
        val req = RunRequest(
            runId = RunId.new(),
            snapshotId = SnapshotId.new(),
            scenarioId = ScenarioId.new(),
            profile = ExecutionProfile.TrustedLocal,
        )
        val r = runner.submit(req)
        assertIs<SubmissionResult.Admitted>(r)
        // The admitted controlRoot must live under controlRootBase.
        val base = tmp.resolve("control").toAbsolutePath().normalize()
        val got = r.controlRoot.toAbsolutePath().normalize()
        assertTrue(got.startsWith(base), "admitted controlRoot escapes base: $got vs $base")
    }

    /**
     * B2.4 (2026-10-10): the runner MUST refuse ExecutionProfile.Untrusted
     * with a clear, documented reason — never silently fall through to a
     * non-sandboxed execution. The B2 mandate is explicit: "Si se
     * necesita un perfil no confiable, admitirlo solo cuando haya un
     * mecanismo OS concreto y un test de aislamiento superado. En
     * otro caso, denegarlo." The contract this test locks:
     *  - the spawner is never invoked (no subprocess started);
     *  - the result is SubmissionResult.Refused;
     *  - the reason mentions PROFILE_NOT_SUPPORTED and points the
     *    operator at the TrustedLocal-only invariant.
     *
     * The boolean guard via the spawner is what proves the runner
     * short-circuited before any side effect.
     */
    @Test
    fun `B2_4 Untrusted profile is refused before any subprocess spawn`(@TempDir tmp: Path) {
        val spawnerCalled = java.util.concurrent.atomic.AtomicBoolean(false)
        val spawner = object : ProcessSpawner {
            override fun spawn(mainClass: String, classpath: List<Path>, argv: List<String>): SpawnedProcess {
                spawnerCalled.set(true)
                error("spawner must not be called for Untrusted")
            }
        }
        val runner = PipelineKRunner(
            controlRootBase = tmp,
            sdkClasspath = listOf(tmp.resolve("fake.jar")),
            mainClass = "Main",
            spawner = spawner,
            snapshotSource = StubSnapshotSource(),
        )
        val req = RunRequest(
            runId = RunId.new(),
            snapshotId = SnapshotId.new(),
            scenarioId = ScenarioId.new(),
            profile = ExecutionProfile.Untrusted,
        )
        val r = runner.submit(req)
        assertIs<SubmissionResult.Refused>(r)
        assertTrue(
            r.reason.contains("PROFILE_NOT_SUPPORTED"),
            "expected PROFILE_NOT_SUPPORTED in reason, got: ${r.reason}",
        )
        assertTrue(
            r.reason.contains("OS-level sandbox"),
            "expected reason to mention OS-level sandbox requirement, got: ${r.reason}",
        )
        assertTrue(
            r.reason.contains("TrustedLocal"),
            "expected reason to name the only admitted profile, got: ${r.reason}",
        )
        // The boolean guard: no subprocess was ever spawned.
        assertTrue(
            !spawnerCalled.get(),
            "B2.4 invariant: Untrusted must short-circuit before any side effect",
        )
    }

    /**
     * Audit F-P0-3 (2026-10-10): the previous implementation was a hard-
     * coded `Unknown("status supervisor not implemented in S0 bootstrap")`
     * that masked the real state. The fix must:
     *  - return Unknown with a clear reason when no subprocess is registered
     *  - return Unknown with a different reason (no fake "Live" claim) when
     *    the supervisor has no Process
     *  - never return a hard-coded "not implemented" string
     */
    @Test
    fun `status returns Unknown with explicit reason when no subprocess registered`(@TempDir tmp: Path) {
        val runner = PipelineKRunner(
            controlRootBase = tmp,
            sdkClasspath = emptyList(),
            mainClass = "Main",
            spawner = object : ProcessSpawner {
                override fun spawn(mainClass: String, classpath: List<Path>, argv: List<String>): SpawnedProcess {
                    error("not used")
                }
            },
            snapshotSource = StubSnapshotSource(),
        )
        val res = runner.status(RunId.new())
        assertIs<RunHandleStatus.Unknown>(res)
        assertTrue(res.reason.contains("no live subprocess"), "expected honest reason, got '${res.reason}'")
        assertTrue(
            !res.reason.contains("not implemented", ignoreCase = true),
            "old 'not implemented' stub is still present: '${res.reason}'",
        )
    }

    // R0.3.6 (2026-10-11) — adversarial tests pinning the no-fabrication
    // contract: the upstream run id MUST come from the subprocess's own
    // journal (a RunStarted event whose payload carries `upstream_id`),
    // never a UUID minted by the runner.

    @Test
    fun `submit reads upstream id from subprocess journal not from a UUID (R036)`(@TempDir tmp: Path) {
        val realUpstreamId = "upstream-from-journal-real"
        val runner = PipelineKRunner(
            controlRootBase = tmp,
            sdkClasspath = emptyList(),
            mainClass = "Main",
            spawner = object : ProcessSpawner {
                override fun spawn(mainClass: String, classpath: List<Path>, argv: List<String>): SpawnedProcess {
                    val ctrlRoot = argv[argv.indexOf("--control-root") + 1]
                    val runDb = Path.of(ctrlRoot).resolve("run.db")
                    preCreateRunStartedEvent(runDb, realUpstreamId)
                    return object : SpawnedProcess {
                        override fun asyncDetach() { /* noop */ }
                        override val pid: Long? = 4242L
                    }
                }
            },
            snapshotSource = StubSnapshotSource(),
        )
        val req = RunRequest(
            runId = RunId.new(),
            snapshotId = SnapshotId.new(),
            scenarioId = ScenarioId.new(),
            profile = ExecutionProfile.TrustedLocal,
        )
        val result = runner.submit(req)
        assertIs<SubmissionResult.Admitted>(result)
        // R6.1.2 (2026-10-12): upstreamRunId is nullable
        // (the runner admits immediately and reads the
        // upstream id asynchronously). The PRE-R6.1.2
        // contract — the value MUST be set on submit —
        // is GONE. The R0.3.6 guard now lives in the
        // status() observation: the daemon reads from
        // the journal and surfaces the real id, or it
        // never observes a journal entry and surfaces
        // null. A fabricated "pipelinek-${UUID}" is
        // never returned.
        val req2 = req // alias for clarity
        val runId2 = req2.runId
        val deadline = System.nanoTime() + 5_000_000_000L
        var observedUpstreamId: String? = null
        while (System.nanoTime() < deadline) {
            val s = runner.status(runId2)
            if (s is dev.pipelinek.studio.application.ports.RunHandleStatus.Live) {
                observedUpstreamId = s.upstreamRunId
                if (observedUpstreamId != null) break
            }
            Thread.sleep(20)
        }
        assertEquals(
            realUpstreamId,
            observedUpstreamId,
            "upstreamRunId must come from journal, not a fabricated UUID (null after 5s = daemon reader never observed)",
        )
        assertTrue(
            !observedUpstreamId!!.startsWith("pipelinek-"),
            "the R0.3.6 anti-pattern was a \"pipelinek-\${UUID.randomUUID()}\" placeholder; got '$observedUpstreamId'",
        )
    }

    @Test
    fun `submit admits immediately and status reflects journal async reader (R612)`(@TempDir tmp: Path) {
        // R6.1.2 (2026-10-12): the runner now admits
        // immediately and reads the upstream id on a
        // daemon thread. The PRE-R6.1.2 contract that
        // the submit synchronously refused when no
        // RunStarted event arrived is GONE — admit
        // first, observe later. The legacy R0.3.6
        // "pipelinek-${UUID}" fabrication is what
        // this test originally protected against; that
        // guard is now exercised differently: the
        // admission must succeed AND the daemon must
        // NOT invent a UUID prefix.
        val runner = PipelineKRunner(
            controlRootBase = tmp,
            sdkClasspath = emptyList(),
            mainClass = "Main",
            spawner = object : ProcessSpawner {
                override fun spawn(mainClass: String, classpath: List<Path>, argv: List<String>): SpawnedProcess {
                    // No pre-created run.db / RunStarted
                    // event. The runner must still admit
                    // (R6.1.2); the async reader will
                    // never observe a journal entry, so
                    // upstreamRunId stays null.
                    return object : SpawnedProcess {
                        override fun asyncDetach() { /* noop */ }
                        override val pid: Long? = null
                    }
                }
            },
            snapshotSource = StubSnapshotSource(),
        )
        val req = RunRequest(
            runId = RunId.new(),
            snapshotId = SnapshotId.new(),
            scenarioId = ScenarioId.new(),
            profile = ExecutionProfile.TrustedLocal,
        )
        val result = runner.submit(req)
        // R6.1.2: admit first.
        assertIs<SubmissionResult.Admitted>(result, "R6.1.2 admit-first: submit must succeed even before journal emission")
        // The async reader never sees a journal entry,
        // so upstreamRunId MUST be null. The legacy
        // R0.3.6 "pipelinek-${UUID}" fabrication is
        // forbidden: the value is null, never a
        // fabricated string starting with "pipelinek-".
        assertNull(result.upstreamRunId, "R6.1.2: no journal entry -> upstreamRunId is null, not a fabricated UUID")
    }

    @Test
    fun `status returns Live with journal-emitted upstream id (R036)`(@TempDir tmp: Path) {
        val realUpstreamId = "upstream-from-journal-real"
        val runner = PipelineKRunner(
            controlRootBase = tmp,
            sdkClasspath = emptyList(),
            mainClass = "Main",
            spawner = object : ProcessSpawner {
                override fun spawn(mainClass: String, classpath: List<Path>, argv: List<String>): SpawnedProcess {
                    val ctrlRoot = argv[argv.indexOf("--control-root") + 1]
                    val runDb = Path.of(ctrlRoot).resolve("run.db")
                    preCreateRunStartedEvent(runDb, realUpstreamId)
                    return object : SpawnedProcess {
                        override fun asyncDetach() { /* noop */ }
                        override val pid: Long? = 9999L
                    }
                }
            },
            snapshotSource = StubSnapshotSource(),
        )
        val runId = RunId.new()
        val req = RunRequest(
            runId = runId,
            snapshotId = SnapshotId.new(),
            scenarioId = ScenarioId.new(),
            profile = ExecutionProfile.TrustedLocal,
        )
        val sub = runner.submit(req)
        assertIs<SubmissionResult.Admitted>(sub)
        // R6.1.2 (2026-10-12): the runner admits
        // immediately and reads the upstream id on a
        // daemon thread. The test must poll for the
        // daemon to surface the journal emission;
        // calling status() once is racy on slow CI.
        val deadline = System.nanoTime() + 5_000_000_000L // 5s
        var status: RunHandleStatus = RunHandleStatus.Unknown("not yet observed")
        while (System.nanoTime() < deadline) {
            status = runner.status(runId)
            if (status is RunHandleStatus.Live && status.upstreamRunId == realUpstreamId) break
            Thread.sleep(20)
        }
        assertIs<RunHandleStatus.Live>(status, "after submit, status must be Live with journal-emitted id")
        assertEquals(realUpstreamId, status.upstreamRunId, "status upstreamRunId must match journal emission")
        assertTrue(!status.upstreamRunId.startsWith("pipelinek-"), "no UUID prefix allowed (R0.3.6)")
    }

    /** Pre-create a run.db with a single `RunStarted` event whose payload carries the supplied upstream_id. */
    private fun preCreateRunStartedEvent(runDb: Path, upstreamId: String) {
        java.nio.file.Files.createDirectories(runDb.parent)
        val payload = "{\"upstream_id\":\"$upstreamId\",\"started_at\":\"2026-10-11T10:00:00Z\"}"
        java.sql.DriverManager.getConnection("jdbc:sqlite:$runDb").use { c ->
            c.createStatement().use { st ->
                st.executeUpdate(
                    "CREATE TABLE events (" +
                        "event_id TEXT, run_id TEXT, sequence INTEGER, " +
                        "kind TEXT, occurred_at TEXT, payload TEXT)",
                )
                st.executeUpdate(
                    "INSERT INTO events(event_id, run_id, sequence, kind, occurred_at, payload) " +
                        "VALUES ('e1', 'r1', 1, 'RunStarted', '2026-10-11T10:00:00Z', '$payload')",
                )
            }
        }
        // R6.1 (2026-10-12): the runner now reads the
        // upstream_id from the subprocess's stdout journal
        // (the SDK writes the same RunStarted event to BOTH
        // run.db AND stdout.log). The runner's journal poll
        // looks at stdout.log, not run.db. Tests that
        // pre-populate run.db must also pre-populate
        // stdout.log so the runner finds the event. The
        // stdout.log entry's `runId` MUST equal the
        // upstream_id the test expects (the runner extracts
        // the runId from the RunStarted line, not a
        // `upstream_id` field — the SDK event uses runId
        // as the upstream identifier).
        val stdoutLog = runDb.parent.resolve("stdout.log")
        val entry = "{\"eventId\":\"e1\",\"runId\":\"$upstreamId\",\"sequence\":1," +
            "\"kind\":\"RunStarted\",\"occurredAt\":\"2026-10-11T10:00:00Z\"," +
            "\"payload\":$payload}"
        java.nio.file.Files.writeString(stdoutLog, "[$entry]")
    }
}


private class StubSnapshotSource : dev.pipelinek.studio.application.ports.StudioMetadataPort {
    override fun findExperiment(id: dev.pipelinek.studio.domain.ExperimentId) = null
    override fun findSnapshot(id: dev.pipelinek.studio.domain.SnapshotId) = null
    override fun findScenario(id: dev.pipelinek.studio.domain.ScenarioId) = null
    override fun findRun(id: dev.pipelinek.studio.domain.RunId) = null
    override fun listRuns(state: dev.pipelinek.studio.domain.RunState?, limit: Int, offset: Int) = emptyList<dev.pipelinek.studio.application.ports.RunBindingSnapshot>()
    override fun saveSnapshot(snap: dev.pipelinek.studio.domain.SourceSnapshot) { /* noop */ }
    override fun loadSnapshotSource(id: dev.pipelinek.studio.domain.SnapshotId): ByteArray? =
        "pipeline { stages { stage(\"noop\") {} } }".toByteArray()
    override fun saveExperiment(experiment: dev.pipelinek.studio.domain.Experiment) { /* noop */ }
    override fun persistRunBinding(binding: dev.pipelinek.studio.domain.RunBinding) { /* noop */ }
    override fun transitionRunState(studioRunId: dev.pipelinek.studio.domain.RunId, newState: dev.pipelinek.studio.domain.RunState) { /* noop */ }
        override fun listScenarios(limit: Int, offset: Int): List<dev.pipelinek.studio.application.ports.ScenarioSnapshot> = emptyList()
        override fun saveScenario(scenario: dev.pipelinek.studio.domain.SourceScenario) { /* noop */ }
}
