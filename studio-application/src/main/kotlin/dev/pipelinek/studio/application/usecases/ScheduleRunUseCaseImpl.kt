package dev.pipelinek.studio.application.usecases

import dev.pipelinek.studio.application.ports.CapacityPort
import dev.pipelinek.studio.application.ports.ScheduleDecision
import dev.pipelinek.studio.application.ports.ScheduleRunResult
import dev.pipelinek.studio.application.ports.ScheduleRunUseCase
import dev.pipelinek.studio.application.ports.WorkerRequirement

/**
 * SFW-H5.1: the implementation of
 * [ScheduleRunUseCase] that wraps the
 * [CapacityPort] with the 4-arm
 * `ScheduleRunResult` family.
 *
 * ## The dispatch in one paragraph
 *
 * The `schedule(requirement)` method:
 * 1. calls `capacityPort.schedule(requirement)`
 *    (the [CapacityPort] is the
 *    `studio-application`-side seam; the
 *    H5.1 stub returns
 *    [ScheduleDecision.NoCapacity] until
 *    the future H5.2+ Fabric `WorkerPool`
 *    RPC ships);
 * 2. maps the 4-arm [ScheduleDecision] to
 *    the 4-arm [ScheduleRunResult] (the
 *    mapping is identity: the shapes are
 *    the same by design);
 * 3. wraps unexpected exceptions in
 *    [ScheduleRunResult.Refused] (the
 *    H5.1 contract: "any unexpected
 *    exception on the schedule path is a
 *    refusal, not an unknown").
 *
 * ## Why a 4-arm `ScheduleRunResult`
 * (and not 3)
 *
 * The H4.1 4-arm `CancelRunResult`
 * discipline is load-bearing: a transport
 * failure is a refusal, not an unknown.
 * H5.1 mirrors the discipline: a
 * `Refused(reason)` is a transport failure
 * (the pool itself failed), a
 * `NoCapacity(reason)` is the pool's
 * deliberate "I have no worker that can
 * host this run" answer, an `Unknown(
 * reason)` is "the pool does not know the
 * worker" (a future H5+ race), and
 * `Placed(workerId, region)` is the
 * success. The future H5.2+ HTTP route
 * renders the 4 arms (200/507/503/202 or
 * similar; the H5.2+ sub-milestone will
 * pin the exact codes).
 *
 * @param capacityPort the [CapacityPort]
 *   the use case delegates to. The
 *   production boot wires a
 *   [dev.pipelinek.studio.infra.fabric.CapacityPortAdapter]
 *   (the H5.1 stub; the future H5.2+
 *   real Fabric gRPC client).
 */
class ScheduleRunUseCaseImpl(
    private val capacityPort: CapacityPort,
) : ScheduleRunUseCase {

    override suspend fun schedule(requirement: WorkerRequirement): ScheduleRunResult = try {
        when (val decision = capacityPort.schedule(requirement)) {
            is ScheduleDecision.Placed -> ScheduleRunResult.Placed(
                workerId = decision.workerId,
                region = decision.region,
            )
            is ScheduleDecision.NoCapacity -> ScheduleRunResult.NoCapacity(decision.reason)
            is ScheduleDecision.Refused -> ScheduleRunResult.Refused(decision.reason)
            is ScheduleDecision.Unknown -> ScheduleRunResult.Unknown(decision.reason)
        }
    } catch (failure: Exception) {
        // The H5.1 contract: any unexpected
        // exception on the schedule path is a
        // refusal, not an unknown. The future
        // H5.2+ HTTP route renders this as
        // `503 SCHEDULE_REFUSED`.
        ScheduleRunResult.Refused(
            reason = "schedule failed: ${failure.javaClass.simpleName}: ${failure.message}",
        )
    }
}
