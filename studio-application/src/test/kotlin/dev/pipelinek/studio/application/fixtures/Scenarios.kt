package dev.pipelinek.studio.application.fixtures

import dev.pipelinek.studio.application.ports.ResultDescriptor
import dev.pipelinek.studio.application.ports.ScenarioExecutionRequest
import dev.pipelinek.studio.application.ports.ScenarioExecutionResult
import dev.pipelinek.studio.application.ports.ScenarioRunnerPort
import dev.pipelinek.studio.domain.ExperimentId
import dev.pipelinek.studio.domain.RunId
import dev.pipelinek.studio.domain.ScenarioId
import dev.pipelinek.studio.domain.SnapshotId

/**
 * S2 E2.4 — Reusable scenario fixtures for the plugin lab. These are the
 * well-known "hello world" scenarios a new Step plugin is expected to pass
 * before admission (per E2.4). The fixtures are pure data + a small runner
 * port that returns deterministic Ok results; the SDK is the source of truth
 * for the actual step execution semantics.
 */
object Scenarios {

    /** A scenario that produces a single text "hello" result. */
    val HelloWorld = ScenarioExecutionRequest(
        scenarioId = ScenarioId("hello-world"),
        stepId = "CoreNoopStep",
        inputJson = "{}",
    )

    /** A scenario that produces a JSON `{ "ok": true }` result. */
    val JsonSuccess = ScenarioExecutionRequest(
        scenarioId = ScenarioId("json-success"),
        stepId = "CoreJsonStep",
        inputJson = """{"answer":42}""",
    )

    /** A scenario that produces a small table. */
    val TableOutput = ScenarioExecutionRequest(
        scenarioId = ScenarioId("table-output"),
        stepId = "CoreTableStep",
        inputJson = """{"rows":2}""",
    )

    /** A scenario whose runner is expected to fail. */
    val FailingScenario = ScenarioExecutionRequest(
        scenarioId = ScenarioId("failing"),
        stepId = "CoreNoopStep",
        inputJson = """{"force_failure":true}""",
    )

    /** Expected descriptors for the success-path scenarios. */
    val ExpectedHello = ResultDescriptor.Text("hello")
    val ExpectedJson = ResultDescriptor.Json("""{"ok":true,"answer":42}""")
    val ExpectedTable = ResultDescriptor.Table(
        headers = listOf("id", "name"),
        rows = listOf(listOf("1", "alpha"), listOf("2", "beta")),
    )

    /**
     * Deterministic stub runner that returns a known result for each
     * well-known scenario above. The unknown-input fallback is
     * `Failure` (not a fake empty Ok) so a test that forgets to register
     * its scenario in the stub gets a visible failure.
     */
    fun deterministicRunner(): ScenarioRunnerPort = object : ScenarioRunnerPort {
        override fun execute(req: ScenarioExecutionRequest): ScenarioExecutionResult = when (req.scenarioId.value) {
            "hello-world" -> ScenarioExecutionResult.Ok(ExpectedHello, runtimeMs = 7L)
            "json-success" -> ScenarioExecutionResult.Ok(ExpectedJson, runtimeMs = 12L)
            "table-output" -> ScenarioExecutionResult.Ok(ExpectedTable, runtimeMs = 3L)
            "failing" -> ScenarioExecutionResult.Failure("forced failure for test")
            else -> ScenarioExecutionResult.Failure("unknown scenario id: ${req.scenarioId.value}")
        }
    }

    /**
     * Convenience: a known run/experiment/snapshot triple for tests that
     * need real-looking IDs without re-deriving them.
     */
    object Ids {
        val Experiment = ExperimentId("11111111-1111-1111-1111-111111111111")
        val Snapshot = SnapshotId("22222222-2222-2222-2222-222222222222")
        val Scenario = ScenarioId("33333333-3333-3333-3333-333333333333")
        val Run = RunId("44444444-4444-4444-4444-444444444444")
    }
}
