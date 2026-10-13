@file:Suppress("TooGenericExceptionCaught")

package dev.pipelinek.studio.infra.storage

import dev.pipelinek.studio.domain.RunId
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
     * creating the row before this method is called).
     *
     * Idempotent: a retry that reaches here twice with
     * the same `(studioRunId, fabricRunId)` records the
     * same value both times. A retry with a different
     * `fabricRunId` overwrites — a "did the call land
     * twice?" question is the use-case layer's, not the
     * store's.
     */
    fun recordAdmission(
        studioRunId: RunId,
        fabricRunId: io.jenkins.pipelinek.fabric.contracts.model.RunId,
        packageDigest: String,
    ): Boolean = updateColumns(
        studioRunId = studioRunId,
        setFabricRunId = fabricRunId.value,
        setPackageDigest = packageDigest,
    )

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
     * Read all 3 identity fields plus the package digest for
     * a binding. Returns `null` when the row does not exist
     * or when the row has no `fabric_run_id` (the local-only
     * path; the H2 path has not been taken).
     */
    fun getBinding(studioRunId: RunId): RunBindingRecord? =
        dataSource.connection.use { conn ->
            conn.prepareStatement(
                "SELECT fabric_run_id, pipelinek_run_id, package_digest " +
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
            add("updated_at = ?")
        }
        require(setClauses.size > 1) {
            "updateColumns: at least one of (fabricRunId, pipelinekRunId, packageDigest) must be set"
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
                ps.setString(idx++, nowIso)
                ps.setString(idx, studioRunId.value)
                ps.executeUpdate() == 1
            }
        }
    }
}
