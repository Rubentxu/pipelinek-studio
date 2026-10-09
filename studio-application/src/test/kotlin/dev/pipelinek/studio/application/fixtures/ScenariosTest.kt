package dev.pipelinek.studio.application.fixtures

import dev.pipelinek.studio.application.usecases.RunScenarioUseCase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * S2 E2.4 — Fixture tests. The scenarios are intended to be reused across
 * the plugin lab, MCP fixtures, and CLI tests; these tests pin the
 * behaviour of the fixtures themselves.
 */
class ScenariosTest {

    @Test
    fun `hello world scenario passes with the expected descriptor`() {
        val v = RunScenarioUseCase(Scenarios.deterministicRunner())
            .execute(Scenarios.HelloWorld, Scenarios.ExpectedHello)
        assertTrue(v.passed, "got $v")
    }

    @Test
    fun `json success scenario passes with the expected descriptor`() {
        val v = RunScenarioUseCase(Scenarios.deterministicRunner())
            .execute(Scenarios.JsonSuccess, Scenarios.ExpectedJson)
        assertTrue(v.passed, "got $v")
    }

    @Test
    fun `table output scenario passes with the expected descriptor`() {
        val v = RunScenarioUseCase(Scenarios.deterministicRunner())
            .execute(Scenarios.TableOutput, Scenarios.ExpectedTable)
        assertTrue(v.passed, "got $v")
    }

    @Test
    fun `failing scenario is reported as failure with the forced reason`() {
        val v = RunScenarioUseCase(Scenarios.deterministicRunner()).execute(Scenarios.FailingScenario)
        assertFalse(v.passed)
        assertEquals("forced failure for test", v.reason)
    }

    @Test
    fun `unknown scenario id falls back to Failure, not fake PASS`() {
        val v = RunScenarioUseCase(Scenarios.deterministicRunner())
            .execute(Scenarios.HelloWorld.copy(scenarioId = dev.pipelinek.studio.domain.ScenarioId("not-in-fixtures")))
        assertFalse(v.passed)
        assertTrue(v.reason?.contains("unknown scenario id") == true, "got ${v.reason}")
    }
}
