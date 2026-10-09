package dev.pipelinek.studio.application.usecases

import dev.pipelinek.studio.application.ports.ResultDescriptor
import dev.pipelinek.studio.application.ports.ScenarioExecutionRequest
import dev.pipelinek.studio.application.ports.ScenarioExecutionResult
import dev.pipelinek.studio.application.ports.ScenarioRunnerPort
import dev.pipelinek.studio.domain.ScenarioId
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * S2 E2.3 — RunScenarioUseCase unit tests. Pure functions over a stub port;
 * no SDK or HTTP required.
 */
class RunScenarioUseCaseTest {

    @Test
    fun `successful run with no expected descriptor passes`() {
        val runner = object : ScenarioRunnerPort {
            override fun execute(req: ScenarioExecutionRequest) =
                ScenarioExecutionResult.Ok(ResultDescriptor.Text("ok"), runtimeMs = 12L)
        }
        val v = RunScenarioUseCase(runner).execute(REQ)
        assertTrue(v.passed, "expected pass, got $v")
        assertEquals(12L, v.runtimeMs)
        assertNull(v.reason)
    }

    @Test
    fun `runner Failure produces failed verdict with reason`() {
        val runner = object : ScenarioRunnerPort {
            override fun execute(req: ScenarioExecutionRequest) =
                ScenarioExecutionResult.Failure("SDK not installed")
        }
        val v = RunScenarioUseCase(runner).execute(REQ)
        assertFalse(v.passed)
        assertEquals("SDK not installed", v.reason)
        assertEquals(null, v.actual)
    }

    @Test
    fun `expected Text descriptor with matching body passes`() {
        val runner = object : ScenarioRunnerPort {
            override fun execute(req: ScenarioExecutionRequest) =
                ScenarioExecutionResult.Ok(ResultDescriptor.Text("hello\n"), runtimeMs = 5L)
        }
        val v = RunScenarioUseCase(runner).execute(REQ, ResultDescriptor.Text("hello"))
        assertTrue(v.passed, "got $v")
    }

    @Test
    fun `expected Text descriptor with different body fails with mismatch reason`() {
        val runner = object : ScenarioRunnerPort {
            override fun execute(req: ScenarioExecutionRequest) =
                ScenarioExecutionResult.Ok(ResultDescriptor.Text("actual"), runtimeMs = 5L)
        }
        val v = RunScenarioUseCase(runner).execute(REQ, ResultDescriptor.Text("expected"))
        assertFalse(v.passed)
        assertTrue(v.reason?.contains("mismatch") == true, "got ${v.reason}")
    }

    @Test
    fun `expected Table descriptor compares headers and rows`() {
        val expected = ResultDescriptor.Table(
            headers = listOf("a", "b"),
            rows = listOf(listOf("1", "2"), listOf("3", "4")),
        )
        val matching = ResultDescriptor.Table(
            headers = listOf("a", "b"),
            rows = listOf(listOf("1", "2"), listOf("3", "4")),
        )
        val different = ResultDescriptor.Table(
            headers = listOf("a", "b"),
            rows = listOf(listOf("1", "2")),
        )
        val runner = object : ScenarioRunnerPort {
            override fun execute(req: ScenarioExecutionRequest) =
                ScenarioExecutionResult.Ok(matching, runtimeMs = 1L)
        }
        assertTrue(RunScenarioUseCase(runner).execute(REQ, expected).passed, "matching should pass")

        val failingRunner = object : ScenarioRunnerPort {
            override fun execute(req: ScenarioExecutionRequest) =
                ScenarioExecutionResult.Ok(different, runtimeMs = 1L)
        }
        assertFalse(RunScenarioUseCase(failingRunner).execute(REQ, expected).passed)
    }

    @Test
    fun `descriptor type mismatch is reported as failure`() {
        val runner = object : ScenarioRunnerPort {
            override fun execute(req: ScenarioExecutionRequest) =
                ScenarioExecutionResult.Ok(ResultDescriptor.Text("x"), runtimeMs = 1L)
        }
        val v = RunScenarioUseCase(runner).execute(REQ, ResultDescriptor.Json("""{"x":1}"""))
        assertFalse(v.passed)
        assertTrue(v.reason?.contains("mismatch") == true, "got ${v.reason}")
    }

    companion object {
        val REQ = ScenarioExecutionRequest(
            scenarioId = ScenarioId("s-1"),
            stepId = "CoreNoopStep",
            inputJson = "{}",
        )
    }
}
