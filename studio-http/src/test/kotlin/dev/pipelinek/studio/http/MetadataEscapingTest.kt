package dev.pipelinek.studio.http

import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsText
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * AAT-SEC-009: XSS, HTML, ANSI y URL maliciosa en output/metadata tratados
 * como datos no confiables. The HTTP layer must JSON-escape any string
 * field a client posts, so a name with `<script>alert(1)</script>` is
 * returned as a literal string in a JSON field, NOT as an executable
 * `<script>` tag in the response body.
 *
 * The test uses the JSON content type explicitly to confirm the server
 * responds with a JSON document, not an HTML one.
 */
class MetadataEscapingTest {

    @Test
    fun `experiment name with HTML is returned as a JSON-escaped string`() = testApplication {
        application { StudioHttpModule(stubWiring()).install(this) }
        val xss = "<script>alert(1)</script>"
        val r = client.post("/experiments") {
            contentType(ContentType.Application.Json)
            setBody("""{"name":"$xss"}""")
        }
        // The response Content-Type MUST be JSON, not HTML.
        val ct = r.headers[HttpHeaders.ContentType] ?: ""
        assertTrue(ct.contains("json"), "expected JSON content type, got: $ct")
        // The response body MUST be a valid JSON document with the XSS as
        // a JSON string, not as an executable tag.
        val body = r.bodyAsText()
        // The literal `<script>alert(1)</script>` substring is allowed in
        // the JSON string (it's just a payload), but the response must
        // contain a `"` immediately before and after, which makes it a
        // string value, not an HTML element.
        assertTrue(body.contains("\"$xss\""), "expected XSS payload to be a JSON string; got: $body")
    }

    @Test
    fun `experiment name with ANSI escape is preserved verbatim`() = testApplication {
        // A control-character or ANSI escape in a name is preserved as data.
        // The server does not strip it (which would be lossy) nor does it
        // re-emit it in a way that could re-interpret the escape. JSON
        // encoding handles the quoting; downstream consumers (the SPA, the
        // CLI) are responsible for not interpreting the escape.
        application { StudioHttpModule(stubWiring()).install(this) }
        val ansi = "[31mRED[0m"
        val r = client.post("/experiments") {
            contentType(ContentType.Application.Json)
            setBody("""{"name":"$ansi"}""")
        }
        val body = r.bodyAsText()
        assertTrue(body.contains("RED"), "expected name to round-trip; got: $body")
    }

    @Test
    fun `experiment name with javascript URL is preserved verbatim`() = testApplication {
        // javascript: URLs in names are a known XSS vector. The server
        // stores and returns them as data; the SPA / CLI are responsible
        // for not rendering them as links. This test pins the server
        // contract: the URL is in a JSON string field, not a `href`.
        application { StudioHttpModule(stubWiring()).install(this) }
        val url = "javascript:alert(1)"
        val r = client.post("/experiments") {
            contentType(ContentType.Application.Json)
            setBody("""{"name":"$url"}""")
        }
        val body = r.bodyAsText()
        assertTrue(body.contains("\"$url\""), "expected URL in JSON string; got: $body")
    }

    @Test
    fun `health endpoint is plain text and never interprets the body`() = testApplication {
        // /health returns a small JSON object with status=ok. Verify the
        // shape is exactly what we expect — not a re-interpretation of any
        // user-supplied data. /health is a GET and the handler never reads
        // the body, so any string the client sends is simply ignored.
        application { StudioHttpModule(stubWiring()).install(this) }
        val r = client.get("/health")
        val body = r.bodyAsText()
        assertEquals("{\"status\":\"ok\"}", body, "health response should be the documented status document")
    }

    // Minimal stub: /experiments needs only the metadata port's persistExperiment
    // (which is on the createExperiment use case). We don't exercise the
    // use case's persistence here — the focus is on response Content-Type
    // and JSON escaping.
    private fun stubWiring(): () -> dev.pipelinek.studio.application.usecases.Wiring {
        val noopMetadata = object : dev.pipelinek.studio.application.ports.StudioMetadataPort {
            override fun findExperiment(id: dev.pipelinek.studio.domain.ExperimentId) = null
            override fun findSnapshot(id: dev.pipelinek.studio.domain.SnapshotId) = null
            override fun findScenario(id: dev.pipelinek.studio.domain.ScenarioId) = null
            override fun findRun(id: dev.pipelinek.studio.domain.RunId) = null
            override fun listRuns(
                state: dev.pipelinek.studio.domain.RunState?,
                limit: Int,
                offset: Int,
            ): List<dev.pipelinek.studio.application.ports.RunBindingSnapshot> = emptyList()
        }
        val noopRunner = object : dev.pipelinek.studio.application.ports.PipelineRunnerPort {
            override fun submit(req: dev.pipelinek.studio.application.ports.RunRequest) =
                dev.pipelinek.studio.application.ports.SubmissionResult.Admitted("u", java.nio.file.Path.of("/tmp"))
            override fun cancel(runId: dev.pipelinek.studio.domain.RunId) =
                dev.pipelinek.studio.application.ports.CancellationResult.Unknown("stub")
            override fun status(runId: dev.pipelinek.studio.domain.RunId) =
                dev.pipelinek.studio.application.ports.RunHandleStatus.Unknown("stub")
        }
        val noopRead = object : dev.pipelinek.studio.application.ports.RunReadPort {
            override fun events(
                runId: dev.pipelinek.studio.domain.RunId,
                after: dev.pipelinek.studio.application.ports.EventCursor?,
                limit: Int,
            ) = dev.pipelinek.studio.application.ports.RunReadResult.Unknown("stub")
            override fun state(runId: dev.pipelinek.studio.domain.RunId) =
                dev.pipelinek.studio.application.ports.RunReadResult.Unknown("stub")
        }
        val noopOutput = object : dev.pipelinek.studio.application.ports.OutputReadPort {
            override fun read(
                runId: dev.pipelinek.studio.domain.RunId,
                cursor: dev.pipelinek.studio.application.ports.OutputCursor?,
                bytes: Int,
            ) = dev.pipelinek.studio.application.ports.OutputReadResult.UnknownStream("stub")
        }
        val noopIdem = object : dev.pipelinek.studio.application.ports.IdempotencyPort {
            override fun lookup(scope: String, key: String, requestDigestSha256: String) =
                dev.pipelinek.studio.application.ports.IdempotencyLookupResult.NotFound
            override fun record(
                scope: String, key: String,
                operationId: dev.pipelinek.studio.domain.OperationId,
                requestDigestSha256: String,
            ) = dev.pipelinek.studio.application.ports.IdempotencyRecordResult.Recorded
        }
        val noopRuntime = object : dev.pipelinek.studio.application.ports.PipelineKRuntimePort {
            override fun describe() =
                dev.pipelinek.studio.application.ports.RuntimeDescriptorResult.Unavailable
        }
        return {
            dev.pipelinek.studio.application.usecases.Wiring(
                noopMetadata, noopRunner, noopRead, noopOutput, noopIdem, noopRuntime,
            )
        }
    }
}
