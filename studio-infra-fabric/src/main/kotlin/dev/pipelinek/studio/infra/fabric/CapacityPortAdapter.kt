package dev.pipelinek.studio.infra.fabric

import dev.pipelinek.studio.application.ports.CapacityPort
import dev.pipelinek.studio.application.ports.ScheduleDecision
import dev.pipelinek.studio.application.ports.WorkerRequirement

/**
 * SFW-H5.1: the stub [CapacityPort] production
 * adapter. H5.1 ships the abstract surface;
 * the actual Fabric `WorkerPool` RPC is a
 * separate work-unit (H5.2+).
 *
 * ## What it does
 *
 * For H5.1, the adapter returns
 * [ScheduleDecision.NoCapacity] for every
 * request with a "Fabric WorkerPool RPC not
 * yet wired; H5.2+ work-unit will ship the
 * actual RPC" reason. The stub is honest
 * about the unwired state: the future H5.2+
 * sub-milestone will replace the stub body
 * with a real Fabric gRPC client.
 *
 * ## Why a stub (not a real Fabric RPC) in H5.1
 *
 * The actual Fabric `WorkerPool` RPC is a
 * Fabric-side change (a new gRPC service +
 * a new proto). Per the SFW mandate:
 * "Avanza una unidad de trabajo coherente
 * por iteración: contrato → implementación
 * focal → verificación → evidencia". The
 * H5.1 unit is the Studio-side abstract
 * surface; the H5.2+ unit is the Fabric-side
 * RPC. Splitting the two units keeps each
 * one small and verifiable.
 */
class CapacityPortAdapter : CapacityPort {

    override suspend fun schedule(requirement: WorkerRequirement): ScheduleDecision =
        ScheduleDecision.NoCapacity(
            reason = "Fabric WorkerPool RPC not yet wired; " +
                "H5.2+ work-unit will ship the actual RPC",
        )
}
