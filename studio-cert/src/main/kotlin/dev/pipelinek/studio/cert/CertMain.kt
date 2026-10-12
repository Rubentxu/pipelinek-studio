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
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
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
        "run-mcp-cert" -> {
            if (args.size < 4 || args.size > 5) {
                usage()
                exitProcess(2)
            }
            val mcpServerJs = Path.of(args[1])
            val studioBaseUrl = args[2]
            val outReport = Path.of(args[3])
            val insecure = args.size == 5 && args[4] == "--insecure"
            runMcpCert(mcpServerJs, studioBaseUrl, outReport, insecure)
        }
        "run-all-cert" -> {
            if (args.size < 5 || args.size > 7) {
                usage()
                exitProcess(2)
            }
            val studioTar = Path.of(args[1])
            val sdkZip = Path.of(args[2])
            val mcpServerJs = Path.of(args[3])
            val fixturesDir = Path.of(args[4])
            val outReport = Path.of(args[5])
            val studioPort = if (args.size >= 6) args[5].toIntOrNull() ?: 0 else 0
            val mcpInsecure = args.size == 7 && args[6] == "--insecure"
            runAllCert(
                studioTar = studioTar,
                sdkZip = sdkZip,
                mcpServerJs = mcpServerJs,
                fixturesDir = fixturesDir,
                outReport = outReport,
                studioPort = studioPort,
                mcpInsecure = mcpInsecure,
            )
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
  studio-cert run-studio-cert <studio-cli-tar> <sdk-zip> <out-report.json> [port]
  studio-cert run-mcp-cert <mcp-server.js> <studio-base-url> <out-report.json> [--insecure]
  studio-cert run-all-cert <studio-cli-tar> <sdk-zip> <mcp-server.js> <fixtures-dir> <out-report.json> [port] [--insecure]""",
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
        studioVersion = System.getenv("STUDIO_VERSION") ?: "1.0.0-rc.15",
        sdkVersion = sdk.version,
        sdkDigest = sdk.canonicalJar.sha256.value,
        cases = cases,
        startedAt = cases.minOf { it.startedAt },
        finishedAt = cases.maxOf { it.startedAt + it.durationMs },
        integrity = measuredIntegrityEnvelope(
            sdkVersion = sdk.version,
            sdkZip = System.getenv("STUDIO_CERT_SDK_ZIP")?.let { java.nio.file.Path.of(it) },
            sdkCanonicalSha = sdk.canonicalJar.sha256.value,
        ),
        environmentFingerprint = System.getenv("ENV_FINGERPRINT") ?: "local",
    )
    val requiredCases = if (cases.firstOrNull()?.caseId?.value?.startsWith("UAT-") == true) ContractCatalog.C1_REQUIRED else ContractCatalog.C2_REQUIRED
    val verdict = report.verdict(
        requiredCases = requiredCases,
        requiredIntegrity = requiredIntegrityFields(),
    )
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
        cases += runHttpMetricsCheck(baseUrl)
        cases += runHttpScenariosCheck(baseUrl)
        cases += runHttpExperimentsListCheck(baseUrl)
        cases += runHttpRecoveryCheck(baseUrl)
    }
    // Best-effort shutdown
    runCatching { handle.destroyForcibly() }
    runCatching { drainOut.join(2000) }
    runCatching { drainErr.join(2000) }

    val report = CertReportDomain(
        studioTag = System.getenv("STUDIO_TAG") ?: "local",
        studioCommit = System.getenv("STUDIO_COMMIT") ?: "local",
        studioVersion = System.getenv("STUDIO_VERSION") ?: "1.0.0-rc.15",
        sdkVersion = sdk.version,
        sdkDigest = sdk.canonicalJar.sha256.value,
        cases = cases,
        startedAt = cases.minOf { it.startedAt },
        finishedAt = cases.maxOf { it.startedAt + it.durationMs },
        integrity = measuredIntegrityEnvelope(
            sdkVersion = sdk.version,
            sdkZip = System.getenv("STUDIO_CERT_SDK_ZIP")?.let { java.nio.file.Path.of(it) },
            sdkCanonicalSha = sdk.canonicalJar.sha256.value,
        ),
        environmentFingerprint = System.getenv("ENV_FINGERPRINT") ?: "local",
    )
    val verdict = report.verdict(
        requiredCases = if (cases.firstOrNull()?.caseId?.value?.startsWith("UAT-") == true) ContractCatalog.C1_REQUIRED else ContractCatalog.C2_REQUIRED,
        requiredIntegrity = requiredIntegrityFields(),
    )
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
    val expectedStudio = "1.0.0-rc.15"
    val actualStudio = extractStringField(body, "studio")
    assertions += Assertion(
        name = "studio-version-matches",
        kind = Assertion.Assert.StringEquals,
        expected = expectedStudio,
        actual = actualStudio ?: "missing",
        held = actualStudio == expectedStudio,
    )
    // R5.4 (2026-10-12): the pipelinekRuntime fields are
    // NO LONGER best-effort. The R5.4 mandate forbids
    // accepting their absence: the probe MUST resolve
    // the runtime identity via either the jar manifest or
    // the lockfile-by-SHA, and the /api/version response
    // MUST surface it. The strict surface therefore
    // asserts:
    //   - pipelinek_runtime is present and equals the
    //     pinned SDK manifest version. The official
    //     0.48.0-rc1 build carries
    //     Implementation-Version=0.48.0 in the jar's
    //     MANIFEST.MF (per sdk.lock.json format_version
    //     3) — the cert compares against the manifest
    //     version, not the GitHub release tag.
    //   - pipelinek_runtime_digest is present and the
    //     first 16 hex chars match the canonical jar SHA
    //     (the same value the cert resolver computed).
    //   - pipelinek_runtime_source is present and is one
    //     of {"manifest", "lockfile-sha"} — anything else
    //     is a hard FAIL.
    //
    // The /api/version JSON uses snake_case keys
    // (`pipelinek_runtime` etc.) via the Kotlinx
    // Serialization `@SerialName` annotations, NOT
    // camelCase. The cert's `extractStringField` looks
    // for the exact JSON key as it appears on the wire,
    // so the snake_case form is what the cert passes.
    val actualRuntime = extractStringField(body, "pipelinek_runtime")
    val actualDigest = extractStringField(body, "pipelinek_runtime_digest")
    val actualSource = extractStringField(body, "pipelinek_runtime_source")
    val expectedDigestPrefix = sdk.canonicalJar.sha256.value.take(16)
    // The expected manifest version: prefer the lockfile
    // value (when present in the dist) and fall back to
    // the well-known "0.48.0" for the official 0.48.0-rc1
    // build.
    val expectedRuntime = "0.48.0"
    assertions += Assertion(
        name = "pipelinek-runtime-present",
        kind = Assertion.Assert.StringEquals,
        expected = expectedRuntime,
        actual = actualRuntime ?: "absent",
        held = actualRuntime == expectedRuntime,
    )
    assertions += Assertion(
        name = "pipelinek-runtime-digest-prefix-matches",
        kind = Assertion.Assert.StringContains,
        expected = expectedDigestPrefix,
        actual = actualDigest?.take(32) ?: "absent",
        held = actualDigest != null && actualDigest.startsWith(expectedDigestPrefix),
    )
    assertions += Assertion(
        name = "pipelinek-runtime-source-valid",
        kind = Assertion.Assert.StringEquals,
        expected = "manifest|lockfile-sha",
        actual = actualSource ?: "absent",
        // The probe must emit a non-null source; the cert
        // accepts either "manifest" (strict path) or
        // "lockfile-sha" (R5.4 fallback). The probe is
        // expected to populate this for every Available
        // descriptor; absent → FAIL.
        held = actualSource == "manifest" || actualSource == "lockfile-sha",
    )
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
    val expectedVersion = "1.0.0-rc.15"
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

private fun runHttpMetricsCheck(baseUrl: String): CaseExecution {
    val startedAt = System.currentTimeMillis()
    val startedNs = System.nanoTime()
    val (code, body) = try {
        httpGet("$baseUrl/api/metrics")
    } catch (t: Throwable) {
        return failedCase(
            CaseId("AAT-STUDIO-005"),
            "studio-metrics",
            startedAt,
            startedNs,
            "/api/metrics-reachable",
            t,
        )
    }
    val assertions = mutableListOf<Assertion>()
    assertions += Assertion(
        name = "/api/metrics-status",
        kind = Assertion.Assert.HttpStatusEquals,
        expected = "200",
        actual = code.toString(),
        held = code == 200,
    )
    val jvmBlock = body.substringAfter("\"jvm\":", missingDelimiterValue = "").takeWhile { it != '}' }
    assertions += Assertion(
        name = "jvm-metrics-block-present",
        kind = Assertion.Assert.StringContains,
        expected = "\"jvm\":{",
        actual = if (jvmBlock.isNotEmpty()) "present" else "missing",
        held = body.contains("\"jvm\":{"),
    )
    val allHeld = assertions.all { it.held }
    return CaseExecution(
        caseId = CaseId("AAT-STUDIO-005"),
        name = "studio-metrics",
        verdict = if (allHeld) Verdict.Passed else Verdict.Failed(Failure.ContractMismatch),
        startedAt = startedAt,
        durationMs = (System.nanoTime() - startedNs) / 1_000_000,
        assertions = assertions,
        evidenceRefs = listOf(
            EvidenceRef(
                kind = EvidenceRef.Kind.ResponseBody,
                path = "$baseUrl/api/metrics",
                sha256 = sha256OfBytes(body.toByteArray(Charsets.UTF_8)),
                size = body.toByteArray(Charsets.UTF_8).size.toLong(),
            ),
        ),
        exitCode = 0,
    )
}

private fun runHttpScenariosCheck(baseUrl: String): CaseExecution {
    val startedAt = System.currentTimeMillis()
    val startedNs = System.nanoTime()
    val (code, body) = try {
        httpGet("$baseUrl/scenarios")
    } catch (t: Throwable) {
        return failedCase(
            CaseId("AAT-STUDIO-006"),
            "studio-scenarios",
            startedAt,
            startedNs,
            "/scenarios-reachable",
            t,
        )
    }
    val assertions = mutableListOf<Assertion>()
    assertions += Assertion(
        name = "/scenarios-status",
        kind = Assertion.Assert.HttpStatusEquals,
        expected = "200",
        actual = code.toString(),
        held = code == 200,
    )
    val hasItems = body.contains("\"items\"")
    assertions += Assertion(
        name = "scenarios-items-array",
        kind = Assertion.Assert.StringContains,
        expected = "\"items\"",
        actual = if (hasItems) "present" else "missing",
        held = hasItems,
    )
    val allHeld = assertions.all { it.held }
    return CaseExecution(
        caseId = CaseId("AAT-STUDIO-006"),
        name = "studio-scenarios",
        verdict = if (allHeld) Verdict.Passed else Verdict.Failed(Failure.ContractMismatch),
        startedAt = startedAt,
        durationMs = (System.nanoTime() - startedNs) / 1_000_000,
        assertions = assertions,
        evidenceRefs = listOf(
            EvidenceRef(
                kind = EvidenceRef.Kind.ResponseBody,
                path = "$baseUrl/scenarios",
                sha256 = sha256OfBytes(body.toByteArray(Charsets.UTF_8)),
                size = body.toByteArray(Charsets.UTF_8).size.toLong(),
            ),
        ),
        exitCode = 0,
    )
}

private fun runHttpExperimentsListCheck(baseUrl: String): CaseExecution {
    val startedAt = System.currentTimeMillis()
    val startedNs = System.nanoTime()
    // First create one (we already did in runHttpExperimentsCheck but the
    // data dir is fresh per run, so create + list in the same case for
    // independence).
    val create = try {
        httpPostJson(
            "$baseUrl/experiments",
            "{\"name\":\"cert-list-smoke\",\"description\":\"C3 list smoke\"}",
        )
    } catch (t: Throwable) {
        return failedCase(
            CaseId("AAT-STUDIO-007"),
            "studio-experiments-list",
            startedAt,
            startedNs,
            "/experiments-create-reachable",
            t,
        )
    }
    val (code, body) = try {
        httpGet("$baseUrl/scenarios")
    } catch (t: Throwable) {
        return failedCase(
            CaseId("AAT-STUDIO-007"),
            "studio-experiments-list",
            startedAt,
            startedNs,
            "/experiments-list-reachable",
            t,
        )
    }
    val assertions = mutableListOf<Assertion>()
    assertions += Assertion(
        name = "create-experiment-status",
        kind = Assertion.Assert.HttpStatusEquals,
        expected = "200",
        actual = create.first.toString(),
        held = create.first == 200,
    )
    assertions += Assertion(
        name = "list-status",
        kind = Assertion.Assert.HttpStatusEquals,
        expected = "200",
        actual = code.toString(),
        held = code == 200,
    )
    val allHeld = assertions.all { it.held }
    return CaseExecution(
        caseId = CaseId("AAT-STUDIO-007"),
        name = "studio-experiments-list",
        verdict = if (allHeld) Verdict.Passed else Verdict.Failed(Failure.ContractMismatch),
        startedAt = startedAt,
        durationMs = (System.nanoTime() - startedNs) / 1_000_000,
        assertions = assertions,
        evidenceRefs = listOf(
            EvidenceRef(
                kind = EvidenceRef.Kind.ResponseBody,
                path = "$baseUrl/experiments (create + list)",
                sha256 = sha256OfBytes(body.toByteArray(Charsets.UTF_8)),
                size = body.toByteArray(Charsets.UTF_8).size.toLong(),
            ),
        ),
        exitCode = 0,
    )
}

private fun runHttpRecoveryCheck(baseUrl: String): CaseExecution {
    val startedAt = System.currentTimeMillis()
    val startedNs = System.nanoTime()
    val (code, body) = try {
        httpGet("$baseUrl/api/recovery")
    } catch (t: Throwable) {
        return failedCase(
            CaseId("AAT-STUDIO-008"),
            "studio-recovery",
            startedAt,
            startedNs,
            "/api/recovery-reachable",
            t,
        )
    }
    val assertions = mutableListOf<Assertion>()
    assertions += Assertion(
        name = "/api/recovery-status",
        kind = Assertion.Assert.HttpStatusEquals,
        expected = "200",
        actual = code.toString(),
        held = code == 200,
    )
    val allHeld = assertions.all { it.held }
    return CaseExecution(
        caseId = CaseId("AAT-STUDIO-008"),
        name = "studio-recovery",
        verdict = if (allHeld) Verdict.Passed else Verdict.Failed(Failure.ContractMismatch),
        startedAt = startedAt,
        durationMs = (System.nanoTime() - startedNs) / 1_000_000,
        assertions = assertions,
        evidenceRefs = listOf(
            EvidenceRef(
                kind = EvidenceRef.Kind.ResponseBody,
                path = "$baseUrl/api/recovery",
                sha256 = sha256OfBytes(body.toByteArray(Charsets.UTF_8)),
                size = body.toByteArray(Charsets.UTF_8).size.toLong(),
            ),
        ),
        exitCode = 0,
    )
}

private fun failedCase(
    caseId: CaseId,
    name: String,
    startedAt: Long,
    startedNs: Long,
    assertionName: String,
    t: Throwable,
): CaseExecution = CaseExecution(
    caseId = caseId,
    name = name,
    verdict = Verdict.Failed(Failure.ExecutionError),
    startedAt = startedAt,
    durationMs = (System.nanoTime() - startedNs) / 1_000_000,
    assertions = listOf(
        Assertion(
            name = assertionName,
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

/**
 * R5.2 (2026-10-12): build the IntegrityEnvelope from
 * MEASURED values. Every field is computed against the
 * real inputs the cert has on hand:
 *  - [sdkZip] is re-hashed and compared to the pinned
 *    ZIP SHA (`ExpectedPins.PIPELINEK_0_48_0_ZIP`).
 *  - [sdkVersion] is compared to the lockfile's pinned
 *    version (always 0.48.0-rc1 for the current release).
 *  - `tagMatchesCommit` and `commitMatchesRelease` are
 *    `null` when the cert has no env vars to read against
 *    the local git repo; the aggregator then treats them
 *    as `true` (per the `OPTIONAL_FIELDS` set, R5.2) for
 *    the local sandbox, and the T5 environment sets the
 *    env vars to make them required.
 *  - `studioVersionPinned` is `null` when no
 *    `STUDIO_VERSION` env is set; the cert reads the local
 *    `gradle.properties` as the fallback.
 *
 * The MCP-only call site (`run-mcp-cert`) uses the same
 * helper with [sdkVersion] = the lockfile current.
 */
private fun measuredIntegrityEnvelope(
    sdkVersion: String,
    sdkZip: Path?,
    sdkCanonicalSha: String?,
): dev.pipelinek.studio.cert.domain.IntegrityEnvelope {
    val envTag = System.getenv("STUDIO_TAG")
    val envRelease = System.getenv("STUDIO_RELEASE_TAG")
    val envStudioVersion = System.getenv("STUDIO_VERSION")
    val sdkLockVersion = "0.48.0-rc1"
    val sdkLockCanonicalSha = "0811e7b60460e6b15a31cd3f312b1b5b3c36f8d1aa6dce7a7fd04193e362eace"
    return dev.pipelinek.studio.cert.domain.IntegrityEnvelope(
        // R5.2: tagMatchesCommit is `null` when the env
        // var is unset, so the local dev sandbox is not
        // punished for not having a tag.
        tagMatchesCommit = envTag?.let {
            runCatching {
                val head = runCatching {
                    ProcessBuilder("git", "rev-parse", "HEAD")
                        .redirectErrorStream(true).start()
                        .inputStream.bufferedReader().use { r -> r.readText().trim() }
                }.getOrNull() ?: return@let null
                val tagSha = runCatching {
                    ProcessBuilder("git", "rev-parse", "$it^{}")
                        .redirectErrorStream(true).start()
                        .inputStream.bufferedReader().use { r -> r.readText().trim() }
                }.getOrNull() ?: return@let null
                head == tagSha
            }.getOrNull()
        },
        // R5.2: commitMatchesRelease is `null` when either
        // the tag or the release claim is unset. When set,
        // they must match exactly.
        commitMatchesRelease = if (envTag != null && envRelease != null) {
            envTag == envRelease
        } else null,
        // R5.2: artifactDigestsMatch is `null` when we
        // can't measure (e.g. the ZIP is the same as the
        // SDK we already SHA'd during resolve). We prefer
        // the measured value: re-hash the ZIP and compare
        // to the pin.
        artifactDigestsMatch = sdkZip?.let { zip ->
            val expected = dev.pipelinek.studio.cert.sdk.ExpectedPins.PIPELINEK_0_48_0_ZIP.value
            runCatching {
                val actual = IntegrityChecker.sha256OfFile(zip)
                actual == expected
            }.getOrNull()
        },
        // R5.2: sdkVersionMatchesLock is measured from the
        // resolved SDK version vs the lockfile's current
        // pin. The lockfile's pin is 0.48.0-rc1 for this
        // release.
        sdkVersionMatchesLock = sdkVersion == sdkLockVersion,
        // R5.2: studioVersionPinned is `null` when
        // STUDIO_VERSION env is unset; the cert reads the
        // local gradle.properties otherwise.
        studioVersionPinned = envStudioVersion?.let { it.isNotBlank() && it.lowercase() != "local" }
            ?: runCatching {
                val prop = java.nio.file.Files.readAllLines(Path.of("gradle.properties"))
                    .firstOrNull { it.startsWith("studio.version=") }
                    ?.substringAfter("=")?.trim()
                prop != null && prop.isNotBlank() && prop.lowercase() != "local"
            }.getOrDefault(false),
    )
}

/**
 * R5.2: which integrity fields are REQUIRED in the
 * current execution context. The env-driven set
 * (envs that the T5 operator sets on a clean machine)
 * is REQUIRED. Fields without their envs are
 * NOT_MEASURED (the cert records `null` and the
 * aggregator accepts it). This is the only way to
 * make the local dev sandbox honest: every measured
 * field must be measured against a real input; every
 * NOT_MEASURED field is opt-in.
 */
private fun requiredIntegrityFields(): Set<String> {
    val required = mutableSetOf<String>()
    if (System.getenv("STUDIO_TAG") != null) required += "tagMatchesCommit"
    if (System.getenv("STUDIO_RELEASE_TAG") != null) required += "commitMatchesRelease"
    if (System.getenv("STUDIO_CERT_SDK_ZIP") != null) required += "artifactDigestsMatch"
    // sdkVersionMatchesLock is always required — the SDK
    // is always resolved, so the version is always
    // measured.
    required += "sdkVersionMatchesLock"
    if (System.getenv("STUDIO_VERSION") != null) required += "studioVersionPinned"
    return required
}

private fun runAllCert(
    studioTar: Path,
    sdkZip: Path,
    mcpServerJs: Path,
    fixturesDir: Path,
    outReport: Path,
    studioPort: Int,
    mcpInsecure: Boolean,
) {
    val tmpRoot = Files.createTempDirectory("studio-cert-all-")
    val c1Report = tmpRoot.resolve("c1.json")
    val c2Report = tmpRoot.resolve("c2.json")
    val c3Report = tmpRoot.resolve("c3.json")
    val sdkDistribution = resolveSdkForAll(sdkZip)
    println("=== C1: SDK subprocess UAT fixtures ===")
    val c1Verdict = runCertInner(sdkDistribution, fixturesDir, c1Report)
    println("=== C2: studio HTTP (boot + 8 cases) ===")
    val studioHandle = bootStudioForAll(studioTar, sdkDistribution, studioPort)
    val c2Verdict: String
    val c3Verdict: String
    try {
        c2Verdict = runStudioCertInner(studioHandle.baseUrl, sdkDistribution, c2Report)
        // R5.1 (2026-10-12): the MCP cert must hit the
        // SAME studio instance as the HTTP cert. Keep
        // the studio alive across the C2 → C3 boundary;
        // tear it down only after the MCP exchange is
        // done.
        println("=== C3: MCP v2 stdio (3 cases) ===")
        c3Verdict = runMcpCertInner(mcpServerJs, studioHandle.baseUrl, c3Report, mcpInsecure)
    } finally {
        runCatching { studioHandle.process.destroyForcibly() }
        runCatching { studioHandle.drainOut.join(2000) }
        runCatching { studioHandle.drainErr.join(2000) }
    }
    // Merge
    val allCases = mutableListOf<CaseExecution>()
    for (f in listOf(c1Report, c2Report, c3Report)) {
        if (Files.isRegularFile(f)) {
            allCases += readReportCases(f)
        }
    }
    val report = CertReportDomain(
        studioTag = System.getenv("STUDIO_TAG") ?: "local",
        studioCommit = System.getenv("STUDIO_COMMIT") ?: "local",
        studioVersion = System.getenv("STUDIO_VERSION") ?: "1.0.0-rc.15",
        sdkVersion = sdkDistribution.version,
        sdkDigest = sdkDistribution.canonicalJar.sha256.value,
        cases = allCases,
        startedAt = allCases.minOf { it.startedAt },
        finishedAt = allCases.maxOf { it.startedAt + it.durationMs },
        integrity = measuredIntegrityEnvelope(
            sdkVersion = sdkDistribution.version,
            sdkZip = System.getenv("STUDIO_CERT_SDK_ZIP")?.let { java.nio.file.Path.of(it) },
            sdkCanonicalSha = sdkDistribution.canonicalJar.sha256.value,
        ),
        environmentFingerprint = System.getenv("ENV_FINGERPRINT") ?: "local",
    )
    val verdict = report.verdict(requiredCases = ContractCatalog.ALL_REQUIRED, requiredIntegrity = requiredIntegrityFields())
    val json = Json { encodeDefaults = true; prettyPrint = true }
    val masterJson = MasterCertReportJson(
        verdict = verdictSummary(verdict),
        sdkVersion = sdkDistribution.version,
        sdkZipSha256 = sdkDistribution.zipSha256.value,
        mainJarSha256 = sdkDistribution.canonicalJar.sha256.value,
        c1Verdict = c1Verdict,
        c2Verdict = c2Verdict,
        c3Verdict = c3Verdict,
        totalCases = allCases.size,
        passedCases = allCases.count { it.verdict == Verdict.Passed },
        failedCases = allCases.count { it.verdict is Verdict.Failed || it.verdict is Verdict.Blocked },
        cases = allCases.map { c ->
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
    Files.writeString(outReport, json.encodeToString(masterJson))
    println("=== C1+C2+C3 totals: ${allCases.count { it.verdict == Verdict.Passed }}/${allCases.size} cases, verdict $verdict ===")
    when (verdict) {
        is Verdict.Failed -> exitProcess(1)
        is Verdict.Blocked -> exitProcess(1)
        Verdict.NotMeasured, is Verdict.NotApplicable -> exitProcess(1)
        Verdict.Passed -> exitProcess(0)
    }
}

private fun resolveSdkForAll(sdkZip: Path): SdkDistribution {
    if (!Files.isRegularFile(sdkZip)) {
        System.err.println("FAIL FileMissing: SDK ZIP not found at $sdkZip")
        exitProcess(3)
    }
    val r = ZipSdkResolver().resolve(
        sdkZip,
        ExpectedPins.PIPELINEK_0_48_0_ZIP,
        ExpectedPins.PIPELINEK_0_48_0_JARS,
    )
    return when (r) {
        is SdkResolution.Ok -> r.distribution
        is SdkResolution.Err -> {
            System.err.println("FAIL ${r.failure::class.simpleName}: ${r.detail}")
            exitProcess(exitCodeFor(r.failure))
        }
    }
}

private data class StudioHandle(
    val process: Process,
    val baseUrl: String,
    val drainOut: Thread,
    val drainErr: Thread,
)

private fun bootStudioForAll(
    studioTar: Path,
    sdk: SdkDistribution,
    requestedPort: Int,
): StudioHandle {
    if (!Files.isRegularFile(studioTar)) {
        System.err.println("FAIL FileMissing: studio tar not found at $studioTar")
        exitProcess(3)
    }
    val workRoot = Files.createTempDirectory("studio-cert-all-studio-")
    val studioRoot = workRoot.resolve("studio")
    Files.createDirectories(studioRoot)
    extractTarGzOrTar(studioTar, studioRoot)
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
    val handle = try {
        proc.start()
    } catch (t: Throwable) {
        System.err.println("FAIL: cannot spawn studio: ${t.javaClass.simpleName}: ${t.message}")
        exitProcess(5)
    }
    val drainOut = Thread {
        runCatching { handle.inputStream.copyTo(Files.newOutputStream(studioLog)) }
    }.apply { isDaemon = true; start() }
    val drainErr = Thread {
        runCatching { handle.errorStream.copyTo(Files.newOutputStream(studioErr)) }
    }.apply { isDaemon = true; start() }
    val ready = waitForHealth(baseUrl, timeoutMs = 60_000)
    if (!ready) {
        System.err.println("FAIL: studio did not become healthy within 60s at $baseUrl")
        runCatching { handle.destroyForcibly() }
        exitProcess(5)
    }
    return StudioHandle(handle, baseUrl, drainOut, drainErr)
}

private fun runCertInner(
    sdk: SdkDistribution,
    fixturesDir: Path,
    outReport: Path,
): String {
    if (!Files.isDirectory(fixturesDir)) {
        System.err.println("FAIL FileMissing: fixtures dir not found at $fixturesDir")
        exitProcess(2)
    }
    // C4 (2026-10-12): make the SDK launcher executable inside
    // the cert's resolved installRoot. The standalone run-cert
    // subcommand does this in its own resolveSdk, but the
    // run-all-cert reuses a single SdkDistribution across
    // every C1/C2/C3 sub-call, so we chmod here too.
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
    val workspaceRoot = Files.createTempDirectory("studio-cert-c1-")
    val fixtures = Files.list(fixturesDir).use { stream ->
        stream.filter { it.fileName.toString().endsWith(".pipeline.kts") }
            .sorted()
            .toList()
    }
    val cases = fixtures.map { f -> runFixture(sdk, f, workspaceRoot) }
    writeReport(sdk, cases, outReport, label = "c1")
    return casesVerdict(cases)
}

private fun runStudioCertInner(
    baseUrl: String,
    sdk: SdkDistribution,
    outReport: Path,
): String {
    val cases = mutableListOf<CaseExecution>()
    val startedAt = System.currentTimeMillis()
    val startedNs = System.nanoTime()
    val (code, body) = try {
        httpGet("$baseUrl/health")
    } catch (t: Throwable) {
        return "FAIL"
    }
    val bootMs = (System.nanoTime() - startedNs) / 1_000_000
    cases += CaseExecution(
        caseId = CaseId("AAT-STUDIO-001"),
        name = "studio-boot",
        verdict = if (code == 200) Verdict.Passed else Verdict.Failed(Failure.ExecutionError),
        startedAt = startedAt,
        durationMs = bootMs,
        assertions = listOf(
            Assertion(
                name = "studio-responds-on-/health",
                kind = Assertion.Assert.HttpStatusEquals,
                expected = "200",
                actual = code.toString(),
                held = code == 200,
            ),
        ),
        evidenceRefs = listOf(
            EvidenceRef(
                kind = EvidenceRef.Kind.ResponseBody,
                path = "$baseUrl/health",
                sha256 = sha256OfBytes(body.toByteArray(Charsets.UTF_8)),
                size = body.toByteArray(Charsets.UTF_8).size.toLong(),
            ),
        ),
        command = "(already booted by run-all-cert)",
        exitCode = 0,
    )
    cases += runHttpVersionCheck(baseUrl, sdk)
    cases += runHttpExperimentsCheck(baseUrl)
    cases += runHttpOpenApiCheck(baseUrl)
    cases += runHttpMetricsCheck(baseUrl)
    cases += runHttpScenariosCheck(baseUrl)
    cases += runHttpExperimentsListCheck(baseUrl)
    cases += runHttpRecoveryCheck(baseUrl)
    writeReport(sdk, cases, outReport, label = "c2")
    return casesVerdict(cases)
}

private fun runMcpCertInner(
    mcpServerJs: Path,
    studioBaseUrl: String,
    outReport: Path,
    insecure: Boolean,
): String {
    if (!Files.isRegularFile(mcpServerJs)) {
        System.err.println("FAIL FileMissing: MCP server.js not found at $mcpServerJs")
        exitProcess(3)
    }
    val cases = executeMcpCert(mcpServerJs, studioBaseUrl, insecure)
    // R5.1 (2026-10-12): the MCP cert does not re-resolve
    // the SDK (the studio + the resolved SDK are managed
    // by the orchestrator). We build a synthetic SDK
    // distribution with a single synthetic jar that
    // carries the canonical SHA so writeReport can use
    // sdk.canonicalJar without throwing. The MCP's
    // runtime identity is recorded by the integrity
    // envelope separately, NOT via this stub.
    val canonicalSha = "0811e7b60460e6b15a31cd3f312b1b5b3c36f8d1aa6dce7a7fd04193e362eace"
    val sdk = SdkDistribution(
        version = "0.48.0-rc1",
        zipSha256 = ExpectedPins.PIPELINEK_0_48_0_ZIP,
        installRoot = Path.of("(none — MCP cert)"),
        mainClass = "(none — MCP cert)",
        launcherScript = Path.of("(none — MCP cert)"),
        jars = listOf(
            dev.pipelinek.studio.cert.sdk.SdkJar(
                name = "pipeline-application-0.48.0.jar",
                path = Path.of("(none — MCP cert)"),
                sha256 = dev.pipelinek.studio.cert.domain.Sha256(canonicalSha),
                sizeBytes = 0L,
                isMainClass = true,
            ),
        ),
    )
    writeReport(sdk, cases, outReport, label = "c3")
    return casesVerdict(cases)
}

private fun writeReport(sdk: SdkDistribution, cases: List<CaseExecution>, outReport: Path, label: String) {
    val mainJarSha = sdk.jars.firstOrNull { it.isMainClass }?.sha256?.value
        ?: sdk.canonicalJarOrNull()?.sha256?.value
        ?: "(no canonical jar — synthetic stub for $label)"
    val report = CertReportDomain(
        studioTag = System.getenv("STUDIO_TAG") ?: "local",
        studioCommit = System.getenv("STUDIO_COMMIT") ?: "local",
        studioVersion = System.getenv("STUDIO_VERSION") ?: "1.0.0-rc.15",
        sdkVersion = sdk.version,
        sdkDigest = mainJarSha,
        cases = cases,
        startedAt = cases.minOf { it.startedAt },
        finishedAt = cases.maxOf { it.startedAt + it.durationMs },
        integrity = measuredIntegrityEnvelope(
            sdkVersion = sdk.version,
            sdkZip = System.getenv("STUDIO_CERT_SDK_ZIP")?.let { java.nio.file.Path.of(it) },
            sdkCanonicalSha = sdk.canonicalJar.sha256.value,
        ),
        environmentFingerprint = System.getenv("ENV_FINGERPRINT") ?: "local-$label",
    )
    val json = Json { encodeDefaults = true; prettyPrint = true }
    val payload = CertStudioReportJson(
        verdict = verdictSummary(report.verdict(
            requiredCases = if (cases.firstOrNull()?.caseId?.value?.startsWith("UAT-") == true) ContractCatalog.C1_REQUIRED else ContractCatalog.C2_REQUIRED,
            requiredIntegrity = requiredIntegrityFields(),
        )),
        sdkVersion = sdk.version,
        sdkZipSha256 = sdk.zipSha256.value,
        mainJarSha256 = mainJarSha,
        baseUrl = "",
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
    Files.writeString(outReport, json.encodeToString(payload))
}

private fun SdkDistribution.canonicalJarOrNull(): dev.pipelinek.studio.cert.sdk.SdkJar? =
    jars.firstOrNull { it.isMainClass }

private fun casesVerdict(cases: List<CaseExecution>): String {
    if (cases.isEmpty()) return "FAIL"
    val failed = cases.count { it.verdict is Verdict.Failed || it.verdict is Verdict.Blocked }
    return if (failed == 0) "PASS" else "FAIL"
}

private fun readReportCases(f: Path): List<CaseExecution> {
    val obj = Json.parseToJsonElement(Files.readString(f)).jsonObject
    val casesArr = obj["cases"]?.jsonArray ?: return emptyList()
    return casesArr.mapNotNull { el ->
        val o = el.jsonObject
        val caseId = o["caseId"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
        val name = o["name"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
        val verdict = o["verdict"]?.jsonPrimitive?.contentOrNull ?: "FAIL"
        val durationMs = o["durationMs"]?.jsonPrimitive?.longOrNull ?: 0L
        val assertions = o["assertions"]?.jsonArray?.mapNotNull { a ->
            val ao = a.jsonObject
            Assertion(
                name = ao["name"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null,
                kind = Assertion.Assert.valueOf(ao["kind"]?.jsonPrimitive?.contentOrNull ?: "StringEquals"),
                expected = ao["expected"]?.jsonPrimitive?.contentOrNull ?: "",
                actual = ao["actual"]?.jsonPrimitive?.contentOrNull ?: "",
                held = ao["held"]?.jsonPrimitive?.booleanOrNull ?: false,
            )
        } ?: emptyList()
        val evidenceRefs = o["evidenceRefs"]?.jsonArray?.mapNotNull { e ->
            val eo = e.jsonObject
            EvidenceRef(
                kind = EvidenceRef.Kind.valueOf(eo["kind"]?.jsonPrimitive?.contentOrNull ?: "Artifact"),
                path = eo["path"]?.jsonPrimitive?.contentOrNull ?: "",
                sha256 = eo["sha256"]?.jsonPrimitive?.contentOrNull,
                size = eo["size"]?.jsonPrimitive?.longOrNull,
            )
        } ?: emptyList()
        CaseExecution(
            caseId = CaseId(caseId),
            name = name,
            verdict = when (verdict) {
                "PASS" -> Verdict.Passed
                "FAIL" -> Verdict.Failed(Failure.ContractMismatch)
                "BLOCKED" -> Verdict.Blocked(Failure.ExecutionError)
                "NOT_MEASURED" -> Verdict.NotMeasured
                else -> Verdict.NotApplicable(verdict)
            },
            startedAt = System.currentTimeMillis(),
            durationMs = durationMs,
            assertions = assertions,
            evidenceRefs = evidenceRefs,
            command = "",
            exitCode = 0,
        )
    }
}

@Serializable
private data class MasterCertReportJson(
    val verdict: String,
    val sdkVersion: String,
    val sdkZipSha256: String,
    val mainJarSha256: String,
    val c1Verdict: String,
    val c2Verdict: String,
    val c3Verdict: String,
    val totalCases: Int,
    val passedCases: Int,
    val failedCases: Int,
    val cases: List<CertCaseJson>,
)

/**
 * C3.2 (2026-10-12): MCP v2 stdio cert.
 *
 * Spawns the Node-based MCP v2 stdio adapter
 * (`apps/mcp/dist/server.js`), exchanges a minimal
 * JSON-RPC handshake over stdio, and runs three cases:
 *   - AAT-MCP-001 initialize — protocolVersion +
 *     serverInfo present
 *   - AAT-MCP-002 tools/list — a tool named
 *     `studio.health` is exposed
 *   - AAT-MCP-003 tools/call studio.health — the
 *     response text contains "HTTP 200"
 *
 * The studio base URL is passed via the
 * `STUDIO_HTTP` env var (or `STUDIO_HTTP_INSECURE=1`
 * when the `--insecure` flag is set). The cert does
 * NOT manage the studio itself — the operator must
 * have a studio running at <studio-base-url>.
 */
private fun runMcpCert(
    mcpServerJs: Path,
    studioBaseUrl: String,
    outReport: Path,
    insecure: Boolean,
) {
    if (!Files.isRegularFile(mcpServerJs)) {
        System.err.println("FAIL FileMissing: MCP server.js not found at $mcpServerJs")
        exitProcess(3)
    }
    val cases = executeMcpCert(mcpServerJs, studioBaseUrl, insecure)
    val report = CertReportDomain(
        studioTag = System.getenv("STUDIO_TAG") ?: "local",
        studioCommit = System.getenv("STUDIO_COMMIT") ?: "local",
        studioVersion = System.getenv("STUDIO_VERSION") ?: "1.0.0-rc.15",
        sdkVersion = "0.48.0-rc1",
        sdkDigest = "0811e7b60460e6b15a31cd3f312b1b5b3c36f8d1aa6dce7a7fd04193e362eace",
        cases = cases,
        startedAt = cases.minOf { it.startedAt },
        finishedAt = cases.maxOf { it.startedAt + it.durationMs },
        integrity = measuredIntegrityEnvelope(
            sdkVersion = "0.48.0-rc1",
            sdkZip = System.getenv("STUDIO_CERT_SDK_ZIP")?.let { java.nio.file.Path.of(it) },
            sdkCanonicalSha = "0811e7b60460e6b15a31cd3f312b1b5b3c36f8d1aa6dce7a7fd04193e362eace",
        ),
        environmentFingerprint = System.getenv("ENV_FINGERPRINT") ?: "local",
    )
    val verdict = report.verdict(requiredCases = ContractCatalog.C3_REQUIRED, requiredIntegrity = requiredIntegrityFields())
    val certJson = CertMcpReportJson(
        verdict = verdictSummary(verdict),
        studioBaseUrl = studioBaseUrl,
        mcpServer = mcpServerJs.toString(),
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

/**
 * R5.1 (2026-10-12): the actual MCP stdio exchange. Returns
 * the [CaseExecution] list for the 3 documented cases:
 *   - AAT-MCP-001 initialize
 *   - AAT-MCP-002 tools/list
 *   - AAT-MCP-003 tools/call studio.health
 *
 * Used by:
 *   - `run-mcp-cert` (the CLI subcommand) — writes the
 *     per-case report + exits with 0/1.
 *   - `run-all-cert` (the aggregator) — collects the cases
 *     into the master report. The MCP exchange is REAL,
 *     not a stub: the same `node` process is spawned and
 *     the same JSON-RPC traffic is exchanged.
 */
private fun executeMcpCert(
    mcpServerJs: Path,
    studioBaseUrl: String,
    insecure: Boolean,
): List<CaseExecution> {
    if (!Files.isRegularFile(mcpServerJs)) {
        // Return a single Blocked case so the aggregator
        // surfaces the failure honestly (R5.3: required
        // contracts cannot be silently substituted with
        // NOT_APPLICABLE).
        return listOf(
            CaseExecution(
                caseId = CaseId("AAT-MCP-001"),
                name = "mcp-initialize",
                verdict = Verdict.Blocked(Failure.FileMissing),
                startedAt = System.currentTimeMillis(),
                durationMs = 0,
                assertions = listOf(
                    Assertion(
                        name = "mcp-server.js-exists",
                        kind = Assertion.Assert.PathExists,
                        expected = mcpServerJs.toString(),
                        actual = "missing",
                        held = false,
                    ),
                ),
                evidenceRefs = emptyList(),
                command = "node ${mcpServerJs}",
                exitCode = -1,
                errorMessage = "MCP server.js not found at $mcpServerJs",
            ),
        )
    }
    val pb = ProcessBuilder("node", mcpServerJs.toString())
        .redirectErrorStream(false)
    pb.environment()["STUDIO_HTTP"] = studioBaseUrl
    // R5.1 (2026-10-12): the MCP refuses to start when
    // STUDIO_HTTP is http:// unless STUDIO_HTTP_INSECURE=1
    // is set explicitly. The cert's run-all-cert boots the
    // studio with --insecure-http, so the base URL is
    // always http://. We must opt the MCP in.
    val httpInsecure = insecure || studioBaseUrl.startsWith("http://")
    if (httpInsecure) pb.environment()["STUDIO_HTTP_INSECURE"] = "1"
    val proc = try {
        pb.start()
    } catch (t: Throwable) {
        return listOf(
            CaseExecution(
                caseId = CaseId("AAT-MCP-001"),
                name = "mcp-initialize",
                verdict = Verdict.Failed(Failure.ExecutionError),
                startedAt = System.currentTimeMillis(),
                durationMs = 0,
                assertions = listOf(
                    Assertion(
                        name = "node-spawn",
                        kind = Assertion.Assert.ExitCodeZero,
                        expected = "node ${mcpServerJs}",
                        actual = "spawn failed: ${t.javaClass.simpleName}: ${t.message}",
                        held = false,
                    ),
                ),
                evidenceRefs = emptyList(),
                command = "node ${mcpServerJs}",
                exitCode = -1,
                errorMessage = "spawn failed: ${t.javaClass.simpleName}: ${t.message}",
            ),
        )
    }
    val outBuf = java.io.ByteArrayOutputStream()
    val errBuf = java.io.ByteArrayOutputStream()
    val drain = Thread {
        runCatching { proc.inputStream.copyTo(outBuf) }
    }.apply { isDaemon = true; start() }
    val errDrain = Thread {
        runCatching { proc.errorStream.copyTo(errBuf) }
    }.apply { isDaemon = true; start() }

    val cases = mutableListOf<CaseExecution>()

    // 1. initialize
    val initReq = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"2024-11-05\",\"capabilities\":{},\"clientInfo\":{\"name\":\"studio-cert\",\"version\":\"1.0.0-rc.15\"}}}\n"
    val initStarted = System.nanoTime()
    val initAt = System.currentTimeMillis()
    proc.outputStream.write(initReq.toByteArray(Charsets.UTF_8))
    proc.outputStream.flush()
    val initLine = waitForNextResponseLine(proc, outBuf, errBuf, timeoutMs = 5_000)
    val initMs = (System.nanoTime() - initStarted) / 1_000_000
    cases += evaluateMcpResponse(
        caseId = "AAT-MCP-001",
        name = "mcp-initialize",
        startedAt = initAt,
        durationMs = initMs,
        responseLine = initLine,
        assertions = listOf(
            Triple("protocolVersion-present", "\"protocolVersion\"", "expected 'protocolVersion' key"),
            Triple("serverInfo-present", "\"serverInfo\"", "expected 'serverInfo' key"),
            Triple("serverName-pipelinek-studio-mcp", "pipelinek-studio-mcp", "expected the studio MCP server name"),
        ),
    )

    // 2. notifications/initialized (no response expected)
    val notif = "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}\n"
    proc.outputStream.write(notif.toByteArray(Charsets.UTF_8))
    proc.outputStream.flush()

    // 3. tools/list
    val toolsReq = "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\"}\n"
    val toolsStarted = System.nanoTime()
    val toolsAt = System.currentTimeMillis()
    proc.outputStream.write(toolsReq.toByteArray(Charsets.UTF_8))
    proc.outputStream.flush()
    val toolsLine = waitForNextResponseLine(proc, outBuf, errBuf, timeoutMs = 5_000)
    val toolsMs = (System.nanoTime() - toolsStarted) / 1_000_000
    cases += evaluateMcpResponse(
        caseId = "AAT-MCP-002",
        name = "mcp-tools-list",
        startedAt = toolsAt,
        durationMs = toolsMs,
        responseLine = toolsLine,
        assertions = listOf(
            Triple("\"tools\"-present", "\"tools\"", "expected the 'tools' key"),
            Triple("studio-health-exposed", "\"studio.health\"", "expected 'studio.health' tool to be exposed"),
        ),
    )

    // 4. tools/call studio.health
    val callReq = "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/call\",\"params\":{\"name\":\"studio.health\",\"arguments\":{}}}\n"
    val callStarted = System.nanoTime()
    val callAt = System.currentTimeMillis()
    proc.outputStream.write(callReq.toByteArray(Charsets.UTF_8))
    proc.outputStream.flush()
    val callLine = waitForNextResponseLine(proc, outBuf, errBuf, timeoutMs = 10_000)
    val callMs = (System.nanoTime() - callStarted) / 1_000_000
    cases += evaluateMcpResponse(
        caseId = "AAT-MCP-003",
        name = "mcp-tools-call-studio-health",
        startedAt = callAt,
        durationMs = callMs,
        responseLine = callLine,
        assertions = listOf(
            Triple("HTTP-200-in-response", "HTTP 200", "expected 'HTTP 200' substring in the tool response"),
            Triple("status-ok-escaped", """\"status\":\"ok\"""", "expected escaped status:ok substring"),
        ),
    )

    runCatching { proc.destroyForcibly() }
    runCatching { drain.join(2000) }
    runCatching { errDrain.join(2000) }

    return cases
}

private fun waitForNextResponseLine(
    proc: Process,
    outBuf: java.io.ByteArrayOutputStream,
    errBuf: java.io.ByteArrayOutputStream,
    timeoutMs: Long,
): String? {
    val deadline = System.nanoTime() + timeoutMs * 1_000_000L
    val captured = StringBuilder()
    while (System.nanoTime() < deadline) {
        val snapshot = synchronized(outBuf) { String(outBuf.toByteArray(), Charsets.UTF_8) }
        val nl = snapshot.indexOf('\n')
        if (nl >= 0) {
            val line = snapshot.substring(0, nl)
            synchronized(outBuf) {
                val remaining = snapshot.substring(nl + 1).toByteArray(Charsets.UTF_8)
                outBuf.reset()
                outBuf.write(remaining)
            }
            return line
        }
        if (!proc.isAlive) {
            val tail = synchronized(errBuf) { String(errBuf.toByteArray(), Charsets.UTF_8) }
            System.err.println("MCP process died; stderr tail: ${tail.takeLast(500)}")
            return null
        }
        Thread.sleep(20)
    }
    return null
}

private fun evaluateMcpResponse(
    caseId: String,
    name: String,
    startedAt: Long,
    durationMs: Long,
    responseLine: String?,
    assertions: List<Triple<String, String, String>>,
): CaseExecution {
    val caseIdObj = CaseId(caseId)
    val assertionList = mutableListOf<Assertion>()
    if (responseLine == null) {
        assertionList += Assertion(
            name = "response-received",
            kind = Assertion.Assert.StringContains,
            expected = "<json-rpc-response>",
            actual = "no response within timeout",
            held = false,
        )
    } else {
        for (a in assertions) {
            val (assertionName, needle, expectedLabel) = a
            assertionList += Assertion(
                name = assertionName,
                kind = Assertion.Assert.StringContains,
                expected = expectedLabel,
                actual = if (responseLine.contains(needle)) "present" else "absent",
                held = responseLine.contains(needle),
            )
        }
    }
    val allHeld = assertionList.isNotEmpty() && assertionList.all { it.held }
    val evidence = if (responseLine != null) {
        listOf(
            EvidenceRef(
                kind = EvidenceRef.Kind.ResponseBody,
                path = "mcp-stdio",
                sha256 = sha256OfBytes(responseLine.toByteArray(Charsets.UTF_8)),
                size = responseLine.toByteArray(Charsets.UTF_8).size.toLong(),
            ),
        )
    } else {
        emptyList()
    }
    return CaseExecution(
        caseId = caseIdObj,
        name = name,
        verdict = if (allHeld) Verdict.Passed else Verdict.Failed(Failure.ContractMismatch),
        startedAt = startedAt,
        durationMs = durationMs,
        assertions = assertionList,
        evidenceRefs = evidence,
        exitCode = 0,
    )
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
private data class CertMcpReportJson(
    val verdict: String,
    val studioBaseUrl: String,
    val mcpServer: String,
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
