package dev.pipelinek.studio.infra.fabric

import dev.pipelinek.studio.application.ports.RunRequest
import dev.pipelinek.studio.application.ports.StudioFabricCancellationResult
import dev.pipelinek.studio.application.ports.StudioFabricSubmissionResult
import dev.pipelinek.studio.domain.RunId
import dev.pipelinek.studio.domain.ScenarioId
import dev.pipelinek.studio.domain.SnapshotId
import io.jenkins.pipelinek.fabric.contracts.model.RunId as FabricRunId
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * SFW F4 Post-Bump Part 11 — falsifier for [StudioFabricGatewayAdapter].
 *
 * The adapter's only job is ADT-mapping: it takes a H2.2c
 * [FabricRunnerPort.submit/cancel] (which uses Fabric-side types)
 * and exposes the result as the application-layer pure-Kotlin
 * [StudioFabricSubmissionResult] / [StudioFabricCancellationResult]
 * from F4 Part 10. These tests pin the 7-arm mapping (3 submission +
 * 4 cancellation) and prove the delegate is called with the right
 * inputs and the pure-Kotlin fabricRunId never leaks a Fabric type.
 *
 * The tests use a hand-written `FabricRunnerPort` fake that records
 * the call and returns a configurable result per scenario. This keeps
 * the adapter tests independent of `FabricRunnerAdapter` and the
 * `RunControl` it depends on.
 */
@DisplayName("StudioFabricGatewayAdapter (SFW F4 Part 11 — ADR-014 delegating adapter)")
class StudioFabricGatewayAdapterF11Test {

    /**
     * Recording fake of [FabricRunnerPort]. The tests configure the
     * canned responses and assert the deltas afterwards.
     */
    private class FakeRunner : FabricRunnerPort {
        data class Recorded(val runRequest: RunRequest?, val cancelId: RunId?)

        var submitResult: FabricSubmissionResult? = null
        var cancelResult: FabricCancellationResult? = null
        val calls = mutableListOf<Recorded>()

        override suspend fun submit(req: RunRequest): FabricSubmissionResult {
            calls += Recorded(runRequest = req, cancelId = null)
            return requireNotNull(submitResult) { "test must set submitResult before submit()" }
        }

        override suspend fun cancel(studioRunId: RunId): FabricCancellationResult {
            calls += Recorded(runRequest = null, cancelId = studioRunId)
            return requireNotNull(cancelResult) { "test must set cancelResult before cancel()" }
        }
    }

    private fun fakeRunRequest() = RunRequest(
        runId = RunId("studio-1"),
        snapshotId = SnapshotId("snap-1"),
        scenarioId = ScenarioId("sc-1"),
        profile = dev.pipelinek.studio.application.ports.ExecutionProfile.TrustedLocal,
    )

    // ------------------------------------------------------------------------
    // Submission arm mapping
    // ------------------------------------------------------------------------

    @Test
    fun `submit maps FabricSubmissionResult_Admitted to StudioFabricSubmissionResult_Admitted (fabricRunId is String, not Fabric type)`() = runBlocking {
        val fake = FakeRunner().apply {
            submitResult = FabricSubmissionResult.Admitted(
                studioRunId = RunId("studio-1"),
                fabricRunId = FabricRunId("fabric-abc-123"),
            )
        }
        val adapter = StudioFabricGatewayAdapter(fake)
        val req = fakeRunRequest()

        val result = adapter.submit(req)

        val admitted = assertIs<StudioFabricSubmissionResult.Admitted>(result)
        assertEquals(RunId("studio-1"), admitted.studioRunId)
        assertEquals("fabric-abc-123", admitted.fabricRunId, "fabricRunId must be unwrapped String; not Fabric value class")
    }

    @Test
    fun `submit maps FabricSubmissionResult_Refused to StudioFabricSubmissionResult_Refused (reason preserved)`() = runBlocking {
        val fake = FakeRunner().apply {
            submitResult = FabricSubmissionResult.Refused(reason = "conflict: same id")
        }
        val adapter = StudioFabricGatewayAdapter(fake)

        val result = adapter.submit(fakeRunRequest())

        val refused = assertIs<StudioFabricSubmissionResult.Refused>(result)
        assertEquals("conflict: same id", refused.reason)
    }

    @Test
    fun `submit maps FabricSubmissionResult_AlreadyRunning to StudioFabricSubmissionResult_AlreadyRunning (fabricRunId is String)`() = runBlocking {
        val fake = FakeRunner().apply {
            submitResult = FabricSubmissionResult.AlreadyRunning(
                fabricRunId = FabricRunId("fabric-existing-xyz"),
            )
        }
        val adapter = StudioFabricGatewayAdapter(fake)

        val result = adapter.submit(fakeRunRequest())

        val already = assertIs<StudioFabricSubmissionResult.AlreadyRunning>(result)
        assertEquals("fabric-existing-xyz", already.fabricRunId)
    }

    @Test
    fun `submit forwards the same RunRequest to the delegate without transformation`() = runBlocking {
        val fake = FakeRunner().apply {
            submitResult = FabricSubmissionResult.Refused(reason = "noop")
        }
        val adapter = StudioFabricGatewayAdapter(fake)
        val req = fakeRunRequest()

        adapter.submit(req)

        val recorded = fake.calls.single()
        assertNotNull(recorded.runRequest, "delegate must have been called with a submit")
        assertEquals(req, recorded.runRequest, "the RunRequest must be forwarded untouched")
    }

    // ------------------------------------------------------------------------
    // Cancellation arm mapping
    // ------------------------------------------------------------------------

    @Test
    fun `cancel maps FabricCancellationResult_CancelRequested to StudioFabricCancellationResult_CancelRequested`(
    ) = runBlocking {
        val fake = FakeRunner().apply {
            cancelResult = FabricCancellationResult.CancelRequested
        }
        val adapter = StudioFabricGatewayAdapter(fake)

        val result = adapter.cancel(RunId("studio-1"))

        assertIs<StudioFabricCancellationResult.CancelRequested>(result)
    }

    @Test
    fun `cancel maps FabricCancellationResult_AlreadyTerminal preserving finalState`() = runBlocking {
        val fake = FakeRunner().apply {
            cancelResult = FabricCancellationResult.AlreadyTerminal(finalState = "Running")
        }
        val adapter = StudioFabricGatewayAdapter(fake)

        val result = adapter.cancel(RunId("studio-1"))

        val at = assertIs<StudioFabricCancellationResult.AlreadyTerminal>(result)
        assertEquals("Running", at.finalState)
    }

    @Test
    fun `cancel maps FabricCancellationResult_Unknown preserving reason`() = runBlocking {
        val fake = FakeRunner().apply {
            cancelResult = FabricCancellationResult.Unknown(reason = "fabric has no record of studio-1")
        }
        val adapter = StudioFabricGatewayAdapter(fake)

        val result = adapter.cancel(RunId("studio-1"))

        val u = assertIs<StudioFabricCancellationResult.Unknown>(result)
        assertEquals("fabric has no record of studio-1", u.reason)
    }

    @Test
    fun `cancel maps FabricCancellationResult_Refused preserving reason`() = runBlocking {
        val fake = FakeRunner().apply {
            cancelResult = FabricCancellationResult.Refused(reason = "already terminal")
        }
        val adapter = StudioFabricGatewayAdapter(fake)

        val result = adapter.cancel(RunId("studio-1"))

        val r = assertIs<StudioFabricCancellationResult.Refused>(result)
        assertEquals("already terminal", r.reason)
    }

    @Test
    fun `cancel forwards the same StudioRunId to the delegate without transformation`() = runBlocking {
        val fake = FakeRunner().apply {
            cancelResult = FabricCancellationResult.CancelRequested
        }
        val adapter = StudioFabricGatewayAdapter(fake)

        adapter.cancel(RunId("studio-xyz"))

        val recorded = fake.calls.single()
        assertNotNull(recorded.cancelId)
        assertEquals(RunId("studio-xyz"), recorded.cancelId)
    }

    // ------------------------------------------------------------------------
    // Cross-cutting invariant: no Fabric gRPC types reach the pure-Kotlin
    // surface.
    // ------------------------------------------------------------------------

    @Test
    fun `gateway results carry no Fabric gRPC types in any declared field`() {
        // For every arm, walk the declared fields and assert the field
        // types do not contain the Fabric contracts package.
        val classes = listOf(
            StudioFabricSubmissionResult.Admitted::class.java,
            StudioFabricSubmissionResult.Refused::class.java,
            StudioFabricSubmissionResult.AlreadyRunning::class.java,
            StudioFabricCancellationResult.AlreadyTerminal::class.java,
            StudioFabricCancellationResult.Unknown::class.java,
            StudioFabricCancellationResult.Refused::class.java,
        )
        for (c in classes) {
            val offending = c.declaredFields.map { it.type.name }.filter { it.contains("pipelinek.fabric") }
            assertTrue(
                offending.isEmpty(),
                "${c.simpleName} must not carry Fabric gRPC types in fields; got $offending"
            )
        }
    }
}
