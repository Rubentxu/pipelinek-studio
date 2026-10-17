@file:Suppress("TooGenericExceptionCaught")

package dev.pipelinek.studio.infra.storage

import dev.pipelinek.studio.domain.RunId
import dev.pipelinek.studio.domain.RunState
import dev.pipelinek.studio.infra.fabric.BindingLookup
import java.sql.Connection
import java.time.Instant
import javax.sql.DataSource

/**
 * SFW-H2.3: the SQLite-backed store for the 3-identity
 * binding between a Studio run and a Fabric run.
 *
 * ## What it backs
 *
 * The [BindingLookup] seam that the four H2.2c ports
 * (`FabricRunnerAdapter`, `FabricRunReadAdapter`,
 * `FabricOutputReadAdapter`,
 * `FabricRunBindingReconcilerAdapter`) consume. The seam
 * was a `fun interface` in H2.2c+1; H2.3 backs it with a
 * real table (`run_bindings` + the V3 columns
 * `fabric_run_id`, `pipelinek_run_id`, `package_digest`).
 *
 * ## What it does NOT do
 *
 * It does **not** create the `run_bindings` row. The row
 * is created by the local start-run flow (V1) before the
 * H2 path is taken. The H2.3 store is a column-update
 * store: a [recordAdmission] / [recordPipelinekRunId]
 * call updates the existing row or returns `false` if the
 * row is not there. The seam accepts the cost because the
 * alternative (inserting a row from H2.3) would either
 * duplicate the local row or split the binding into two
 * tables, both wrong.
 *
 * ## Why prepared statements
 *
 * The column values are untrusted (the `studioRunId` /
 * `fabricRunId` arrive from the wire, the `packageDigest`
 * from a SHA-256 over user bytes). The store has 4 SQL
 * statements; a builder would be premature, and prepared
 * statements are the conservative choice for a small,
 * stable query set.
 */
class RunBindingsH2Store(
    private val dataSource: DataSource,
) : BindingLookup {

    override fun fabricRunIdFor(studioRunId: RunId): io.jenkins.pipelinek.fabric.contracts.model.RunId? =
        dataSource.connection.use { conn ->
            conn.prepareStatement(
                "SELECT fabric_run_id FROM run_bindings WHERE studio_run_id = ?",
            ).use { ps ->
                ps.setString(1, studioRunId.value)
                ps.executeQuery().use { rs ->
                    if (rs.next()) {
                        val value = rs.getString(1)
                        if (value != null) {
                            io.jenkins.pipelinek.fabric.contracts.model.RunId(value)
                        } else {
                            null
                        }
                    } else {
                        null
                    }
                }
            }
        }

    /**
     * Record that a Studio run was admitted to Fabric with
     * the given [fabricRunId] (and against the given
     * [packageDigest]).
     *
     * Returns `true` iff one row was updated. Returns
     * `false` when the row does not exist (the local
     * start-run flow never created it — out of scope for
     * H2.3; the use-case layer is responsible for
     * creating the row before this method is called) and,
     * since Studio P0-04, also `false` when the row already
     * holds a **contradictory** established identity.
     *
     * ## P0-04 admission integrity
     *
     * `fabric_run_id` and `package_digest` are part of the
     * binding identity: the cancellation path cancels
     * exclusively through the persisted `fabric_run_id`, so
     * replacing an established value would silently retarget
     * a cancel to a different Fabric run. The guard is in the
     * `UPDATE`'s `WHERE` clause (not a read-before-write
     * check), so it is atomic with the write:
     *
     *  - a retry with the same `(fabricRunId, packageDigest)`
     *    updates the row and returns `true` (idempotent);
     *  - a call with a different `fabricRunId` or a different
     *    `packageDigest` matches zero rows, leaves the stored
     *    values untouched, and returns `false`;
     *  - a legacy or local-only row (`fabric_run_id` /
     *    `package_digest` IS NULL) still accepts the first
     *    admission.
     *
     * The other column setters ([cancelSelection],
     * [dedupKey]) keep their previous behaviour: they are
     * written only when non-null, on a row whose identity
     * did not contradict.
     */
    fun recordAdmission(
        studioRunId: RunId,
        fabricRunId: io.jenkins.pipelinek.fabric.contracts.model.RunId,
        packageDigest: String,
        /**
         * SFW-H4.7: the H2.4 [dev.pipelinek.studio.application.ports.RuntimeBackendSelector]
         * decision at submit time (the enum's `name`, e.g. "Local" or
         * "Fabric"). When non-null, the value is persisted in the
         * `run_bindings.cancel_selection` column so future cancel + retry
         * operations can read it back instead of re-running the selector.
         * Defaults to `null` for legacy callers (the column was added in
         * the V4 migration; legacy rows have `cancel_selection` IS NULL).
         */
        cancelSelection: String? = null,
        /**
         * SFW-H6.1: the dedup key (SHA-256 of the canonical
         * [dev.pipelinek.studio.application.ports.RunRequest] shape).
         * When non-null, the value is persisted in the
         * `run_bindings.dedup_key` column so future requests with
         * the same content can be deduplicated. Defaults to `null`
         * for legacy callers (the column was added in the V6
         * migration; legacy rows have `dedup_key` IS NULL).
         */
        dedupKey: String? = null,
    ): Boolean {
        // The identity columns are always written, so the SET
        // clause is fixed; the WHERE clause is what makes a
        // contradictory admission a no-op instead of an
        // overwrite. Keeping this in one statement (rather than
        // read-then-update) means two concurrent admissions
        // cannot both observe "no identity yet" and then both
        // write.
        val setClauses = buildList {
            add("fabric_run_id = ?")
            add("package_digest = ?")
            if (cancelSelection != null) add("cancel_selection = ?")
            if (dedupKey != null) add("dedup_key = ?")
            add("updated_at = ?")
        }
        val sql = "UPDATE run_bindings SET ${setClauses.joinToString(", ")} " +
            "WHERE studio_run_id = ? " +
            "AND (fabric_run_id IS NULL OR fabric_run_id = ?) " +
            "AND (package_digest IS NULL OR package_digest = ?)"
        val nowIso = Instant.now().toString()

        return dataSource.connection.use { conn ->
            conn.prepareStatement(sql).use { ps ->
                var idx = 1
                ps.setString(idx++, fabricRunId.value)
                ps.setString(idx++, packageDigest)
                if (cancelSelection != null) ps.setString(idx++, cancelSelection)
                if (dedupKey != null) ps.setString(idx++, dedupKey)
                ps.setString(idx++, nowIso)
                ps.setString(idx++, studioRunId.value)
                ps.setString(idx++, fabricRunId.value)
                ps.setString(idx, packageDigest)
                ps.executeUpdate() == 1
            }
        }
    }

    /**
     * Record the executor-side `RunId` (the id PipelineK's
     * subprocess minted) once a fact stream surfaces it.
     *
     * Returns `true` iff one row was updated. Returns
     * `false` when the row does not exist.
     */
    fun recordPipelinekRunId(
        studioRunId: RunId,
        pipelinekRunId: io.jenkins.pipelinek.fabric.contracts.model.RunId,
    ): Boolean = updateColumns(
        studioRunId = studioRunId,
        setPipelinekRunId = pipelinekRunId.value,
    )

    /**
     * SFW-H5.5: record the placed worker that
     * hosts this run. The placed worker is the
     * H5.1 [dev.pipelinek.studio.application.ports.ScheduleRunResult.Placed]
     * decision; a future H5.6+ use case change
     * will call this method after the Placed arm
     * is returned. H5.5 ships the persistence
     * only.
     *
     * Returns `true` iff one row was updated.
     * Returns `false` when the row does not
     * exist.
     */
    fun recordPlacement(
        studioRunId: RunId,
        workerId: String,
        region: String,
        placedAt: java.time.Instant = java.time.Instant.now(),
    ): Boolean = updateColumns(
        studioRunId = studioRunId,
        setPlacementAudit = encodePlacementAudit(
            PlacementAudit(
                workerId = workerId,
                region = region,
                placedAt = placedAt,
            ),
        ),
    )

    /**
     * SFW-H6.4 (2026-10-13): clear the dedup key
     * for a run (cancel-before-event releases the
     * dedup key so a future request with the same
     * content may re-submit). The H6.4 use case
     * calls this when the cancel succeeds AND the
     * binding state is `CREATED`. Returns `true`
     * iff one row was updated.
     */
    fun clearDedupKey(studioRunId: RunId): Boolean = updateColumns(
        studioRunId = studioRunId,
        clearDedupKey = true,
    )

    /**
     * SFW-H6.1: look up a run by its dedup key.
     * The H6.1 V6 migration adds the `dedup_key`
     * column to `run_bindings` + the
     * `idx_run_bindings_dedup_key` index. The
     * query uses the index for O(1) lookup.
     * The LIMIT 1 makes the lookup
     * deterministic: the first matching row
     * wins (the dedup is content-based; two
     * requests with the same content have the
     * same dedup key; the first run is the
     * dedup target).
     *
     * Returns a [DedupedRunSnapshot] with the
     * existing run's identity + state, or
     * `null` when no run with the given key
     * exists (a legacy row with `dedup_key IS
     * NULL` is NOT a match; the dedup key is
     * null; the row is not a dedup candidate).
     */
    fun findByDedupKey(dedupKey: String): dev.pipelinek.studio.application.ports.DedupedRunSnapshot? =
        dataSource.connection.use { conn ->
            conn.prepareStatement(
                "SELECT studio_run_id, state FROM run_bindings " +
                    "WHERE dedup_key = ? LIMIT 1",
            ).use { ps ->
                ps.setString(1, dedupKey)
                ps.executeQuery().use { rs ->
                    if (rs.next()) {
                        dev.pipelinek.studio.application.ports.DedupedRunSnapshot(
                            studioRunId = RunId(rs.getString(1)),
                            state = RunState.valueOf(rs.getString(2)),
                        )
                    } else {
                        null
                    }
                }
            }
        }

    /**
     * Read all 3 identity fields plus the package digest for
     * a binding. Returns `null` when the row does not exist
     * or when the row has no `fabric_run_id` (the local-only
     * path; the H2 path has not been taken).
     */
    fun getBinding(studioRunId: RunId): RunBindingRecord? =
        dataSource.connection.use { conn ->
            conn.prepareStatement(
                "SELECT fabric_run_id, pipelinek_run_id, package_digest, cancel_selection, placement_audit, dedup_key " +
                    "FROM run_bindings WHERE studio_run_id = ?",
            ).use { ps ->
                ps.setString(1, studioRunId.value)
                ps.executeQuery().use { rs ->
                    if (!rs.next()) return null
                    val fabricRunId = rs.getString(1) ?: return null
                    RunBindingRecord(
                        studioRunId = studioRunId,
                        fabricRunId = io.jenkins.pipelinek.fabric.contracts.model.RunId(fabricRunId),
                        pipelinekRunId = rs.getString(2)?.let {
                            io.jenkins.pipelinek.fabric.contracts.model.RunId(it)
                        },
                        packageDigest = rs.getString(3),
                        // SFW-H4.7: the H2.4 selector decision at submit
                        // time; null for pre-H4.7 rows. The H4.5 use case
                        // layer is Fabric-agnostic and does not read this
                        // field yet; future H4+ units may use it to
                        // dispatch cancel + retry operations to the
                        // original backend.
                        cancelSelection = rs.getString(4),
                        // SFW-H5.5: the placed worker that hosts this
                        // run; null for pre-H5.5 rows. The use case
                        // layer parses the JSON back to the
                        // [PlacementAudit] data class. A future H5.6+
                        // unit may extend the H5.1 ScheduleRunUseCaseImpl
                        // to call [recordPlacement] after the Placed
                        // arm is returned; H5.5 ships the persistence
                        // only.
                        placementAudit = rs.getString(5)?.let { decodePlacementAudit(it) },
                        // SFW-H6.1: the dedup key (SHA-256 of the
                        // canonical RunRequest shape); null for
                        // pre-H6.1 rows. The future H6.2+ use case
                        // integration will call
                        // [dev.pipelinek.studio.application.usecases.DedupRunUseCase.checkDuplicate]
                        // BEFORE the existing runner.submit(req)
                        // call; when the use case returns
                        // DedupRunResult.DedupedRun, the new submit
                        // is a noop. H6.1 ships the persistence only.
                        dedupKey = rs.getString(6),
                    )
                }
            }
        }

    /**
     * The 3 identity fields + the package digest for a
     * binding, as a value (not nullable per-field). The
     * factory method [getBinding] is the only way to
     * build one.
     */
    data class RunBindingRecord(
        val studioRunId: RunId,
        val fabricRunId: io.jenkins.pipelinek.fabric.contracts.model.RunId,
        val pipelinekRunId: io.jenkins.pipelinek.fabric.contracts.model.RunId?,
        val packageDigest: String?,
        /**
         * SFW-H4.7: the H2.4 [dev.pipelinek.studio.application.ports.RuntimeBackendSelector]
         * decision at submit time (the enum's `name`, e.g. "Local" or
         * "Fabric"). When `null`, the row is a pre-H4.7 row (the V4
         * migration added the column as NULL by default). Future H4+
         * units may dispatch cancel + retry operations to the original
         * backend using this field.
         */
        val cancelSelection: String? = null,
        /**
         * SFW-H5.5: the placed worker that hosts this run (the H5.1
         * [dev.pipelinek.studio.application.ports.ScheduleRunResult.Placed]
         * decision). When `null`, the row is a pre-H5.5 row (the V5
         * migration added the column as NULL by default). Future H5.6+
         * units may extend the H5.1 use case to call [recordPlacement]
         * after the Placed arm is returned; H5.5 ships the persistence
         * only.
         */
        val placementAudit: PlacementAudit? = null,
        /**
         * SFW-H6.1: the dedup key (SHA-256 of the canonical
         * [dev.pipelinek.studio.application.ports.RunRequest] shape:
         * `snapshotId + scenarioId + profile` joined with NUL). When
         * `null`, the row is a pre-H6.1 row (the V6 migration added
         * the column as NULL by default). Future H6.2+ units may
         * extend the
         * [dev.pipelinek.studio.application.usecases.StartRunUseCase]
         * to call
         * [dev.pipelinek.studio.application.usecases.DedupRunUseCase.checkDuplicate]
         * BEFORE the existing runner.submit(req) call; H6.1 ships
         * the persistence only.
         */
        val dedupKey: String? = null,
    )

    /**
     * SFW-H5.5: the structured audit record for
     * the placed worker. The H5.1
     * [dev.pipelinek.studio.application.ports.ScheduleRunResult.Placed]
     * decision includes `workerId` + `region`; the
     * `placedAt` is the wall-clock time the
     * `recordPlacement` call was made (a future
     * H5.6+ use case change will set this when
     * the use case is extended to call
     * `recordPlacement`).
     */
    data class PlacementAudit(
        val workerId: String,
        val region: String,
        val placedAt: java.time.Instant,
    )

    /**
     * Internal: a single UPDATE statement that takes
     * optional column values. The columns that are null in
     * the parameter list are **not** updated (they keep
     * their current value). Returns `true` iff one row
     * was updated.
     */
    private fun updateColumns(
        studioRunId: RunId,
        setFabricRunId: String? = null,
        setPipelinekRunId: String? = null,
        setPackageDigest: String? = null,
        /**
         * SFW-H4.7: the H2.4 [dev.pipelinek.studio.application.ports.RuntimeBackendSelector]
         * decision at submit time (the enum's `name`, e.g. "Local" or
         * "Fabric"). When non-null, the value is persisted in the
         * `run_bindings.cancel_selection` column. The H4.3 use case
         * does NOT read this field yet; future H4+ units may dispatch
         * cancel + retry operations to the original backend using it.
         */
        setCancelSelection: String? = null,
        /**
         * SFW-H5.5: the placed worker that hosts this
         * run. When non-null, the value is JSON-encoded
         * via [encodePlacementAudit] and persisted in
         * the `run_bindings.placement_audit` column.
         * The H5.1 use case does NOT call
         * [recordPlacement] yet; H5.5 ships the
         * persistence only.
         */
        setPlacementAudit: String? = null,
        /**
         * SFW-H6.1: the dedup key (SHA-256 of the canonical
         * [dev.pipelinek.studio.application.ports.RunRequest] shape).
         * When non-null, the value is persisted in the
         * `run_bindings.dedup_key` column. The H6.1 use case
         * does NOT call [findByDedupKey] yet; H6.1 ships
         * the persistence only.
         */
        setDedupKey: String? = null,
        /**
         * SFW-H6.4 (2026-10-13): clear the dedup key
         * (set it to NULL). Used by the
         * [clearDedupKey] method when a run is
         * cancelled before its first event.
         */
        clearDedupKey: Boolean = false,
    ): Boolean {
        // The SET clause is built from the parameters that
        // are not null. This is the simplest way to keep
        // the API narrow without resorting to a builder;
        // a future work-unit that needs more columns can
        // add them here.
        val setClauses = buildList {
            if (setFabricRunId != null) add("fabric_run_id = ?")
            if (setPipelinekRunId != null) add("pipelinek_run_id = ?")
            if (setPackageDigest != null) add("package_digest = ?")
            if (setCancelSelection != null) add("cancel_selection = ?")
            if (setPlacementAudit != null) add("placement_audit = ?")
            if (setDedupKey != null) add("dedup_key = ?")
            if (clearDedupKey) add("dedup_key = NULL")
            add("updated_at = ?")
        }
        require(setClauses.size > 1) {
            "updateColumns: at least one of (fabricRunId, pipelinekRunId, packageDigest, cancelSelection, placementAudit, dedupKey) must be set"
        }
        val sql = "UPDATE run_bindings SET ${setClauses.joinToString(", ")} " +
            "WHERE studio_run_id = ?"
        val nowIso = Instant.now().toString()

        return dataSource.connection.use { conn ->
            conn.prepareStatement(sql).use { ps ->
                var idx = 1
                if (setFabricRunId != null) {
                    ps.setString(idx++, setFabricRunId)
                }
                if (setPipelinekRunId != null) {
                    ps.setString(idx++, setPipelinekRunId)
                }
                if (setPackageDigest != null) {
                    ps.setString(idx++, setPackageDigest)
                }
                if (setCancelSelection != null) {
                    ps.setString(idx++, setCancelSelection)
                }
                if (setPlacementAudit != null) {
                    ps.setString(idx++, setPlacementAudit)
                }
                if (setDedupKey != null) {
                    ps.setString(idx++, setDedupKey)
                }
                ps.setString(idx++, nowIso)
                ps.setString(idx, studioRunId.value)
                ps.executeUpdate() == 1
            }
        }
    }

    // ----------------------------------------------------------------
    // SFW-H5.5: placement_audit encode/decode helpers
    // ----------------------------------------------------------------

    private fun encodePlacementAudit(audit: PlacementAudit): String {
        // Minimal hand-rolled JSON encoding. We avoid pulling in
        // a JSON dependency here (the H2.3 store is a pure
        // SQL/JDBC layer). The shape is:
        //   {"workerId":"...","region":"...","placedAt":"<iso8601>"}
        val esc = { s: String -> s.replace("\\", "\\\\").replace("\"", "\\\"") }
        return "{" +
            "\"workerId\":\"${esc(audit.workerId)}\"," +
            "\"region\":\"${esc(audit.region)}\"," +
            "\"placedAt\":\"${audit.placedAt}\"" +
            "}"
    }

    private fun decodePlacementAudit(json: String): PlacementAudit? {
        // Minimal hand-rolled JSON decoder. Returns null when
        // the value cannot be parsed (the column was corrupted
        // or written by a future version with a different
        // shape). The use case layer is defensive: a null
        // placementAudit is a valid state (the legacy row
        // path).
        val workerId = extractJsonString(json, "workerId") ?: return null
        val region = extractJsonString(json, "region") ?: return null
        val placedAtStr = extractJsonString(json, "placedAt") ?: return null
        return try {
            PlacementAudit(
                workerId = workerId,
                region = region,
                placedAt = java.time.Instant.parse(placedAtStr),
            )
        } catch (failure: Exception) {
            null
        }
    }

    private fun extractJsonString(json: String, key: String): String? {
        val needle = "\"$key\":\""
        val start = json.indexOf(needle)
        if (start < 0) return null
        val valueStart = start + needle.length
        val valueEnd = json.indexOf('"', valueStart)
        if (valueEnd < 0) return null
        return json.substring(valueStart, valueEnd)
            .replace("\\\"", "\"")
            .replace("\\\\", "\\")
    }
}
