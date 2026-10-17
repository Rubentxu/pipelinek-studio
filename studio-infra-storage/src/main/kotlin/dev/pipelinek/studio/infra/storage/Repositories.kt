package dev.pipelinek.studio.infra.storage

import dev.pipelinek.studio.application.ports.*
import dev.pipelinek.studio.domain.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
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

    /**
     * R5 EVO-C.T3 (2026-10-16): collection read for `GET /experiments`.
     * Ordering + pagination mirror [listScenarios] (`created_at DESC`,
     * `LIMIT`/`OFFSET`) so the read surface has one pagination semantic.
     * Immutability is enforced on write (`ON CONFLICT DO NOTHING`), so the
     * collection can never contain two rows for one `experiment_id`.
     */
    override fun listExperiments(limit: Int, offset: Int): List<ExperimentSnapshot> {
        require(limit in 1..500) { "limit must be in 1..500, got $limit" }
        require(offset >= 0) { "offset must be >= 0, got $offset" }
        return db.open().use { c ->
            c.prepareStatement(
                "SELECT experiment_id, name, created_at FROM experiments ORDER BY created_at DESC LIMIT ? OFFSET ?",
            ).use { ps ->
                ps.setInt(1, limit)
                ps.setInt(2, offset)
                ps.executeQuery().use { rs ->
                    val out = mutableListOf<ExperimentSnapshot>()
                    while (rs.next()) out += experimentFromRow(rs)
                    out
                }
            }
        }
    }

    /**
     * R5 EVO-C.T3 (2026-10-16): collection read for
     * `GET /experiments/{experimentId}/snapshots`. Same ordering +
     * pagination contract as [listExperiments], filtered by the canonical
     * `experiment_id` column so the client never has to associate by name.
     */
    override fun listSnapshots(experimentId: ExperimentId, limit: Int, offset: Int): List<SnapshotSnapshot> {
        require(limit in 1..500) { "limit must be in 1..500, got $limit" }
        require(offset >= 0) { "offset must be >= 0, got $offset" }
        return db.open().use { c ->
            c.prepareStatement(
                "SELECT snapshot_id, experiment_id, sha256, created_at FROM snapshots " +
                    "WHERE experiment_id = ? ORDER BY created_at DESC LIMIT ? OFFSET ?",
            ).use { ps ->
                ps.setString(1, experimentId.value)
                ps.setInt(2, limit)
                ps.setInt(3, offset)
                ps.executeQuery().use { rs ->
                    val out = mutableListOf<SnapshotSnapshot>()
                    while (rs.next()) out += snapshotFromRow(rs)
                    out
                }
            }
        }
    }

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
        c.prepareStatement("SELECT scenario_id, version, plugin_lock_digest, environment_profile FROM scenarios WHERE scenario_id = ?").use { ps ->
            ps.setString(1, id.value)
            ps.executeQuery().use { rs -> if (rs.next()) scenarioFromRow(rs) else null }
        }
    }

    // R0.3.1 (2026-10-11): list scenarios ordered by created_at DESC.
// Mirrors [listRuns] pagination contract. Returns an empty list when
// the table is empty.
    override fun listScenarios(limit: Int, offset: Int): List<ScenarioSnapshot> {
        require(limit in 1..500) { "limit must be in 1..500, got $limit" }
        require(offset >= 0) { "offset must be >= 0, got $offset" }
        return db.open().use { c ->
            c.prepareStatement(
                "SELECT scenario_id, version, plugin_lock_digest, environment_profile " +
                    "FROM scenarios ORDER BY created_at DESC LIMIT ? OFFSET ?",
            ).use { ps ->
                ps.setInt(1, limit)
                ps.setInt(2, offset)
                ps.executeQuery().use { rs ->
                    val out = mutableListOf<ScenarioSnapshot>()
                    while (rs.next()) out.add(scenarioFromRow(rs))
                    out
                }
            }
        }
    }

    // R0.3.1 (2026-10-11) + R0.3.9 (2026-10-11): persist a freshly-
    // registered scenario. `INSERT ... ON CONFLICT(scenario_id) DO
    // NOTHING` mirrors the experiment/snapshot idempotency contract.
    // The version field is monotonic per scenario id within a single
    // studio.db lifetime; the application-level use case enforces
    // this by reading before insert and rejecting mismatched
    // versions. Schema stays single-column PK on scenario_id (matches
    // ADR-005 immutability for registered scenarios).
    //
    // R0.3.9 fix: the V1 schema declares `environment_profile TEXT
    // NOT NULL`. R0.3.1's INSERT omitted that column, so a fresh
    // scenario registration against the real repository would have
    // raised SQLITE_CONSTRAINT_NOTNULL. R0.3.9 binds the
    // SourceScenario.environmentProfile into the row alongside the
    // other three identity fields (snapshot, scenario, runtime,
    // plugins, profile).
    override fun saveScenario(scenario: SourceScenario) {
        db.open().use { c ->
            c.prepareStatement(
                "INSERT INTO scenarios(scenario_id, version, plugin_lock_digest, environment_profile, created_at) " +
                    "VALUES(?, ?, ?, ?, ?) " +
                    "ON CONFLICT(scenario_id) DO NOTHING",
            ).use { ps ->
                ps.setString(1, scenario.id.value)
                ps.setInt(2, scenario.version)
                ps.setString(3, scenario.pluginLockDigest)
                ps.setString(4, scenario.environmentProfile)
                ps.setString(5, scenario.createdAt.toString())
                ps.executeUpdate()
            }
        }
    }

    private fun scenarioFromRow(rs: java.sql.ResultSet) = ScenarioSnapshot(
        ScenarioId(rs.getString("scenario_id")),
        rs.getInt("version"),
        rs.getString("plugin_lock_digest"),
        rs.getString("environment_profile") ?: SourceScenario.DEFAULT_PROFILE,
    )

    /**
     * R2.5 (2026-10-11): encode the frozen plugin set as a JSON
     * array string for storage in `run_bindings.frozen_plugin_digests`.
     *
     * The list is expected to be already sorted by [FrozenPluginDigest.jarFilename]
     * (the use case does that before calling `persistRunBinding`).
     * Empty list → `"[]"`. The shape mirrors the admission lock
     * file's `admitted` array (R2.3) so a future UI can read either
     * without a custom decoder.
     */
    internal fun encodeFrozenPluginDigests(freeze: List<FrozenPluginDigest>): String {
        val arr = buildJsonArray {
            for (f in freeze) {
                add(buildJsonObject {
                    put("jarFilename", f.jarFilename)
                    put("sha256", f.sha256)
                    put("admittedBy", f.admittedBy)
                    put("admittedAt", f.admittedAt.toString())
                    put("frozenAt", f.frozenAt.toString())
                })
            }
        }
        return arr.toString()
    }

    /**
     * R2.5 (2026-10-11): decode the JSON-encoded freeze column. The
     * shape is fixed: an array of objects with the same 5 fields as
     * [encodeFrozenPluginDigests]. Missing column → `emptyList()`
     * (legacy rows pre-R2.5). Empty string → `emptyList()`. Malformed
     * payload → `emptyList()` (we do not crash the read on a corrupt
     * freeze; the read surface is best-effort and a bad row is a
     * data-integrity bug to surface via separate audit, not a 500).
     */
    internal fun decodeFrozenPluginDigests(text: String?): List<FrozenPluginDigest> {
        if (text.isNullOrBlank()) return emptyList()
        return try {
            val arr = Json.parseToJsonElement(text) as? JsonArray ?: return emptyList()
            arr.mapNotNull { el ->
                val obj = el as? JsonObject ?: return@mapNotNull null
                val jar = obj["jarFilename"]?.toString()?.trim('"') ?: return@mapNotNull null
                val sha = obj["sha256"]?.toString()?.trim('"') ?: return@mapNotNull null
                val by = obj["admittedBy"]?.toString()?.trim('"') ?: return@mapNotNull null
                val atRaw = obj["admittedAt"]?.toString()?.trim('"') ?: return@mapNotNull null
                val frRaw = obj["frozenAt"]?.toString()?.trim('"') ?: return@mapNotNull null
                FrozenPluginDigest(
                    jarFilename = jar,
                    sha256 = sha,
                    admittedBy = by,
                    admittedAt = try { Instant.parse(atRaw) } catch (_: Throwable) { return@mapNotNull null },
                    frozenAt = try { Instant.parse(frRaw) } catch (_: Throwable) { return@mapNotNull null },
                )
            }
        } catch (_: Throwable) {
            emptyList()
        }
    }

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

    // R-DET-001 follow-up (2026-10-11): the `ps.setString(N, …)`
    // sequence below is parameterised SQL where N tracks the
    // INSERT column order (1..13) declared in the SQL string
    // immediately above. The literal indexes mirror the
    // schema definition; renaming them to constants would
    // hide the order behind an indirection that doesn't help
    // the reader. The MagicNumber suppression documents
    // the contract in-place.
    @Suppress("MagicNumber")
    override fun persistRunBinding(binding: RunBinding) {
        db.open().use { c ->
            // B1.2: a run binding is immutable. State transitions go to
            // run_state_history, not back to this row. The SQL is now
            // `INSERT ... ON CONFLICT(studio_run_id) DO NOTHING` — a
            // re-persist of the same run_id is a no-op, not an
            // overwrite. (B1.4 will swap this for a transaction that
            // also covers the idempotency row, so 50 concurrent
            // identical requests collapse into a single row.)
            //
            // R2.5 (2026-10-11): column 14 is the JSON-encoded
            // freeze. `INSERT ... ON CONFLICT DO NOTHING` means a
            // re-persist of the same binding is a no-op, so the
            // freeze cannot be retroactively edited by a re-call.
            c.prepareStatement(
                """
                INSERT INTO run_bindings(
                  studio_run_id, experiment_id, snapshot_id, scenario_id,
                  upstream_run_id, run_db_ref, control_root_ref,
                  runtime_version, runtime_digest, plugin_set_digest,
                  state, created_at, updated_at, frozen_plugin_digests
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
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
                ps.setString(14, encodeFrozenPluginDigests(binding.frozenPluginDigests))
                ps.executeUpdate()
            }
            recordTransition(c, binding.runId, null, binding.state)
        }
    }

    /**
     * SFW-H4.5: transition a run binding's
     * [state] column. Called by the
     * [dev.pipelinek.studio.application.usecases.RecoveryUseCase]
     * when the H4.4 [ReconcilerUseCase] returns
     * a [ReconcileDecision.Transition].
     *
     * The UPDATE only touches the `state` +
     * `updated_at` columns; a missing row is
     * silently ignored (the recovery use case
     * already had the previous state from the
     * [RunBindingsQueryPort] snapshot, and the
     * row's absence is a data race the recovery
     * use case will see as a refused binding on
     * the next iteration). A future
     * `transitionRunStateWithReason` variant
     * could also append to `run_state_history`
     * (the B2.5 `transitionState` does this for
     * the in-process reconciler).
     */
    override fun transitionRunState(studioRunId: RunId, newState: RunState) {
        db.open().use { c ->
            c.prepareStatement(
                "UPDATE run_bindings SET state = ?, updated_at = ? WHERE studio_run_id = ?",
            ).use { ps ->
                ps.setString(1, newState.name)
                ps.setString(2, Instant.now().toString())
                ps.setString(3, studioRunId.value)
                ps.executeUpdate()
            }
        }
    }

    /**
     * R2.5 (2026-10-11): read back the full [RunBinding] (not the
     * [RunBindingSnapshot] projection that `findRun` returns) so the
     * freeze column is observable. Returns null when the binding
     * is missing. Legacy rows pre-R2.5 have `frozen_plugin_digests`
     * NULL → empty list.
     */
    fun findRunBinding(id: RunId): RunBinding? = db.open().use { c ->
        c.prepareStatement(
            """
            SELECT studio_run_id, experiment_id, snapshot_id, scenario_id,
                   upstream_run_id, run_db_ref, control_root_ref,
                   runtime_version, runtime_digest, plugin_set_digest,
                   state, created_at, updated_at, frozen_plugin_digests
            FROM run_bindings WHERE studio_run_id = ?
            """.trimIndent(),
        ).use { ps ->
            ps.setString(1, id.value)
            ps.executeQuery().use { rs ->
                if (!rs.next()) return@use null
                RunBinding(
                    runId = RunId(rs.getString("studio_run_id")),
                    experimentId = ExperimentId(rs.getString("experiment_id")),
                    snapshotId = SnapshotId(rs.getString("snapshot_id")),
                    scenarioId = ScenarioId(rs.getString("scenario_id")),
                    upstreamRunId = rs.getString("upstream_run_id")?.let { UpstreamRunId(it) },
                    runDbRef = rs.getString("run_db_ref"),
                    controlRootRef = rs.getString("control_root_ref"),
                    runtimeVersion = rs.getString("runtime_version"),
                    runtimeDigest = rs.getString("runtime_digest"),
                    pluginSetDigest = rs.getString("plugin_set_digest"),
                    state = RunState.valueOf(rs.getString("state")),
                    createdAt = Instant.parse(rs.getString("created_at")),
                    updatedAt = Instant.parse(rs.getString("updated_at")),
                    frozenPluginDigests = decodeFrozenPluginDigests(rs.getString("frozen_plugin_digests")),
                )
            }
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
