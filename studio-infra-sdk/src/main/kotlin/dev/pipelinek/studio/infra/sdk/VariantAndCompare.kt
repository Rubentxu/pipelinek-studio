package dev.pipelinek.studio.infra.sdk

import dev.pipelinek.studio.application.ports.RunComparison
import dev.pipelinek.studio.application.ports.RunComparisonPort
import dev.pipelinek.studio.application.ports.RunComparisonResult
import dev.pipelinek.studio.application.ports.VariantPort
import dev.pipelinek.studio.application.ports.VariantRequest
import dev.pipelinek.studio.application.ports.VariantResult
import dev.pipelinek.studio.domain.ExperimentId
import dev.pipelinek.studio.domain.RunId
import dev.pipelinek.studio.domain.Sha256
import dev.pipelinek.studio.domain.SnapshotId
import dev.pipelinek.studio.domain.SourceSnapshot
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import java.time.Instant

/**
 * S4 (Agent) variant + compare adapter.
 *
 * Variant: reads source snapshot bytes from `<controlRootBase>/<runId>-snapshots/snapshots.db`
 * (the Studio metadata file `studio.db.snapshots` content), creates a new Snapshot
 * record in that DB with new id + same sha256 (immutability preserved) but a
 * different `targetExperimentId`.
 *
 * Compare: pulls events from each run's run.db and computes counts.
 *
 * NOTE on storage: until S4 the SDK-side snapshots live in BLOB columns inside
 * the per-run controlRoot; this adapter uses an in-memory map seeded by the
 * caller. The intent is to demonstrate the *shape* of the API and the S4
 * invariant (never mutate the original). Real persistence to `studio.db.snapshots`
 * belongs to a future work-unit after D-007 release.
 */
class VariantAndCompareAdapter(
    private val snapshotBytes: Map<SnapshotId, ByteArray>,
    private val controlRootBase: Path,
) : VariantPort, RunComparisonPort {

    override fun variant(request: VariantRequest): VariantResult {
        val src = snapshotBytes[request.sourceSnapshotId]
            ?: return VariantResult.Failure("source snapshot ${request.sourceSnapshotId.value} not in local cache")
        val sha = Sha256.digest(src)
        return VariantResult.Ok(
            newSnapshotId = SnapshotId.new().also {
                // Demonstrative only — real persistence lands in next slice.
                // We log the sha so an agent inspecting the studio.db.snapshots
                // table sees a stable fingerprint for the variant.
                System.out.println("studio variant: ${it.value} sha=${sha.value.take(12)}")
            },
        )
    }

    override fun compare(base: RunId, other: RunId): RunComparisonResult {
        val baseEvents = readEvents(base)
            ?: return RunComparisonResult.Failure("base run ${base.value} has no run.db")
        val otherEvents = readEvents(other)
            ?: return RunComparisonResult.Failure("other run ${other.value} has no run.db")

        val baseSet = baseEvents.map { it.first to it.second }.toSet()
        val otherSet = otherEvents.map { it.first to it.second }.toSet()
        val identical = (baseSet intersect otherSet).size
        val onlyInBase = baseSet.subtract(otherSet).size
        val onlyInOther = otherSet.subtract(baseSet).size

        return RunComparisonResult.Ok(
            RunComparison(
                baseRunId = base,
                otherRunId = other,
                baseTerminal = baseEvents.lastOrNull()?.second,
                otherTerminal = otherEvents.lastOrNull()?.second,
                identicalEvents = identical,
                onlyInBase = onlyInBase,
                onlyInOther = onlyInOther,
            ),
        )
    }

    private fun readEvents(runId: RunId): List<Pair<Long, String>>? {
        val db = controlRootBase.resolve(runId.value).resolve("run.db")
        if (!Files.isRegularFile(db)) return null
        val events = mutableListOf<Pair<Long, String>>()
        DriverManager.getConnection("jdbc:sqlite:${db.toAbsolutePath()}").use { c ->
            c.prepareStatement(
                "SELECT sequence, kind FROM events WHERE run_id = ? ORDER BY sequence",
            ).use { ps ->
                ps.setString(1, runId.value)
                ps.executeQuery().use { rs ->
                    while (rs.next()) events += rs.getLong("sequence") to (rs.getString("kind") ?: "")
                }
            }
        }
        return events
    }
}
