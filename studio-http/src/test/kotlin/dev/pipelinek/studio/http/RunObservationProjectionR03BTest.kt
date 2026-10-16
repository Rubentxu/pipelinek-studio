package dev.pipelinek.studio.http

import dev.pipelinek.studio.application.ports.CancellationResult
import dev.pipelinek.studio.application.ports.EventCursor
import dev.pipelinek.studio.application.ports.IdempotencyLookupResult
import dev.pipelinek.studio.application.ports.IdempotencyPort
import dev.pipelinek.studio.application.ports.IdempotencyRecordResult
import dev.pipelinek.studio.application.ports.OutputReadPort
import dev.pipelinek.studio.application.ports.OutputReadResult
import dev.pipelinek.studio.application.ports.PipelineKRuntimePort
import dev.pipelinek.studio.application.ports.PipelineRunnerPort
import dev.pipelinek.studio.application.ports.RunBindingSnapshot
import dev.pipelinek.studio.application.ports.RunEvent
import dev.pipelinek.studio.application.ports.RunHandleStatus
import dev.pipelinek.studio.application.ports.RunReadPort
import dev.pipelinek.studio.application.ports.RunReadResult
import dev.pipelinek.studio.application.ports.RunRequest
import dev.pipelinek.studio.application.ports.RuntimeDescriptor
import dev.pipelinek.studio.application.ports.RuntimeDescriptorResult
import dev.pipelinek.studio.application.ports.ScenarioSnapshot
import dev.pipelinek.studio.application.ports.StudioMetadataPort
import dev.pipelinek.studio.application.ports.SubmissionResult
import dev.pipelinek.studio.domain.ExperimentId
import dev.pipelinek.studio.domain.OperationId
import dev.pipelinek.studio.domain.RunEvidence
import dev.pipelinek.studio.domain.RunId
import dev.pipelinek.studio.domain.RunObservation
import dev.pipelinek.studio.domain.RunState
import dev.pipelinek.studio.domain.ScenarioId
import dev.pipelinek.studio.domain.SnapshotId
import dev.pipelinek.studio.domain.SourceScenario
import dev.pipelinek.studio.domain.SourceSnapshot
import dev.pipelinek.studio.domain.VerifiedOutcome
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * R0.3.7 (2026-10-11) — adversarial tests for the
 * `GET /runs/{runId}` projection of the four-case [RunObservation]
 * ADT to the wire envelope.
 *
 * The mandate R0.3.7 is:
 *  > Implementar una sola proyección de RunObservation y resultado
 *  > terminal basada en autoridad upstream. **UNKNOWN no puede
 *  > convertirse en FAILED ni en Terminal sin evidencia.**
 *
 * These tests pin the invariant at the HTTP boundary without
 * requiring a real SDK subprocess. Each test wires a `RunReadPort`
 * whose `observation(runId)` returns one of the four ADT cases,
 * then asserts the wire response shape:
 *
 *   | Observation case                | Expected status | Expected `state`           |
 *   |---------------------------------|-----------------|----------------------------|
 *   | Live(upstreamId, pid)            | 200             | RUNNING                    |
 *   | Terminal(DeclaredPending)       | 200             | CREATED                    |
 *   | Terminal(Executed("FAILED", _)) | 200             | FAILED (legitimate path)   |
 *   | Terminal(Executed("SUCCEEDED")) | 200             | SUCCEEDED                  |
 *   | RecoveryRequired(reason)        | 503             | RECOVERY_REQUIRED          |
 *   | Unavailable(reason)             | 503             | UNAVAILABLE                |
 *
 * The "Unknown must not become FAILED" invariant is enforced by:
 *  - the two 503 cases (RecoveryRequired + Unavailable) — the wire
 *    must surface a non-FAILED state;
 *  - the Terminal(DeclaredPending) case — a run that has not been
 *    observed must not be claimed FAILED just because no terminal
 *    event has arrived yet;
 *  - the RunReadResult.Unknown path test — the default impl of
 *    [RunReadPort.observation]` collapses to Unavailable, which is
 *    the safe projection.
 */
class RunObservationProjectionR03BTest {

    @Test
    fun `RunObservation Unavailable projects to 503 state UNAVAILABLE not FAILED (R037)`() = runWithObservation(
        RunObservation.Unavailable("journal table missing after restart"),
    ) { status, body ->
        assertEquals(HttpStatusCode.ServiceUnavailable, status)
        assertTrue(body.contains("\"state\":\"UNAVAILABLE\""), "body=$body")
        assertFalse(body.contains("FAILED"), "UNKNOWN must not become FAILED on the wire; body=$body")
    }

    @Test
    fun `RunObservation RecoveryRequired projects to 503 state RECOVERY_REQUIRED not FAILED (R037)`() = runWithObservation(
        RunObservation.RecoveryRequired("non-terminal event RunStarted; subprocess gone after restart"),
    ) { status, body ->
        assertEquals(HttpStatusCode.ServiceUnavailable, status)
        assertTrue(body.contains("\"state\":\"RECOVERY_REQUIRED\""), "body=$body")
        assertFalse(body.contains("FAILED"), "RECOVERY_REQUIRED must not collapse to FAILED; body=$body")
    }

    @Test
    fun `RunObservation Terminal DeclaredPending projects to 200 state CREATED not FAILED (R037)`() = runWithObservation(
        RunObservation.Terminal(VerifiedOutcome.DeclaredPending),
    ) { status, body ->
        assertEquals(HttpStatusCode.OK, status)
        assertTrue(body.contains("\"state\":\"CREATED\""), "body=$body")
        // wire name is snake_case per @SerialName("claim_status")
        assertTrue(body.contains("\"claim_status\":\"DECLARED_PENDING\""), "body=$body")
        assertFalse(body.contains("FAILED"), "DeclaredPending must not be projected as FAILED; body=$body")
    }

    @Test
    fun `RunObservation Terminal Executed FAILED projects to 200 state FAILED (legitimate FAILED path R037)`() = runWithObservation(
        RunObservation.Terminal(
            VerifiedOutcome.Executed(
                terminal = "FAILED",
                evidence = RunEvidence(
                    runDbPath = "/tmp/run.db",
                    terminalSequence = 42L,
                    terminalKind = "RunFinished",
                    terminalSha256 = "deadbeef".repeat(8),
                ),
            ),
        ),
    ) { status, body ->
        assertEquals(HttpStatusCode.OK, status)
        assertTrue(body.contains("\"state\":\"FAILED\""), "body=$body")
        assertTrue(body.contains("EXECUTED:FAILED"), "body=$body")
    }

    @Test
    fun `RunObservation Terminal Executed SUCCEEDED projects to 200 state SUCCEEDED (control)`() = runWithObservation(
        RunObservation.Terminal(
            VerifiedOutcome.Executed(
                terminal = "SUCCEEDED",
                evidence = RunEvidence(
                    runDbPath = "/tmp/run.db",
                    terminalSequence = 42L,
                    terminalKind = "RunFinished",
                    terminalSha256 = "cafebabe".repeat(8),
                ),
            ),
        ),
    ) { status, body ->
        assertEquals(HttpStatusCode.OK, status)
        assertTrue(body.contains("\"state\":\"SUCCEEDED\""), "body=$body")
        assertTrue(body.contains("EXECUTED:SUCCEEDED"), "body=$body")
    }

    @Test
    fun `RunObservation Live projects to 200 state RUNNING with upstream id (R037)`() = runWithObservation(
        RunObservation.Live(upstreamId = "upstream-7", pid = 1234L),
    ) { status, body ->
        assertEquals(HttpStatusCode.OK, status)
        assertTrue(body.contains("\"state\":\"RUNNING\""), "body=$body")
        // wire name is snake_case per @SerialName("upstream_run_id")
        assertTrue(body.contains("\"upstream_run_id\":\"upstream-7\""), "body=$body")
        assertFalse(body.contains("FAILED"), "Live must not be projected as FAILED; body=$body")
    }

    @Test
    fun `RunReadResult Unknown via default observation impl projects to Unavailable not FAILED (R037)`() =
        testApplication {
            application {
                // Default RunReadPort.observation(runId) impl maps
                // RunReadResult.Unknown -> RunObservation.Unavailable
                // (see Ports.kt:167). We pin that invariant at the
                // port boundary so a future refactor cannot silently
                // turn Unknown into FAILED at the use-case layer.
                StudioHttpModule(
                    wiringFactory = fakeWiringFactoryForObservation(
                        observation = RunObservation.Unavailable(StubReadPortWithDefaultImpl.UNKNOWN_REASON),
                    ),
                    runReadFactory = { StubReadPortWithDefaultImpl },
                ).install(this)
            }
            val r = hostClient().get("/runs/00000000-0000-0000-0000-000000000000")
            assertEquals(HttpStatusCode.ServiceUnavailable, r.status)
            val body = r.bodyAsText()
            assertTrue(body.contains("\"state\":\"UNAVAILABLE\""), "body=$body")
            assertFalse(body.contains("FAILED"), "RunReadResult.Unknown must not collapse to FAILED; body=$body")
        }

    // ---- harness ----

    private fun runWithObservation(
        observation: RunObservation,
        assertions: (HttpStatusCode, String) -> Unit,
    ) = testApplication {
        application {
            StudioHttpModule(
                wiringFactory = fakeWiringFactoryForObservation(observation),
                runReadFactory = { StubRunReadPort(observation) },
            ).install(this)
        }
        val r = hostClient().get("/runs/00000000-0000-0000-0000-000000000000")
        assertions(r.status, r.bodyAsText())
    }

    private val stubRunRow = RunBindingSnapshot(
        id = RunId("00000000-0000-0000-0000-000000000000"),
        experimentId = ExperimentId("11111111-1111-1111-1111-111111111111"),
        snapshotId = SnapshotId("22222222-2222-2222-2222-222222222222"),
        scenarioId = ScenarioId("33333333-3333-3333-3333-333333333333"),
        state = "RUNNING",
        createdAt = Instant.parse("2026-10-11T11:00:00Z"),
    )

    private fun fakeWiringFactoryForObservation(observation: RunObservation): () -> dev.pipelinek.studio.application.usecases.Wiring {
        val metadata = object : StudioMetadataPort {
            override fun findExperiment(id: ExperimentId) = null
            override fun findSnapshot(id: SnapshotId) = null
            override fun findScenario(id: ScenarioId) = null
            override fun findRun(id: RunId): RunBindingSnapshot? = if (id == stubRunRow.id) stubRunRow else null
            override fun listRuns(state: RunState?, limit: Int, offset: Int): List<RunBindingSnapshot> = emptyList()
            override fun listScenarios(limit: Int, offset: Int): List<ScenarioSnapshot> = emptyList()
            override fun saveExperiment(experiment: dev.pipelinek.studio.domain.Experiment) { /* drop */ }
            override fun saveSnapshot(snap: SourceSnapshot) { /* drop */ }
            override fun loadSnapshotSource(id: SnapshotId): ByteArray? = null
            override fun persistRunBinding(binding: dev.pipelinek.studio.domain.RunBinding) { /* drop */ }
            override fun transitionRunState(studioRunId: dev.pipelinek.studio.domain.RunId, newState: dev.pipelinek.studio.domain.RunState) { /* noop */ }
            override fun saveScenario(scenario: SourceScenario) { /* drop */ }
        }
        val runner = object : PipelineRunnerPort {
            override fun submit(req: RunRequest): SubmissionResult = SubmissionResult.Admitted("u", java.nio.file.Path.of("/tmp"))
            override fun cancel(runId: RunId): CancellationResult = CancellationResult.Unknown("stub")
            override fun status(runId: RunId): RunHandleStatus = RunHandleStatus.Unknown("stub")
        }
        val idempotency = object : IdempotencyPort {
            override fun lookup(scope: String, key: String, requestDigestSha256: String) = IdempotencyLookupResult.NotFound
            override fun record(
                scope: String,
                key: String,
                operationId: OperationId,
                requestDigestSha256: String,
                responseRef: String?,
            ): IdempotencyRecordResult = IdempotencyRecordResult.Recorded
        }
        val runtime = object : PipelineKRuntimePort {
            override fun describe(): RuntimeDescriptorResult = RuntimeDescriptorResult.Available(
                RuntimeDescriptor(
                    version = "test-0.0.1",
                    artifactDigest = "test-digest",
                    pluginSdkVersion = "test-0.0.1",
                    capabilities = setOf("compile", "eval"),
                ),
            )
        }
        val output = object : OutputReadPort {
            override fun read(runId: RunId, cursor: dev.pipelinek.studio.application.ports.OutputCursor?, byteBudget: Int): OutputReadResult =
                OutputReadResult.UnknownStream("stub")
        }
        return {
            dev.pipelinek.studio.application.usecases.buildWiring(
                metadata,
                runner,
                StubRunReadPort(observation),
                output,
                idempotency,
                runtime,
            )
        }
    }
}

/**
 * Minimal `RunReadPort` that returns a fixed [RunObservation] from
 * `observation()` and refuses everything else. Mirrors the contract
 * the SDK adapter overrides to surface RecoveryRequired vs
 * Unavailable precisely per B1.5.
 */
private class StubRunReadPort(private val obs: RunObservation) : RunReadPort {
    override fun events(runId: RunId, after: EventCursor?, limit: Int): RunReadResult =
        RunReadResult.Unknown("stub")
    override fun state(runId: RunId): RunReadResult = RunReadResult.Unknown("stub")
    override fun observation(runId: RunId): RunObservation = obs
}

/**
 * Variant that exercises the **default** `observation()` impl from
 * [RunReadPort] (the one production code relies on when no SDK
 * adapter overrides it). The default collapses `state()` to
 * `Terminal(DeclaredPending)` for Ok or `Unavailable(reason)` for
 * Unknown — never to FAILED.
 */
private object StubReadPortWithDefaultImpl : RunReadPort {
    const val UNKNOWN_REASON = "journal table missing"

    override fun events(runId: RunId, after: EventCursor?, limit: Int): RunReadResult =
        RunReadResult.Unknown("stub")
    override fun state(runId: RunId): RunReadResult = RunReadResult.Unknown(UNKNOWN_REASON)
    // Uses the default observation() impl from RunReadPort: Unknown
    // -> Unavailable. No override here on purpose — we are pinning
    // the default behaviour.
}