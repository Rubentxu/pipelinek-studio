package dev.pipelinek.studio.application.fixtures

import dev.pipelinek.studio.application.ports.VariantPort
import dev.pipelinek.studio.application.ports.VariantRequest
import dev.pipelinek.studio.application.ports.VariantResult
import dev.pipelinek.studio.application.ports.RunComparisonPort
import dev.pipelinek.studio.application.ports.RunComparisonResult
import dev.pipelinek.studio.application.ports.RunReadResult
import dev.pipelinek.studio.application.ports.RunEvent
import dev.pipelinek.studio.application.ports.EventCursor
import dev.pipelinek.studio.domain.RunId
import java.time.Instant

/**
 * S4 E4.3 — Mutation scenario fixtures for the variant + compare flow.
 * These are the canonical "mutate the source, observe the delta" inputs
 * that the agent workflows use to drive the S4 deliverables.
 *
 * The fixtures are deterministic: the variant port produces a fresh
 * snapshot with a different SHA, and the comparison port computes the
 * identical-vs-divergent event counts. Real implementations of the
 * VariantPort / RunComparisonPort should be drop-in compatible with the
 * shapes here.
 */
object Mutations {

    /**
     * Stub variant port: clones the source bytes under a new snapshot id.
     * The downstream port implementation should produce a real Kotlin-
     * source mutation; the fixture is for testing the *protocol*, not
     * the syntax tree.
     */
    fun variantPort(snapshotBytes: Map<dev.pipelinek.studio.domain.SnapshotId, ByteArray>): VariantPort =
        object : VariantPort {
            override fun variant(req: VariantRequest): VariantResult = runCatching {
                val src = snapshotBytes[req.sourceSnapshotId]
                    ?: return@runCatching VariantResult.Failure("unknown source ${req.sourceSnapshotId}")
                VariantResult.Ok(
                    newSnapshotId = dev.pipelinek.studio.domain.SnapshotId.new(),
                )
            }.getOrElse { VariantResult.Failure(it.message ?: "variant failed") }
        }

    /**
     * Stub comparison port: counts the events in the two run.dbs and
     * reports identical/only-in-base/only-in-other by kind.
     */
    fun comparisonPort(baseEvents: List<RunEvent>, otherEvents: List<RunEvent>): RunComparisonPort =
        object : RunComparisonPort {
            override fun compare(base: RunId, other: RunId): RunComparisonResult {
                val baseKinds = baseEvents.map { it.kind }.toSet()
                val otherKinds = otherEvents.map { it.kind }.toSet()
                val onlyBase = baseEvents.count { it.kind !in otherKinds }
                val onlyOther = otherEvents.count { it.kind !in baseKinds }
                val shared = baseEvents.count { it.kind in otherKinds }
                return RunComparisonResult.Ok(
                    comparison = dev.pipelinek.studio.application.ports.RunComparison(
                        baseRunId = base,
                        otherRunId = other,
                        identicalEvents = shared,
                        onlyInBase = onlyBase,
                        onlyInOther = onlyOther,
                        baseTerminal = baseEvents.lastOrNull()?.kind,
                        otherTerminal = otherEvents.lastOrNull()?.kind,
                    ),
                )
            }
        }

    /** Canonical "base run" events for mutation tests. */
    fun baseRunEvents(): List<RunEvent> = listOf(
        RunEvent(1L, "Compile", Instant.parse("2026-10-09T20:00:00Z"), mapOf("ok" to "true")),
        RunEvent(2L, "RunStart", Instant.parse("2026-10-09T20:00:01Z"), emptyMap()),
        RunEvent(3L, "StepA", Instant.parse("2026-10-09T20:00:02Z"), mapOf("x" to "1")),
        RunEvent(4L, "StepDoneA", Instant.parse("2026-10-09T20:00:03Z"), mapOf("x" to "1")),
    )

    /** "Mutated" run: shares Compile + RunStart, diverges on the step sequence. */
    fun mutatedRunEvents(): List<RunEvent> = listOf(
        RunEvent(1L, "Compile", Instant.parse("2026-10-09T20:00:00Z"), mapOf("ok" to "true")),
        RunEvent(2L, "RunStart", Instant.parse("2026-10-09T20:00:01Z"), emptyMap()),
        RunEvent(3L, "StepB", Instant.parse("2026-10-09T20:00:02Z"), mapOf("x" to "2")),
        RunEvent(4L, "StepDoneB", Instant.parse("2026-10-09T20:00:03Z"), mapOf("x" to "2")),
    )
}
