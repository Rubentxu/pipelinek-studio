package dev.pipelinek.studio.http

import dev.pipelinek.studio.application.ports.*
import dev.pipelinek.studio.application.usecases.CreateExperimentUseCase
import dev.pipelinek.studio.application.usecases.buildWiring
import dev.pipelinek.studio.domain.*
import dev.pipelinek.studio.infra.storage.StudioDatabase
import dev.pipelinek.studio.infra.storage.StudioMetadataRepository
import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsText
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * AAT-SEC-007: secret canaries do not appear in API/CLI/MCP/logs/SSE/
 * error/exportable files. The HTTP layer must scrub known secret
 * patterns on the snapshot seal path so a snapshot that contains a
 * secret never has the secret on disk.
 *
 * Two tests, two guarantees:
 *  - [SnapshotRedactionTest.snapshot seal response does not echo the secret in any field]
 *    is the original check (the response never echoes the token). On its own
 *    it is NOT discriminating: it passes with the redactor bypassed.
 *  - [SnapshotRedactionTest.snapshot intake persists only the redacted source and hashes those bytes]
 *    is the discriminating regression: it seals through the real route into a
 *    real SQLite metadata store and asserts the persisted bytes and the
 *    recorded/returned SHA-256 are the REDACTED source. Removing the redaction
 *    turns it red (verified by mutation).
 */
class SnapshotRedactionTest {

    /** Canary: matches `PatternSecretRedactor`'s AWS access-key pattern. */
    private val awsSecret = "AKIAIOSFODNN7EXAMPLE"

    /**
     * Minimum wiring that satisfies [StudioHttpModule]: every port the
     * snapshot seal path does not use is a refusing or inert stub, so a test
     * that only seals a snapshot can never accidentally depend on a run
     * actually executing.
     */
    private fun noopWiring(metadata: StudioMetadataPort) = buildWiring(
        metadata = metadata,
        runner = object : PipelineRunnerPort {
            override fun submit(req: RunRequest) =
                SubmissionResult.Admitted("stub", Path.of("build", "operational-redaction", "never-submitted"))
            override fun cancel(runId: RunId) = CancellationResult.Unknown("stub")
            override fun status(runId: RunId) = RunHandleStatus.Unknown("stub")
        },
        read = RefusingRunReadPort,
        output = RefusingOutputReadPort,
        idempotency = object : IdempotencyPort {
            override fun lookup(scope: String, key: String, requestDigestSha256: String) =
                IdempotencyLookupResult.NotFound
            override fun record(scope: String, key: String, operationId: OperationId, requestDigestSha256: String, responseRef: String?,) =
                IdempotencyRecordResult.Recorded
        },
        runtime = object : PipelineKRuntimePort {
            override fun describe() = RuntimeDescriptorResult.Unavailable
        },
    )

    @Test
    fun `snapshot seal response does not echo the secret in any field`() = testApplication {
        // Wiring that has a real SealSnapshotUseCase; the use case calls
        // metadata to persist. We use a noop metadata so the test focuses
        // on the HTTP path: the redactor runs BEFORE the use case, so we
        // just need to confirm the route returns 200 and the response
        // body has no secret.
        val noopRunner = object : PipelineRunnerPort {
            override fun submit(req: RunRequest) = SubmissionResult.Admitted("u", java.nio.file.Path.of("/tmp/c"))
            override fun cancel(runId: RunId) = CancellationResult.Unknown("stub")
            override fun status(runId: RunId) = RunHandleStatus.Unknown("stub")
        }
        val noopRead = object : RunReadPort {
            override fun events(runId: RunId, after: EventCursor?, limit: Int) = RunReadResult.Unknown("stub")
            override fun state(runId: RunId) = RunReadResult.Unknown("stub")
        }
        val noopOutput = object : OutputReadPort {
            override fun read(runId: RunId, cursor: OutputCursor?, bytes: Int) =
                OutputReadResult.UnknownStream("stub")
        }
        val noopIdem = object : IdempotencyPort {
            override fun lookup(scope: String, key: String, requestDigestSha256: String) =
                IdempotencyLookupResult.NotFound
            override fun record(scope: String, key: String, operationId: OperationId, requestDigestSha256: String, responseRef: String?,) =
                IdempotencyRecordResult.Recorded
        }
        val noopRuntime = object : PipelineKRuntimePort {
            override fun describe() = RuntimeDescriptorResult.Unavailable
        }
        val metadata = object : StudioMetadataPort {
            override fun findExperiment(id: ExperimentId) = null
            override fun findSnapshot(id: SnapshotId) = null
            override fun findScenario(id: ScenarioId) = null
            override fun findRun(id: RunId) = null
            override fun listRuns(state: RunState?, limit: Int, offset: Int): List<RunBindingSnapshot> = emptyList()
            override fun saveSnapshot(snap: dev.pipelinek.studio.domain.SourceSnapshot) { /* noop */ }
            override fun loadSnapshotSource(id: dev.pipelinek.studio.domain.SnapshotId): ByteArray? = null
            override fun saveExperiment(experiment: dev.pipelinek.studio.domain.Experiment) { /* noop */ }
            override fun persistRunBinding(binding: dev.pipelinek.studio.domain.RunBinding) { /* noop */ }
            override fun transitionRunState(studioRunId: dev.pipelinek.studio.domain.RunId, newState: dev.pipelinek.studio.domain.RunState) { /* noop */ }
            override fun listScenarios(limit: Int, offset: Int): List<dev.pipelinek.studio.application.ports.ScenarioSnapshot> = emptyList()
            override fun saveScenario(scenario: dev.pipelinek.studio.domain.SourceScenario) { /* noop */ }
        }
        application {
            StudioHttpModule(
                { dev.pipelinek.studio.application.usecases.Wiring(
                    metadata, noopRunner, noopRead, noopOutput, noopIdem, noopRuntime,
                ) },
                runReadFactory = null,
            ).install(this)
        }
        val r = hostClient().post("/experiments/00000000-0000-0000-0000-000000000000/snapshots") {
            contentType(ContentType.Application.Json)
            setBody("""{"source":"val key = \"$awsSecret\"","creator":"test"}""")
        }
        // The route must NOT echo the secret in any response field.
        val body = r.bodyAsText()
        assertFalse(body.contains(awsSecret), "secret leaked in response: $body")
    }

    /**
     * AAT-SEC-007 discriminating regression.
     *
     * The production contract (`StudioHttpModule`: `POST
     * /experiments/{experimentId}/snapshots`) is: redact the source on
     * intake, then hash those bytes, then persist those same bytes. This test
     * seals through the real route into a real [StudioDatabase] and asserts
     * the three observable consequences of that order:
     *
     *  1. the persisted `snapshots.source_utf8` BLOB is the redacted source,
     *     never the raw request bytes;
     *  2. the SHA-256 recorded on the row and returned on the wire is the
     *     digest of the redacted bytes, not of the raw request;
     *  3. the raw token never reaches the persisted storage files.
     *
     * Scratch state lives under the module's ignored `build/` tree and is
     * removed by the test.
     */
    @Test
    fun `snapshot intake persists only the redacted source and hashes those bytes`() {
        val scratch = Path.of("build", "operational-redaction", "snapshot-intake-${UUID.randomUUID()}")
        Files.createDirectories(scratch)
        try {
            // One line so the JSON request body stays readable in the raw string below.
            val rawSource = "val key = \"$awsSecret\"; val stage = \"prod\""
            var sealedSnapshotId: SnapshotId? = null

            StudioDatabase(scratch.resolve("studio.db")).use { db ->
                val metadata = StudioMetadataRepository(db)
                val experiment = CreateExperimentUseCase(metadata).execute("redaction-intake")
                val wiring = noopWiring(metadata)
                testApplication {
                    application { StudioHttpModule({ wiring }, runReadFactory = null).install(this) }
                    val response = hostClient().post("/experiments/${experiment.id.value}/snapshots") {
                        contentType(ContentType.Application.Json)
                        setBody("""{"source":"val key = \"$awsSecret\"; val stage = \"prod\"","creator":"test"}""")
                    }
                    assertEquals(HttpStatusCode.OK, response.status)
                    val body = response.bodyAsText()
                    assertFalse(body.contains(awsSecret), "secret leaked in response: $body")
                    val fields = Json.parseToJsonElement(body).jsonObject
                    val snapshotId = SnapshotId(fields.getValue("snapshot_id").jsonPrimitive.content)
                    val responseDigest = fields.getValue("sha256").jsonPrimitive.content

                    // (1) persisted bytes are the redacted source, not the raw request.
                    val persisted = metadata.loadSnapshotSource(snapshotId)
                    assertTrue(persisted != null, "sealed snapshot has no persisted source bytes")
                    val persistedText = persisted.toString(Charsets.UTF_8)
                    assertFalse(
                        persistedText.contains(awsSecret),
                        "raw secret persisted in snapshots.source_utf8: $persistedText",
                    )
                    assertFalse(
                        persisted.contentEquals(rawSource.toByteArray(Charsets.UTF_8)),
                        "persisted bytes are the raw request source, so intake never redacted them",
                    )
                    assertEquals(
                        PatternSecretRedactor().redact(rawSource),
                        persistedText,
                        "persisted bytes are not the redacted source",
                    )

                    // (2) the digest is over the redacted bytes, so the stored
                    //     bytes and the recorded digest cannot drift apart.
                    val storedDigest = metadata.findSnapshot(snapshotId)?.sha256
                    assertEquals(Sha256.digest(persisted).value, storedDigest, "stored sha256 is not the digest of the stored bytes")
                    assertNotEquals(
                        Sha256.digest(rawSource.toByteArray(Charsets.UTF_8)).value,
                        storedDigest,
                        "stored sha256 is the digest of the RAW source: the hash was taken before redaction",
                    )
                    assertEquals(storedDigest, responseDigest, "response sha256 disagrees with the persisted row")

                    sealedSnapshotId = snapshotId
                }
            }

            // (3) the raw token never appears in the persisted storage files.
            assertTrue(sealedSnapshotId != null, "snapshot was not sealed")
            assertOnlyRedactedSourceOnDisk(scratch, awsSecret, PatternSecretRedactor().redact(rawSource))
        } finally {
            scratch.toFile().deleteRecursively()
        }
    }

    /**
     * The on-disk scan carries its own positive control: the redacted source
     * must be findable in the persisted storage files. Without that, an
     * "absent token" assertion would also pass on a directory that holds no
     * snapshot row at all.
     */
    private fun assertOnlyRedactedSourceOnDisk(dir: Path, rawToken: String, redactedSource: String) {
        Files.list(dir).use { stream ->
            val stored = stream.filter { Files.isRegularFile(it) }
                .map { it to Files.readAllBytes(it).toString(Charsets.ISO_8859_1) }
                .toList()
            assertTrue(stored.isNotEmpty(), "no persisted studio.db files found under $dir")
            assertTrue(
                stored.any { (_, text) -> text.contains(redactedSource) },
                "the redacted snapshot source is not on disk (${stored.size} files); the storage scan is not looking at persisted snapshot bytes",
            )
            stored.forEach { (file, text) ->
                assertFalse(
                    text.contains(rawToken),
                    "raw secret bytes found in persisted storage file ${file.fileName}",
                )
            }
        }
    }
}
