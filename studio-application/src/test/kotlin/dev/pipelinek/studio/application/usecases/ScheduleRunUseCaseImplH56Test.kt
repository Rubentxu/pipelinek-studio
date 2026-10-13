@file:Suppress("TooManyFunctions")

package dev.pipelinek.studio.application.usecases

import dev.pipelinek.studio.application.ports.CapacityPort
import dev.pipelinek.studio.application.ports.PlacementAuditPort
import dev.pipelinek.studio.application.ports.ScheduleDecision
import dev.pipelinek.studio.application.ports.ScheduleRunResult
import dev.pipelinek.studio.application.ports.ScheduleRunUseCase
import dev.pipelinek.studio.application.ports.WorkerRequirement
import dev.pipelinek.studio.domain.RunId
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Falsifiers for the SFW-H5.6 extension to
 * [ScheduleRunUseCaseImpl] — the use case
 * change that consumes the H5.5
 * `placement_audit` persistence.
 *
 * The tests pin the 5 paths:
 * 1. Placed + port wired → port receives
 *    the `recordPlacement` call; the use
 *    case still returns
 *    `ScheduleRunResult.Placed`.
 * 2. Placed + port unwired (null) → the
 *    use case does NOT call any port.
 * 3. Placed + port wired + port throws →
 *    the use case still returns
 *    `ScheduleRunResult.Placed` (the
 *    persistence is best-effort).
 * 4. NoCapacity + port wired → port is
 *    NOT called (only the `Placed` arm
 *    triggers the port).
 * 5. Refused + port wired → port is NOT
 *    called (only the `Placed` arm
 *    triggers the port).
 */
@DisplayName("ScheduleRunUseCaseImpl (SFW-H5.6 consume placement_audit)")
class ScheduleRunUseCaseImplH56Test {

    private val requirement = WorkerRequirement(
        cpuMillicores = 1000,
        memoryMb = 512,
        region = "us-west-2",
        studioRunId = RunId("studio-h56-1"),
    )

    // ------------------------------------------------------------------
    // Falsifier 1: Placed + port wired maps to port receives the call
    // ------------------------------------------------------------------
    @Test
    fun `Placed + port wired maps to port receives recordPlacement call, use case still returns Placed`() = runBlocking {
        val capacity = FakeCapacityPortForH56().apply {
            nextDecision = ScheduleDecision.Placed(
                workerId = "worker-1",
                region = "us-west-2",
            )
        }
        val audit = FakePlacementAuditPortForH56()
        val useCase = ScheduleRunUseCaseImpl(capacity, audit)

        val result = useCase.schedule(requirement)

        val placed = result as? ScheduleRunResult.Placed
        assertNotNull(placed, "expected ScheduleRunResult.Placed, got $result")
        assertEquals(1, audit.recordCalls, "port must be called once when Placed + port wired")
        assertEquals(RunId("studio-h56-1"), audit.lastStudioRunId)
        assertEquals("worker-1", audit.lastWorkerId)
        assertEquals("us-west-2", audit.lastRegion)
    }

    // ------------------------------------------------------------------
    // Falsifier 2: Placed + port unwired (null) maps to port is NOT called
    // ------------------------------------------------------------------
    @Test
    fun `Placed + port unwired (null) maps to use case does not call any port`() = runBlocking {
        val capacity = FakeCapacityPortForH56().apply {
            nextDecision = ScheduleDecision.Placed(
                workerId = "worker-1",
                region = "us-west-2",
            )
        }
        // No audit port -- the constructor's
        // default parameter is null.
        val useCase = ScheduleRunUseCaseImpl(capacity)

        val result = useCase.schedule(requirement)

        val placed = result as? ScheduleRunResult.Placed
        assertNotNull(placed, "expected ScheduleRunResult.Placed, got $result")
        // The H5.1 stub path is preserved: the
        // use case still returns Placed
        // regardless of the port's presence.
    }

    // ------------------------------------------------------------------
    // Falsifier 3: Placed + port wired + port throws maps to use case still returns Placed
    // ------------------------------------------------------------------
    @Test
    fun `Placed + port wired + port throws maps to use case still returns Placed (persistence is best-effort)`() = runBlocking {
        val capacity = FakeCapacityPortForH56().apply {
            nextDecision = ScheduleDecision.Placed(
                workerId = "worker-1",
                region = "us-west-2",
            )
        }
        val audit = FakePlacementAuditPortForH56().apply {
            throwOnRecord = RuntimeException("SQLite write conflict")
        }
        val useCase = ScheduleRunUseCaseImpl(capacity, audit)

        val result = useCase.schedule(requirement)

        val placed = result as? ScheduleRunResult.Placed
        assertNotNull(placed, "expected ScheduleRunResult.Placed, got $result")
        // The persistence is best-effort: a
        // port failure is NOT surfaced to the
        // operator; the use case still returns
        // Placed.
    }

    // ------------------------------------------------------------------
    // Falsifier 4: NoCapacity + port wired maps to port is NOT called
    // ------------------------------------------------------------------
    @Test
    fun `NoCapacity + port wired maps to port is NOT called (only the Placed arm triggers the port)`() = runBlocking {
        val capacity = FakeCapacityPortForH56().apply {
            nextDecision = ScheduleDecision.NoCapacity("no worker available")
        }
        val audit = FakePlacementAuditPortForH56()
        val useCase = ScheduleRunUseCaseImpl(capacity, audit)

        val result = useCase.schedule(requirement)

        val noCap = result as? ScheduleRunResult.NoCapacity
        assertNotNull(noCap, "expected ScheduleRunResult.NoCapacity, got $result")
        assertEquals(0, audit.recordCalls, "port must NOT be called when result is NoCapacity")
    }

    // ------------------------------------------------------------------
    // Falsifier 5: Refused + port wired maps to port is NOT called
    // ------------------------------------------------------------------
    @Test
    fun `Refused + port wired maps to port is NOT called (only the Placed arm triggers the port)`() = runBlocking {
        val capacity = FakeCapacityPortForH56().apply {
            nextDecision = ScheduleDecision.Refused("transport failure")
        }
        val audit = FakePlacementAuditPortForH56()
        val useCase = ScheduleRunUseCaseImpl(capacity, audit)

        val result = useCase.schedule(requirement)

        val refused = result as? ScheduleRunResult.Refused
        assertNotNull(refused, "expected ScheduleRunResult.Refused, got $result")
        assertEquals(0, audit.recordCalls, "port must NOT be called when result is Refused")
    }
}

// --------------------------------------------------------------------
// Test fixtures
// --------------------------------------------------------------------

private class FakeCapacityPortForH56 : CapacityPort {
    var nextDecision: ScheduleDecision = ScheduleDecision.NoCapacity("default")
    var scheduleCalls: Int = 0
        private set
    var lastRequirement: WorkerRequirement? = null
        private set

    override suspend fun schedule(requirement: WorkerRequirement): ScheduleDecision {
        scheduleCalls += 1
        lastRequirement = requirement
        return nextDecision
    }
}

private class FakePlacementAuditPortForH56 : PlacementAuditPort {
    var recordCalls: Int = 0
        private set
    var lastStudioRunId: RunId? = null
        private set
    var lastWorkerId: String? = null
        private set
    var lastRegion: String? = null
        private set
    var throwOnRecord: Throwable? = null

    override suspend fun recordPlacement(
        studioRunId: RunId,
        workerId: String,
        region: String,
    ) {
        recordCalls += 1
        lastStudioRunId = studioRunId
        lastWorkerId = workerId
        lastRegion = region
        throwOnRecord?.let { throw it }
    }
}
