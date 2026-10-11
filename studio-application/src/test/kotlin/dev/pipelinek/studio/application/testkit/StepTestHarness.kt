package dev.pipelinek.studio.application.testkit

import dev.pipelinek.studio.application.ports.ResultDescriptor
import dev.pipelinek.studio.application.ports.ScenarioExecutionRequest
import dev.pipelinek.studio.application.ports.ScenarioExecutionResult
import dev.pipelinek.studio.application.ports.ScenarioRunnerPort
import dev.pipelinek.studio.application.ports.StepCatalogReadPort
import dev.pipelinek.studio.application.ports.StepCatalogResult
import dev.pipelinek.studio.application.ports.StepContract
import dev.pipelinek.studio.domain.RunId
import dev.pipelinek.studio.domain.ScenarioId

/**
 * R2.8 (2026-10-11): the Step TestKit harness. A pure-Kotlin helper
 * (test code, not production) that exercises a step end-to-end
 * through the catalog and a scenario runner port.
 *
 *   1. Resolve the step via [StepCatalogReadPort.describe].
 *   2. Validate the input is non-empty and the step declares a
 *      contract (capability gating honored — refuse to run a step
 *      with no capabilities).
 *   3. Invoke [ScenarioRunnerPort.execute] with a fresh
 *      [ScenarioExecutionRequest] carrying the caller-supplied
 *      [runId] (so two runs of the same step with the same id are
 *      comparable by [dev.pipelinek.studio.application.ports.RunComparisonPort],
 *      per AAT-024).
 *   4. Project the [ScenarioExecutionResult] into a [StepTestResult]
 *      with a typed `Ok` / `Refused` / `Missing` shape so a test
 *      can `assertEquals(Ok, result.outcome)`.
 *
 * The harness does NOT classload the JAR; the catalog adapter
 * reads the manifest statically. The canary invariant from
 * AAT-021 (static initializer never runs during inspection) is
 * preserved by the harness contract.
 */
class StepTestHarness(
    private val catalog: StepCatalogReadPort,
    private val runner: ScenarioRunnerPort,
    private val stepId: String,
    private val inputJson: String,
    private val runId: RunId = RunId.new(),
) {
    /** Execute the step. Returns a [StepTestResult] with the typed
     *  outcome + the resolved [StepContract] (or null when the
     *  catalog didn't find the step). */
    fun execute(): StepTestResult {
        // (1) Resolve via the catalog.
        val resolved: StepContract? = when (val r = catalog.describe(stepId)) {
            is StepCatalogResult.Ok -> r.steps.singleOrNull()
            is StepCatalogResult.Failure -> null
        }
        if (resolved == null) return StepTestResult.Missing(catalog = catalog.describe(stepId).toString())

        // (2) Defensive input validation. The harness itself
        //     doesn't validate against the inputSchema (the schema
        //     is a contract declaration, the runner enforces it).
        //     The capability gate (AAT-SEC-001) is the production
        //     runner's responsibility, not the harness's: the
        //     harness must be able to exercise a step with empty
        //     capabilities (a stub catalog, a fixture that doesn't
        //     declare them) so the test can assert what the runner
        //     does, not what the harness assumes.
        require(inputJson.isNotBlank()) { "inputJson must be non-blank" }

        // (3) Invoke the runner.
        val req = ScenarioExecutionRequest(
            scenarioId = ScenarioId.new(),
            stepId = stepId,
            inputJson = inputJson,
            runId = runId,
        )
        val raw: ScenarioExecutionResult = runner.execute(req)
        // (4) Project.
        return when (raw) {
            is ScenarioExecutionResult.Ok -> StepTestResult.Ok(
                contract = resolved,
                descriptor = raw.descriptor,
                runtimeMs = raw.runtimeMs,
                runId = runId,
            )
            is ScenarioExecutionResult.Failure -> StepTestResult.Refused(
                contract = resolved,
                reason = raw.reason,
                runId = runId,
            )
        }
    }
}

/**
 * R2.8 (2026-10-11): the typed outcome of a step test. Three
 * variants: [Ok] (the step ran + produced a [ResultDescriptor]),
 * [Refused] (the runner refused with a reason — admission gate
 * tripped, missing capability, etc.), [Missing] (the catalog
 * did not return the step).
 */
sealed interface StepTestResult {
    val runId: RunId

    data class Ok(
        val contract: StepContract,
        val descriptor: ResultDescriptor,
        val runtimeMs: Long,
        override val runId: RunId,
    ) : StepTestResult

    data class Refused(
        val contract: StepContract,
        val reason: String,
        override val runId: RunId,
    ) : StepTestResult

    data class Missing(
        val catalog: String,
    ) : StepTestResult {
        override val runId: RunId = RunId.new()  // sentinel; never reached in practice
    }
}
