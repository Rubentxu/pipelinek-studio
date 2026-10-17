package dev.pipelinek.studio.http

import dev.pipelinek.studio.application.ports.*
import dev.pipelinek.studio.application.usecases.CreateExperimentUseCase
import dev.pipelinek.studio.application.usecases.SealSnapshotUseCase
import dev.pipelinek.studio.application.usecases.Wiring
import dev.pipelinek.studio.application.usecases.buildWiring
import dev.pipelinek.studio.domain.ExperimentId
import dev.pipelinek.studio.domain.OperationId
import dev.pipelinek.studio.domain.RunId
import dev.pipelinek.studio.domain.RunState
import dev.pipelinek.studio.domain.ScenarioId
import dev.pipelinek.studio.domain.SourceSnapshot
import dev.pipelinek.studio.domain.SnapshotId
import dev.pipelinek.studio.domain.SourceScenario
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
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * R0.6.1 (2026-10-17): behaviour tests for the two routes that the
 * OpenAPI / SDK declared but the Ktor module was missing. Wiring them
 * was the only safe way to honour the existing contract.
 *
 *   - `GET /` returns the documented root-discovery body.
 *   - `GET /snapshots/{snapshotId}` returns 200 + the seal-time shape
 *     for a real sealed snapshot and 404 + SNAPSHOT_NOT_FOUND for an
 *     unknown id, with 400 for a malformed id.
 *
 * The parity test ([OpenApiDocPathParityTest]) already catches the
 * "declared in contract but not wired" drift at the source level;
 * these tests pin the wire-level shape so a future refactor that
 * re-shapes the JSON breaks here, not in production.
 */
class R0_6_1RoutesTest {

    @Test
    fun `root discovery returns 200 with documented shape`() = testApplication {
        application { StudioHttpModule(stubWiringFactory(), runReadFactory = null).install(this) }
        val r = hostClient().get("/")
        assertEquals(HttpStatusCode.OK, r.status)
        val body = r.bodyAsText()
        val root = Json.parseToJsonElement(body).jsonObject
        for (field in listOf("service", "health_path", "version_path", "openapi_path")) {
            assertTrue(
                field in root,
                "expected field '$field' in /  response, got: $body",
            )
        }
        assertEquals("pipelinek-studio", root.getValue("service").jsonPrimitive.content)
        // The advertised paths must be reachable from the same module
        // (caller can chain `getRoot` -> `getHealth` without a separate
        // OpenAPI round-trip).
        assertEquals("/health", root.getValue("health_path").jsonPrimitive.content)
        assertEquals("/api/version", root.getValue("version_path").jsonPrimitive.content)
        assertEquals("/openapi.json", root.getValue("openapi_path").jsonPrimitive.content)
    }

    @Test
    fun `snapshot lookup returns 200 with seal shape for a real snapshot`() {
        val scratch = Path.of("build", "operational-r061", "snapshot-lookup-${UUID.randomUUID()}")
        Files.createDirectories(scratch)
        try {
            StudioDatabase(scratch.resolve("studio.db")).use { db ->
                val metadata = StudioMetadataRepository(db)
                val experiment = CreateExperimentUseCase(metadata).execute("r0-6-1")
                val sealedId = SealSnapshotUseCase(metadata).execute(
                    experimentId = experiment.id,
                    sourceUtf8 = "val r0_6_1 = true".toByteArray(Charsets.UTF_8),
                    creator = "test",
                ).id
                val wiring = noopWiring(metadata)
                testApplication {
                    application { StudioHttpModule({ wiring }, runReadFactory = null).install(this) }
                    val r = hostClient().get("/snapshots/${sealedId.value}")
                    assertEquals(HttpStatusCode.OK, r.status)
                    val body = r.bodyAsText()
                    val fields = Json.parseToJsonElement(body).jsonObject
                    assertEquals(
                        sealedId.value,
                        fields.getValue("snapshot_id").jsonPrimitive.content,
                    )
                    assertEquals(
                        experiment.id.value,
                        fields.getValue("experiment_id").jsonPrimitive.content,
                    )
                    // The sealed source is "val r0_6_1 = true" so the
                    // SHA-256 is the digest of those exact bytes.
                    val expectedSha = dev.pipelinek.studio.domain.Sha256
                        .digest("val r0_6_1 = true".toByteArray(Charsets.UTF_8))
                        .value
                    assertEquals(
                        expectedSha,
                        fields.getValue("sha256").jsonPrimitive.content,
                    )
                    assertNotNull(
                        fields["created_at"],
                        "expected created_at in /snapshots/{id} response, got: $body",
                    )
                }
            }
        } finally {
            scratch.toFile().deleteRecursively()
        }
    }

    @Test
    fun `snapshot lookup returns 404 SNAPSHOT_NOT_FOUND for unknown id`() = testApplication {
        val wiring = noopWiring(noopMetadata())
        application { StudioHttpModule({ wiring }, runReadFactory = null).install(this) }
        val unknown = UUID.randomUUID()
        val r = hostClient().get("/snapshots/$unknown")
        assertEquals(HttpStatusCode.NotFound, r.status)
        val body = r.bodyAsText()
        assertTrue(
            body.contains("SNAPSHOT_NOT_FOUND"),
            "expected SNAPSHOT_NOT_FOUND in response, got: $body",
        )
    }

    @Test
    fun `snapshot lookup returns 400 for malformed id`() = testApplication {
        val wiring = noopWiring(noopMetadata())
        application { StudioHttpModule({ wiring }, runReadFactory = null).install(this) }
        val r = hostClient().get("/snapshots/not-a-uuid")
        assertEquals(HttpStatusCode.BadRequest, r.status)
        val body = r.bodyAsText()
        assertTrue(
            body.contains("BAD_REQUEST"),
            "expected BAD_REQUEST in response, got: $body",
        )
    }

    private fun stubWiringFactory(): () -> Wiring = {
        error("`GET /` does not touch the wiring; this factory should never be invoked")
    }

    /**
     * Minimum wiring that satisfies [StudioHttpModule] for routes that
     * only need the metadata port (no runner/read/output/idempotency).
     * Mirrors the pattern in `SnapshotRedactionTest`.
     */
    private fun noopWiring(metadata: StudioMetadataPort): Wiring = buildWiring(
        metadata = metadata,
        runner = object : PipelineRunnerPort {
            override fun submit(req: RunRequest) = SubmissionResult.Admitted(
                "stub", Path.of("build", "operational-r061", "never-submitted"),
            )
            override fun cancel(runId: RunId) = CancellationResult.Unknown("stub")
            override fun status(runId: RunId) = RunHandleStatus.Unknown("stub")
        },
        read = RefusingRunReadPort,
        output = RefusingOutputReadPort,
        idempotency = object : IdempotencyPort {
            override fun lookup(scope: String, key: String, requestDigestSha256: String) =
                IdempotencyLookupResult.NotFound
            override fun record(
                scope: String, key: String, operationId: OperationId,
                requestDigestSha256: String, responseRef: String?,
            ) = IdempotencyRecordResult.Recorded
        },
        runtime = object : PipelineKRuntimePort {
            override fun describe() = RuntimeDescriptorResult.Unavailable
        },
    )

    /**
     * Noop metadata for the 404 / 400 paths. Returning `null` for
     * `findSnapshot` is the natural failure mode we want to exercise.
     */
    private fun noopMetadata(): StudioMetadataPort =
        object : StudioMetadataPort {
            override fun findExperiment(id: ExperimentId) = null
            override fun findSnapshot(id: SnapshotId) = null
            override fun findScenario(id: ScenarioId) = null
            override fun findRun(id: RunId) = null
            override fun listRuns(state: RunState?, limit: Int, offset: Int) =
                emptyList<RunBindingSnapshot>()
            override fun saveSnapshot(snap: SourceSnapshot) {}
            override fun loadSnapshotSource(id: SnapshotId): ByteArray? = null
            override fun saveExperiment(experiment: dev.pipelinek.studio.domain.Experiment) {}
            override fun persistRunBinding(binding: dev.pipelinek.studio.domain.RunBinding) {}
            override fun transitionRunState(studioRunId: RunId, newState: RunState) {}
            override fun listScenarios(limit: Int, offset: Int) =
                emptyList<ScenarioSnapshot>()
            override fun saveScenario(scenario: SourceScenario) {}
        }
}
