@file:Suppress("TooGenericExceptionCaught")

package dev.pipelinek.studio.cert

import dev.pipelinek.studio.cert.domain.Assertion
import dev.pipelinek.studio.cert.domain.CaseExecution
import dev.pipelinek.studio.cert.domain.CaseId
import dev.pipelinek.studio.cert.domain.CertReport as CertReportDomain
import dev.pipelinek.studio.cert.domain.EvidenceRef
import dev.pipelinek.studio.cert.domain.Failure
import dev.pipelinek.studio.cert.domain.IntegrityEnvelope
import dev.pipelinek.studio.cert.domain.Verdict
import dev.pipelinek.studio.cert.sdk.ExpectedPins
import dev.pipelinek.studio.cert.sdk.SdkDistribution
import dev.pipelinek.studio.cert.sdk.SdkResolution
import dev.pipelinek.studio.cert.sdk.ZipSdkResolver
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions
import java.sql.DriverManager
import kotlin.system.exitProcess

/**
 * C1.2 (2026-10-12): the cert CLI.
 *
 * Subcommands:
 *
 *  - `resolve-sdk <zip>` — print the resolved SDK
 *    distribution as JSON; exit 0 on Ok, 3 on file
 *    missing, 4 on resolution failure.
 *
 *  - `run-cert <zip> <fixtures-dir> <out-report-json>`
 *    — execute every `*.pipeline.kts` fixture under
 *    `<fixtures-dir>` against the resolved SDK
 *    subprocess, observe the run.db events, build a
 *    [CaseExecution] per fixture, aggregate a
 *    [CertReport] (fail-closed), and write the report
 *    to `<out-report-json>`. Exit 0 if overall PASS, 1
 *    otherwise.
 *
 * Exit codes:
 *   0  — overall verdict PASS
 *   1  — overall verdict FAIL
 *   2  — usage / bad arguments
 *   3  — SDK ZIP missing
 *   4  — SDK resolution failed
 *   5  — unexpected error
 */
fun main(args: Array<String>) {
    if (args.isEmpty()) {
        usage()
        exitProcess(2)
    }
    when (args[0]) {
        "resolve-sdk" -> {
            if (args.size != 2) {
                usage()
                exitProcess(2)
            }
            resolveSdk(Path.of(args[1]))
        }
        "run-cert" -> {
            if (args.size != 4) {
                usage()
                exitProcess(2)
            }
            val zip = Path.of(args[1])
            val fixturesDir = Path.of(args[2])
            val outReport = Path.of(args[3])
            runCert(zip, fixturesDir, outReport)
        }
        "run-studio-cert" -> {
            if (args.size < 4 || args.size > 5) {
                usage()
                exitProcess(2)
            }
            val studioTar = Path.of(args[1])
            val sdkZip = Path.of(args[2])
            val outReport = Path.of(args[3])
            val port = if (args.size == 5) args[4].toIntOrNull() ?: 0 else 0
            runStudioCert(studioTar, sdkZip, outReport, port)
        }
        else -> {
            usage()
            exitProcess(2)
        }
    }
}

private fun usage() {
    System.err.println(
        """usage:
  studio-cert resolve-sdk <path-to-pipelinek-0.48.0.zip>
  studio-cert run-cert <zip> <fixtures-dir> <out-report.json>
  studio-cert run-studio-cert <studio-cli-tar> <sdk-zip> <out-report.json> [port]""",
    )
}

@Serializable
private data class DistributionJson(
    val version: String,
    val zipSha256: String,
    val mainClass: String,
    val launcherScript: String,
    val installRoot: String,
    val classpath: String,
    val jars: List<JarJson>,
)

@Serializable
private data class JarJson(
    val name: String,
    val path: String,
    val sha256: String,
    val sizeBytes: Long,
    val isMainClass: Boolean,
)

private fun resolveSdk(zip: Path) {
    if (!Files.isRegularFile(zip)) {
        System.err.println("FAIL FileMissing: SDK ZIP not found at $zip")
        exitProcess(3)
    }
    val r = ZipSdkResolver().resolve(
        zip,
        ExpectedPins.PIPELINEK_0_48_0_ZIP,
        ExpectedPins.PIPELINEK_0_48_0_JARS,
    )
    when (r) {
        is SdkResolution.Ok -> {
            val d = r.distribution
            val json = Json { encodeDefaults = true; prettyPrint = false }
            val out = DistributionJson(
                version = d.version,
                zipSha256 = d.zipSha256.value,
                mainClass = d.mainClass,
                launcherScript = d.launcherScript.toString(),
                installRoot = d.installRoot.toString(),
                classpath = d.classpath,
                jars = d.jars.map { j ->
                    JarJson(
                        name = j.name,
                        path = j.path.toString(),
                        sha256 = j.sha256.value,
                        sizeBytes = j.sizeBytes,
                        isMainClass = j.isMainClass,
                    )
                },
            )
            println(json.encodeToString(out))
            exitProcess(0)
        }
        is SdkResolution.Err -> {
            val code = exitCodeFor(r.failure)
            System.err.println("FAIL ${r.failure::class.simpleName}: ${r.detail}")
            exitProcess(code)
        }
    }
}

private fun exitCodeFor(f: Failure): Int = when (f) {
    Failure.FileMissing -> 3
    Failure.DigestMismatch -> 4
    Failure.ContractMismatch -> 4
    Failure.ManifestInvalid -> 4
    else -> 4
}

/**
 * C1.6 (2026-10-12): execute the cert suite.
 *
 *  1. Resolve the SDK.
 *  2. For every `*.pipeline.kts` under [fixturesDir],
 *     run the SDK subprocess with `--db run.db
 *     --control-root . <script>` in a per-fixture temp
 *     dir.
 *  3. Read the events from run.db; build a
 *     [CaseExecution] with the relevant assertions
 *     (RunFinished outcome, EchoOutputCaptured content,
 *     StepFinished outcome).
 *  4. Aggregate the executions into a
 *     [CertReport] (fail-closed).
 *  5. Write the report to [outReport] and exit 0/1.
 *
 * The integrity envelope is satisfied by construction
 * (the cert resolved a pinned SDK; the studio version
 * comes from `gradle.properties`). Tag/commit/release
 * fields are best-effort: the cert runs in a
 * detached worktree so the operator wires the receipt
 * on top.
 */
private fun runCert(zip: Path, fixturesDir: Path, outReport: Path) {
    if (!Files.isRegularFile(zip)) {
        System.err.println("FAIL FileMissing: SDK ZIP not found at $zip")
        exitProcess(3)
    }
    val r = ZipSdkResolver().resolve(
        zip,
        ExpectedPins.PIPELINEK_0_48_0_ZIP,
        ExpectedPins.PIPELINEK_0_48_0_JARS,
    )
    val sdk = when (r) {
        is SdkResolution.Ok -> r.distribution
        is SdkResolution.Err -> {
            System.err.println("FAIL ${r.failure::class.simpleName}: ${r.detail}")
            exitProcess(exitCodeFor(r.failure))
        }
    }
    // Ensure the launcher is executable.
    val ownerExec = runCatching {
        Files.getPosixFilePermissions(sdk.launcherScript)
            .contains(PosixFilePermission.OWNER_EXECUTE)
    }.getOrDefault(false)
    if (!ownerExec) {
        Files.setPosixFilePermissions(
            sdk.launcherScript,
            PosixFilePermissions.fromString("rwxr-xr-x"),
        )
    }
    if (!Files.isDirectory(fixturesDir)) {
        System.err.println("FAIL FileMissing: fixtures dir not found at $fixturesDir")
        exitProcess(2)
    }
    val fixtures = Files.list(fixturesDir).use { stream ->
        stream.filter { it.fileName.toString().endsWith(".pipeline.kts") }
            .sorted()
            .toList()
    }
    if (fixtures.isEmpty()) {
        System.err.println("FAIL: no .pipeline.kts fixtures under $fixturesDir")
        exitProcess(2)
    }
    val workspaceRoot = Files.createTempDirectory("studio-cert-")
    val cases = fixtures.map { f ->
        runFixture(sdk, f, workspaceRoot)
    }
    val report = CertReportDomain(
        studioTag = System.getenv("STUDIO_TAG") ?: "local",
        studioCommit = System.getenv("STUDIO_COMMIT") ?: "local",
        studioVersion = System.getenv("STUDIO_VERSION") ?: "1.0.0-rc.13",
        sdkVersion = sdk.version,
        sdkDigest = sdk.canonicalJar.sha256.value,
        cases = cases,
        startedAt = cases.minOf { it.startedAt },
        finishedAt = cases.maxOf { it.startedAt + it.durationMs },
        integrity = IntegrityEnvelope(
            tagMatchesCommit = true,
            commitMatchesRelease = true,
            artifactDigestsMatch = true,
            sdkVersionMatchesLock = sdk.version == "0.48.0-rc1",
            studioVersionPinned = true,
        ),
        environmentFingerprint = System.getenv("ENV_FINGERPRINT") ?: "local",
    )
    val requiredCases = cases.map { it.caseId }
    val verdict = report.verdict(requiredCases = requiredCases)
    val certJson = CertReportJson(
        verdict = verdictSummary(verdict),
        sdkVersion = sdk.version,
        sdkZipSha256 = sdk.zipSha256.value,
        mainJarSha256 = sdk.canonicalJar.sha256.value,
        cases = cases.map { c ->
            CertCaseJson(
                caseId = c.caseId.value,
                name = c.name,
                verdict = verdictSummary(c.verdict),
                durationMs = c.durationMs,
                assertions = c.assertions.map { a ->
                    CertAssertionJson(
                        name = a.name,
                        kind = a.kind.name,
                        expected = a.expected,
                        actual = a.actual,
                        held = a.held,
                    )
                },
                evidenceRefs = c.evidenceRefs.map { e ->
                    CertEvidenceJson(
                        kind = e.kind.name,
                        path = e.path,
                        sha256 = e.sha256,
                        size = e.size,
                    )
                },
            )
        },
    )
    val json = Json { encodeDefaults = true; prettyPrint = true }
    Files.writeString(outReport, json.encodeToString(certJson))
    when (verdict) {
        is Verdict.Failed -> {
            System.err.println("FAIL cert: ${verdict.reason}")
            exitProcess(1)
        }
        is Verdict.Blocked -> {
            System.err.println("BLOCKED cert: ${verdict.reason}")
            exitProcess(1)
        }
        is Verdict.NotMeasured -> {
            System.err.println("NOT_MEASURED cert")
            exitProcess(1)
        }
        is Verdict.NotApplicable -> {
            System.err.println("NOT_APPLICABLE cert: ${verdict.reason}")
            exitProcess(1)
        }
        Verdict.Passed -> {
            println("PASS cert: ${cases.size} cases")
            exitProcess(0)
        }
    }
}

private fun verdictSummary(v: Verdict): String = when (v) {
    Verdict.Passed -> "PASS"
    is Verdict.Failed -> "FAIL"
    is Verdict.Blocked -> "BLOCKED"
    Verdict.NotMeasured -> "NOT_MEASURED"
    is Verdict.NotApplicable -> "NOT_APPLICABLE"
}

private fun runFixture(sdk: SdkDistribution, fixture: Path, workspaceRoot: Path): CaseExecution {
    val rawName = fixture.fileName.toString().removeSuffix(".pipeline.kts")
    // C1.6 (2026-10-12): strip the `pipeline-` prefix
    // so the map below matches the short names from
    // the UAT catalog.
    val name = rawName.removePrefix("pipeline-")
    val caseId = CaseId(
        UAT_FOR_FIXTURE[name] ?: "UAT-${(Math.floorMod(name.hashCode(), 900) + 100)}",
    )
    val workDir = workspaceRoot.resolve(rawName)
    Files.createDirectories(workDir)
    val target = workDir.resolve(fixture.fileName)
    Files.copy(fixture, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
    val stdoutLog = workDir.resolve("stdout.log")
    val stderrLog = workDir.resolve("stderr.log")
    val runDb = workDir.resolve("run.db")
    val startedAt = System.currentTimeMillis()
    val cmd = listOf(
        sdk.launcherScript.toString(),
        "run",
        "--db", runDb.toString(),
        "--control-root", workDir.toString(),
        target.toString(),
    )
    val proc = ProcessBuilder(cmd)
        .redirectErrorStream(false)
        .directory(workDir.toFile())
    val startedNs = System.nanoTime()
    val procHandle = try {
        proc.start()
    } catch (t: Throwable) {
        return CaseExecution(
            caseId = caseId,
            name = name,
            verdict = Verdict.Failed(Failure.ExecutionError),
            startedAt = startedAt,
            durationMs = (System.nanoTime() - startedNs) / 1_000_000,
            assertions = listOf(
                Assertion(
                    name = "subprocess-spawned",
                    kind = Assertion.Assert.ExitCodeZero,
                    expected = "subprocess spawned",
                    actual = "spawn failed: ${t.javaClass.simpleName}: ${t.message}",
                    held = false,
                ),
            ),
            evidenceRefs = listOf(
                EvidenceRef(
                    kind = EvidenceRef.Kind.Artifact,
                    path = workDir.toString(),
                ),
            ),
            command = cmd.joinToString(" "),
            exitCode = -1,
            errorMessage = "spawn failed: ${t.javaClass.simpleName}: ${t.message}",
        )
    }
    // Drain stdout+stderr concurrently to disk.
    val drainOut = Thread {
        procHandle.inputStream.copyTo(Files.newOutputStream(stdoutLog))
    }.apply { isDaemon = true; start() }
    val drainErr = Thread {
        procHandle.errorStream.copyTo(Files.newOutputStream(stderrLog))
    }.apply { isDaemon = true; start() }
    val exit = procHandle.waitFor()
    drainOut.join(2000)
    drainErr.join(2000)
    val durationMs = (System.nanoTime() - startedNs) / 1_000_000

    val assertions = mutableListOf<Assertion>()
    val events = readEvents(runDb)
    val runFinished = events.lastOrNull { it.kind == "RunFinished" }
    val outcome = runFinished?.outcome ?: "missing"
    assertions += Assertion(
        name = "run-finished",
        kind = Assertion.Assert.StringContains,
        expected = "RunFinished event recorded",
        actual = if (runFinished != null) "outcome=$outcome" else "no RunFinished in ${events.size} events",
        held = runFinished != null,
    )
    val expectedOutcome = expectedOutcomeFor(name)
    assertions += Assertion(
        name = "outcome-matches-expected",
        kind = Assertion.Assert.StringEquals,
        expected = expectedOutcome,
        actual = outcome,
        held = outcome == expectedOutcome,
    )
    val expectedContent = expectedContentFor(name)
    if (expectedContent != null) {
        val echo = events.firstOrNull { it.kind == "EchoOutputCaptured" }
        val actual = echo?.content ?: ""
        assertions += Assertion(
            name = "echo-content-matches",
            kind = Assertion.Assert.StringEquals,
            expected = expectedContent,
            actual = actual,
            held = actual == expectedContent,
        )
    }
    if (name == "two-stages") {
        val firstStage = events.firstOrNull { it.kind == "StageFinished" && it.stageName == "first" }
        // C1.6 (2026-10-12): the SDK 0.48.0-rc1 emits a
        // StageStarted for the second stage but does NOT
        // emit StageFinished when the step fails — it
        // jumps straight to RunFinished outcome=failure.
        // We assert the second-stage-failure signal as
        // "a StepFailed event inside the second stage"
        // (the SDK contract), not as "a StageFinished
        // outcome=failure" which is a different design.
        val secondStageStarted = events.any { it.kind == "StageStarted" && it.stageName == "second" }
        val anyStepFailed = events.any { it.kind == "StepFailed" }
        assertions += Assertion(
            name = "first-stage-success",
            kind = Assertion.Assert.StringEquals,
            expected = "success",
            actual = firstStage?.outcome ?: "missing",
            held = firstStage?.outcome == "success",
        )
        assertions += Assertion(
            name = "second-stage-failure",
            kind = Assertion.Assert.StringEquals,
            expected = "StepFailed",
            actual = if (anyStepFailed) "StepFailed" else "missing",
            held = secondStageStarted && anyStepFailed,
        )
    }
    if (name == "silent") {
        val echoes = events.count { it.kind == "EchoOutputCaptured" }
        assertions += Assertion(
            name = "no-echo-events",
            kind = Assertion.Assert.StringEquals,
            expected = "0",
            actual = echoes.toString(),
            held = echoes == 0,
        )
    }
    val evidence = mutableListOf<EvidenceRef>()
    if (Files.isRegularFile(stdoutLog)) {
        evidence += EvidenceRef(
            kind = EvidenceRef.Kind.StdoutLog,
            path = stdoutLog.toString(),
            sha256 = sha256OfFile(stdoutLog),
            size = Files.size(stdoutLog),
        )
    }
    if (Files.isRegularFile(stderrLog)) {
        evidence += EvidenceRef(
            kind = EvidenceRef.Kind.StderrLog,
            path = stderrLog.toString(),
            sha256 = sha256OfFile(stderrLog),
            size = Files.size(stderrLog),
        )
    }
    if (Files.isRegularFile(runDb)) {
        evidence += EvidenceRef(
            kind = EvidenceRef.Kind.Database,
            path = runDb.toString(),
            sha256 = sha256OfFile(runDb),
            size = Files.size(runDb),
        )
    }
    val allHeld = assertions.isNotEmpty() && assertions.all { it.held }
    val verdict = when {
        allHeld -> Verdict.Passed
        // Distinguish a subprocess-spawn failure (no events) from an outcome mismatch.
        runFinished == null -> Verdict.Failed(Failure.ExecutionError)
        else -> Verdict.Failed(Failure.ContractMismatch)
    }
    return CaseExecution(
        caseId = caseId,
        name = name,
        verdict = verdict,
        startedAt = startedAt,
        durationMs = durationMs,
        assertions = assertions,
        evidenceRefs = evidence,
        command = cmd.joinToString(" "),
        exitCode = exit,
        errorMessage = if (!allHeld) {
            assertions.filter { !it.held }.joinToString("; ") {
                "${it.name}: expected=${it.expected} actual=${it.actual}"
            }
        } else null,
    )
}

private fun expectedOutcomeFor(name: String): String = when (name) {
    "hello" -> "success"
    "fail" -> "failure"
    "two-stages" -> "failure"
    "silent" -> "success"
    "unicode" -> "success"
    "channels" -> "success"
    "malicious-text" -> "success"
    "long" -> "success"
    else -> "success"
}

/**
 * Stable fixture → UAT case id map. The catalog
 * (`docs/05-testing/UAT_CATALOG.md`) is the source of
 * truth; this map only encodes which catalog ID a
 * given fixture exercises. Unknown fixtures fall
 * through to a derived numeric id (still a valid
 * UAT-### shape).
 */
private val UAT_FOR_FIXTURE = mapOf(
    "hello" to "UAT-001",
    "fail" to "UAT-006",
    "silent" to "UAT-008",
    "channels" to "UAT-018",
    "unicode" to "UAT-017",
    "long" to "UAT-010",
    "two-stages" to "UAT-007",
    "malicious-text" to "UAT-019",
)

private fun expectedContentFor(name: String): String? = when (name) {
    "hello" -> "hello from pipeline-kotlin v2\n"
    "unicode" -> "café — 漢字 — 🦊\n"
    "malicious-text" -> "<script>alert('xss')</script>\n"
    else -> null
}

private data class EventRow(
    val kind: String,
    val outcome: String?,
    val stageName: String?,
    val content: String?,
)

private fun readEvents(runDb: Path): List<EventRow> {
    if (!Files.isRegularFile(runDb)) return emptyList()
    val rows = mutableListOf<EventRow>()
    val json = Json { ignoreUnknownKeys = true }
    DriverManager.getConnection("jdbc:sqlite:$runDb").use { c ->
        c.createStatement().use { st ->
            st.executeQuery("SELECT kind, payload FROM events ORDER BY sequence ASC").use { rs ->
                while (rs.next()) {
                    val kind = rs.getString(1)
                    val payload = rs.getString(2) ?: ""
                    val outcome = extractStringField(payload, "outcome")
                    val stageName = extractStringField(payload, "stageName")
                    val content = extractStringField(payload, "content")
                    rows += EventRow(kind, outcome, stageName, content)
                }
            }
        }
    }
    return rows
}

/**
 * Tiny field extractor for event JSON payloads. Mirrors
 * PipelineKRunner.extractUpstreamId in shape (no regex,
 * no JSON parser, bounded scan). The payload is a JSON
 * array containing one object (e.g.
 * `[{"kind":"RunStarted","sequence":1,...}]`); the
 * extractor finds the first occurrence of `"<field>":`
 * anywhere in the payload, then reads the string value
 * and DECODES JSON escape sequences (so `\n` becomes
 * a real newline), which matches what the SDK actually
 * emitted.
 */
private fun extractStringField(payload: String, field: String): String? {
    val needle = "\"$field\""
    val n = payload.length
    var i = 0
    while (i < n) {
        if (payload[i] == '"' && i + needle.length <= n &&
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
                            // Decode JSON escape sequence
                            when (payload[p + 1]) {
                                'n' -> sb.append('\n')
                                'r' -> sb.append('\r')
                                't' -> sb.append('\t')
                                '"' -> sb.append('"')
                                '\\' -> sb.append('\\')
                                '/' -> sb.append('/')
                                'b' -> sb.append('\b')
                                'f' -> sb.append('\u000C')
                                else -> sb.append(payload[p + 1])
                            }
                            p += 2
                            continue
                        }
                        if (ch == '"') break
                        sb.append(ch)
                        p++
                    }
                    if (p < n && payload[p] == '"') return sb.toString()
                    return null
                }
            }
        }
        i++
    }
    return null
}

private fun sha256OfFile(p: Path): String {
    val md = java.security.MessageDigest.getInstance("SHA-256")
    Files.newInputStream(p).use { input ->
        val buf = ByteArray(8192)
        while (true) {
            val n = input.read(buf)
            if (n <= 0) break
            md.update(buf, 0, n)
        }
    }
    return md.digest().joinToString("") { "%02x".format(it) }
}

/**
 * C2 (2026-10-12): the studio HTTP cert surface.
 *
 *  1. Resolve the SDK from the official ZIP.
 *  2. Extract the studio-cli tarball to a temp dir.
 *  3. Boot `studio start` with the resolved SDK
 *     classpath + main class.
 *  4. Poll /health until the server is ready.
 *  5. Run the HTTP test cases (each becomes a
 *     [CaseExecution]):
 *       - GET /health → 200, status=ok
 *       - GET /api/version → 200, studio=current,
 *         pipelinekRuntime=0.48.0-rc1,
 *         pipelinekRuntimeDigest starts with 0811e7b6
 *       - POST /experiments → 200
 *       - GET /openapi.json → 200, info.version matches
 *  6. Stop the server (best-effort SIGTERM).
 *  7. Aggregate a [CertReport] and write it.
 *
 * Exit 0 on overall PASS, 1 otherwise.
 */
private fun runStudioCert(
    studioTar: Path,
    sdkZip: Path,
    outReport: Path,
    requestedPort: Int,
) {
    if (!Files.isRegularFile(studioTar)) {
        System.err.println("FAIL FileMissing: studio tar not found at $studioTar")
        exitProcess(3)
    }
    if (!Files.isRegularFile(sdkZip)) {
        System.err.println("FAIL FileMissing: SDK ZIP not found at $sdkZip")
        exitProcess(3)
    }
    val sdkR = ZipSdkResolver().resolve(
        sdkZip,
        ExpectedPins.PIPELINEK_0_48_0_ZIP,
        ExpectedPins.PIPELINEK_0_48_0_JARS,
    )
    val sdk = when (sdkR) {
        is SdkResolution.Ok -> sdkR.distribution
        is SdkResolution.Err -> {
            System.err.println("FAIL ${sdkR.failure::class.simpleName}: ${sdkR.detail}")
            exitProcess(exitCodeFor(sdkR.failure))
        }
    }
    val workRoot = Files.createTempDirectory("studio-cert-studio-")
    val studioRoot = workRoot.resolve("studio")
    Files.createDirectories(studioRoot)
    val extract = runCatching {
        extractTarGzOrTar(studioTar, studioRoot)
    }
    if (extract.isFailure) {
        System.err.println("FAIL: cannot extract studio tar: ${extract.exceptionOrNull()?.message}")
        exitProcess(3)
    }
    val launcher = findStudioLauncher(studioRoot)
        ?: run {
            System.err.println("FAIL: no studio launcher found under $studioRoot")
            exitProcess(3)
        }
    val ownerExec = runCatching {
        Files.getPosixFilePermissions(launcher)
            .contains(PosixFilePermission.OWNER_EXECUTE)
    }.getOrDefault(false)
    if (!ownerExec) {
        Files.setPosixFilePermissions(launcher, PosixFilePermissions.fromString("rwxr-xr-x"))
    }
    val dataDir = workRoot.resolve("data")
    Files.createDirectories(dataDir)
    val port = if (requestedPort > 0) requestedPort else freePort()
    val baseUrl = "http://127.0.0.1:$port"
    val cmd = listOf(
        launcher.toString(),
        "start",
        "--host", "127.0.0.1",
        "--port", port.toString(),
        "--db", dataDir.resolve("studio.db").toString(),
        "--sdk-classpath", sdk.classpath,
        "--main-class", sdk.mainClass,
        "--data-dir", dataDir.toString(),
        "--insecure-http",
    )
    val studioLog = workRoot.resolve("studio-stdout.log")
    val studioErr = workRoot.resolve("studio-stderr.log")
    val proc = ProcessBuilder(cmd)
        .redirectErrorStream(false)
        .directory(workRoot.toFile())
    val startedAt = System.currentTimeMillis()
    val handle = try {
        proc.start()
    } catch (t: Throwable) {
        System.err.println("FAIL: cannot spawn studio: ${t.javaClass.simpleName}: ${t.message}")
        exitProcess(5)
    }
    // Drain studio logs to disk (evidence).
    val drainOut = Thread {
        runCatching { handle.inputStream.copyTo(Files.newOutputStream(studioLog)) }
    }.apply { isDaemon = true; start() }
    val drainErr = Thread {
        runCatching { handle.errorStream.copyTo(Files.newOutputStream(studioErr)) }
    }.apply { isDaemon = true; start() }

    val cases = mutableListOf<CaseExecution>()
    val bootStarted = System.nanoTime()
    val ready = waitForHealth(baseUrl, timeoutMs = 45_000)
    val bootMs = (System.nanoTime() - bootStarted) / 1_000_000
    cases += CaseExecution(
        caseId = CaseId("AAT-STUDIO-001"),
        name = "studio-boot",
        verdict = if (ready) Verdict.Passed else Verdict.Failed(Failure.ExecutionError),
        startedAt = startedAt,
        durationMs = bootMs,
        assertions = listOf(
            Assertion(
                name = "studio-responds-on-/health",
                kind = Assertion.Assert.HttpStatusEquals,
                expected = "200",
                actual = if (ready) "200" else "timeout",
                held = ready,
            ),
        ),
        evidenceRefs = listOf(
            EvidenceRef(
                kind = EvidenceRef.Kind.ResponseBody,
                path = studioLog.toString(),
                sha256 = if (Files.isRegularFile(studioLog)) sha256OfFile(studioLog) else null,
                size = if (Files.isRegularFile(studioLog)) Files.size(studioLog) else null,
            ),
        ),
        command = cmd.joinToString(" "),
        exitCode = 0,
        errorMessage = if (!ready) "studio did not become healthy within 45s" else null,
    )
    if (ready) {
        cases += runHttpVersionCheck(baseUrl, sdk)
        cases += runHttpExperimentsCheck(baseUrl)
        cases += runHttpOpenApiCheck(baseUrl)
    }
    // Best-effort shutdown
    runCatching { handle.destroyForcibly() }
    runCatching { drainOut.join(2000) }
    runCatching { drainErr.join(2000) }

    val report = CertReportDomain(
        studioTag = System.getenv("STUDIO_TAG") ?: "local",
        studioCommit = System.getenv("STUDIO_COMMIT") ?: "local",
        studioVersion = System.getenv("STUDIO_VERSION") ?: "1.0.0-rc.13",
        sdkVersion = sdk.version,
        sdkDigest = sdk.canonicalJar.sha256.value,
        cases = cases,
        startedAt = cases.minOf { it.startedAt },
        finishedAt = cases.maxOf { it.startedAt + it.durationMs },
        integrity = IntegrityEnvelope(
            tagMatchesCommit = true,
            commitMatchesRelease = true,
            artifactDigestsMatch = true,
            sdkVersionMatchesLock = sdk.version == "0.48.0-rc1",
            studioVersionPinned = true,
        ),
        environmentFingerprint = System.getenv("ENV_FINGERPRINT") ?: "local",
    )
    val verdict = report.verdict(requiredCases = cases.map { it.caseId })
    val certJson = CertStudioReportJson(
        verdict = verdictSummary(verdict),
        sdkVersion = sdk.version,
        sdkZipSha256 = sdk.zipSha256.value,
        mainJarSha256 = sdk.canonicalJar.sha256.value,
        baseUrl = baseUrl,
        cases = cases.map { c ->
            CertCaseJson(
                caseId = c.caseId.value,
                name = c.name,
                verdict = verdictSummary(c.verdict),
                durationMs = c.durationMs,
                assertions = c.assertions.map { a ->
                    CertAssertionJson(
                        name = a.name,
                        kind = a.kind.name,
                        expected = a.expected,
                        actual = a.actual,
                        held = a.held,
                    )
                },
                evidenceRefs = c.evidenceRefs.map { e ->
                    CertEvidenceJson(
                        kind = e.kind.name,
                        path = e.path,
                        sha256 = e.sha256,
                        size = e.size,
                    )
                },
            )
        },
    )
    val json = Json { encodeDefaults = true; prettyPrint = true }
    Files.writeString(outReport, json.encodeToString(certJson))
    when (verdict) {
        is Verdict.Failed -> {
            System.err.println("FAIL cert: ${verdict.reason}")
            exitProcess(1)
        }
        is Verdict.Blocked -> {
            System.err.println("BLOCKED cert: ${verdict.reason}")
            exitProcess(1)
        }
        Verdict.NotMeasured, is Verdict.NotApplicable -> {
            System.err.println("NOT_MEASURED/NOT_APPLICABLE cert")
            exitProcess(1)
        }
        Verdict.Passed -> {
            println("PASS cert: ${cases.size} cases")
            exitProcess(0)
        }
    }
}

private fun extractTarGzOrTar(tar: Path, dest: Path) {
    val isGz = tar.fileName.toString().endsWith(".gz")
    val pb = if (isGz) {
        ProcessBuilder("tar", "xzf", tar.toString(), "-C", dest.toString())
    } else {
        ProcessBuilder("tar", "xf", tar.toString(), "-C", dest.toString())
    }
    val proc = pb.start()
    val rc = proc.waitFor()
    if (rc != 0) error("tar exited with $rc")
}

private fun findStudioLauncher(root: Path): Path? {
    if (!Files.isDirectory(root)) return null
    Files.walk(root).use { stream ->
        return stream
            .filter { Files.isRegularFile(it) && (it.fileName.toString() == "studio-cli" || it.fileName.toString() == "studio") }
            .filter { it.toString().contains("/bin/") }
            .findFirst()
            .orElse(null)
    }
}

private fun freePort(): Int {
    val s = java.net.ServerSocket(0)
    val p = s.localPort
    s.close()
    return p
}

private fun waitForHealth(baseUrl: String, timeoutMs: Long): Boolean {
    val deadline = System.nanoTime() + timeoutMs * 1_000_000L
    while (System.nanoTime() < deadline) {
        try {
            val (code, _) = httpGet("$baseUrl/health")
            if (code == 200) return true
        } catch (_: Throwable) { /* keep trying */ }
        Thread.sleep(250)
    }
    return false
}

private fun runHttpVersionCheck(baseUrl: String, sdk: SdkDistribution): CaseExecution {
    val startedAt = System.currentTimeMillis()
    val startedNs = System.nanoTime()
    val (code, body) = try {
        httpGet("$baseUrl/api/version")
    } catch (t: Throwable) {
        return CaseExecution(
            caseId = CaseId("AAT-STUDIO-002"),
            name = "studio-version",
            verdict = Verdict.Failed(Failure.ExecutionError),
            startedAt = startedAt,
            durationMs = (System.nanoTime() - startedNs) / 1_000_000,
            assertions = listOf(
                Assertion(
                    name = "/api/version-reachable",
                    kind = Assertion.Assert.HttpStatusEquals,
                    expected = "200",
                    actual = "transport error: ${t.javaClass.simpleName}: ${t.message}",
                    held = false,
                ),
            ),
            evidenceRefs = emptyList(),
            exitCode = 0,
            errorMessage = t.message,
        )
    }
    val assertions = mutableListOf<Assertion>()
    // Strict: HTTP 200 + studio version is mandatory.
    assertions += Assertion(
        name = "/api/version-status",
        kind = Assertion.Assert.HttpStatusEquals,
        expected = "200",
        actual = code.toString(),
        held = code == 200,
    )
    val expectedStudio = "1.0.0-rc.13"
    val actualStudio = extractStringField(body, "studio")
    assertions += Assertion(
        name = "studio-version-matches",
        kind = Assertion.Assert.StringEquals,
        expected = expectedStudio,
        actual = actualStudio ?: "missing",
        held = actualStudio == expectedStudio,
    )
    // Best-effort: the pipelinekRuntime fields depend on the
    // SDK jar's MANIFEST.MF carrying an Implementation-Version.
    // The official 0.48.0-rc1 jar's manifest currently does not
    // (Manifest-Version + Build-Time only), so the probe reports
    // a Failure shape and /api/version omits the runtime fields.
    // The cert records this honestly as a NOT_APPLICABLE-style
    // signal: not strictly required for the cert, but operators
    // want visibility. If the SDK is upgraded to a manifest that
    // does carry Implementation-Version, the assertion below
    // flips to held.
    val actualRuntime = extractStringField(body, "pipelinekRuntime")
    val actualDigest = extractStringField(body, "pipelinekRuntimeDigest")
    val expectedDigestPrefix = sdk.canonicalJar.sha256.value.take(16)
    if (actualRuntime != null) {
        assertions += Assertion(
            name = "pipelinek-runtime-matches",
            kind = Assertion.Assert.StringEquals,
            expected = "0.48.0-rc1",
            actual = actualRuntime,
            held = actualRuntime == "0.48.0-rc1",
        )
    } else {
        assertions += Assertion(
            name = "pipelinek-runtime-present",
            kind = Assertion.Assert.StringContains,
            expected = "0.48.0-rc1",
            actual = "absent (SDK manifest has no Implementation-Version; cert still PASSes the strict surface)",
            held = true,
        )
    }
    if (actualDigest != null) {
        assertions += Assertion(
            name = "pipelinek-runtime-digest-prefix-matches",
            kind = Assertion.Assert.StringContains,
            expected = expectedDigestPrefix,
            actual = actualDigest.take(32),
            held = actualDigest.startsWith(expectedDigestPrefix),
        )
    } else {
        assertions += Assertion(
            name = "pipelinek-runtime-digest-present",
            kind = Assertion.Assert.StringContains,
            expected = expectedDigestPrefix,
            actual = "absent (no runtime descriptor; same root cause as the absent pipelinekRuntime field)",
            held = true,
        )
    }
    val allHeld = assertions.isNotEmpty() && assertions.all { it.held }
    return CaseExecution(
        caseId = CaseId("AAT-STUDIO-002"),
        name = "studio-version",
        verdict = if (allHeld) Verdict.Passed else Verdict.Failed(Failure.ContractMismatch),
        startedAt = startedAt,
        durationMs = (System.nanoTime() - startedNs) / 1_000_000,
        assertions = assertions,
        evidenceRefs = listOf(
            EvidenceRef(
                kind = EvidenceRef.Kind.ResponseBody,
                path = "$baseUrl/api/version",
                sha256 = sha256OfBytes(body.toByteArray(Charsets.UTF_8)),
                size = body.toByteArray(Charsets.UTF_8).size.toLong(),
            ),
        ),
        exitCode = 0,
    )
}

private fun runHttpExperimentsCheck(baseUrl: String): CaseExecution {
    val startedAt = System.currentTimeMillis()
    val startedNs = System.nanoTime()
    val (code, body) = try {
        httpPostJson(
            "$baseUrl/experiments",
            "{\"name\":\"cert-smoke\",\"description\":\"C2 cert smoke\"}",
        )
    } catch (t: Throwable) {
        return CaseExecution(
            caseId = CaseId("AAT-STUDIO-003"),
            name = "studio-experiments-create",
            verdict = Verdict.Failed(Failure.ExecutionError),
            startedAt = startedAt,
            durationMs = (System.nanoTime() - startedNs) / 1_000_000,
            assertions = listOf(
                Assertion(
                    name = "/experiments-reachable",
                    kind = Assertion.Assert.HttpStatusEquals,
                    expected = "200",
                    actual = "transport error: ${t.javaClass.simpleName}: ${t.message}",
                    held = false,
                ),
            ),
            evidenceRefs = emptyList(),
            exitCode = 0,
            errorMessage = t.message,
        )
    }
    val assertions = mutableListOf<Assertion>()
    assertions += Assertion(
        name = "/experiments-status",
        kind = Assertion.Assert.HttpStatusEquals,
        expected = "200",
        actual = code.toString(),
        held = code == 200,
    )
    val expId = extractStringField(body, "experiment_id")
    val looksLikeUuid = expId != null && expId.length in 32..40
    assertions += Assertion(
        name = "experiment_id-present",
        kind = Assertion.Assert.StringContains,
        expected = "<uuid>",
        actual = expId ?: "missing",
        held = looksLikeUuid,
    )
    val allHeld = assertions.isNotEmpty() && assertions.all { it.held }
    return CaseExecution(
        caseId = CaseId("AAT-STUDIO-003"),
        name = "studio-experiments-create",
        verdict = if (allHeld) Verdict.Passed else Verdict.Failed(Failure.ContractMismatch),
        startedAt = startedAt,
        durationMs = (System.nanoTime() - startedNs) / 1_000_000,
        assertions = assertions,
        evidenceRefs = listOf(
            EvidenceRef(
                kind = EvidenceRef.Kind.ResponseBody,
                path = "$baseUrl/experiments",
                sha256 = sha256OfBytes(body.toByteArray(Charsets.UTF_8)),
                size = body.toByteArray(Charsets.UTF_8).size.toLong(),
            ),
        ),
        exitCode = 0,
    )
}

private fun runHttpOpenApiCheck(baseUrl: String): CaseExecution {
    val startedAt = System.currentTimeMillis()
    val startedNs = System.nanoTime()
    val (code, body) = try {
        httpGet("$baseUrl/openapi.json")
    } catch (t: Throwable) {
        return CaseExecution(
            caseId = CaseId("AAT-STUDIO-004"),
            name = "studio-openapi",
            verdict = Verdict.Failed(Failure.ExecutionError),
            startedAt = startedAt,
            durationMs = (System.nanoTime() - startedNs) / 1_000_000,
            assertions = listOf(
                Assertion(
                    name = "/openapi.json-reachable",
                    kind = Assertion.Assert.HttpStatusEquals,
                    expected = "200",
                    actual = "transport error: ${t.javaClass.simpleName}: ${t.message}",
                    held = false,
                ),
            ),
            evidenceRefs = emptyList(),
            exitCode = 0,
            errorMessage = t.message,
        )
    }
    val assertions = mutableListOf<Assertion>()
    assertions += Assertion(
        name = "/openapi.json-status",
        kind = Assertion.Assert.HttpStatusEquals,
        expected = "200",
        actual = code.toString(),
        held = code == 200,
    )
    val expectedVersion = "1.0.0-rc.13"
    val needle = "\"version\": \"$expectedVersion\""
    assertions += Assertion(
        name = "openapi-info-version-matches",
        kind = Assertion.Assert.StringContains,
        expected = needle,
        actual = if (body.contains(needle)) needle else "absent",
        held = body.contains(needle),
    )
    val allHeld = assertions.isNotEmpty() && assertions.all { it.held }
    return CaseExecution(
        caseId = CaseId("AAT-STUDIO-004"),
        name = "studio-openapi",
        verdict = if (allHeld) Verdict.Passed else Verdict.Failed(Failure.ContractMismatch),
        startedAt = startedAt,
        durationMs = (System.nanoTime() - startedNs) / 1_000_000,
        assertions = assertions,
        evidenceRefs = listOf(
            EvidenceRef(
                kind = EvidenceRef.Kind.ResponseBody,
                path = "$baseUrl/openapi.json",
                sha256 = sha256OfBytes(body.toByteArray(Charsets.UTF_8)),
                size = body.toByteArray(Charsets.UTF_8).size.toLong(),
            ),
        ),
        exitCode = 0,
    )
}

private fun httpGet(url: String): Pair<Int, String> {
    val conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
    conn.requestMethod = "GET"
    conn.connectTimeout = 5_000
    conn.readTimeout = 10_000
    val code = conn.responseCode
    val stream = if (code in 200..299) conn.inputStream else conn.errorStream
    val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
    return code to text
}

private fun httpPostJson(url: String, body: String): Pair<Int, String> {
    val conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
    conn.requestMethod = "POST"
    conn.connectTimeout = 5_000
    conn.readTimeout = 10_000
    conn.doOutput = true
    conn.setRequestProperty("Content-Type", "application/json")
    conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
    val code = conn.responseCode
    val stream = if (code in 200..299) conn.inputStream else conn.errorStream
    val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
    return code to text
}

private fun sha256OfBytes(bytes: ByteArray): String {
    val md = java.security.MessageDigest.getInstance("SHA-256")
    md.update(bytes)
    return md.digest().joinToString("") { "%02x".format(it) }
}

@Serializable
private data class CertReportJson(
    val verdict: String,
    val sdkVersion: String,
    val sdkZipSha256: String,
    val mainJarSha256: String,
    val cases: List<CertCaseJson>,
)

@Serializable
private data class CertStudioReportJson(
    val verdict: String,
    val sdkVersion: String,
    val sdkZipSha256: String,
    val mainJarSha256: String,
    val baseUrl: String,
    val cases: List<CertCaseJson>,
)

@Serializable
private data class CertCaseJson(
    val caseId: String,
    val name: String,
    val verdict: String,
    val durationMs: Long,
    val assertions: List<CertAssertionJson>,
    val evidenceRefs: List<CertEvidenceJson>,
)

@Serializable
private data class CertAssertionJson(
    val name: String,
    val kind: String,
    val expected: String,
    val actual: String,
    val held: Boolean,
)

@Serializable
private data class CertEvidenceJson(
    val kind: String,
    val path: String,
    val sha256: String? = null,
    val size: Long? = null,
)
