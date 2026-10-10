package dev.pipelinek.studio.http

import dev.pipelinek.studio.application.ports.CancellationResult
import dev.pipelinek.studio.application.ports.IdempotencyLookupResult
import dev.pipelinek.studio.application.ports.IdempotencyPort
import dev.pipelinek.studio.application.ports.IdempotencyRecordResult
import dev.pipelinek.studio.application.ports.PipelineKRuntimePort
import dev.pipelinek.studio.application.ports.PipelineRunnerPort
import dev.pipelinek.studio.application.ports.RunBindingSnapshot
import dev.pipelinek.studio.application.ports.RunHandleStatus
import dev.pipelinek.studio.application.ports.RunRequest
import dev.pipelinek.studio.application.ports.RuntimeDescriptor
import dev.pipelinek.studio.application.ports.RuntimeDescriptorResult
import dev.pipelinek.studio.application.ports.StudioMetadataPort
import dev.pipelinek.studio.application.ports.SubmissionResult
import dev.pipelinek.studio.domain.ExperimentId
import dev.pipelinek.studio.domain.OperationId
import dev.pipelinek.studio.domain.RunBinding
import dev.pipelinek.studio.domain.RunId
import dev.pipelinek.studio.domain.RunState
import dev.pipelinek.studio.domain.ScenarioId
import dev.pipelinek.studio.domain.SnapshotId
import dev.pipelinek.studio.domain.SourceSnapshot
import io.ktor.client.HttpClient
import io.ktor.client.plugins.DefaultRequest
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import io.ktor.server.testing.ApplicationTestBuilder

/**
 * Test support helpers (audit H-15, 2026-10-10).
 *
 * The ktor testApplication's default client does not set a Host
 * header. The studio's HostAllowList interceptor (H-15 fix) refuses
 * requests with a missing Host header, so every test request must
 * carry one. This helper creates a client with a default Host
 * header baked in.
 *
 * Usage in a test:
 * ```
 * testApplication {
 *     application { ... }
 *     val r = hostClient().get("/runs")
 * }
 * ```
 */
fun ApplicationTestBuilder.hostClient(): HttpClient = createClient {
    install(DefaultRequest) {
        header(HttpHeaders.Host, "127.0.0.1")
    }
}

/**
 * B3.0 (2026-10-10): top-level wiring factory for
 * StudioHttpModule tests. The pre-B3.0 version of
 * this lived as a private method on
 * [StudioHttpModuleTest] and was not accessible to
 * other test files. Extracting it here lets new
 * tests (e.g. B30DiagnosticCapabilityTest) wire
 * `StudioHttpModule(fakeWiringFactory(), ...)` and
 * exercise the diagnostics / capability routes
 * without redeclaring the whole stub surface.
 */
fun fakeWiringFactory(): () -> dev.pipelinek.studio.application.usecases.Wiring {
    val noopMetadata = object : StudioMetadataPort {
        override fun findExperiment(id: ExperimentId) = null
        override fun findSnapshot(id: SnapshotId) = null
        override fun findScenario(id: ScenarioId) = null
        override fun findRun(id: RunId) = null
        override fun listRuns(state: RunState?, limit: Int, offset: Int): List<RunBindingSnapshot> = emptyList()
        override fun saveSnapshot(snap: SourceSnapshot) { /* noop */ }
        override fun loadSnapshotSource(id: SnapshotId): ByteArray? = null
        override fun saveExperiment(experiment: dev.pipelinek.studio.domain.Experiment) { /* noop */ }
        override fun persistRunBinding(binding: dev.pipelinek.studio.domain.RunBinding) { /* noop */ }
    }
    val noopRunner = object : PipelineRunnerPort {
        override fun submit(req: RunRequest): SubmissionResult =
            SubmissionResult.Admitted("upstream-1", java.nio.file.Path.of("/tmp/ctrl"))
        override fun cancel(runId: RunId): CancellationResult = CancellationResult.Unknown("stub")
        override fun status(runId: RunId): RunHandleStatus = RunHandleStatus.Unknown("stub")
    }
    val noopIdempotency = object : IdempotencyPort {
        override fun lookup(scope: String, key: String, requestDigestSha256: String) =
            IdempotencyLookupResult.NotFound
        override fun record(scope: String, key: String, operationId: OperationId, requestDigestSha256: String, responseRef: String?) =
            IdempotencyRecordResult.Recorded
    }
    val noopRuntime = object : PipelineKRuntimePort {
        override fun describe(): RuntimeDescriptorResult =
            RuntimeDescriptorResult.Available(
                RuntimeDescriptor(
                    version = "test-0.0.1",
                    artifactDigest = "test-digest",
                    pluginSdkVersion = "test-0.0.1",
                    capabilities = setOf("compile", "eval"),
                ),
            )
    }
    return {
        dev.pipelinek.studio.application.usecases.buildWiring(
            noopMetadata,
            noopRunner,
            RefusingRunReadPort,
            RefusingOutputReadPort,
            noopIdempotency,
            noopRuntime,
        )
    }
}
