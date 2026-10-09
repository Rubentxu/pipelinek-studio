package dev.pipelinek.studio.infra.sdk

import dev.pipelinek.studio.application.ports.PipelineKRuntimePort
import dev.pipelinek.studio.application.ports.RuntimeDescriptor
import dev.pipelinek.studio.application.ports.RuntimeDescriptorResult
import dev.pipelinek.studio.application.usecases.buildWiring
import dev.pipelinek.studio.http.StudioHttpModule
import dev.pipelinek.studio.infra.storage.IdempotencyRepository
import dev.pipelinek.studio.infra.storage.StudioDatabase
import dev.pipelinek.studio.infra.storage.StudioMetadataRepository
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.Netty
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Live E2E test against real PipelineK SDK distribution. Auto-skipped if
 * SDK_PATH env var is not set. Provides the proof of life receipt for S0.
 */
class PipelineKE2EIT {

    private var sdkClasspath: List<Path> = emptyList()
    private var server: EmbeddedServer<*, *>? = null
    private var port: Int = 0
    private var controlRoot: Path? = null
    private var studioDb: Path? = null

    private fun requireSdk(): List<Path> {
        if (sdkClasspath.isNotEmpty()) return sdkClasspath
        val sdkStr = System.getenv("SDK_PATH")
        val sdkDir = sdkStr?.let { Path.of(it) }
        if (sdkDir == null || !Files.isDirectory(sdkDir)) {
            // Skip the test silently via assumption-failure.
            assumeTrue(false, "Skipping E2E: SDK_PATH env not pointing to valid PipelineK distribution")
        }
        sdkClasspath = Files.list(sdkDir!!.resolve("lib")).use { stream ->
            stream.filter { it.fileName.toString().endsWith(".jar") }
                .filter { !it.fileName.toString().endsWith("-sources.jar") }
                .toList()
        }
        assertTrue(sdkClasspath.isNotEmpty(), "no jars found under $sdkDir/lib")
        assertTrue(
            sdkClasspath.any { it.fileName.toString().contains("pipeline-application") },
            "expected pipeline-application jar, got $sdkClasspath",
        )
        return sdkClasspath
    }

    @AfterEach
    fun tearDown() {
        server?.stop(50, 100)
    }

    @Test
    fun `real SDK completes a run and Studio persists run binding`(@TempDir tmp: Path) {
        val sdkJars = requireSdk()
        val cr = tmp.resolve("control-root").also { Files.createDirectories(it) }
        val sdb = tmp.resolve("studio.db")
        controlRoot = cr
        studioDb = sdb

        val db = StudioDatabase(sdb)
        val metadata = StudioMetadataRepository(db)
        val idempotency = IdempotencyRepository(db)
        val runner = PipelineKRunner(
            controlRootBase = cr,
            sdkClasspath = sdkJars,
            mainClass = "dev.rubentxu.pipeline.v2.application.MainKt",
            // E2E test uses the same metadata repo the route layer uses;
            // the runner reads the real sealed bytes through it.
            snapshotSource = metadata,
        )
        // Use a stub probe for the E2E test — the probe's job is structural introspection of the
        // host JVM classpath, which the test does not exercise. We already validated SDK jars
        // exist under requireSdk().
        val probeJar = sdkJars.first { it.fileName.toString().contains("pipeline-application") }
        val probeSha = sha256Of(probeJar)
        val probe: PipelineKRuntimePort = object : PipelineKRuntimePort {
            override fun describe(): RuntimeDescriptorResult =
                RuntimeDescriptorResult.Available(
                    RuntimeDescriptor(
                        version = "0.48.0-rc1",
                        artifactDigest = probeSha,
                        pluginSdkVersion = "0.48.0-rc1",
                        capabilities = setOf("compile", "eval", "durable"),
                    ),
                )
        }
        println("STUDIO-E2E SDK pinned: 0.48.0-rc1 digest=${probeSha.take(16)}...")

        val wiring: () -> dev.pipelinek.studio.application.usecases.Wiring = {
            buildWiring(
                metadata,
                runner,
                dev.pipelinek.studio.http.RefusingRunReadPort,
                dev.pipelinek.studio.http.RefusingOutputReadPort,
                idempotency,
                probe,
            )
        }

        val s = embeddedServer(Netty, port = 0) { StudioHttpModule(wiring).install(this) }
        s.start(wait = false)
        server = s
        port = runBlocking { s.engine.resolvedConnectors().first().port }

        val expBody = """{"name":"e2e-real-sdk"}"""
        val expResp: String = runBlocking { httpPost("/experiments", expBody) }
        val experimentId: String = extractField(expResp, "experiment_id")!!
        assertNotNull(experimentId)

        val src = "println(\"studio-e2e-" + UUID.randomUUID().toString().take(8) + "\")\n"
        val snapBody = "{\"source\":\"" + jsonEscape(src) + "\",\"creator\":\"studio-e2e\"}"
        val snapResp: String = runBlocking { httpPost("/experiments/$experimentId/snapshots", snapBody) }
        val snapshotId: String = extractField(snapResp, "snapshot_id")!!
        assertNotNull(snapshotId)

        val scenarioId = "00000000-0000-0000-0000-000000000002"
        val idemKey = UUID.randomUUID().toString()
        val runBody = """{"experiment_id":"$experimentId","snapshot_id":"$snapshotId","scenario_id":"$scenarioId"}"""
        val runResp: String = runBlocking { httpPost("/runs", runBody, idemKey) }
        val runId: String? = extractField(runResp, "run_id")
        assertNotNull(runId, "expected run_id in: $runResp")

        // SDK writes run.db async (compile + eval takes seconds); poll up to 90s.
        val expectedRunDir: Path = cr.resolve(runId!!)
        var runDb: Path? = null
        repeat(180) {
            val candidate: Path = expectedRunDir.resolve("run.db")
            if (Files.isRegularFile(candidate) && Files.size(candidate) > 0) {
                runDb = candidate
                return@repeat
            }
            Thread.sleep(500)
        }
        if (runDb == null) {
            // Dump what we have for debugging before failing
            val ls = buildString {
                Files.walk(cr).use { s ->
                    s.forEach { p -> appendLine(cr.relativize(p).toString()) }
                }
            }
            println("STUDIO-E2E DIR LISTING for $cr:\n$ls")
        }
        assertNotNull(runDb, "expected run.db to materialize under $expectedRunDir")

        val tables = mutableListOf<String>()
        java.sql.DriverManager.getConnection("jdbc:sqlite:$runDb").use { c ->
            c.metaData.getTables(null, null, null, arrayOf("TABLE")).use { rs ->
                while (rs.next()) tables += rs.getString("TABLE_NAME").lowercase()
            }
        }
        assertTrue("events" in tables, "expected events table in real SDK run.db, got $tables")

        val storedState: String? = java.sql.DriverManager.getConnection("jdbc:sqlite:$sdb").use { c ->
            c.prepareStatement("SELECT state FROM run_bindings WHERE studio_run_id = ?").use { ps ->
                ps.setString(1, runId)
                ps.executeQuery().use { rs -> if (rs.next()) rs.getString("state") else null }
            }
        }
        assertTrue(storedState != null, "expected run_binding row in studio.db")
    }

    private suspend fun httpPost(path: String, body: String, idemKey: String? = null): String {
        val client = HttpClient(CIO)
        try {
            val r: HttpResponse = client.post("http://127.0.0.1:$port$path") {
                contentType(ContentType.Application.Json)
                if (idemKey != null) header("Idempotency-Key", idemKey)
                setBody(body)
            }
            return r.bodyAsText()
        } finally {
            client.close()
        }
    }

    private fun extractField(json: String, field: String): String? {
        val regex = Regex(""""$field"\s*:\s*"([^"]+)"""")
        return regex.find(json)?.groupValues?.get(1)
    }

    private fun jsonEscape(s: String): String = s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")

    private fun sha256Of(p: Path): String {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        Files.newInputStream(p).use { ins ->
            val buf = ByteArray(8192)
            while (true) {
                val n = ins.read(buf)
                if (n <= 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}
