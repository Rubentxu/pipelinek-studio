@file:Suppress("TooManyFunctions")

package dev.pipelinek.studio.application.usecases

import dev.pipelinek.studio.application.ports.CapacityPort
import dev.pipelinek.studio.application.ports.ScheduleDecision
import dev.pipelinek.studio.application.ports.ScheduleRunResult
import dev.pipelinek.studio.application.ports.ScheduleRunUseCase
import dev.pipelinek.studio.application.ports.WorkerRequirement
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Falsifiers for the SFW-H5.1
 * [ScheduleRunUseCaseImpl] — the use case
 * that wraps the [CapacityPort] with the
 * 4-arm `ScheduleRunResult` family.
 *
 * The tests pin the 4 arms of
 * `ScheduleDecision` for the use case:
 * Placed, NoCapacity, Refused, Unknown,
 * plus the unexpected-exception path.
 */
@DisplayName("ScheduleRunUseCaseImpl (SFW-H5.1 wrap CapacityPort with ScheduleRunResult)")
class ScheduleRunUseCaseImplH51Test {

    private val requirement = WorkerRequirement(
        cpuMillicores = 1000,
        memoryMb = 512,
        region = "us-west-2",
    )

    // ------------------------------------------------------------------
    // Falsifier 1: Placed -> ScheduleRunResult.Placed
    // ------------------------------------------------------------------
    @Test
    fun `Placed maps to ScheduleRunResult Placed with workerId and region`() = runBlocking {
        val port = FakeCapacityPortForH51().apply {
            nextDecision = ScheduleDecision.Placed(
                workerId = "worker-1",
                region = "us-west-2",
            )
        }
        val useCase = ScheduleRunUseCaseImpl(port)

        val result = useCase.schedule(requirement)

        val placed = result as? ScheduleRunResult.Placed
        assertTrue(placed != null, "expected ScheduleRunResult.Placed, got $result")
        assertEquals("worker-1", placed!!.workerId)
        assertEquals("us-west-2", placed.region)
    }

    // ------------------------------------------------------------------
    // Falsifier 2: NoCapacity -> ScheduleRunResult.NoCapacity
    // ------------------------------------------------------------------
    @Test
    fun `NoCapacity maps to ScheduleRunResult NoCapacity with reason`() = runBlocking {
        val port = FakeCapacityPortForH51().apply {
            nextDecision = ScheduleDecision.NoCapacity("no worker in us-west-2 has 1GiB memory")
        }
        val useCase = ScheduleRunUseCaseImpl(port)

        val result = useCase.schedule(requirement)

        val noCap = result as? ScheduleRunResult.NoCapacity
        assertTrue(noCap != null, "expected ScheduleRunResult.NoCapacity, got $result")
        assertTrue(
            noCap!!.reason.contains("us-west-2"),
            "reason should reference region: ${noCap.reason}",
        )
    }

    // ------------------------------------------------------------------
    // Falsifier 3: Refused -> ScheduleRunResult.Refused
    // ------------------------------------------------------------------
    @Test
    fun `Refused maps to ScheduleRunResult Refused with reason`() = runBlocking {
        val port = FakeCapacityPortForH51().apply {
            nextDecision = ScheduleDecision.Refused(
                reason = "transport failure on WorkerPool.schedule: connection reset",
            )
        }
        val useCase = ScheduleRunUseCaseImpl(port)

        val result = useCase.schedule(requirement)

        val refused = result as? ScheduleRunResult.Refused
        assertTrue(refused != null, "expected ScheduleRunResult.Refused, got $result")
        assertTrue(
            refused!!.reason.contains("connection reset"),
            "reason should reference transport: ${refused.reason}",
        )
    }

    // ------------------------------------------------------------------
    // Falsifier 4: Unknown -> ScheduleRunResult.Unknown
    // ------------------------------------------------------------------
    @Test
    fun `Unknown maps to ScheduleRunResult Unknown with reason`() = runBlocking {
        val port = FakeCapacityPortForH51().apply {
            nextDecision = ScheduleDecision.Unknown(
                reason = "pool has no record of worker-1 (a placement race)",
            )
        }
        val useCase = ScheduleRunUseCaseImpl(port)

        val result = useCase.schedule(requirement)

        val unknown = result as? ScheduleRunResult.Unknown
        assertTrue(unknown != null, "expected ScheduleRunResult.Unknown, got $result")
        assertTrue(
            unknown!!.reason.contains("placement race"),
            "reason should reference placement race: ${unknown.reason}",
        )
    }

    // ------------------------------------------------------------------
    // Falsifier 5: unexpected exception on the port -> Refused
    // ------------------------------------------------------------------
    @Test
    fun `unexpected exception on the port maps to Refused (not Unknown)`() = runBlocking {
        val port = FakeCapacityPortForH51().apply {
            throwOnSchedule = RuntimeException("Fabric gRPC stub: channel closed")
        }
        val useCase = ScheduleRunUseCaseImpl(port)

        val result = useCase.schedule(requirement)

        val refused = result as? ScheduleRunResult.Refused
        assertTrue(refused != null, "expected ScheduleRunResult.Refused on exception, got $result")
        assertTrue(
            refused!!.reason.contains("schedule failed"),
            "reason should reference 'schedule failed': ${refused.reason}",
        )
    }
}

// --------------------------------------------------------------------
// Test fixtures
// --------------------------------------------------------------------

private class FakeCapacityPortForH51 : CapacityPort {
    var nextDecision: ScheduleDecision = ScheduleDecision.NoCapacity("default")
    var throwOnSchedule: Throwable? = null
    var scheduleCalls: Int = 0
        private set
    var lastRequirement: WorkerRequirement? = null
        private set

    override suspend fun schedule(requirement: WorkerRequirement): ScheduleDecision {
        scheduleCalls += 1
        lastRequirement = requirement
        throwOnSchedule?.let { throw it }
        return nextDecision
    }
}
