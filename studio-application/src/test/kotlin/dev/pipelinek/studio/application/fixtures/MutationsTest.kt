package dev.pipelinek.studio.application.fixtures

import dev.pipelinek.studio.application.ports.VariantRequest
import dev.pipelinek.studio.domain.ExperimentId
import dev.pipelinek.studio.domain.RunId
import dev.pipelinek.studio.domain.SnapshotId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * S4 E4.3 — Mutation fixture tests. The fixtures are pure data + a small
 * port stub; these tests pin the protocol shape so future implementations
 * stay drop-in compatible.
 */
class MutationsTest {

    @Test
    fun `variant port returns Ok for known source and Failure for unknown`() {
        val srcId = SnapshotId("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa")
        val port = Mutations.variantPort(mapOf(srcId to "println(\"hi\")".toByteArray()))
        val ok = port.variant(VariantRequest(srcId, ExperimentId.new(), "studio-test"))
        assertTrue(ok is dev.pipelinek.studio.application.ports.VariantResult.Ok, "got $ok")
        val fail = port.variant(VariantRequest(SnapshotId.new(), ExperimentId.new(), "studio-test"))
        assertTrue(fail is dev.pipelinek.studio.application.ports.VariantResult.Failure, "got $fail")
    }

    @Test
    fun `comparison port counts identical, only-in-base, only-in-other`() {
        val port = Mutations.comparisonPort(Mutations.baseRunEvents(), Mutations.mutatedRunEvents())
        val cmp = port.compare(RunId.new(), RunId.new())
        assertTrue(cmp is dev.pipelinek.studio.application.ports.RunComparisonResult.Ok, "got $cmp")
        val c = (cmp as dev.pipelinek.studio.application.ports.RunComparisonResult.Ok).comparison
        // shared: Compile + RunStart = 2 events
        assertEquals(2, c.identicalEvents)
        // only in base: StepA + StepDoneA = 2
        assertEquals(2, c.onlyInBase)
        // only in other: StepB + StepDoneB = 2
        assertEquals(2, c.onlyInOther)
        assertEquals("StepDoneA", c.baseTerminal)
        assertEquals("StepDoneB", c.otherTerminal)
    }
}
