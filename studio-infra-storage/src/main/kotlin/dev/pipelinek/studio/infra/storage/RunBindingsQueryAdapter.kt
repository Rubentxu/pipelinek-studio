@file:Suppress("TooManyFunctions")

package dev.pipelinek.studio.infra.storage

import dev.pipelinek.studio.application.ports.RecoveryBindingSnapshot
import dev.pipelinek.studio.application.ports.RunBindingsQueryPort
import dev.pipelinek.studio.domain.RunId
import dev.pipelinek.studio.domain.RunState

/**
 * SFW-H4.5: the SQLite-backed
 * [RunBindingsQueryPort] that the
 * [dev.pipelinek.studio.application.usecases.RecoveryUseCase]
 * uses to enumerate the bindings eligible for
 * reconciliation.
 *
 * ## What it returns
 *
 * Every row in `run_bindings` (no state
 * filter) — the [dev.pipelinek.studio.application.usecases.ReconcilerUseCase]
 * is the one with the intelligence: a
 * terminal binding (SUCCEEDED, FAILED,
 * CANCELLED) will return
 * [ReconcileDecision.Unchanged] or
 * [ReconcileDecision.Refused] (a local-only
 * binding has no Fabric record, so the
 * reconciler refuses it). The recovery
 * use case aggregates the per-binding
 * decisions into a 4-arm
 * [dev.pipelinek.studio.application.ports.RecoveryResult].
 *
 * ## Why no state filter
 *
 * Two reasons:
 * 1. The reconciler is the authority on
 *    "is this binding in sync with
 *    Fabric?" — the SQL filter would
 *    duplicate that logic;
 * 2. A binding the operator thought was
 *    terminal might be UNKNOWN from
 *    Fabric's perspective (e.g., a
 *    crash between the local commit
 *    and the Fabric admit). The recovery
 *    use case must be able to surface
 *    that as a Transition
 *    (UNKNOWN → whatever Fabric
 *    observed) so the operator sees the
 *    full picture.
 *
 * ## Why a new adapter (not reusing
 * [RunBindingsH2Store])
 *
 * The [RunBindingsH2Store] backs the
 * 4 H2.2c ports (the `BindingLookup`
 * seam) with single-row lookups by
 * `studio_run_id`. A full table scan
 * is a different access pattern and
 * belongs to a different port.
 *
 * ## Why no `fabricRunId` in the
 * returned projection
 *
 * The use-case layer is Fabric-agnostic
 * (the hexagonal boundary keeps
 * `studio-application` from depending on
 * the Fabric module). The recovery use
 * case only needs (studioRunId, state);
 * a binding's `fabric_run_id` is the
 * reconciler's concern, not the
 * enumerator's.
 */
class RunBindingsQueryAdapter(
    private val db: StudioDatabase,
) : RunBindingsQueryPort {

    override fun listAllForRecovery(): List<RecoveryBindingSnapshot> = db.open().use { c ->
        c.prepareStatement(
            "SELECT studio_run_id, state FROM run_bindings",
        ).use { ps ->
            ps.executeQuery().use { rs ->
                val out = mutableListOf<RecoveryBindingSnapshot>()
                while (rs.next()) {
                    out += RecoveryBindingSnapshot(
                        studioRunId = RunId(rs.getString("studio_run_id")),
                        state = RunState.valueOf(rs.getString("state")),
                    )
                }
                out
            }
        }
    }
}
