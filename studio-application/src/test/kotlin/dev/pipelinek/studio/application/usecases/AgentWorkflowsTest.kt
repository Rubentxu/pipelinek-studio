package dev.pipelinek.studio.application.usecases

import dev.pipelinek.studio.application.fixtures.Mutations
import dev.pipelinek.studio.application.fixtures.Scenarios
import dev.pipelinek.studio.application.ports.ScenarioExecutionResult
import dev.pipelinek.studio.application.ports.VariantResult
import dev.pipelinek.studio.domain.ExperimentId
import dev.pipelinek.studio.domain.SnapshotId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * S4 E4.6 — Agent workflow tests. Pin the protocol: a successful flow
 * (variant Ok + scenario pass + comparison Ok) produces a successful
 * AgentStepResult; a failure in any step downgrades the result without
 * raising.
 */
class AgentWorkflowsTest {

    @Test
    fun `full happy path produces a successful AgentStepResult`() {
        val source = Scenarios.Ids.Snapshot
        val variant = Mutations.variantPort(mapOf(source to "println(\"hi\")".toByteArray()))
        val runner = Scenarios.deterministicRunner()
        val compare = Mutations.comparisonPort(Mutations.baseRunEvents(), Mutations.mutatedRunEvents())
        val r = AgentWorkflows.mutateAndCompare(
            source = source,
            targetExperiment = Scenarios.Ids.Experiment,
            scenario = Scenarios.HelloWorld,
            variant = variant,
            runner = runner,
            compare = compare,
            baseRunId = Scenarios.Ids.Run,
        )
        assertTrue(r.success, "expected success, got $r")
        assertNotNull(r.newSnapshotId)
        assertNotNull(r.scenarioVerdict)
        assertTrue(r.scenarioVerdict!!.passed)
    }

    @Test
    fun `variant Failure short-circuits with no new snapshot and success=false`() {
        val variant = Mutations.variantPort(emptyMap()) // empty -> Failure for any source
        val runner = Scenarios.deterministicRunner()
        val compare = Mutations.comparisonPort(emptyList(), emptyList())
        val r = AgentWorkflows.mutateAndCompare(
            source = Scenarios.Ids.Snapshot,
            targetExperiment = Scenarios.Ids.Experiment,
            scenario = Scenarios.HelloWorld,
            variant = variant, runner = runner, compare = compare, baseRunId = Scenarios.Ids.Run,
        )
        assertFalse(r.success)
        assertEquals(null, r.newSnapshotId)
    }

    @Test
    fun `runner Failure downgrades the verdict but the variant still completed`() {
        val source = Scenarios.Ids.Snapshot
        val variant = Mutations.variantPort(mapOf(source to "x".toByteArray()))
        val failingRunner = object : dev.pipelinek.studio.application.ports.ScenarioRunnerPort {
            override fun execute(req: dev.pipelinek.studio.application.ports.ScenarioExecutionRequest) =
                ScenarioExecutionResult.Failure("forced")
        }
        val compare = Mutations.comparisonPort(emptyList(), emptyList())
        val r = AgentWorkflows.mutateAndCompare(
            source, Scenarios.Ids.Experiment, Scenarios.HelloWorld,
            variant, failingRunner, compare, Scenarios.Ids.Run,
        )
        assertFalse(r.success)
        assertNotNull(r.newSnapshotId, "variant already completed before runner failure")
        assertEquals(false, r.scenarioVerdict?.passed)
    }

    /**
     * Audit C-4 (2026-10-10): the previous implementation called
     * `compare.compare(baseRunId, newRunId)` with a freshly-minted
     * [dev.pipelinek.studio.domain.RunId] that the [ScenarioRunnerPort]
     * never associated with any execution. The fix threads the explicit
     * [newRunId] through [ScenarioExecutionRequest] and surfaces it on
     * [AgentWorkflows.AgentStepResult.newRunId]. This test pins the
     * contract: the scenario request the runner receives carries the
     * same [newRunId] the helper surfaces back to the caller.
     */
    @Test
    fun `scenario request receives the explicit newRunId and result surfaces it`() {
        val source = Scenarios.Ids.Snapshot
        val variant = Mutations.variantPort(mapOf(source to "println(\"hi\")".toByteArray()))
        val explicitNewRunId = dev.pipelinek.studio.domain.RunId.new()
        var capturedRunId: dev.pipelinek.studio.domain.RunId? = null
        val runner = object : dev.pipelinek.studio.application.ports.ScenarioRunnerPort {
            override fun execute(req: dev.pipelinek.studio.application.ports.ScenarioExecutionRequest) =
                ScenarioExecutionResult.Ok(
                    dev.pipelinek.studio.application.ports.ResultDescriptor.Text("text/plain", "hi"),
                    5L,
                ).also { capturedRunId = req.runId }
        }
        val compare = Mutations.comparisonPort(Mutations.baseRunEvents(), Mutations.mutatedRunEvents())
        val r = AgentWorkflows.mutateAndCompare(
            source = source,
            targetExperiment = Scenarios.Ids.Experiment,
            scenario = Scenarios.HelloWorld,
            variant = variant,
            runner = runner,
            compare = compare,
            baseRunId = Scenarios.Ids.Run,
            newRunId = explicitNewRunId,
        )
        assertEquals(explicitNewRunId, capturedRunId,
            "the runner must receive the same newRunId the helper passes in")
        assertEquals(explicitNewRunId, r.newRunId,
            "the result must surface the same newRunId the runner actually executed under")
    }
}
