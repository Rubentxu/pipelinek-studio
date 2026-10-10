package dev.pipelinek.studio.http

import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsText
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * AAT-SEC-005 — Host header allow-list. The server must reject requests
 * whose Host header is not in the configured allow-list. This is the
 * DNS-rebinding / CSRF defense: a malicious actor that can poison the
 * resolver for the server's loopback cannot trick the server into
 * treating a different origin as canonical.
 */
class HostAllowListTest {

    @Test
    fun `forged Host header is rejected with 400 HOST_NOT_ALLOWED`() = testApplication {
        // Use an explicit narrow allow-list for this test so we don't
        // depend on the env override or the test framework's default.
        application {
            StudioHttpModule(
                fakeWiringFactory(),
                runReadFactory = null,
                hostAllowList = setOf("127.0.0.1", "localhost"),
            ).install(this)
        }
        val r = client.get("/health") {
            // Override the Host header to a value not in the allow-list.
            header(HttpHeaders.Host, "evil.example.com")
        }
        assertEquals(HttpStatusCode.BadRequest, r.status)
        val body = r.bodyAsText()
        assertTrue(body.contains("HOST_NOT_ALLOWED"), "expected HOST_NOT_ALLOWED, got: $body")
    }

    @Test
    fun `allowed Host header is accepted`() = testApplication {
        application {
            StudioHttpModule(
                fakeWiringFactory(),
                runReadFactory = null,
                hostAllowList = setOf("127.0.0.1", "localhost"),
            ).install(this)
        }
        val r = client.get("/health") {
            // 127.0.0.1 is in the allow-list.
            header(HttpHeaders.Host, "127.0.0.1")
        }
        // /health returns 200; if the allow-list had rejected, we'd get 400.
        assertEquals(HttpStatusCode.OK, r.status)
    }

    @Test
    fun `host header with port is normalized before allow-list check`() = testApplication {
        application {
            StudioHttpModule(
                fakeWiringFactory(),
                runReadFactory = null,
                hostAllowList = setOf("127.0.0.1"),
            ).install(this)
        }
        val r = client.get("/health") {
            // The "127.0.0.1:7070" host must be normalized to "127.0.0.1"
            // and accepted.
            header(HttpHeaders.Host, "127.0.0.1:7070")
        }
        assertEquals(HttpStatusCode.OK, r.status)
    }

    // A minimal wiring factory that does not exercise the metadata port.
    private fun fakeWiringFactory(): () -> dev.pipelinek.studio.application.usecases.Wiring {
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
                    override fun saveSnapshot(snap: dev.pipelinek.studio.domain.SourceSnapshot) { /* noop */ }
            override fun loadSnapshotSource(id: dev.pipelinek.studio.domain.SnapshotId): ByteArray? = null
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
                responseRef: String?,
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
