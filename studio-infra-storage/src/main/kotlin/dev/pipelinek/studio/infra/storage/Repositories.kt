package dev.pipelinek.studio.infra.storage

import dev.pipelinek.studio.application.ports.*
import dev.pipelinek.studio.domain.*
import java.nio.file.Path
import java.sql.Timestamp
import java.time.Instant

/**
 * SQL-backed implementation of the metadata + idempotency ports.
 *
 * Single-writer-per-DB invariant: only one Studio process opens `studio.db` in writer mode.
 * Multiple readers are allowed (SQLite WAL). See DATA_MODEL_AND_PERSISTENCE.md.
 */
class StudioMetadataRepository(private val db: StudioDatabase) : StudioMetadataPort {

    override fun findExperiment(id: ExperimentId): ExperimentSnapshot? = db.open().use { c ->
        c.prepareStatement("SELECT experiment_id, name, created_at FROM experiments WHERE experiment_id = ?").use { ps ->
            ps.setString(1, id.value)
            ps.executeQuery().use { rs -> if (rs.next()) experimentFromRow(rs) else null }
        }
    }

    /**
     * B1.1: persist a freshly-created experiment. The use case owns the
     * ExperimentId and the createdAt timestamp (from the injected clock);
     * the port never invents an id. The SQL is `INSERT ... ON CONFLICT
     * DO NOTHING` because an experiment is immutable once sealed — a
     * re-persist of the same ExperimentId is a no-op, not an overwrite
     * (per ADR-005 immutability + B1 mandate "eliminar INSERT OR REPLACE").
     */
    override fun saveExperiment(experiment: Experiment) {
        db.open().use { c ->
            c.prepareStatement(
                "INSERT INTO experiments(experiment_id, name, created_at) VALUES(?, ?, ?) " +
                    "ON CONFLICT(experiment_id) DO NOTHING",
            ).use { ps ->
                ps.setString(1, experiment.id.value)
                ps.setString(2, experiment.name)
                ps.setString(3, experiment.createdAt.toString())
                ps.executeUpdate()
            }
        }
    }

    private fun experimentFromRow(rs: java.sql.ResultSet) = ExperimentSnapshot(
        ExperimentId(rs.getString("experiment_id")),
        rs.getString("name"),
        Instant.parse(rs.getString("created_at")),
    )

    override fun findSnapshot(id: SnapshotId): SnapshotSnapshot? = db.open().use { c ->
        c.prepareStatement("SELECT snapshot_id, experiment_id, sha256, created_at FROM snapshots WHERE snapshot_id = ?").use { ps ->
            ps.setString(1, id.value)
            ps.executeQuery().use { rs -> if (rs.next()) snapshotFromRow(rs) else null }
        }
    }

    // S1 E1.5 + B1.2: persist snapshot metadata + source bytes (BLOB).
    // Per ADR-005 (snapshot immutability; sha256 is the source of truth)
    // and the B1 mandate (\"eliminar INSERT OR REPLACE\"), the SQL is
    // now `INSERT ... ON CONFLICT(snapshot_id) DO NOTHING`. A re-persist
    // of the same snapshot_id is a no-op — it never overwrites the
    // original bytes. The `UNIQUE(experiment_id, sha256)` constraint
    // still rejects a re-seal of the same bytes under a different
    // snapshot_id, which is the second-layer invariant the mandate
    // requires.
    override fun saveSnapshot(snap: SourceSnapshot) {
        db.open().use { c ->
            c.prepareStatement(
                "INSERT INTO snapshots(" +
                    "snapshot_id, experiment_id, sha256, source_utf8, creator, created_at" +
                    ") VALUES(?, ?, ?, ?, ?, ?) " +
                    "ON CONFLICT(snapshot_id) DO NOTHING",
            ).use { ps ->
                ps.setString(1, snap.id.value)
                ps.setString(2, snap.experimentId.value)
                ps.setString(3, snap.sha256.value)
                ps.setBytes(4, snap.sourceUtf8)
                ps.setString(5, snap.creator)
                ps.setString(6, snap.createdAt.toString())
                ps.executeUpdate()
            }
        }
    }

    // S1 E1.5: read the source bytes back. Returns null on missing row;
    // the caller MUST refuse to fall back to a placeholder, because that
    // would silently run a different script than the user sealed.
    override fun loadSnapshotSource(id: SnapshotId): ByteArray? = db.open().use { c ->
        c.prepareStatement("SELECT source_utf8 FROM snapshots WHERE snapshot_id = ?").use { ps ->
            ps.setString(1, id.value)
            ps.executeQuery().use { rs -> if (rs.next()) rs.getBytes("source_utf8") else null }
        }
    }

    private fun snapshotFromRow(rs: java.sql.ResultSet) = SnapshotSnapshot(
        SnapshotId(rs.getString("snapshot_id")),
        ExperimentId(rs.getString("experiment_id")),
        rs.getString("sha256"),
        Instant.parse(rs.getString("created_at")),
    )

    override fun findScenario(id: ScenarioId): ScenarioSnapshot? = db.open().use { c ->
        c.prepareStatement("SELECT scenario_id, version, plugin_lock_digest FROM scenarios WHERE scenario_id = ?").use { ps ->
            ps.setString(1, id.value)
            ps.executeQuery().use { rs -> if (rs.next()) scenarioFromRow(rs) else null }
        }
    }

    private fun scenarioFromRow(rs: java.sql.ResultSet) = ScenarioSnapshot(
        ScenarioId(rs.getString("scenario_id")),
        rs.getInt("version"),
        rs.getString("plugin_lock_digest"),
    )

    override fun findRun(id: RunId): RunBindingSnapshot? = db.open().use { c ->
        c.prepareStatement(
            "SELECT studio_run_id, experiment_id, snapshot_id, scenario_id, state, created_at FROM run_bindings WHERE studio_run_id = ?",
        ).use { ps ->
            ps.setString(1, id.value)
            ps.executeQuery().use { rs -> if (rs.next()) runFromRow(rs) else null }
        }
    }

    override fun listRuns(
        state: dev.pipelinek.studio.domain.RunState?,
        limit: Int,
        offset: Int,
    ): List<RunBindingSnapshot> = db.open().use { c ->
        // Defensive bounds — callers can ask for less, but never more.
        val boundedLimit = limit.coerceIn(1, 200)
        val boundedOffset = offset.coerceAtLeast(0)
        val sql = if (state == null) {
            "SELECT studio_run_id, experiment_id, snapshot_id, scenario_id, state, created_at FROM run_bindings ORDER BY created_at DESC LIMIT ? OFFSET ?"
        } else {
            "SELECT studio_run_id, experiment_id, snapshot_id, scenario_id, state, created_at FROM run_bindings WHERE state = ? ORDER BY created_at DESC LIMIT ? OFFSET ?"
        }
        c.prepareStatement(sql).use { ps ->
            var idx = 1
            if (state != null) {
                ps.setString(idx, state.name); idx++
            }
            ps.setInt(idx, boundedLimit); idx++
            ps.setInt(idx, boundedOffset)
            ps.executeQuery().use { rs ->
                val out = mutableListOf<RunBindingSnapshot>()
                while (rs.next()) out += runFromRow(rs)
                out
            }
        }
    }

    private fun runFromRow(rs: java.sql.ResultSet) = RunBindingSnapshot(
        RunId(rs.getString("studio_run_id")),
        ExperimentId(rs.getString("experiment_id")),
        SnapshotId(rs.getString("snapshot_id")),
        ScenarioId(rs.getString("scenario_id")),
        rs.getString("state"),
        Instant.parse(rs.getString("created_at")),
    )

    override fun persistRunBinding(binding: RunBinding) {
        db.open().use { c ->
            // B1.2: a run binding is immutable. State transitions go to
            // run_state_history, not back to this row. The SQL is now
            // `INSERT ... ON CONFLICT(studio_run_id) DO NOTHING` — a
            // re-persist of the same run_id is a no-op, not an
            // overwrite. (B1.4 will swap this for a transaction that
            // also covers the idempotency row, so 50 concurrent
            // identical requests collapse into a single row.)
            c.prepareStatement(
                """
                INSERT INTO run_bindings(
                  studio_run_id, experiment_id, snapshot_id, scenario_id,
                  upstream_run_id, run_db_ref, control_root_ref,
                  runtime_version, runtime_digest, plugin_set_digest,
                  state, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(studio_run_id) DO NOTHING
                """.trimIndent(),
            ).use { ps ->
                ps.setString(1, binding.runId.value)
                ps.setString(2, binding.experimentId.value)
                ps.setString(3, binding.snapshotId.value)
                ps.setString(4, binding.scenarioId.value)
                ps.setString(5, binding.upstreamRunId?.value)
                ps.setString(6, binding.runDbRef)
                ps.setString(7, binding.controlRootRef)
                ps.setString(8, binding.runtimeVersion)
                ps.setString(9, binding.runtimeDigest)
                ps.setString(10, binding.pluginSetDigest)
                ps.setString(11, binding.state.name)
                ps.setString(12, binding.createdAt.toString())
                ps.setString(13, binding.updatedAt.toString())
                ps.executeUpdate()
            }
            recordTransition(c, binding.runId, null, binding.state)
        }
    }

    fun recordTransition(runId: RunId, from: RunState?, to: RunState, reason: String? = null) {
        db.open().use { c -> recordTransition(c, runId, from, to, reason) }
    }

    /**
     * B2.5 (2026-10-10): the read side of the run_state_history
     * table. The HTTP layer (and tests) can verify that a
     * transition was recorded with the correct from_state /
     * to_state / reason / observed_at shape.
     */
    fun findRunStateHistory(runId: RunId): List<RunStateHistoryEntry> = db.open().use { c ->
        c.prepareStatement(
            "SELECT from_state, to_state, observed_at, reason FROM run_state_history WHERE studio_run_id = ? ORDER BY id ASC",
        ).use { ps ->
            ps.setString(1, runId.value)
            ps.executeQuery().use { rs ->
                val out = mutableListOf<RunStateHistoryEntry>()
                while (rs.next()) out += RunStateHistoryEntry(
                    fromState = rs.getString("from_state"),
                    toState = rs.getString("to_state"),
                    observedAt = Instant.parse(rs.getString("observed_at")),
                    reason = rs.getString("reason"),
                )
                out
            }
        }
    }

    data class RunStateHistoryEntry(
        val fromState: String?,
        val toState: String,
        val observedAt: Instant,
        val reason: String?,
    )

    /**
     * B2.5 (2026-10-10): update the run_bindings.state column AND
     * append the transition to run_state_history in one
     * transaction. The pre-B2.5 recordTransition only logged the
     * transition; the row kept its pre-crash state, so a GET
     * /runs/{id} after restart would report the old state for a
     * run whose subprocess was actually gone. The new method
     * makes the reconciliation durable: the HTTP layer reads
     * the new state directly from the row.
     */
    fun transitionState(
        runId: RunId,
        to: RunState,
        reason: String,
        observedAt: Instant?,
    ) {
        db.open().use { c ->
            val from = c.prepareStatement(
                "SELECT state FROM run_bindings WHERE studio_run_id = ?",
            ).use { ps ->
                ps.setString(1, runId.value)
                ps.executeQuery().use { rs -> if (rs.next()) rs.getString("state") else null }
            }
            if (from == null) {
                // The run_bindings row is missing. The reconciler
                // walks the controlRoot looking at run.db files;
                // a missing studio-side row is possible if the
                // previous process died before B1.1's
                // CreateExperimentUseCase was rolled out. We do
                // not invent the run_bindings row here — the
                // reconciler only reconciles; the recovery
                // operator decides what to do.
                return
            }
            val fromState = RunState.valueOf(from)
            c.prepareStatement(
                "UPDATE run_bindings SET state = ?, updated_at = ? WHERE studio_run_id = ?",
            ).use { ps ->
                ps.setString(1, to.name)
                ps.setString(2, (observedAt ?: Instant.now()).toString())
                ps.setString(3, runId.value)
                ps.executeUpdate()
            }
            recordTransition(c, runId, fromState, to, reason)
        }
    }

    private fun recordTransition(c: java.sql.Connection, runId: RunId, from: RunState?, to: RunState, reason: String? = null) {
        c.prepareStatement(
            "INSERT INTO run_state_history(studio_run_id, from_state, to_state, observed_at, reason) VALUES(?,?,?,?,?)",
        ).use { ps ->
            ps.setString(1, runId.value)
            ps.setString(2, from?.name)
            ps.setString(3, to.name)
            ps.setString(4, Instant.now().toString())
            ps.setString(5, reason)
            ps.executeUpdate()
        }
    }
}

/**
 * B2.5 (2026-10-10): adapter that turns the new
 * `transitionState` repository method into the
 * [ReconcileOutcomePort] the [RunReconciler] calls. The
 * adapter is stateless and safe to share.
 */
class ReconcileOutcomeAdapter(
    private val repo: StudioMetadataRepository,
) : dev.pipelinek.studio.application.ports.ReconcileOutcomePort {
    override fun recordReconciled(
        runId: dev.pipelinek.studio.domain.RunId,
        state: dev.pipelinek.studio.domain.RunState,
        reason: String,
        observedAt: java.time.Instant?,
    ) {
        repo.transitionState(runId, state, reason, observedAt)
    }
}

class IdempotencyRepository(private val db: StudioDatabase) : IdempotencyPort {

    override fun lookup(scope: String, key: String, requestDigestSha256: String): IdempotencyLookupResult =
        db.open().use { c ->
            c.prepareStatement(
                "SELECT request_digest, operation_id, response_ref FROM idempotency WHERE scope_id = ? AND operation_name = 'run' AND key = ?",
            ).use { ps ->
                ps.setString(1, scope)
                ps.setString(2, key)
                ps.executeQuery().use { rs ->
                    if (!rs.next()) return@use IdempotencyLookupResult.NotFound
                    val stored = rs.getString("request_digest")
                    val op = rs.getString("operation_id")
                    val ref = rs.getString("response_ref")
                    if (stored == requestDigestSha256 && op != null) {
                        IdempotencyLookupResult.Match(OperationId(op), ref)
                    } else {
                        IdempotencyLookupResult.Conflict("request_digest mismatch")
                    }
                }
            }
        }

    override fun record(
        scope: String,
        key: String,
        operationId: OperationId,
        requestDigestSha256: String,
        responseRef: String?,
    ): IdempotencyRecordResult =
        db.open().use { c ->
            c.prepareStatement(
                """
                INSERT INTO idempotency(scope_id, operation_name, key, request_digest, operation_id, response_ref, created_at)
                VALUES(?, 'run', ?, ?, ?, ?, ?)
                ON CONFLICT(scope_id, operation_name, key) DO NOTHING
                """.trimIndent(),
            ).use { ps ->
                ps.setString(1, scope)
                ps.setString(2, key)
                ps.setString(3, requestDigestSha256)
                ps.setString(4, operationId.value)
                ps.setString(5, responseRef)
                ps.setString(6, Instant.now().toString())
                val updated = ps.executeUpdate()
                if (updated == 1) IdempotencyRecordResult.Recorded
                else IdempotencyRecordResult.AlreadyExists(operationId)
            }
        }
}
