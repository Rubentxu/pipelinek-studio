package dev.pipelinek.studio.application.usecases

import dev.pipelinek.studio.application.ports.RunComparisonPort
import dev.pipelinek.studio.application.ports.RunComparisonResult
import dev.pipelinek.studio.application.ports.ScenarioExecutionRequest
import dev.pipelinek.studio.application.ports.ScenarioExecutionResult
import dev.pipelinek.studio.application.ports.ScenarioRunnerPort
import dev.pipelinek.studio.application.ports.VariantPort
import dev.pipelinek.studio.application.ports.VariantRequest
import dev.pipelinek.studio.application.ports.VariantResult
import dev.pipelinek.studio.domain.ExperimentId
import dev.pipelinek.studio.domain.RunId
import dev.pipelinek.studio.domain.SnapshotId

/**
 * S4 E4.6 — Agent workflow helper. Coordinates the three operations an
 * agent loop needs to "mutate the source, run the new variant, compare
 * against the base, and decide whether to keep the mutation":
 *
 *   1. variant(source, targetExperiment)        -> newSnapshotId (or Failure)
 *   2. run(scenario on newSnapshot, ...)         -> verdict
 *   3. compare(baseRunId, newRunId)             -> RunComparison
 *
 * The helper produces a single [AgentStepResult] per call so the agent
 * loop can chain them: "is the new variant a regression?" becomes a
 * single read of `result.comparison.identicalEvents` etc.
 *
 * Defaults are wired via the [ScenarioRunnerPort] / [VariantPort] /
 * [RunComparisonPort] interfaces; a real implementation wires a CLI
 * adapter that drives the SDK. The default wiring in [WiringExt] never
 * invents a passing verdict: when a port returns Failure, the helper
 * surfaces the reason and the agent should not progress.
 */
object AgentWorkflows {

    data class AgentStepResult(
        val sourceSnapshotId: SnapshotId,
        val newSnapshotId: SnapshotId?,
        val scenarioVerdict: RunScenarioUseCase.Verdict?,
        val comparison: RunComparisonResult?,
    ) {
        /** True only when every step succeeded AND the verdict passed. */
        val success: Boolean
            get() = newSnapshotId != null &&
                scenarioVerdict?.passed == true &&
                comparison is RunComparisonResult.Ok
    }

    fun mutateAndCompare(
        source: SnapshotId,
        targetExperiment: ExperimentId,
        scenario: ScenarioExecutionRequest,
        variant: VariantPort,
        runner: ScenarioRunnerPort,
        compare: RunComparisonPort,
        baseRunId: RunId,
        newRunId: RunId = RunId.new(),
    ): AgentStepResult {
        val variantResult = variant.variant(VariantRequest(source, targetExperiment, "studio-agent"))
        if (variantResult !is VariantResult.Ok) {
            return AgentStepResult(source, null, null, null)
        }
        val verdict = RunScenarioUseCase(runner).execute(scenario.copy(inputJson = """{"snapshotId":"${variantResult.newSnapshotId.value}"}"""))
        val comparison = compare.compare(baseRunId, newRunId)
        return AgentStepResult(source, variantResult.newSnapshotId, verdict, comparison)
    }
}
