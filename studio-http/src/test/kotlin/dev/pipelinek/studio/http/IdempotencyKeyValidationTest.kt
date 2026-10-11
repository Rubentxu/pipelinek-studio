package dev.pipelinek.studio.http

import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsText
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Audit H-17 (2026-10-10): the previous implementation of
 * POST /runs accepted any Idempotency-Key value without
 * validation. A multi-megabyte header would be parsed and the
 * resulting string stored in studio.db.idempotency, which has
 * no length cap. A single attacker could exhaust disk by
 * sending many oversized keys. The fix caps length to 128
 * chars and constrains charset to [A-Za-z0-9_-]+.
 */
class IdempotencyKeyValidationTest {

    private fun wiring(): () -> dev.pipelinek.studio.application.usecases.Wiring {
        val noopMetadata = object : dev.pipelinek.studio.application.ports.StudioMetadataPort {
            override fun findExperiment(id: dev.pipelinek.studio.domain.ExperimentId) = null
            override fun findSnapshot(id: dev.pipelinek.studio.domain.SnapshotId) = null
            override fun findScenario(id: dev.pipelinek.studio.domain.ScenarioId) = null
            override fun findRun(id: dev.pipelinek.studio.domain.RunId) = null
            override fun listRuns(state: dev.pipelinek.studio.domain.RunState?, limit: Int, offset: Int) =
                emptyList<dev.pipelinek.studio.application.ports.RunBindingSnapshot>()
            override fun saveSnapshot(snap: dev.pipelinek.studio.domain.SourceSnapshot) { /* noop */ }
            override fun loadSnapshotSource(id: dev.pipelinek.studio.domain.SnapshotId): ByteArray? = null
            override fun saveExperiment(experiment: dev.pipelinek.studio.domain.Experiment) { /* noop */ }
            override fun persistRunBinding(binding: dev.pipelinek.studio.domain.RunBinding) { /* noop */ }
        override fun listScenarios(limit: Int, offset: Int): List<dev.pipelinek.studio.application.ports.ScenarioSnapshot> = emptyList()
        override fun saveScenario(scenario: dev.pipelinek.studio.domain.SourceScenario) { /* noop */ }
        }
        val stubRunner = object : dev.pipelinek.studio.application.ports.PipelineRunnerPort {
            override fun submit(req: dev.pipelinek.studio.application.ports.RunRequest) =
                dev.pipelinek.studio.application.ports.SubmissionResult.Admitted("u", java.nio.file.Path.of("/tmp"))
            override fun cancel(runId: dev.pipelinek.studio.domain.RunId) =
                dev.pipelinek.studio.application.ports.CancellationResult.Unknown("stub")
            override fun status(runId: dev.pipelinek.studio.domain.RunId) =
                dev.pipelinek.studio.application.ports.RunHandleStatus.Unknown("stub")
        }
        val stubRead = object : dev.pipelinek.studio.application.ports.RunReadPort {
            override fun events(runId: dev.pipelinek.studio.domain.RunId, after: dev.pipelinek.studio.application.ports.EventCursor?, limit: Int) =
                dev.pipelinek.studio.application.ports.RunReadResult.Unknown("stub")
            override fun state(runId: dev.pipelinek.studio.domain.RunId) =
                dev.pipelinek.studio.application.ports.RunReadResult.Unknown("stub")
        }
        val stubOutput = object : dev.pipelinek.studio.application.ports.OutputReadPort {
            override fun read(runId: dev.pipelinek.studio.domain.RunId, cursor: dev.pipelinek.studio.application.ports.OutputCursor?, bytes: Int) =
                dev.pipelinek.studio.application.ports.OutputReadResult.UnknownStream("stub")
        }
        val stubIdem = object : dev.pipelinek.studio.application.ports.IdempotencyPort {
            override fun lookup(scope: String, key: String, requestDigestSha256: String) =
                dev.pipelinek.studio.application.ports.IdempotencyLookupResult.NotFound
            override fun record(scope: String, key: String, operationId: dev.pipelinek.studio.domain.OperationId, requestDigestSha256: String, responseRef: String?) =
                dev.pipelinek.studio.application.ports.IdempotencyRecordResult.Recorded
        }
        val stubRuntime = object : dev.pipelinek.studio.application.ports.PipelineKRuntimePort {
            override fun describe() =
                dev.pipelinek.studio.application.ports.RuntimeDescriptorResult.Available(
                    dev.pipelinek.studio.application.ports.RuntimeDescriptor(
                        version = "0.48.0-rc1",
                        artifactDigest = "sha-pin",
                        pluginSdkVersion = "0.48.0-rc1",
                        capabilities = setOf("compile", "eval", "durable"),
                    ),
                )
        }
        return {
            dev.pipelinek.studio.application.usecases.Wiring(
                noopMetadata, stubRunner, stubRead, stubOutput, stubIdem, stubRuntime,
            )
        }
    }

    @Test
    fun `oversized Idempotency-Key is rejected with 400 IDEMPOTENCY_KEY_TOO_LONG`() = testApplication {
        application { StudioHttpModule(wiring(), runReadFactory = null).install(this) }
        val oversized = "a".repeat(129)
        val r = hostClient().post("/runs") {
            header("Idempotency-Key", oversized)
            contentType(ContentType.Application.Json)
            setBody("""{"experimentId":"e","snapshotId":"s","scenarioId":"c"}""")
        }
        assertEquals(HttpStatusCode.BadRequest, r.status)
        val body = r.bodyAsText()
        assertTrue(body.contains("IDEMPOTENCY_KEY_TOO_LONG"),
            "expected IDEMPOTENCY_KEY_TOO_LONG, got: $body")
    }

    @Test
    fun `Idempotency-Key with bad charset is rejected with 400 IDEMPOTENCY_KEY_INVALID_CHARSET`() = testApplication {
        application { StudioHttpModule(wiring(), runReadFactory = null).install(this) }
        val r = hostClient().post("/runs") {
            header("Idempotency-Key", "has spaces and !@#")
            contentType(ContentType.Application.Json)
            setBody("""{"experimentId":"e","snapshotId":"s","scenarioId":"c"}""")
        }
        assertEquals(HttpStatusCode.BadRequest, r.status)
        val body = r.bodyAsText()
        assertTrue(body.contains("IDEMPOTENCY_KEY_INVALID_CHARSET"),
            "expected IDEMPOTENCY_KEY_INVALID_CHARSET, got: $body")
    }

    @Test
    fun `Idempotency-Key at the 128-char boundary is accepted`() = testApplication {
        application { StudioHttpModule(wiring(), runReadFactory = null).install(this) }
        val boundary = "a".repeat(128)
        val r = hostClient().post("/runs") {
            header("Idempotency-Key", boundary)
            contentType(ContentType.Application.Json)
            setBody("""{"experimentId":"e","snapshotId":"s","scenarioId":"c"}""")
        }
        // The wiring returns a stub so the actual call is not
        // 400 MISSING_HEADER; the body is the real one that
        // passes the validation. We expect either 201 (created)
        // or 200 (success) depending on the route's responder,
        // but not 400 MISSING_HEADER or 400 IDEMPOTENCY_KEY_*.
        assertTrue(
            r.status.value !in setOf(400),
            "expected non-400 status at the 128-char boundary, got ${r.status}",
        )
    }
}
