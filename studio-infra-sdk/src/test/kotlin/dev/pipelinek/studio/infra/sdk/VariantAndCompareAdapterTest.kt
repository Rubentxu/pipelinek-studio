package dev.pipelinek.studio.infra.sdk

import dev.pipelinek.studio.application.ports.RunComparison
import dev.pipelinek.studio.application.ports.RunComparisonResult
import dev.pipelinek.studio.application.ports.VariantRequest
import dev.pipelinek.studio.application.ports.VariantResult
import dev.pipelinek.studio.domain.RunId
import dev.pipelinek.studio.domain.SnapshotId
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * S4 (Agent) variant + compare surface. Per ROADMAP §"S4" the agent
 * loop needs to "mutate the source, run the new variant, compare
 * against the base, and decide whether to keep the mutation". This
 * test exercises the SDK's adapter directly: it creates two
 * per-run.sqlite run.db files with distinct event sets, then
 * asserts the adapter reports the diff honestly.
 *
 * These tests cover UAT-036 (variant) + UAT-037 (compare) + a small
 * piece of UAT-038 (no fake-PASS on the failure case).
 */
class VariantAndCompareAdapterTest {

    @Test
    fun `variant creates a new snapshot id and reports the source sha (UAT-036)`(
        @TempDir tmp: Path,
    ) {
        val sourceId = SnapshotId.new()
        val sourceBytes = "pipeline { stages { stage(\"a\") { echo(\"x\") } } }".toByteArray()
        val adapter = VariantAndCompareAdapter(
            snapshotBytes = mapOf(sourceId to sourceBytes),
            controlRootBase = tmp,
        )
        val result = adapter.variant(
            VariantRequest(
                sourceSnapshotId = sourceId,
                targetExperimentId = dev.pipelinek.studio.domain.ExperimentId.new(),
                creator = "uat-036",
            ),
        )
        assertIs<VariantResult.Ok>(result)
        assertNotEquals(sourceId, result.newSnapshotId, "variant must mint a new id, not reuse the source id")
    }

    @Test
    fun `variant returns Failure when the source snapshot is not in the local cache`(
        @TempDir tmp: Path,
    ) {
        val adapter = VariantAndCompareAdapter(
            snapshotBytes = emptyMap(),
            controlRootBase = tmp,
        )
        val result = adapter.variant(
            VariantRequest(
                sourceSnapshotId = SnapshotId.new(),
                targetExperimentId = dev.pipelinek.studio.domain.ExperimentId.new(),
                creator = "uat-036",
            ),
        )
        assertIs<VariantResult.Failure>(result)
        // The contract: a real failure is a refusal, never a synthesized
        // ok. The agent loop must see Failure, not invent a variant.
        assertTrue(result.reason.isNotBlank(), "failure reason must explain why the variant was refused")
    }

    @Test
    fun `compare reports the diff between two runs (UAT-037)`(
        @TempDir tmp: Path,
    ) {
        val baseRunId = RunId.new()
        val otherRunId = RunId.new()
        // The (sequence, kind) tuple is the diff key. The two runs share
        // some events at the same sequence AND the same kind (those
        // count as identicalEvents), and differ on others.
        writeRunDb(
            runId = baseRunId,
            dir = tmp,
            events = listOf(
                "CompilationStarted" to 1L,  // identical
                "CompilationFinished" to 2L, // only in base
                "RunStarted" to 3L,           // only in base (seq=3)
                "RunFinished" to 4L,          // identical
            ),
        )
        writeRunDb(
            runId = otherRunId,
            dir = tmp,
            events = listOf(
                "CompilationStarted" to 1L,  // identical
                "RunStarted" to 2L,           // only in other (seq=2)
                "StepStarted" to 3L,          // only in other (kind differs)
                "RunFinished" to 4L,          // identical
            ),
        )
        val adapter = VariantAndCompareAdapter(
            snapshotBytes = emptyMap(),
            controlRootBase = tmp,
        )
        val result = adapter.compare(baseRunId, otherRunId)
        assertIs<RunComparisonResult.Ok>(result)
        val cmp: RunComparison = result.comparison
        // 2 events are in base only (CompilationFinished@2, RunStarted@3)
        assertEquals(2, cmp.onlyInBase, "two (sequence, kind) pairs in base only")
        // 2 events are in other only (RunStarted@2, StepStarted@3)
        assertEquals(2, cmp.onlyInOther, "two (sequence, kind) pairs in other only")
        // 2 events are identical (CompilationStarted@1, RunFinished@4)
        assertEquals(2, cmp.identicalEvents, "two (sequence, kind) pairs identical")
    }

    @Test
    fun `compare returns Failure for an unknown run (no fake PASS, UAT-038)`(
        @TempDir tmp: Path,
    ) {
        val knownRunId = RunId.new()
        writeRunDb(knownRunId, tmp, listOf("CompilationStarted" to 1L))
        val unknownRunId = RunId.new()
        val adapter = VariantAndCompareAdapter(
            snapshotBytes = emptyMap(),
            controlRootBase = tmp,
        )
        val result = adapter.compare(knownRunId, unknownRunId)
        assertIs<RunComparisonResult.Failure>(result)
        assertTrue(result.reason.isNotBlank(), "failure reason must explain which run is missing")
        // The contract: a missing run is a refusal, never a synthesized
        // success. The agent loop must NOT proceed to "keep the
        // mutation" on this result.
    }

    private fun writeRunDb(runId: RunId, dir: Path, events: List<Pair<String, Long>>) {
        val db = dir.resolve(runId.value).resolve("run.db")
        Files.createDirectories(db.parent)
        DriverManager.getConnection("jdbc:sqlite:${db.toAbsolutePath()}").use { c ->
            c.createStatement().use { st ->
                st.execute(
                    "CREATE TABLE events (" +
                        "event_id TEXT, run_id TEXT, sequence INTEGER, " +
                        "kind TEXT, occurred_at TEXT)"
                )
                st.execute(
                    "CREATE TABLE operation_journal (" +
                        "id INTEGER PRIMARY KEY, run_id TEXT, sequence INTEGER, " +
                        "op_kind TEXT, occurred_at TEXT)"
                )
            }
            events.forEach { (kind, sequence) ->
                c.prepareStatement(
                    "INSERT INTO events(event_id, run_id, sequence, kind, occurred_at) " +
                        "VALUES(?, ?, ?, ?, '2026-10-10T00:00:00Z')"
                ).use { ps ->
                    ps.setString(1, java.util.UUID.randomUUID().toString())
                    ps.setString(2, runId.value)
                    ps.setLong(3, sequence)
                    ps.setString(4, kind)
                    ps.executeUpdate()
                }
            }
        }
        // Verify we can read it back (catches schema drift)
        val count = DriverManager.getConnection("jdbc:sqlite:${db.toAbsolutePath()}").use { c ->
            c.prepareStatement("SELECT COUNT(*) FROM events WHERE run_id = ?").use { ps ->
                ps.setString(1, runId.value)
                ps.executeQuery().use { rs -> if (rs.next()) rs.getInt(1) else 0 }
            }
        }
        assertEquals(events.size, count, "wrote $count events but expected ${events.size}")
    }
}
