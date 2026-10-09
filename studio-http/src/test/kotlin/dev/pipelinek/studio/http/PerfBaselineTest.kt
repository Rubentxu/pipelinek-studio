package dev.pipelinek.studio.http

import dev.pipelinek.studio.application.ports.*
import dev.pipelinek.studio.domain.*
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.server.testing.testApplication
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import kotlin.test.assertTrue

/**
 * S5 E5.5 — Performance baselines. Boots the in-process Ktor test app and times the
 * main GET endpoints. CI only asserts the documented budgets; humans review the
 * recorded median/p95 against the baseline to catch drift.
 */
class PerfBaselineTest {

    private val log = LoggerFactory.getLogger(javaClass)

    @Test
    fun `baseline latency for read-only endpoints stays under documented budgets`() = testApplication {
        application {
            StudioHttpModule(
                wiringFactory = { fakeWiring() },
                runReadFactory = null,
                outputReadFactory = null,
                stepCatalogFactory = { RefusingStepCatalog },
                diagnosticsFactory = null,
                irFactory = null,
            ).install(this)
        }

        val samples = 25
        val measurements = listOf(
            Triple("GET /health", "/health", 20L),
            Triple("GET /api/version", "/api/version", 20L),
            Triple("GET /steps", "/steps", 250L),
            Triple("GET /runs/{id}", "/runs/00000000-0000-0000-0000-000000000000", 50L),
        )

        val summary = StringBuilder()
        summary.append("\n=== S5 E5.5 perf baseline (median over $samples in-process calls) ===\n")
        for ((label, route, budget) in measurements) {
            val latencies = LongArray(samples)
            repeat(samples) { i ->
                val t0 = System.nanoTime()
                val r = client.get(route)
                val t1 = System.nanoTime()
                latencies[i] = (t1 - t0) / 1_000_000
                // Accept any 2xx; some endpoints (e.g. /runs/{id} with unknown run) return
                // 202 Accepted with a UNKNOWN body. The point of perf is the response time,
                // not the semantic correctness of the stub.
                assertTrue(
                    r.status.value in 200..299,
                    "$label returned ${r.status}: ${r.bodyAsText()}",
                )
            }
            val sorted = latencies.sortedArray()
            val median = sorted[samples / 2]
            val p95 = sorted[(samples * 95) / 100]
            summary.append("  %-22s median=%4dms p95=%4dms budget=%4dms  %s\n".format(
                label,
                median,
                p95,
                budget,
                if (median <= budget) "OK" else "OVER BUDGET",
            ))
            assertTrue(median <= budget, "$label median ${median}ms exceeds budget ${budget}ms (p95=${p95}ms)")
        }
        log.info(summary.toString())
    }

    private fun fakeWiring(): dev.pipelinek.studio.application.usecases.Wiring {
        val noopMetadata = object : StudioMetadataPort {
            override fun findExperiment(id: ExperimentId) = null
            override fun findSnapshot(id: SnapshotId) = null
            override fun findScenario(id: ScenarioId) = null
            override fun findRun(id: RunId) = null
            override fun listRuns(state: dev.pipelinek.studio.domain.RunState?, limit: Int, offset: Int): List<RunBindingSnapshot> = emptyList()
                    override fun saveSnapshot(snap: dev.pipelinek.studio.domain.SourceSnapshot) { /* noop */ }
            override fun loadSnapshotSource(id: dev.pipelinek.studio.domain.SnapshotId): ByteArray? = null
        }
        val noopRunner = object : PipelineRunnerPort {
            override fun submit(req: RunRequest) =
                SubmissionResult.Admitted("upstream-1", java.nio.file.Path.of("/tmp/ctrl"))
            override fun cancel(runId: RunId) = CancellationResult.Unknown("stub")
            override fun status(runId: RunId) = RunHandleStatus.Unknown("stub")
        }
        val noopIdempotency = object : IdempotencyPort {
            override fun lookup(scope: String, key: String, requestDigestSha256: String) =
                IdempotencyLookupResult.NotFound
            override fun record(scope: String, key: String, operationId: OperationId, requestDigestSha256: String) =
                IdempotencyRecordResult.Recorded
        }
        val noopRuntime = object : PipelineKRuntimePort {
            override fun describe() = RuntimeDescriptorResult.Available(
                RuntimeDescriptor(
                    version = "stub",
                    artifactDigest = "stub",
                    pluginSdkVersion = "stub",
                    capabilities = emptySet(),
                ),
            )
        }
        return dev.pipelinek.studio.application.usecases.buildWiring(
            metadata = noopMetadata,
            runner = noopRunner,
            read = RefusingRunReadPort,
            output = RefusingOutputReadPort,
            idempotency = noopIdempotency,
            runtime = noopRuntime,
        )
    }
}

/** Minimal in-process StepCatalog stub: lists nothing, so the test exercises the no-step path. */
private object RefusingStepCatalog : StepCatalogReadPort {
    override fun list(): StepCatalogResult = StepCatalogResult.Ok(emptyList())
    override fun describe(id: String): StepCatalogResult = StepCatalogResult.Failure("no steps in perf baseline harness")
}
