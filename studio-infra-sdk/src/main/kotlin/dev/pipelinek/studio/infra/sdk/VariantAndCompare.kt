@file:Suppress("TooGenericExceptionCaught")

package dev.pipelinek.studio.infra.sdk

import dev.pipelinek.studio.application.ports.RunComparison
import dev.pipelinek.studio.application.ports.RunComparisonPort
import dev.pipelinek.studio.application.ports.RunComparisonResult
import dev.pipelinek.studio.application.ports.StudioMetadataPort
import dev.pipelinek.studio.application.ports.VariantPort
import dev.pipelinek.studio.application.ports.VariantRequest
import dev.pipelinek.studio.application.ports.VariantResult
import dev.pipelinek.studio.domain.RunId
import dev.pipelinek.studio.domain.Sha256
import dev.pipelinek.studio.domain.SnapshotId
import dev.pipelinek.studio.domain.SourceSnapshot
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import java.time.Instant

/**
 * S4 (Agent) variant + compare adapter.
 *
 * Variant: reads source snapshot bytes from the in-memory cache the
 * caller provides (seeded from the [StudioMetadataPort] at boot), then
 * **persists** a new [SourceSnapshot] row via the metadata port.
 * The new row has the same sha256 as the source (immutability
 * preserved) but a different `targetExperimentId` and a new
 * [SnapshotId]. Without the persistence step the agent loop would
 * mint an id that the rest of the studio cannot resolve (audit F-P0-2,
 * 2026-10-10).
 *
 * Compare: pulls events from each run's run.db and computes counts.
 */
class VariantAndCompareAdapter(
    private val snapshotBytes: Map<SnapshotId, ByteArray>,
    private val controlRootBase: Path,
    private val metadata: StudioMetadataPort,
    private val clock: () -> Instant = Instant::now,
) : VariantPort, RunComparisonPort {

    private val log = LoggerFactory.getLogger(javaClass)

    override fun variant(request: VariantRequest): VariantResult {
        val src = snapshotBytes[request.sourceSnapshotId]
            ?: return VariantResult.Failure(
                "source snapshot ${request.sourceSnapshotId.value} not in local cache",
            )
        val sha = Sha256.digest(src)
        val newId = SnapshotId.new()
        val persisted = SourceSnapshot(
            id = newId,
            experimentId = request.targetExperimentId,
            sha256 = sha,
            sourceUtf8 = src,
            createdAt = clock(),
            creator = request.creator,
        )
        return try {
            metadata.saveSnapshot(persisted)
            log.info(
                "variant: sourceSnapshotId={} newSnapshotId={} sha={}",
                request.sourceSnapshotId.value, newId.value, sha.value.take(12),
            )
            VariantResult.Ok(newSnapshotId = newId)
        } catch (e: Throwable) {
            log.error("variant: saveSnapshot failed for newId={}", newId.value, e)
            VariantResult.Failure("variant persistence failed: ${e.javaClass.simpleName}: ${e.message}")
        }
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
