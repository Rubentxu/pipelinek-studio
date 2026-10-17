package dev.pipelinek.studio.application

import dev.pipelinek.studio.application.ports.*
import dev.pipelinek.studio.application.usecases.*
import dev.pipelinek.studio.domain.*
import io.mockk.*
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.test.*

class TreeEventsR5Test {
    private val metadata = mockk<StudioMetadataPort>(relaxed = true)
    private val runner = mockk<PipelineRunnerPort>()
    private val idempotency = mockk<IdempotencyPort>()
    private val runtime = mockk<PipelineKRuntimePort>()
    private val events = mutableListOf<TreeEvent>()
    private val bus = mockk<TreeEventBusPort> {
        every { publish(any()) } answers { events.add(firstArg()); Unit }
    }

    @Test
    fun `refused submission persists reservation but does not announce started`() {
        val snapshot = SnapshotId.new()
        val experiment = ExperimentId.new()
        every { runtime.describe() } returns RuntimeDescriptorResult.Available(
            RuntimeDescriptor("test", "digest", "sdk", emptySet()),
        )
        every { metadata.findSnapshot(snapshot) } returns SnapshotSnapshot(snapshot, experiment, "sha", Instant.EPOCH)
        every { idempotency.lookup(any(), any(), any()) } returns IdempotencyLookupResult.NotFound
        every { idempotency.record(any(), any(), any(), any(), any()) } returns IdempotencyRecordResult.Recorded
        every { runner.submit(any()) } returns SubmissionResult.Refused("not admitted")
        val result = StartRunUseCase(runner, idempotency, runtime, metadata, treeEventBus = bus)
            .execute(snapshot, ScenarioId.new(), IdempotencyKey.new(), Sha256.digest(byteArrayOf(1)))
        assertIs<ReservationOutcome.Refused>(result)
        assertEquals("not admitted", result.reason)
        verify(exactly = 1) { metadata.persistRunBinding(match { it.state == RunState.CREATED }) }
        assertTrue(events.isEmpty(), "a CREATED reservation is not a started run")
    }

    @Test
    fun `journal facts announce terminal once and ignore unknown outcomes`() {
        val read = mockk<RunReadPort>()
        val run = RunId.new()
        val experiment = ExperimentId.new()
        val publisher = TreeRunEventsUseCase(read, bus)
        val at = Instant.parse("2026-10-16T12:00:00Z")
        every { read.events(run, any(), any()) } returns RunReadResult.Ok(listOf(
            RunEvent(1, "RunStarted", at, emptyMap()),
            RunEvent(2, "RunFinished", at, mapOf("outcome" to "unknown")),
            RunEvent(3, "RunFinished", at, mapOf("outcome" to "success")),
        ), null)
        publisher.execute(run)
        publisher.execute(run)
        assertEquals(listOf("run.completed"), events.map { it.kind })
        assertEquals(at, events.last().at)
        assertEquals("OK", (events.last() as TreeEvent.RunCompleted).status)
    }
}
