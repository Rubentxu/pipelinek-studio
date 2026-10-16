package dev.pipelinek.studio.application.usecases

import dev.pipelinek.studio.application.ports.CapacityPort
import dev.pipelinek.studio.application.ports.PlacementAuditPort
import dev.pipelinek.studio.application.ports.ScheduleDecision
import dev.pipelinek.studio.application.ports.ScheduleRunResult
import dev.pipelinek.studio.application.ports.ScheduleRunUseCase
import dev.pipelinek.studio.application.ports.WorkerRequirement

/**
 * SFW-H5.1 + SFW-H5.6: the implementation of
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
 * 3. **SFW-H5.6: when the result is
 *    [ScheduleRunResult.Placed(workerId,
 *    region)], if [placementAuditPort] is
 *    wired, call
 *    `placementAuditPort.recordPlacement(
 *    studioRunId, workerId, region)`. The
 *    port call is best-effort: a port
 *    failure is NOT surfaced to the
 *    operator; the use case still returns
 *    [ScheduleRunResult.Placed(workerId,
 *    region)]. The 3 other arms do NOT
 *    trigger the port.**
 * 4. wraps unexpected exceptions in
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
 * H5.1 mirrors the discipline.
 *
 * @param capacityPort the [CapacityPort]
 *   the use case delegates to. The
 *   production boot wires a
 *   [dev.pipelinek.studio.infra.fabric.CapacityPortAdapter]
 *   (the H5.1 stub; the future H5.2+
 *   real Fabric gRPC client).
 * @param placementAuditPort the SFW-H5.6
 *   [PlacementAuditPort] the use case
 *   calls after the `Placed` arm is
 *   returned. When null, the use case
 *   does NOT call any port (the H5.1
 *   stub path is preserved: the use case
 *   still returns [ScheduleRunResult.Placed]
 *   regardless of the port's presence).
 */
class ScheduleRunUseCaseImpl(
    private val capacityPort: CapacityPort,
    /**
     * SFW-H5.6: the optional
     * [PlacementAuditPort] the use case
     * calls after the `Placed` arm is
     * returned. When null, the use case
     * does NOT call any port (the H5.1
     * stub path is preserved).
     */
    private val placementAuditPort: PlacementAuditPort? = null,
) : ScheduleRunUseCase {

    override suspend fun schedule(requirement: WorkerRequirement): ScheduleRunResult {
        val result: ScheduleRunResult = try {
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
            return ScheduleRunResult.Refused(
                reason = "schedule failed: ${failure.javaClass.simpleName}: ${failure.message}",
            )
        }
        // SFW-H5.6: when the result is Placed, call
        // the placementAuditPort (if wired). The
        // port call is best-effort: a port failure
        // is NOT surfaced to the operator; the
        // use case still returns the Placed result.
        val studioRunId = requirement.studioRunId
        if (result is ScheduleRunResult.Placed && placementAuditPort != null && studioRunId != null) {
            try {
                placementAuditPort.recordPlacement(
                    studioRunId = studioRunId,
                    workerId = result.workerId,
                    region = result.region,
                )
            } catch (failure: Exception) {
                // The H5.6 contract: a port failure
                // is a transient error. The H5.4
                // route still returns 200 OK with
                // the Placed body. A future H5+
                // retry job can replay the
                // persistence; surfacing the port
                // failure to the operator would
                // require a different status code.
            }
        }
        return result
    }
}
