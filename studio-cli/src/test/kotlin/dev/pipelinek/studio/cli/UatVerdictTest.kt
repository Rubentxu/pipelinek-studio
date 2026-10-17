package dev.pipelinek.studio.cli

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import java.io.IOException
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * T2 / S3+S4 — the UAT verdict contract, exercised as a black box.
 *
 * ## Why this file exists
 *
 * The prior audit found the shipped harness turning non-execution into
 * certification: `scripts/uat-from-distribution.sh` printed
 * `ALL UAT STEPS PASSED` and exited 0 on `409 RUNTIME_UNAVAILABLE` (no run, no
 * PipelineK process), and `scripts/e2e-host.sh` reported `ALL CHECKS PASSED`
 * with an empty run id and an unverified output marker. A gate that cannot fail
 * is not a gate. These cases pin the discriminating verdict behavior.
 *
 * ## Design: real curl, one tiny loopback fixture
 *
 * Each case runs the shipped bash script as a child process against a real
 * `curl` talking to an in-JVM `com.sun.net.httpserver.HttpServer` on
 * 127.0.0.1:0 (JDK only, no new dependency). The server replays the documented
 * Studio wire shapes; nothing emulates the curl protocol itself, so the
 * script's real request/response/timeout path is exercised.
 *
 * THIS IS NOT EVIDENCE OF A REAL PipelineK EXECUTION. The fixture answers HTTP
 * and nothing else: a green case proves the *verdict logic* (exit code +
 * markers + public payload), never that a pipeline ran. Real-engine acceptance
 * stays in the operator UAT path documented in docs/05-testing/UAT_CATALOG.md.
 *
 * ## Coverage map
 *
 *  - false-PASS rules (parameterized): runtime refusal, absent run id, obsolete
 *    id key, non-success terminal state, timeout, stalled HTTP, missing output
 *    marker, output refusal, undocumented status
 *  - public-API currency: real `scenario_id`, `experiment_id` on submit,
 *    `run_id`, `Idempotency-Key`
 *  - diag-vs-acceptance split: documented diagnostic refusals stay diagnostics
 *  - transport safety: explicit target, plain HTTP needs its documented opt-in
 *  - aggregator: one selection point (no nested Podman re-run), no blind fixed
 *    wait, FAILED/BLOCKED propagation, unknown args, empty selection
 *  - step 1 classification (T13): only the runnable module set is executed, the
 *    contract-blocked module is a named sub-result (never a silent green), and an
 *    unclassified module trips the drift guard before Gradle runs
 *  - e2e-host: thin delegation, `doctor` contract, exit propagation
 *
 * All scratch lives under `<repo>/build/operational-uat/` and is deleted after
 * each case.
 */
class UatVerdictTest {

    private lateinit var scratch: Path
    private lateinit var studio: StudioFixture
    private val inventoryRoots = CopyOnWriteArrayList<Path>()

    // ------------------------------------------------------------ fixture

    @BeforeEach
    fun setUp() {
        scratch = Files.createDirectories(repoRoot().resolve("build/operational-uat/${System.nanoTime()}"))
        Files.createDirectories(scratch.resolve("uat-tmp"))
    }

    @AfterEach
    fun tearDown() {
        if (::studio.isInitialized) studio.close()
        deleteRecursively(scratch)
        inventoryRoots.forEach { deleteRecursively(it) }
    }

    private fun repoRoot(): Path {
        var dir: Path? = Path.of(".").toAbsolutePath().normalize()
        while (dir != null && !Files.isRegularFile(dir.resolve("gradle.properties"))) dir = dir.parent
        return requireNotNull(dir) { "could not locate the repo root (gradle.properties) from ${Path.of(".").toAbsolutePath()}" }
    }

    private fun script(name: String): Path {
        val p = repoRoot().resolve("scripts").resolve(name)
        require(Files.isRegularFile(p)) { "missing script under test: $p" }
        return p
    }

    private fun deleteRecursively(dir: Path) {
        if (!Files.exists(dir)) return
        Files.walk(dir).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) } }
    }

    // --------------------------------------------------------- HTTP fixture

    /** One observed request. Header names are lower-cased. */
    private data class Recorded(
        val method: String,
        val path: String,
        val headers: Map<String, String>,
        val body: String,
    )

    /**
     * Replays the documented Studio wire shapes. `mode` selects the verdict
     * scenario under test; every other route answers as a healthy Studio.
     */
    private class StudioFixture(val mode: String, val version: String) : AutoCloseable {
        val requests = CopyOnWriteArrayList<Recorded>()
        private val closed = AtomicBoolean(false)
        private val executor: ExecutorService = Executors.newFixedThreadPool(4)
        private val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        @Volatile private var lastSource: String = ""

        val baseUrl: String get() = "http://127.0.0.1:${server.address.port}"

        init {
            server.executor = executor
            server.createContext("/") { exchange -> handle(exchange) }
            server.start()
        }

        override fun close() {
            closed.set(true)
            server.stop(0)
            executor.shutdownNow()
        }

        private fun handle(exchange: HttpExchange) {
            val body = exchange.requestBody.readBytes().toString(Charsets.UTF_8)
            val record = Recorded(
                method = exchange.requestMethod,
                path = exchange.requestURI.path,
                headers = exchange.requestHeaders.entries.associate { it.key.lowercase() to (it.value.firstOrNull() ?: "") },
                body = body,
            )
            requests.add(record)

            // `stall` delays the FIRST request past any sane client deadline so
            // a test can prove the script's HTTP helper is bounded.
            if (mode == "stall") {
                stall(exchange)
                return
            }
            val (status, payload) = route(record)
            respond(exchange, status, payload)
        }

        private fun stall(exchange: HttpExchange) {
            val until = System.currentTimeMillis() + 60_000
            try {
                while (!closed.get() && System.currentTimeMillis() < until) Thread.sleep(50)
                respond(exchange, 200, """{"status":"ok"}""")
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }

        private fun respond(exchange: HttpExchange, status: Int, payload: String) {
            val bytes = payload.toByteArray(Charsets.UTF_8)
            try {
                exchange.responseHeaders.add("content-type", "application/json")
                exchange.sendResponseHeaders(status, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            } catch (_: IOException) {
                // The client gave up first (bounded deadline) — that IS the
                // behavior under test, not a fixture failure.
            } finally {
                exchange.close()
            }
        }

        private fun route(req: Recorded): Pair<Int, String> {
            val path = req.path
            return when {
                path == "/health" -> 200 to """{"status":"ok","service":"pipelinek-studio"}"""
                path == "/api/version" -> 200 to """{"studio":"$version","version":"$version"}"""
                path == "/openapi.json" -> 200 to OPENAPI
                path == "/scenarios" && req.method == "POST" ->
                    201 to """{"scenario_id":"$SCENARIO_ID","version":1,"environment_profile":"trusted_local"}"""
                path == "/experiments" && req.method == "POST" ->
                    201 to """{"experiment_id":"$EXPERIMENT_ID","name":"uat","created_at":"2026-01-01T00:00:00Z"}"""
                path.endsWith("/snapshots") && req.method == "POST" -> {
                    lastSource = sealedSource(req.body)
                    201 to """{"snapshot_id":"$SNAPSHOT_ID","experiment_id":"$EXPERIMENT_ID","sha256":"${"a".repeat(64)}","created_at":"2026-01-01T00:00:00Z"}"""
                }
                path == "/runs" && req.method == "POST" -> when (mode) {
                    "refused" -> 409 to """{"code":"RUNTIME_UNAVAILABLE","message":"runtime UNAVAILABLE"}"""
                    "no-run-id" -> 201 to """{"state":"QUEUED"}"""
                    "studio-run-id-key" -> 201 to """{"studio_run_id":"$RUN_ID","state":"QUEUED"}"""
                    else -> 201 to """{"run_id":"$RUN_ID","state":"QUEUED","claim_status":"EXECUTED"}"""
                }
                path.matches(Regex("^/runs/[^/]+\$")) -> {
                    val state = if (mode == "run-failed") "FAILED" else if (mode == "run-timeout") "RUNNING" else "SUCCEEDED"
                    200 to """{"run_id":"$RUN_ID","state":"$state"}"""
                }
                path.endsWith("/output") -> when (mode) {
                    "output-refused" -> 202 to """{"code":"OUTPUT_REFUSED","message":"stream not sealed"}"""
                    "marker-missing" -> 200 to output("no marker in this stream")
                    else -> 200 to output(marker())
                }
                path.endsWith("/diagnostics") -> when (mode) {
                    "diag-refused" -> 503 to """{"code":"DIAGNOSTICS_UNAVAILABLE","message":"no port"}"""
                    "diag-weird" -> 500 to """{"code":"INTERNAL","message":"boom"}"""
                    else -> 200 to """{"diagnostics":[]}"""
                }
                path.endsWith("/ir") -> 503 to """{"code":"IR_UNAVAILABLE","message":"no port"}"""
                path.endsWith("/events") -> 200 to """{"items":[]}"""
                path == "/steps" -> 200 to """{"steps":[]}"""
                else -> 404 to """{"code":"NOT_FOUND","message":"$path"}"""
            }
        }

        /** The `source` string the script sealed (the body is escaped JSON). */
        private fun sealedSource(body: String): String = runCatching {
            kotlinx.serialization.json.Json.parseToJsonElement(body)
                .let { it as kotlinx.serialization.json.JsonObject }["source"]
                ?.let { (it as kotlinx.serialization.json.JsonPrimitive).content }
                .orEmpty()
        }.getOrDefault("")

        /** The marker the script sealed into the snapshot source, if any. */
        fun marker(): String =
            Regex("UAT_MARKER-[0-9a-zA-Z]+").find(lastSource)?.value ?: "UAT_MARKER-absent"

        private fun output(text: String): String {
            val b64 = java.util.Base64.getEncoder().encodeToString((text + "\n").toByteArray(Charsets.UTF_8))
            return """{"runId":"$RUN_ID","chunks":[{"streamId":"stdout","bytesBase64":"$b64","cursor":"1","eof":true}],"nextCursor":"1"}"""
        }

        companion object {
            const val SCENARIO_ID = "11111111-1111-1111-1111-111111111111"
            const val EXPERIMENT_ID = "22222222-2222-2222-2222-222222222222"
            const val SNAPSHOT_ID = "33333333-3333-3333-3333-333333333333"
            const val RUN_ID = "44444444-4444-4444-4444-444444444444"

            /** Hand-maintained, indented doc — the shape `OpenApiDoc.current` serves. */
            private val OPENAPI = """
                {
                  "openapi": "3.1.0",
                  "paths": {
                    "/health": {}, "/api/version": {}, "/experiments": {},
                    "/runs": {}, "/steps": {}
                  }
                }
            """.trimIndent()
        }
    }

    // ------------------------------------------------------- process runner

    /** Result of one public-script invocation. */
    private data class Run(val exit: Int, val out: String, val err: String, val elapsedMs: Long) {
        fun all(): String = out + err
    }

    /**
     * Runs a shipped script with stdout/stderr redirected to owned files, then
     * waits with a deadline. On timeout only OUR process is destroyed, then
     * the test fails with the captured output: a hung harness must never hang
     * the suite.
     */
    private fun run(
        scriptName: String,
        args: List<String> = emptyList(),
        env: Map<String, String> = emptyMap(),
        timeoutSeconds: Long = 120,
    ): Run = runPath(script(scriptName), args, env, timeoutSeconds)

    private fun runPath(
        target: Path,
        args: List<String> = emptyList(),
        env: Map<String, String> = emptyMap(),
        timeoutSeconds: Long = 120,
    ): Run {
        val stamp = System.nanoTime()
        val outFile = scratch.resolve("stdout-$stamp.log")
        val errFile = scratch.resolve("stderr-$stamp.log")
        val pb = ProcessBuilder(listOf("/bin/bash", target.toString()) + args)
            .redirectOutput(outFile.toFile())
            .redirectError(errFile.toFile())
        val e = pb.environment()
        // Both the script's own scratch (mktemp under TMPDIR) and any curl/jdk
        // temp stay inside this test's owned directory tree.
        e["TMPDIR"] = scratch.resolve("uat-tmp").toString()
        e.remove("STUDIO_HTTP")
        e.remove("STUDIO_HTTP_INSECURE")
        e.remove("STUDIO_CA_CERT")
        e.remove("STUDIO_CLI_BIN")
        e.remove("UAT_TIMEOUT")
        e.remove("UAT_POLL_INTERVAL")
        e.putAll(env)

        val started = System.nanoTime()
        val proc = pb.start()
        proc.outputStream.close()
        val finished = proc.waitFor(timeoutSeconds, TimeUnit.SECONDS)
        val elapsed = (System.nanoTime() - started) / 1_000_000
        if (!finished) {
            proc.destroyForcibly()
            proc.waitFor(10, TimeUnit.SECONDS)
        }
        val out = outFile.toFile().readText()
        val err = errFile.toFile().readText()
        assertTrue(
            finished,
            "script $target exceeded ${timeoutSeconds}s and was killed (elapsed=${elapsed}ms)\nstdout:\n$out\nstderr:\n$err",
        )
        return Run(proc.exitValue(), out, err, elapsed)
    }

    private fun baseEnv(): Map<String, String> = mapOf(
        "STUDIO_HTTP" to studio.baseUrl,
        "STUDIO_HTTP_INSECURE" to "1",
    )

    private fun assertNoSuccessBanner(r: Run) {
        assertFalse(
            r.all().contains("ALL UAT STEPS PASSED"),
            "non-execution must never print the full-UAT success banner:\n${r.all()}",
        )
    }

    /** A verdict run must not leave scratch state in the caller's TMPDIR. */
    private fun assertScratchClean() {
        val leftovers = Files.list(scratch.resolve("uat-tmp")).use { s -> s.map { it.fileName.toString() }.toList() }
        assertTrue(
            leftovers.none { it.startsWith("uat-") || it.startsWith("studio-") },
            "run left scratch state behind in TMPDIR: $leftovers",
        )
    }

    private fun requestPaths(): List<String> = studio.requests.map { it.path }

    // ------------------------------------------------ canonical UAT verdict

    /**
     * Every entry is a false-PASS channel named by the audit: the run was
     * never executed, or was executed without the expected result, and the old
     * script certified it anyway.
     */
    private data class VerdictCase(
        val mode: String,
        val exit: Int,
        val mustSay: List<String>,
        val env: Map<String, String> = emptyMap(),
        val mustNotRequest: List<String> = emptyList(),
    ) {
        override fun toString(): String = "$mode -> exit $exit"
    }

    companion object {
        /** T9 (S4): every retired static checkpoint and the check that now executes it. */
        @JvmStatic
        fun retiredInventoryCheckpoints(): List<Arguments> = listOf(
            // Keyboard-shortcut greps -> the hook's real test, run by
            // ci.yml apps-checks (`apps/web test` = `vitest run`).
            Arguments.of(
                "4n",
                "useKeyboardShortcuts.test.ts",
                "apps/web/src/useKeyboardShortcuts.test.ts",
            ),
            // "the SPA test harness exists" greps -> that same executed npm step:
            // a missing harness/config/dep/test file makes `vitest run` exit non-zero.
            Arguments.of(
                "4u",
                "apps/web test",
                "apps/web/vitest.config.ts",
            ),
            // Secret-redactor port/pattern greps -> the redactor's own tests, run by
            // `./gradlew test` (step 1) and ci.yml unit-tests.
            Arguments.of(
                "4w",
                "SecretRedactorTest",
                "studio-application/src/test/kotlin/dev/pipelinek/studio/application/ports/SecretRedactorTest.kt",
            ),
        )

        /** T13 (S1+S3+S4): the step-1 classification cases. */
        @JvmStatic
        fun step1ClassificationCases(): List<Arguments> = listOf(
            // A red runnable module is a red verdict, and the harness fails fast
            // instead of attempting the blocked module.
            Step1Case(
                "a runnable module failure stays red",
                gradle = listOf(GradleAnswer(":studio-domain:test", 1, "1 test completed, 1 failed")),
                step1Green = false,
                blockedLines = 0,
                fabricAttempted = false,
                mustSay = listOf("tests failed for the runnable Studio modules"),
            ),
            // The defect: unpublished contracts used to abort checkpoint 1 and
            // therefore the whole certification. Now they are a named sub-result
            // and the run continues to checkpoint 2.
            Step1Case(
                "an unpublished contract block is visible and does not stop the run",
                gradle = listOf(
                    GradleAnswer(
                        ":studio-infra-fabric:test",
                        1,
                        "Could not find io.jenkins.pipelinek.fabric:model:0.5.0-R1-alpha.8.",
                    ),
                ),
                step1Green = true,
                blockedLines = 2,
                fabricAttempted = true,
                mustSay = listOf(
                    "io.jenkins.pipelinek.fabric:model:0.5.0-R1-alpha.8",
                    "=== 2. ./gradlew :studio-cli:distTar ===",
                ),
            ),
            // A module whose contracts DO resolve and whose tests fail is a real
            // failure: it must not be laundered into a "blocked" annotation.
            Step1Case(
                "a resolvable module that fails is a failure, not a block",
                gradle = listOf(GradleAnswer(":studio-infra-fabric:test", 1, "3 tests completed, 1 failed")),
                step1Green = true,
                blockedLines = 0,
                fabricAttempted = true,
                mustSay = listOf("real test failure, not a contract block"),
            ),
            // Drift guard: a new Studio module in settings.gradle.kts that nobody
            // classified trips before any Gradle work starts.
            Step1Case(
                "an unclassified Studio module trips the drift guard",
                gradle = emptyList(),
                extraModules = listOf("studio-brand-new"),
                step1Green = false,
                blockedLines = 0,
                fabricAttempted = false,
                mustSay = listOf("unclassified Studio module", "studio-brand-new"),
            ),
        ).map { c ->
            Arguments.of(c.label, c.gradle, c.extraModules, c.step1Green, c.blockedLines, c.fabricAttempted, c.mustSay)
        }

        /** T13 (S3): the two directions of the green-verdict/blocked-line coupling. */
        @JvmStatic
        fun verdictCouplingCases(): List<Arguments> = listOf(
            // A blocked module that nobody announced may not produce a green verdict.
            Arguments.of("blocked but never announced", false, false),
            // Announced, it is part of the execution summary text next to the verdict.
            Arguments.of("blocked and announced", true, true),
        )

        @JvmStatic
        fun falsePassCases(): List<Arguments> = listOf(
            // 409 RUNTIME_UNAVAILABLE: no run, no PipelineK process, and the
            // output plane must never be consulted.
            VerdictCase(
                "refused", 3,
                listOf("UAT_VERDICT: BLOCKED", "RUNTIME_UNAVAILABLE"),
                mustNotRequest = listOf("/output"),
            ),
            // Submit answered but minted no run id.
            VerdictCase("no-run-id", 1, listOf("UAT_VERDICT: FAILED", "run_id")),
            // Obsolete key: studio_run_id is not the public run id.
            VerdictCase("studio-run-id-key", 1, listOf("UAT_VERDICT: FAILED", "run_id")),
            // Terminal, but not a success.
            VerdictCase("run-failed", 1, listOf("UAT_VERDICT: FAILED", "FAILED")),
            // Never reaches a terminal state.
            VerdictCase("run-timeout", 3, listOf("UAT_VERDICT: BLOCKED", "terminal"), mapOf("UAT_TIMEOUT" to "2")),
            // Server accepts the connection but never answers: the HTTP helper
            // must be bounded by UAT_TIMEOUT instead of hanging forever.
            VerdictCase("stall", 3, listOf("UAT_VERDICT: BLOCKED"), mapOf("UAT_TIMEOUT" to "2")),
            // Run succeeded but the expected result is absent.
            VerdictCase("marker-missing", 1, listOf("UAT_VERDICT: FAILED", "marker"), mapOf("UAT_TIMEOUT" to "2")),
            // Output plane refused: still no expected result.
            VerdictCase("output-refused", 1, listOf("UAT_VERDICT: FAILED", "OUTPUT_REFUSED")),
            // An undocumented response is a contract deviation, not a diagnostic.
            VerdictCase("diag-weird", 1, listOf("UAT_VERDICT: FAILED", "diagnostics")),
        ).map { c -> Arguments.of(c.mode, c.exit, c.mustSay, c.env, c.mustNotRequest) }
    }

    @ParameterizedTest(name = "uat-from-distribution [{0}] cannot report full-UAT PASS")
    @MethodSource("falsePassCases")
    fun `false PASS channels are non-zero and never certify`(
        mode: String,
        expectedExit: Int,
        mustSay: List<String>,
        caseEnv: Map<String, String>,
        mustNotRequest: List<String>,
    ) {
        studio = StudioFixture(mode, "1.0.0")
        val r = run("uat-from-distribution.sh", env = baseEnv() + caseEnv)

        assertEquals(expectedExit, r.exit, "wrong exit for mode=$mode\nstdout:\n${r.out}\nstderr:\n${r.err}")
        mustSay.forEach { needle -> assertContains(r.all(), needle, message = "mode=$mode must report '$needle'") }
        assertNoSuccessBanner(r)
        mustNotRequest.forEach { forbidden ->
            assertTrue(
                requestPaths().none { it.endsWith(forbidden) },
                "mode=$mode must not reach $forbidden; saw ${requestPaths()}",
            )
        }
        assertScratchClean()
    }

    @Test
    fun `a real terminal success with the expected marker is the only PASS`() {
        studio = StudioFixture("happy", "1.0.0")
        val r = run("uat-from-distribution.sh", env = baseEnv())

        assertEquals(0, r.exit, "a genuine success must exit 0\nstdout:\n${r.out}\nstderr:\n${r.err}")
        assertContains(r.out, "ALL UAT STEPS PASSED")
        assertContains(r.out, "UAT_VERDICT: PASSED")
        // The evidence the operator needs to trust the verdict.
        assertContains(r.out, "state=SUCCEEDED")
        assertContains(r.out, studio.marker())

        // S3: current public API shapes only. Real scenario id from
        // POST /scenarios, experiment_id on submit, documented idempotency key.
        val submit = studio.requests.single { it.path == "/runs" }
        assertEquals("POST", submit.method)
        assertContains(submit.body, "\"scenario_id\":\"${StudioFixture.SCENARIO_ID}\"")
        assertContains(submit.body, "\"experiment_id\":\"${StudioFixture.EXPERIMENT_ID}\"")
        assertFalse(
            submit.body.contains("\"scenario_id\":\"${StudioFixture.EXPERIMENT_ID}\""),
            "the experiment id must not be reused as the scenario id",
        )
        assertTrue(submit.headers.containsKey("idempotency-key"), "submit must carry Idempotency-Key: ${submit.headers}")
        assertTrue(requestPaths().contains("/scenarios"), "the scenario must be registered before the submit")
        assertScratchClean()
    }

    @Test
    fun `documented diagnostic refusals stay diagnostics and do not gate acceptance`() {
        // S3: a wired-but-refusing diagnostics/IR port is NOT an acceptance
        // failure. It is reported as NOT MEASURED.
        studio = StudioFixture("diag-refused", "1.0.0")
        val r = run("uat-from-distribution.sh", env = baseEnv())

        assertEquals(0, r.exit, "a documented diagnostic refusal must not fail acceptance\n${r.out}\n${r.err}")
        assertContains(r.out, "UAT_VERDICT: PASSED")
        assertContains(r.out, "DIAGNOSTICS_UNAVAILABLE")
        assertContains(r.out, "IR_UNAVAILABLE")
        assertContains(r.out, "NOT MEASURED")
    }

    @Test
    fun `the API version is cross-checked against the installed binary when given`() {
        // S3: compare against the installed distribution, never a hardcoded
        // release literal (`1.0.0-rc.11` was baked into the old harness).
        studio = StudioFixture("happy", "2.0.0")
        val bin = writeStudioCli("1.0.0")
        val drifted = run(
            "uat-from-distribution.sh",
            env = baseEnv() + mapOf("STUDIO_CLI_BIN" to bin.toString()),
        )
        assertEquals(1, drifted.exit, "installed/API version drift must fail\n${drifted.out}\n${drifted.err}")
        assertContains(drifted.out, "UAT_VERDICT: FAILED")
        assertNoSuccessBanner(drifted)

        studio.close()
        studio = StudioFixture("happy", "1.0.0")
        val passing = run(
            "uat-from-distribution.sh",
            env = baseEnv() + mapOf("STUDIO_CLI_BIN" to writeStudioCli("1.0.0").toString()),
        )
        assertEquals(0, passing.exit, "matching versions must pass\n${passing.out}\n${passing.err}")
    }

    @Test
    fun `usage gates reject before any HTTP request`() {
        // An implicit target and a silent plaintext downgrade are both refused
        // as usage errors: nothing is contacted.
        studio = StudioFixture("happy", "1.0.0")

        val noTarget = run("uat-from-distribution.sh", env = mapOf("STUDIO_HTTP_INSECURE" to "1"))
        assertEquals(2, noTarget.exit, "an implicit target is not allowed\n${noTarget.out}\n${noTarget.err}")
        assertContains(noTarget.all(), "STUDIO_HTTP")

        val plain = run(
            "uat-from-distribution.sh",
            env = mapOf("STUDIO_HTTP" to studio.baseUrl),
        )
        assertEquals(2, plain.exit, "http without opt-in is a usage error\n${plain.out}\n${plain.err}")
        assertContains(plain.all(), "STUDIO_HTTP_INSECURE")

        val badTimeout = run(
            "uat-from-distribution.sh",
            env = baseEnv() + mapOf("UAT_TIMEOUT" to "soon"),
        )
        assertEquals(2, badTimeout.exit, "a non-numeric bounded env must be refused\n${badTimeout.out}\n${badTimeout.err}")
        assertContains(badTimeout.all(), "UAT_TIMEOUT")

        assertTrue(studio.requests.isEmpty(), "no request may be issued before the transport is accepted: ${requestPaths()}")
    }

    // ------------------------------------ shared transport contract (T10)
    //
    // S1/S3/S4: Studio serves https://127.0.0.1:7778 by default and plain
    // http://127.0.0.1:7777 only under the documented `--insecure-http`
    // opt-in. Every script that polls a Studio used to default to the
    // plaintext port, so a healthy TLS server was reported as "studio
    // unreachable" and the failure looked like a product defect. One shared
    // library (`scripts/lib/studio-http.sh`) now owns the contract: the
    // transport is STATED by the operator, never assumed by the script.

    private fun shippedScripts(): List<Path> =
        Files.list(repoRoot().resolve("scripts")).use { s ->
            s.filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".sh") }
                .sorted()
                .toList()
        }

    @Test
    fun `a script that polls a Studio without a stated transport stops before any request`() {
        // verify-plugins.sh is a small, sleep-free representative of the ~30
        // operator scripts that used to default to http://127.0.0.1:7777.
        studio = StudioFixture("happy", "1.0.0")

        val noTarget = run("verify-plugins.sh")
        assertEquals(
            2,
            noTarget.exit,
            "an unstated transport is a usage error, not a polling attempt\n${noTarget.out}\n${noTarget.err}",
        )
        assertContains(noTarget.all(), "STUDIO_HTTP")
        assertTrue(
            studio.requests.isEmpty(),
            "no request may be issued without a stated transport: ${requestPaths()}",
        )

        val plain = run("verify-plugins.sh", env = mapOf("STUDIO_HTTP" to studio.baseUrl))
        assertEquals(
            2,
            plain.exit,
            "plain http:// without the documented opt-in is a usage error\n${plain.out}\n${plain.err}",
        )
        assertContains(plain.all(), "STUDIO_HTTP_INSECURE")
        assertTrue(
            studio.requests.isEmpty(),
            "the plaintext gate must fire before any request: ${requestPaths()}",
        )

        // The shared gate must not block the supported path: with the transport
        // stated the script really polls and then fails on its own contract
        // (the fixture has no plugin catalog), which is a product verdict.
        val live = run("verify-plugins.sh", env = baseEnv())
        assertTrue(studio.requests.isNotEmpty(), "a stated transport must reach the server: ${live.all()}")
        assertTrue(live.exit != 2, "a working transport must get past the usage gate: ${live.all()}")
        assertScratchClean()
    }

    @Test
    fun `no script assumes an implicit Studio transport or bypasses TLS`() {
        val scripts = shippedScripts()
        assertTrue(scripts.size > 30, "the sweep found almost no scripts: $scripts")

        // Only a scheme-carrying fallback is the defect: `${STUDIO_HTTP:-}` is
        // the presence check e2e-host.sh does before delegating.
        val implicitDefault = Regex("""\$\{STUDIO_HTTP:-[^}]""")
        val tlsBypass = Regex("""(?:^|[\s(])(?:-k|--insecure)(?:[\s)]|$)""", RegexOption.MULTILINE)
        val library = "lib/studio-http.sh"

        val offenders = mutableListOf<String>()
        var shared = 0
        scripts.forEach { file ->
            val name = file.fileName.toString()
            val text = Files.readString(file)
            // A `:-` fallback makes the script guess the scheme: that is the
            // defect this test pins. The only legal assignments are the
            // operator's environment (via the library) or a literal endpoint
            // the script itself bound (the Podman port mapping).
            implicitDefault.findAll(text).forEach { offenders += "$name: implicit transport default '${it.value}'" }
            // A plaintext target the script hardcodes must carry the same
            // opt-in the CLI and MCP adapter demand, on the same line.
            text.lines().filter { it.contains("STUDIO_HTTP=\"http://") }.forEach { line ->
                if (!line.contains("STUDIO_HTTP_INSECURE=1")) {
                    offenders += "$name: plaintext transport without the documented opt-in: ${line.trim()}"
                }
            }
            if (text.contains(library)) {
                shared++
                tlsBypass.findAll(text).forEach {
                    offenders += "$name: TLS bypass flag '${it.value.trim()}' under the shared transport contract"
                }
            }
        }
        assertEquals(
            emptyList<String>(),
            offenders,
            "every shipped script must state its transport; the shared contract forbids a trust-all bypass: $offenders",
        )
        assertTrue(
            shared >= 30,
            "the transport contract must be reused from scripts/$library, not re-implemented (only $shared script(s) source it)",
        )
    }

    @Test
    fun `the Podman UATs trust the container certificate and derive the image tag`() {
        // The `scripts/uat-podman-NNN.sh` scripts polled their own container
        // through `CURL_OPTS=(-k --http1.1)` while pinning the image to
        // `1.0.0-rc.11`. Both are defects in a *verification* script: the flag
        // disables exactly the certificate check the script exists to trust,
        // and the release literal turns the version assertion red for a
        // non-defect as soon as the project version moves. The image entrypoint
        // (`studio start --host 0.0.0.0 --data-dir /var/lib/pipelinek-studio`,
        // docker/Dockerfile) carries no `--insecure-http`, so the honest
        // transport is https and the trust anchor is the certificate the
        // container wrote for itself.
        //
        // These scripts cannot run without Podman, which is why this static
        // contract is their executable check. Sourcing the shared library is
        // what puts them under the TLS-bypass sweep above; this case pins that
        // explicitly instead of relying on it staying implicit.
        val dir = repoRoot().resolve("scripts")
        val scenarios = Files.list(dir).use { s ->
            s.filter { it.fileName.toString().matches(Regex("""uat-podman-[0-9]+\.sh""")) }
                .sorted()
                .toList()
        }
        assertEquals(9, scenarios.size, "the Podman UAT set changed; re-check each script's transport: $scenarios")

        scenarios.forEach { file ->
            val name = file.fileName.toString()
            val text = Files.readString(file)
            assertContains(text, "lib/studio-http.sh", message = "$name must reuse the shared transport contract")
            assertContains(
                text,
                "STUDIO_HTTP=\"https://127.0.0.1:7778\"",
                message = "$name starts a container that serves TLS on 7778 and nothing else",
            )
            assertContains(
                text,
                "studio_http_trust_podman_container \"${'$'}CONTAINER\"",
                message = "$name must trust the container's own certificate instead of skipping verification",
            )
            assertFalse(text.contains("1.0.0-rc"), "$name must not pin a release literal: the tag comes from gradle.properties")
            assertFalse(text.contains("CURL_OPTS"), "$name must not own a curl option array (it carried the TLS bypass)")
            assertFalse(
                text.contains("STUDIO_INSECURE"),
                message = "$name passes an env var the image never reads; the transport is stated in the script",
            )
            assertTrue(
                text.contains("studio\\.version=") && text.contains("gradle.properties"),
                message = "$name must derive the expected version from gradle.properties (studio\\.version)",
            )
            assertBashParses(name)
        }

        // The aggregator owns no transport of its own (it hands TAG to the
        // children), but it must still stop baking a release literal in.
        val aggregator = Files.readString(dir.resolve("uat-podman-all.sh"))
        assertFalse(aggregator.contains("1.0.0-rc"), "uat-podman-all.sh must not pin a release literal")
        assertContains(aggregator, "studio\\.version=", message = "uat-podman-all.sh must derive TAG from gradle.properties")
        assertContains(aggregator, "gradle.properties", message = "uat-podman-all.sh must derive TAG from gradle.properties")
        assertBashParses("uat-podman-all.sh")
    }

    /** `bash -n` on a shipped script: the only execution these edits can afford. */
    private fun assertBashParses(name: String) {
        val p = ProcessBuilder("/bin/bash", "-n", script(name).toString()).redirectErrorStream(true).start()
        val out = p.inputStream.readBytes().toString(Charsets.UTF_8)
        assertTrue(p.waitFor(60, TimeUnit.SECONDS), "bash -n $name did not finish")
        assertEquals(0, p.exitValue(), "$name is not valid bash: $out")
    }

    @Test
    fun `the harness starts every backend with the transport it polls`() {
        // Step 5 (MCP) started the backend without the documented opt-in while
        // polling http://127.0.0.1:$MCP_PORT/health: the readiness loop could
        // never succeed and the failure surfaced as "backend did not become
        // healthy" instead of a transport mismatch.
        val launches = Files.readString(script("verify-from-clean-clone.sh"))
            .lines()
            .map { it.trim() }
            .filter { it.startsWith("\"\$BIN\" start") || it.startsWith("\"\$APP_BIN\"") }
        assertEquals(3, launches.size, "the harness backend launches changed; re-check each transport: $launches")
        launches.forEach { line ->
            assertContains(
                line,
                "--insecure-http",
                message = "a backend polled over http://127.0.0.1:\$PORT must be started with --insecure-http: $line",
            )
        }
    }

    // --------------------------------------------------------- aggregator

    /**
     * Builds a throwaway `scripts/` tree whose children record themselves.
     * `uat-podman-all.sh` is a faithful stand-in: it aggregates every
     * `uat-podman-NNN.sh`, so a nested re-run is observable.
     */
    private fun aggregatorRepo(children: Map<String, Int>): Path {
        val repo = Files.createDirectories(scratch.resolve("agg-${System.nanoTime()}"))
        val dir = Files.createDirectories(repo.resolve("scripts"))
        val counter = scratch.resolve("invocations.txt")
        children.forEach { (name, exit) ->
            val f = dir.resolve(name)
            Files.writeString(
                f,
                """
                #!/usr/bin/env bash
                echo "${'$'}(basename "${'$'}0")" >> "$counter"
                exit $exit
                """.trimIndent() + "\n",
            )
            check(f.toFile().setExecutable(true)) { "chmod failed on $f" }
        }
        return repo
    }

    private fun invocations(): List<String> {
        val f = scratch.resolve("invocations.txt")
        return if (Files.isRegularFile(f)) Files.readAllLines(f) else emptyList()
    }

    private fun aggregatorEnv(repo: Path): Map<String, String> = mapOf("REPO_ROOT" to repo.toString())

    @Test
    fun `the aggregator has one selection point per UAT and no blind fixed wait`() {
        val repo = aggregatorRepo(
            mapOf(
                "uat-a.sh" to 0,
                "uat-b.sh" to 0,
                "uat-podman-001.sh" to 0,
                "uat-podman-all.sh" to 0,
            ),
        )
        // Rewrite the stand-in so it aggregates the per-scenario Podman UATs,
        // exactly like the real uat-podman-all.sh does.
        val all = repo.resolve("scripts/uat-podman-all.sh")
        Files.writeString(
            all,
            """
            #!/usr/bin/env bash
            echo "uat-podman-all.sh" >> "${scratch.resolve("invocations.txt")}"
            for f in "${repo.resolve("scripts")}"/uat-podman-[0-9]*.sh; do bash "${'$'}f"; done
            exit 0
            """.trimIndent() + "\n",
        )
        check(all.toFile().setExecutable(true))

        val r = run("run-all-uats.sh", env = aggregatorEnv(repo))
        assertEquals(0, r.exit, "all-green children must exit 0\n${r.out}\n${r.err}")
        assertContains(r.out, "ALL PASSED")

        val runs = invocations()
        assertEquals(1, runs.count { it == "uat-podman-001.sh" }, "nested Podman re-run detected: $runs")
        assertEquals(1, runs.count { it == "uat-podman-all.sh" }, "aggregator must run once: $runs")
        assertEquals(1, runs.count { it == "uat-a.sh" }, "each UAT runs once: $runs")
        // Old behavior: 30s of blind sleep per child (4 children = 120s).
        assertTrue(r.elapsedMs < 15_000, "aggregator must not blind-wait; took ${r.elapsedMs}ms")
        assertScratchClean()
    }

    @Test
    fun `a failed or blocked child is propagated and named`() {
        val failing = aggregatorRepo(mapOf("uat-a.sh" to 0, "uat-b.sh" to 1))
        val failed = run("run-all-uats.sh", env = aggregatorEnv(failing))
        assertEquals(1, failed.exit, "a failed child must fail the aggregator\n${failed.out}\n${failed.err}")
        assertContains(failed.out, "uat-b.sh")
        assertFalse(failed.out.contains("ALL PASSED"), "no success banner with a failing child:\n${failed.out}")

        val blockedRepo = aggregatorRepo(mapOf("uat-a.sh" to 0, "uat-b.sh" to 3))
        val blocked = run("run-all-uats.sh", env = aggregatorEnv(blockedRepo))
        assertEquals(3, blocked.exit, "a blocked child must surface as blocked\n${blocked.out}\n${blocked.err}")
        assertContains(blocked.out, "BLOCKED")
        assertFalse(blocked.out.contains("ALL PASSED"), "no success banner with a blocked child:\n${blocked.out}")
    }

    @Test
    fun `an invalid or empty selection is rejected, not a vacuous pass`() {
        val repo = aggregatorRepo(mapOf("uat-a.sh" to 0))
        val unknown = run("run-all-uats.sh", args = listOf("--select=uat-a"), env = aggregatorEnv(repo))
        assertEquals(2, unknown.exit, "an unsupported selector must be a usage error\n${unknown.out}\n${unknown.err}")
        assertContains(unknown.all(), "usage")
        assertTrue(invocations().isEmpty(), "no child may run for a rejected invocation: ${invocations()}")

        val empty = run("run-all-uats.sh", env = aggregatorEnv(aggregatorRepo(emptyMap())))
        assertEquals(2, empty.exit, "no UAT scripts is a blocked harness\n${empty.out}\n${empty.err}")
        assertFalse(empty.out.contains("ALL PASSED"), "an empty selection must never certify:\n${empty.out}")
    }

    // ----------------------------------------------------------- e2e-host

    /** A stand-in for the installed `studio-cli` launcher (doctor + --version). */
    private fun writeStudioCli(version: String, includeDataDirKey: Boolean = true): Path {
        val home = Files.createDirectories(scratch.resolve("studio-cli-${System.nanoTime()}"))
        val binDir = Files.createDirectories(home.resolve("bin"))
        val bin = binDir.resolve("studio-cli")
        val dataDirLine = if (includeDataDirKey) """echo "data_dir_permissions=ok"""" else "true"
        Files.writeString(
            bin,
            """
            #!/usr/bin/env bash
            case "${'$'}{1:-}" in
              doctor)
                echo "studio_cli_version=$version"
                echo "db_path_writable=true"
                $dataDirLine
                exit 0 ;;
              --version) echo "studio-cli $version"; exit 0 ;;
              *) exit 0 ;;
            esac
            """.trimIndent() + "\n",
        )
        check(bin.toFile().setExecutable(true)) { "chmod failed on $bin" }
        return bin
    }

    @Test
    fun `e2e-host delegates to the canonical UAT and propagates its verdict`() {
        studio = StudioFixture("happy", "1.0.0")
        val home = writeStudioCli("1.0.0").parent.parent
        val happy = run("e2e-host.sh", args = listOf(home.toString()), env = baseEnv())
        assertEquals(0, happy.exit, "a real success must pass\n${happy.out}\n${happy.err}")
        assertContains(happy.out, "ALL UAT STEPS PASSED")
        // The doctor contract is the reason e2e-host still exists.
        assertContains(happy.out, "doctor: db writable")
        assertContains(happy.out, "doctor: data dir perms")

        studio.close()
        studio = StudioFixture("refused", "1.0.0")
        val blocked = run("e2e-host.sh", args = listOf(home.toString()), env = baseEnv())
        assertEquals(3, blocked.exit, "blocked runtime must not become a host e2e pass\n${blocked.out}\n${blocked.err}")
        assertContains(blocked.out, "UAT_VERDICT: BLOCKED")
        assertFalse(blocked.out.contains("ALL CHECKS PASSED"), "no success banner:\n${blocked.out}")
        assertScratchClean()
    }

    @Test
    fun `e2e-host fails before touching HTTP when the binary or doctor contract is missing`() {
        studio = StudioFixture("happy", "1.0.0")

        val noBinary = run(
            "e2e-host.sh",
            args = listOf(Files.createDirectories(scratch.resolve("empty-install")).toString()),
            env = baseEnv(),
        )
        assertEquals(2, noBinary.exit, "missing distribution is a prerequisite failure\n${noBinary.out}\n${noBinary.err}")
        assertTrue(studio.requests.isEmpty(), "no HTTP without a binary: ${requestPaths()}")
        assertFalse(noBinary.out.contains("ALL CHECKS PASSED"), "no success banner:\n${noBinary.out}")

        val incompleteDoctor = writeStudioCli("1.0.0", includeDataDirKey = false).parent.parent
        val doctorMiss = run("e2e-host.sh", args = listOf(incompleteDoctor.toString()), env = baseEnv())
        assertEquals(1, doctorMiss.exit, "a doctor contract miss must fail\n${doctorMiss.out}\n${doctorMiss.err}")
        assertContains(doctorMiss.out, "doctor: data dir perms")
        assertTrue(
            requestPaths().isEmpty(),
            "doctor is checked before the runtime flow: ${requestPaths()}",
        )
    }

    @Test
    fun `the runner bounds a hung child instead of hanging the suite`() {
        // The runner itself is new code: a harness that never returns must be
        // killed at the deadline (and only that child), otherwise CI hangs
        // instead of failing.
        val hung = scratch.resolve("hang.sh")
        Files.writeString(hung, "#!/usr/bin/env bash\necho started\nsleep 60\necho never\n")
        val started = System.nanoTime()
        val failure = kotlin.test.assertFailsWith<AssertionError> {
            runPath(hung, timeoutSeconds = 1)
        }
        val elapsed = (System.nanoTime() - started) / 1_000_000
        assertContains(failure.message.orEmpty(), "exceeded 1s")
        assertContains(failure.message.orEmpty(), "started")
        assertTrue(elapsed < 30_000, "the runner must abort at its deadline, took ${elapsed}ms")
    }

    // ---------------------------------------------- release inventory contract
    //
    // T9 / S3+S4: `HARNESS_SUMMARY passed=N expected=M HARNESS_OK` certifies
    // EXECUTED checkpoints only. The static release inventory (documents that
    // must ship, operator scripts, manifest declarations) reports its own
    // INVENTORY_SUMMARY and can never inflate — or green-light — the execution
    // verdict.

    /** The exact line the release-harness workflow greps for a green verdict. */
    private val greenExecutionVerdict =
        Regex("^HARNESS_SUMMARY passed=\\d+ expected=\\d+ HARNESS_OK$", RegexOption.MULTILINE)

    private val inventoryVerdict =
        Regex("^INVENTORY_SUMMARY passed=(\\d+) expected=(\\d+) INVENTORY_(OK|FAIL)", RegexOption.MULTILINE)

    /** One stub answer for `./gradlew`: a selector substring, its exit code and its log line. */
    data class GradleAnswer(val selector: String, val exit: Int, val stdout: String = "")

    /** A throwaway checkout: the harness, the module list it classifies, and an optional stub Gradle. */
    private data class HarnessDouble(val harness: Path, val tmp: Path, val gradleCalls: Path) {
        fun calls(): List<String> = if (Files.isRegularFile(gradleCalls)) Files.readAllLines(gradleCalls) else emptyList()
    }

    /**
     * A throwaway checkout that carries nothing but the harness (plus the Studio
     * module list the step-1 classification reads). The only rewrite is the
     * harness's log destination (`/tmp/...` -> this test's owned dir), so the run
     * leaves no global state; the verdict logic is untouched. The cert-gate
     * self-test uses the same "tamper a copy" precedent to inject a failure.
     *
     * `gradle` answers `./gradlew` invocations by selector substring; when it is
     * empty no `gradlew` exists at all, so step 1 fails on the missing launcher.
     */
    private fun harnessRoot(
        extraModules: List<String> = emptyList(),
        gradle: List<GradleAnswer> = emptyList(),
    ): HarnessDouble {
        val root = Files.createDirectories(repoRoot().resolve("build/operational-harness/${System.nanoTime()}"))
        inventoryRoots.add(root)
        val scripts = Files.createDirectories(root.resolve("scripts"))
        val ownedTmp = Files.createDirectories(root.resolve("tmp"))
        val harness = scripts.resolve("verify-from-clean-clone.sh")
        val original = Files.readString(script("verify-from-clean-clone.sh"))
        Files.writeString(harness, original.replace("/tmp/", "$ownedTmp/"))
        // The drift guard reads settings.gradle.kts. Copy the real one so the
        // double can never drift from the module list the repository declares.
        val settings = Files.readString(repoRoot().resolve("settings.gradle.kts"))
        val extra = extraModules.joinToString("\n") { "include(\"$it\")" }
        Files.writeString(root.resolve("settings.gradle.kts"), settings + "\n" + extra + "\n")

        val gradleCalls = root.resolve("gradle-calls.log")
        if (gradle.isNotEmpty()) {
            val answers = Files.createDirectories(root.resolve("gradle-answers"))
            gradle.forEachIndexed { index, answer ->
                Files.writeString(answers.resolve("%02d".format(index)), "${answer.selector}|${answer.exit}|${answer.stdout}")
            }
            val stub = root.resolve("gradlew")
            Files.writeString(
                stub,
                """
                #!/usr/bin/env bash
                printf '%s\n' "${'$'}*" >> "$gradleCalls"
                for answer in "$answers"/*; do
                  [ -f "${'$'}answer" ] || continue
                  IFS='|' read -r selector exit_code message < "${'$'}answer"
                  case "${'$'}*" in
                    *"${'$'}selector"*)
                      [ -n "${'$'}message" ] && printf '%s\n' "${'$'}message"
                      exit "${'$'}exit_code" ;;
                  esac
                done
                exit 0
                """.trimIndent() + "\n",
            )
            check(stub.toFile().setExecutable(true)) { "chmod failed on $stub" }
        }
        return HarnessDouble(harness, ownedTmp, gradleCalls)
    }

    @Test
    fun `the release inventory runs on its own and never emits an execution verdict`() {
        // The bounded dry run: no Gradle, no server, no network. It must be green
        // about its own checkpoints AND silent about execution.
        val r = run("verify-from-clean-clone.sh", env = mapOf("HARNESS_INVENTORY_ONLY" to "1"))

        assertFalse(
            greenExecutionVerdict.containsMatchIn(r.all()),
            "a static inventory must never produce an execution verdict:\n${r.all()}",
        )
        assertContains(r.all(), "HARNESS_EXECUTION: NOT_RUN")

        val summary = inventoryVerdict.find(r.out)
            ?: error("the release inventory printed no summary:\n${r.out}\n${r.err}")
        val passed = summary.groupValues[1].toInt()
        val expected = summary.groupValues[2].toInt()
        assertEquals(expected, passed, "every declared inventory checkpoint must be accounted for: ${summary.value}")
        // Text and exit code must agree: a red summary can never exit 0.
        assertEquals(
            summary.groupValues[3] == "OK",
            r.exit == 0,
            "the exit code must match the inventory verdict (${summary.value}):\n${r.out}\n${r.err}",
        )
    }

    @Test
    fun `an inventory gap is reported separately and cannot certify a run`() {
        val (harness, _) = harnessRoot()
        val r = runPath(harness, env = mapOf("HARNESS_INVENTORY_ONLY" to "1"))

        assertTrue(r.exit != 0, "an empty checkout must not certify its inventory:\n${r.out}\n${r.err}")
        val gap = inventoryVerdict.find(r.out) ?: error("no inventory summary:\n${r.out}\n${r.err}")
        assertEquals("FAIL", gap.groupValues[3], "a missing document set is not INVENTORY_OK: ${gap.value}")
        assertTrue(
            gap.groupValues[1].toInt() < gap.groupValues[2].toInt(),
            "the reported inventory gap must be real: ${gap.value}",
        )
        assertFalse(
            greenExecutionVerdict.containsMatchIn(r.all()),
            "an inventory gap must never certify execution:\n${r.all()}",
        )
        assertFalse(
            r.all().contains("INVENTORY_OK"),
            "no inventory may claim OK from an empty checkout:\n${r.all()}",
        )
    }

    @Test
    fun `an execution failure still fails the run and prints no green verdict`() {
        val (harness, ownedTmp) = harnessRoot()
        val r = runPath(harness)

        // No ./gradlew in this checkout: step 1 must fail through fail().
        assertTrue(r.exit != 0, "a failing execution checkpoint must exit non-zero:\n${r.out}\n${r.err}")
        assertContains(r.all(), "HARNESS_FAIL")
        assertFalse(
            greenExecutionVerdict.containsMatchIn(r.all()),
            "a failed execution must not emit a green verdict:\n${r.all()}",
        )
        assertFalse(
            r.all().contains("INVENTORY_SUMMARY"),
            "the inventory must not claim anything when execution died before reaching it:\n${r.all()}",
        )
        assertTrue(
            Files.isRegularFile(ownedTmp.resolve("studio-test.log")),
            "step 1 must have run with its log under the owned dir: $ownedTmp",
        )
    }

    // --------------------------------- step 1 module classification (T13)
    //
    // S1+S3+S4: `./gradlew clean test` could not even build the task graph on a
    // clean machine, because `io.jenkins.pipelinek.fabric:*` is published nowhere
    // but a developer's mavenLocal(). Gradle aborted while resolving
    // `:studio-infra-fabric`, checkpoint 1 failed, and the nine remaining
    // execution checkpoints never ran: the release harness had zero signal.
    // Step 1 now executes the module set `.github/workflows/ci.yml` runs and
    // reports the contract-blocked module as an explicit sub-result.

    /** The modules ci.yml's "Run unit tests" step executes, in declaration order. */
    private val runnableModules = listOf(
        "studio-domain",
        "studio-application",
        "studio-http",
        "studio-infra-podman",
        "studio-infra-sdk",
        "studio-infra-storage",
        "studio-cli",
        "studio-app",
    )

    /** The exact blocked-line shape step 1 emits; the verdict coupling is asserted on it. */
    private val blockedLine =
        "EXECUTION_BLOCKED: studio-infra-fabric (unpublished Fabric contracts: " +
            "io.jenkins.pipelinek.fabric:model:0.5.0-R1-alpha.8)"

    private data class Step1Case(
        val label: String,
        val gradle: List<GradleAnswer>,
        val extraModules: List<String> = emptyList(),
        val step1Green: Boolean,
        val blockedLines: Int,
        val fabricAttempted: Boolean,
        val mustSay: List<String> = emptyList(),
    ) {
        override fun toString(): String = label
    }

    @ParameterizedTest(name = "step 1 classification [{0}]")
    @MethodSource("step1ClassificationCases")
    fun `step 1 runs only the modules a clean machine can run`(
        label: String,
        gradle: List<GradleAnswer>,
        extraModules: List<String>,
        step1Green: Boolean,
        blockedLines: Int,
        fabricAttempted: Boolean,
        mustSay: List<String>,
    ) {
        val double = harnessRoot(extraModules = extraModules, gradle = gradle)
        val r = runPath(double.harness)
        val calls = double.calls()
        val log = r.all()

        // (1) The runnable module set is ONE Gradle invocation carrying exactly
        // the ci.yml test tasks, and never the contract-blocked module.
        if (step1Green) {
            val executedSet = requireNotNull(calls.firstOrNull()) { "step 1 ran no Gradle invocation:\n$log" }
            runnableModules.forEach { module ->
                assertContains(
                    executedSet,
                    ":$module:test",
                    message = "step 1 must execute the runnable module $module (ci.yml unit-tests); call: $executedSet",
                )
            }
            assertContains(executedSet, "clean", message = "step 1 keeps the clean-checkout posture: $executedSet")
            assertFalse(
                executedSet.contains(":studio-infra-fabric:test"),
                "the contract-blocked module must not join the executed set (its resolution failure would take the run down): $executedSet",
            )
        }
        // (2) The blocked module is attempted SEPARATELY, like ci.yml's
        // continue-on-error step — and a red runnable set fails fast instead.
        assertEquals(
            fabricAttempted,
            calls.any { it.contains(":studio-infra-fabric:test") },
            "blocked-module attempt mismatch for '$label'; gradle calls: $calls",
        )
        if (!step1Green && extraModules.isEmpty()) {
            assertEquals(1, calls.size, "a red runnable set must stop before the blocked attempt: $calls")
        }

        // (3) A blocked module is a visible sub-result, never a silent green: it
        // is printed at the checkpoint and again in the execution summary.
        val blockedSeen = Regex("EXECUTION_BLOCKED: studio-infra-fabric \\(unpublished Fabric contracts").findAll(log).count()
        if (blockedLines == 0) {
            assertEquals(0, blockedSeen, "no blocked line may appear for '$label':\n$log")
        } else {
            assertTrue(
                blockedSeen >= blockedLines,
                "the block must be printed at the step AND in the execution summary (expected >= $blockedLines, saw $blockedSeen):\n$log",
            )
        }
        mustSay.forEach { needle -> assertContains(log, needle, message = "'$label' must report '$needle'") }

        // (4) A block never turns a red run green and never certifies execution.
        assertTrue(r.exit != 0, "this double has no distribution, so the run must stay red ('$label'):\n$log")
        assertFalse(
            greenExecutionVerdict.containsMatchIn(log),
            "no step-1 outcome may emit the green verdict from a double ('$label'):\n$log",
        )
    }

    /**
     * The on_exit handler alone, cut out at the `trap` line, so the coupling
     * between a blocked module and the green verdict can be OBSERVED without
     * running ten checkpoints that need a distribution, node and an SDK.
     */
    private fun verdictOnlyHarness(announce: Boolean): Path {
        val header = Files.readString(script("verify-from-clean-clone.sh"))
            .lines()
            .takeWhile { it != "trap on_exit EXIT" } + "trap on_exit EXIT"
        val body = buildString {
            appendLine("HARNESS_MIN_EXPECTED=1; INVENTORY_MIN_EXPECTED=1; INVENTORY_COMPLETE=1")
            appendLine("INVENTORY_EXPECTED=1; INVENTORY_PASSED=1; INVENTORY_FAILED=0")
            appendLine("EXECUTION_BLOCKED_LINES=(\"$blockedLine\")")
            if (announce) appendLine("print_execution_blocked_lines")
            appendLine("step \"x\"; ok \"y\"")
        }
        val file = scratch.resolve("verdict-${System.nanoTime()}.sh")
        Files.writeString(file, header.joinToString("\n") + "\n" + body)
        return file
    }

    @ParameterizedTest(name = "green verdict coupling [{0}]")
    @MethodSource("verdictCouplingCases")
    fun `a blocked module is coupled to the green verdict`(label: String, announce: Boolean, green: Boolean) {
        val r = runPath(verdictOnlyHarness(announce))
        val verdict = "HARNESS_SUMMARY passed=1 expected=1 HARNESS_OK"

        assertEquals(
            green,
            greenExecutionVerdict.containsMatchIn(r.out),
            "'$label': green=$green expected (announce=$announce)\nstdout:\n${r.out}\nstderr:\n${r.err}",
        )
        assertEquals(green, r.exit == 0, "'$label': the exit code must match the verdict:\n${r.all()}")
        if (green) {
            val beforeVerdict = r.out.substringBefore(verdict).trimEnd()
            assertTrue(
                beforeVerdict.endsWith("alpha.8)"),
                "'$label': the blocked line must sit immediately above the green verdict:\n${r.all()}",
            )
        } else {
            assertFalse(r.out.contains(blockedLine), "'$label': nothing announced a block:\n${r.all()}")
            assertContains(r.out, "no EXECUTION_BLOCKED line was ever printed", message = "'$label' must name the reason")
        }
    }

    @Test
    fun `a blocked module cannot hide inside a green verdict`() {
        // The green branch of on_exit is unreachable from a double (it would need
        // the real distribution, podman and an SDK), so the invariant that ties
        // `HARNESS_OK` to a visible blocked line is pinned on the source: the
        // announcement flag is written by ONE printer, and the green branch must
        // re-print the blocked lines before printing the verdict.
        val harness = Files.readString(script("verify-from-clean-clone.sh"))
        val greenBranch = harness.substringAfter("if [ \"${'$'}rc\" -eq 0 ]; then")
        assertContains(
            greenBranch,
            "EXECUTION_BLOCKED_ANNOUNCED",
            message = "a green verdict must refuse to be printed when a module is blocked and nobody was told",
        )
        val printed = greenBranch.indexOf("print_execution_blocked_lines")
        val verdict = greenBranch.indexOf("HARNESS_SUMMARY passed=%d expected=%d HARNESS_OK")
        assertTrue(
            printed >= 0 && verdict >= 0 && printed < verdict,
            "the blocked line must be re-printed before the green verdict (printed=$printed verdict=$verdict)",
        )
        assertEquals(
            1,
            Regex("EXECUTION_BLOCKED_ANNOUNCED=1").findAll(harness).count(),
            "only the blocked-line printer may announce the block, otherwise the flag proves nothing",
        )
    }

    @ParameterizedTest(name = "retired inventory checkpoint {0} is gone and keeps its executed equivalent")
    @MethodSource("retiredInventoryCheckpoints")
    fun `a retired inventory checkpoint is removed with a named, existing replacement`(
        id: String,
        equivalentNeedle: String,
        equivalentPath: String,
    ) {
        val harness = Files.readString(script("verify-from-clean-clone.sh"))
        assertFalse(
            Regex("^\\s*inventory \"$id\" ", RegexOption.MULTILINE).containsMatchIn(harness),
            "checkpoint $id must not be declared as a static inventory checkpoint any more",
        )
        assertContains(harness, "RETIRED $id", message = "the retirement of $id must be recorded next to the inventory")
        assertContains(harness, equivalentNeedle, message = "the retirement note for $id must name its replacement")
        assertTrue(
            Files.isRegularFile(repoRoot().resolve(equivalentPath)),
            "the executed replacement named for $id is missing: $equivalentPath",
        )
    }

    @Test
    fun `the modified scripts are valid bash`() {
        listOf("e2e-host.sh", "uat-from-distribution.sh", "run-all-uats.sh", "verify-from-clean-clone.sh").forEach { name ->
            val pb = ProcessBuilder("/bin/bash", "-n", script(name).toString()).redirectErrorStream(true)
            val p = pb.start()
            val out = p.inputStream.readBytes().toString(Charsets.UTF_8)
            assertTrue(p.waitFor(60, TimeUnit.SECONDS), "bash -n $name did not finish")
            assertEquals(0, p.exitValue(), "$name is not valid bash: $out")
        }
    }
}
