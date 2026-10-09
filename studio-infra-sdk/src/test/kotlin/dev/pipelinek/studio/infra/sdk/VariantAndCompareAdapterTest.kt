package dev.pipelinek.studio.infra.sdk

import dev.pipelinek.studio.application.ports.RunComparisonResult
import dev.pipelinek.studio.application.ports.VariantResult
import dev.pipelinek.studio.domain.ExperimentId
import dev.pipelinek.studio.domain.RunId
import dev.pipelinek.studio.domain.SnapshotId
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.assertEquals

class VariantAndCompareAdapterTest {

    private fun makeRunDb(dir: java.nio.file.Path, runId: RunId, kinds: List<String>) {
        val runDir = dir.resolve(runId.value)
        Files.createDirectories(runDir)
        val db = runDir.resolve("run.db")
        java.sql.DriverManager.getConnection("jdbc:sqlite:$db").use { c ->
            c.createStatement().use { it.executeUpdate("CREATE TABLE events (event_id TEXT, run_id TEXT, sequence INTEGER, kind TEXT, occurred_at TEXT, payload TEXT)") }
            c.prepareStatement("INSERT INTO events(event_id, run_id, sequence, kind, occurred_at, payload) VALUES(?,?,?,?,?,?)").use { ps ->
                kinds.forEachIndexed { i, k ->
                    ps.setString(1, "e$i"); ps.setString(2, runId.value)
                    ps.setLong(3, i.toLong()); ps.setString(4, k)
                    ps.setString(5, "2026-10-09T20:00:00Z")
                    ps.setString(6, "{}")
                    ps.executeUpdate()
                }
            }
        }
    }

    @Test
    fun `variant returns new SnapshotId without mutating source`(@TempDir tmp: Path) {
        val sourceId = SnapshotId.new()
        val bytes = "println(\"variant-test\")".toByteArray()
        val adapter = VariantAndCompareAdapter(mapOf(sourceId to bytes), tmp)
        val res = adapter.variant(
            dev.pipelinek.studio.application.ports.VariantRequest(
                sourceSnapshotId = sourceId,
                targetExperimentId = ExperimentId.new(),
                creator = "studio-test",
            ),
        )
        assertTrue(res is VariantResult.Ok, "got $res")
        val newId = (res as VariantResult.Ok).newSnapshotId
        assertTrue(newId != sourceId)
    }

    @Test
    fun `variant unknown source returns Failure not crash`(@TempDir tmp: Path) {
        val adapter = VariantAndCompareAdapter(snapshotBytes = emptyMap(), controlRootBase = tmp)
        val res = adapter.variant(
            dev.pipelinek.studio.application.ports.VariantRequest(
                sourceSnapshotId = SnapshotId.new(),
                targetExperimentId = ExperimentId.new(),
                creator = "studio-test",
            ),
        )
        assertTrue(res is VariantResult.Failure, "got $res")
    }

    @Test
    fun `compare counts identical vs divergent events`(@TempDir tmp: Path) {
        val r1 = RunId.new()
        val r2 = RunId.new()
        // r1: 4 events, two unique
        makeRunDb(tmp, r1, listOf("Compile", "RunStart", "StepA", "StepDoneA"))
        // r2: 4 events, one shared, two different
        makeRunDb(tmp, r2, listOf("Compile", "RunStart", "StepB", "StepDoneB"))
        val adapter = VariantAndCompareAdapter(emptyMap(), tmp)
        val res = adapter.compare(r1, r2)
        assertTrue(res is RunComparisonResult.Ok, "got $res")
        val cmp = (res as RunComparisonResult.Ok).comparison
        // identical: Compile + RunStart → 2 events in intersection
        assertEquals(2, cmp.identicalEvents)
        // only in base (r1): StepA, StepDoneA → 2
        assertEquals(2, cmp.onlyInBase)
        // only in other (r2): StepB, StepDoneB → 2
        assertEquals(2, cmp.onlyInOther)
        assertNotNull(cmp.baseTerminal)
        assertNotNull(cmp.otherTerminal)
    }

    @Test
    fun `compare missing run-db returns Failure`(@TempDir tmp: Path) {
        val adapter = VariantAndCompareAdapter(emptyMap(), tmp)
        val res = adapter.compare(RunId.new(), RunId.new())
        assertTrue(res is RunComparisonResult.Failure)
    }
}
