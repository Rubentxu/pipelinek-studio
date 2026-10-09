package dev.pipelinek.studio.application.fixtures

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * S4 E4.1 — ScenarioBuilder tests. The builder turns typed fields into a
 * valid JSON input string; the spec round-trips losslessly.
 */
class ScenarioBuilderTest {

    @Test
    fun `builder produces JSON with the right keys and primitive values`() {
        val req = ScenarioBuilder(dev.pipelinek.studio.domain.ScenarioId("s-1"), "CoreNoopStep")
            .put("name", "hello")
            .put("answer", 42)
            .put("enabled", true)
            .build()
        // inputJson is a real JSON object with the three fields.
        assertTrue(req.inputJson.contains("\"name\":\"hello\""), "got: ${req.inputJson}")
        assertTrue(req.inputJson.contains("\"answer\":42"), "got: ${req.inputJson}")
        assertTrue(req.inputJson.contains("\"enabled\":true"), "got: ${req.inputJson}")
    }

    @Test
    fun `text scenario factory sets body field`() {
        val req = ScenarioBuilder.textScenario("s-text", "CoreTextStep", "hi")
        assertTrue(req.inputJson.contains("\"body\":\"hi\""))
        assertEquals("CoreTextStep", req.stepId)
    }

    @Test
    fun `json scenario factory sets payload field`() {
        val req = ScenarioBuilder.jsonScenario("s-json", "CoreJsonStep", "{\"a\":1}")
        assertTrue(req.inputJson.contains("\"payload\":\"{\\\"a\\\":1}\""), "got: ${req.inputJson}")
    }

    @Test
    fun `spec round-trips through toRequest and from`() {
        val spec = ScenarioSpec(
            id = "s-roundtrip",
            stepId = "CoreNoopStep",
            input = mapOf("a" to "1", "b" to "two"),
        )
        val req = spec.toRequest()
        val roundTripped = ScenarioSpec.from(req)
        assertEquals(spec.id, roundTripped.id)
        assertEquals(spec.stepId, roundTripped.stepId)
        assertEquals(spec.input, roundTripped.input)
    }
}
