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

    fun createExperiment(name: String): ExperimentSnapshot = db.open().use { c ->
        val id = ExperimentId.new()
        c.prepareStatement(
            "INSERT INTO experiments(experiment_id, name, created_at) VALUES(?, ?, ?)",
        ).use { ps ->
            ps.setString(1, id.value)
            ps.setString(2, name)
            ps.setString(3, Instant.now().toString())
            ps.executeUpdate()
        }
        ExperimentSnapshot(id, name, Instant.now())
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

    private fun runFromRow(rs: java.sql.ResultSet) = RunBindingSnapshot(
        RunId(rs.getString("studio_run_id")),
        ExperimentId(rs.getString("experiment_id")),
        SnapshotId(rs.getString("snapshot_id")),
        ScenarioId(rs.getString("scenario_id")),
        rs.getString("state"),
        Instant.parse(rs.getString("created_at")),
    )

    fun persistRunBinding(binding: RunBinding) {
        db.open().use { c ->
            c.prepareStatement(
                """
                INSERT OR REPLACE INTO run_bindings(
                  studio_run_id, experiment_id, snapshot_id, scenario_id,
                  upstream_run_id, run_db_ref, control_root_ref,
                  runtime_version, runtime_digest, plugin_set_digest,
                  state, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
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

class IdempotencyRepository(private val db: StudioDatabase) : IdempotencyPort {

    override fun lookup(scope: String, key: String, requestDigestSha256: String): IdempotencyLookupResult =
        db.open().use { c ->
            c.prepareStatement(
                "SELECT request_digest, operation_id FROM idempotency WHERE scope_id = ? AND operation_name = 'run' AND key = ?",
            ).use { ps ->
                ps.setString(1, scope)
                ps.setString(2, key)
                ps.executeQuery().use { rs ->
                    if (!rs.next()) return@use IdempotencyLookupResult.NotFound
                    val stored = rs.getString("request_digest")
                    val op = rs.getString("operation_id")
                    if (stored == requestDigestSha256 && op != null) {
                        IdempotencyLookupResult.Match(OperationId(op))
                    } else {
                        IdempotencyLookupResult.Conflict("request_digest mismatch")
                    }
                }
            }
        }

    override fun record(scope: String, key: String, operationId: OperationId, requestDigestSha256: String): IdempotencyRecordResult =
        db.open().use { c ->
            c.prepareStatement(
                """
                INSERT INTO idempotency(scope_id, operation_name, key, request_digest, operation_id, created_at)
                VALUES(?, 'run', ?, ?, ?, ?)
                ON CONFLICT(scope_id, operation_name, key) DO NOTHING
                """.trimIndent(),
            ).use { ps ->
                ps.setString(1, scope)
                ps.setString(2, key)
                ps.setString(3, requestDigestSha256)
                ps.setString(4, operationId.value)
                ps.setString(5, Instant.now().toString())
                val updated = ps.executeUpdate()
                if (updated == 1) IdempotencyRecordResult.Recorded
                else IdempotencyRecordResult.AlreadyExists(operationId)
            }
        }
}
