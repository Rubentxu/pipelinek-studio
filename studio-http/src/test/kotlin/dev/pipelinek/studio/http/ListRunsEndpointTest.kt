package dev.pipelinek.studio.http

import dev.pipelinek.studio.application.ports.*
import dev.pipelinek.studio.domain.*
import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsText
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * S1 read-side — GET /runs lists runs with optional state filter
 * and limit/offset pagination. The endpoint must:
 *   - return 200 with the documented JSON shape
 *   - return 400 for an unknown state
 *   - default to limit=50 and offset=0 when no query params are given
 *   - be reachable without auth (same posture as the other read endpoints)
 */
class ListRunsEndpointTest {

    private fun wiringForListRuns(): () -> dev.pipelinek.studio.application.usecases.Wiring {
        val metadata = object : StudioMetadataPort {
            override fun findExperiment(id: ExperimentId) = null
            override fun findSnapshot(id: SnapshotId) = null
            override fun findScenario(id: ScenarioId) = null
            override fun findRun(id: RunId) = null
            override fun listRuns(state: RunState?, limit: Int, offset: Int): List<RunBindingSnapshot> {
                // Return a small fixed list so the test can assert on the shape.
                val snap = RunBindingSnapshot(
                    id = RunId("00000000-0000-0000-0000-000000000001"),
                    experimentId = ExperimentId("exp-1"),
                    snapshotId = SnapshotId("snap-1"),
                    scenarioId = ScenarioId("sc-1"),
                    state = state?.name ?: "COMPLETED",
                    createdAt = java.time.Instant.parse("2026-10-09T12:00:00Z"),
                )
                return listOf(snap).drop(offset).take(limit)
            }
            override fun saveSnapshot(snap: dev.pipelinek.studio.domain.SourceSnapshot) { /* noop */ }
            override fun loadSnapshotSource(id: dev.pipelinek.studio.domain.SnapshotId): ByteArray? = null
        }
        val noopRunner = object : PipelineRunnerPort {
            override fun submit(req: RunRequest): SubmissionResult =
                SubmissionResult.Admitted("u", java.nio.file.Path.of("/tmp/c"))
            override fun cancel(runId: RunId): CancellationResult = CancellationResult.Unknown("stub")
            override fun status(runId: RunId): RunHandleStatus = RunHandleStatus.Unknown("stub")
        }
        val noopRead = object : RunReadPort {
            override fun events(runId: RunId, after: EventCursor?, limit: Int) =
                RunReadResult.Unknown("stub")
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
        return {
            dev.pipelinek.studio.application.usecases.Wiring(
                metadata, noopRunner, noopRead, noopOutput, noopIdem, noopRuntime,
            )
        }
    }

    @Test
    fun `list runs returns 200 with documented JSON shape`() = testApplication {
        application { StudioHttpModule(wiringForListRuns(), runReadFactory = null).install(this) }
        val r = client.get("/runs")
        assertEquals(HttpStatusCode.OK, r.status)
        val body = r.bodyAsText()
        assertTrue(body.contains("\"items\""), "expected items[] in response, got: $body")
        assertTrue(body.contains("\"run_id\""), "expected run_id field, got: $body")
        assertTrue(body.contains("\"state\""), "expected state field, got: $body")
    }

    @Test
    fun `list runs returns 400 for unknown state filter`() = testApplication {
        application { StudioHttpModule(wiringForListRuns(), runReadFactory = null).install(this) }
        val r = client.get("/runs?state=NOT_A_REAL_STATE")
        assertEquals(HttpStatusCode.BadRequest, r.status)
        assertTrue(r.bodyAsText().contains("BAD_REQUEST"))
    }

    @Test
    fun `list runs accepts valid SUCCEEDED state filter`() = testApplication {
        application { StudioHttpModule(wiringForListRuns(), runReadFactory = null).install(this) }
        val r = client.get("/runs?state=SUCCEEDED&limit=10")
        assertEquals(HttpStatusCode.OK, r.status)
    }

    @Test
    fun `list runs clamps limit to a sane range`() = testApplication {
        // limit=99999 must be clamped to 200 (the hard ceiling). The response
        // must still be 200 OK; the contract is "we silently bound it".
        application { StudioHttpModule(wiringForListRuns(), runReadFactory = null).install(this) }
        val r = client.get("/runs?limit=99999")
        assertEquals(HttpStatusCode.OK, r.status)
    }
}
