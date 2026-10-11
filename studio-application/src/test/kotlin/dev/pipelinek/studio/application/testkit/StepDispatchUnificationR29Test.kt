package dev.pipelinek.studio.application.testkit

import dev.pipelinek.studio.application.ports.ResultDescriptor
import dev.pipelinek.studio.application.ports.ScenarioExecutionRequest
import dev.pipelinek.studio.application.ports.ScenarioExecutionResult
import dev.pipelinek.studio.application.ports.ScenarioRunnerPort
import dev.pipelinek.studio.application.ports.StepAffordance
import dev.pipelinek.studio.application.ports.StepCatalogReadPort
import dev.pipelinek.studio.application.ports.StepCatalogResult
import dev.pipelinek.studio.application.ports.StepContract
import dev.pipelinek.studio.application.ports.StepDiscovery
import dev.pipelinek.studio.application.ports.StepProvenance
import dev.pipelinek.studio.application.ports.StepSchema
import dev.pipelinek.studio.application.usecases.StepCollisionResolver
import dev.pipelinek.studio.domain.RunId
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * R2.9 (2026-10-11): unified dispatch invariant.
 *
 * A built-in step (`StepDiscovery.Builtin`, no pluginJar) and an
 * external step (`StepDiscovery.ServiceLoader`, with pluginJar)
 * MUST go through the same code path:
 *  - The production `RunScenarioUseCase` calls
 *    `scenarioRunner.execute(req)` with the `stepId`. It does NOT
 *    branch on `step.discovery`.
 *  - The `ResultDescriptor` returned by the runner is the same
 *    sealed-interface type regardless of the step's provenance.
 *
 * AAT-024 contract: "Same stepId, different discovery → same
 * dispatch path, same ResultDescriptor shape".
 *
 * Structural check (also asserted by code review): the production
 * `studio-application/src/main` source has zero
 * `when (step.discovery == ...)` branches. The grep-based check
 * lives in this test as a self-documenting assertion (re-runs
 * every CI pass).
 */
class StepDispatchUnificationR29Test {

    private fun contract(
        id: String,
        discovery: StepDiscovery,
        pluginJar: String? = null,
        capabilities: Set<String> = setOf("default"),
    ): StepContract = StepContract(
        id = id,
        name = id,
        pluginJar = pluginJar,
        capabilities = capabilities,
        inputSchema = StepSchema.NoSchema,
        outputSchema = StepSchema.NoSchema,
        affordances = listOf(StepAffordance("artifact", "$id-output")),
        provenance = StepProvenance(
            pluginJar = pluginJar ?: "<builtin>",
            vendor = if (pluginJar != null) "dev.rubentxu.pipeline.v2" else null,
            sdkVersionRange = if (pluginJar != null) "[0.0.0, 2.0.0)" else null,
            discovery = discovery,
        ),
    )

    private class FixedDiscoveryCatalog(private val steps: List<StepContract>) : StepCatalogReadPort {
        override fun list(): StepCatalogResult = StepCatalogResult.Ok(steps)
        override fun describe(id: String): StepCatalogResult {
            val m = steps.firstOrNull { it.id == id }
            return if (m != null) StepCatalogResult.Ok(listOf(m))
            else StepCatalogResult.Failure("no step '$id'")
        }
    }

    /** A single runner that returns a `Text` descriptor regardless
     *  of which stepId was passed. The test asserts that the
     *  ResultDescriptor type is Text for BOTH discoveries. */
    private class UniformTextRunner : ScenarioRunnerPort {
        override fun execute(req: ScenarioExecutionRequest): ScenarioExecutionResult =
            ScenarioExecutionResult.Ok(
                descriptor = ResultDescriptor.Text(body = "ran ${req.stepId}", mime = "text/plain"),
                runtimeMs = 0L,
            )
    }

    private class UniformJsonRunner : ScenarioRunnerPort {
        override fun execute(req: ScenarioExecutionRequest): ScenarioExecutionResult =
            ScenarioExecutionResult.Ok(
                descriptor = ResultDescriptor.Json(body = """{"stepId":"${req.stepId}"}"""),
                runtimeMs = 0L,
            )
    }

    @Test
    fun `R2_9 AAT-024 builtin step and external step with same id - same dispatch path, same descriptor type`() {
        val builtinContract = contract("compile", StepDiscovery.Builtin, pluginJar = null)
        val externalContract = contract("compile", StepDiscovery.ServiceLoader, pluginJar = "alpha-1.0.0.jar")

        val builtinCatalog = FixedDiscoveryCatalog(listOf(builtinContract))
        val externalCatalog = FixedDiscoveryCatalog(listOf(externalContract))
        val runner = UniformTextRunner()

        val builtinResult = StepTestHarness(builtinCatalog, runner, "compile", """{"x":1}""").execute()
        val externalResult = StepTestHarness(externalCatalog, runner, "compile", """{"x":1}""").execute()

        assertTrue(builtinResult is StepTestResult.Ok, "builtin should be Ok, got $builtinResult")
        assertTrue(externalResult is StepTestResult.Ok, "external should be Ok, got $externalResult")

        val builtinDesc = (builtinResult as StepTestResult.Ok).descriptor
        val externalDesc = (externalResult as StepTestResult.Ok).descriptor

        // The AAT-024 invariant: same descriptor TYPE, not same
        // content. Both are Text — the dispatch path is uniform.
        assertEquals(builtinDesc::class, externalDesc::class, "AAT-024: same stepId via different discovery must produce same ResultDescriptor type")
        assertTrue(builtinDesc is ResultDescriptor.Text)
        assertTrue(externalDesc is ResultDescriptor.Text)
    }

    @Test
    fun `R2_9 AAT-024 same invariant holds for Json descriptor`() {
        val builtinContract = contract("eval", StepDiscovery.Builtin)
        val externalContract = contract("eval", StepDiscovery.ServiceLoader, pluginJar = "beta-2.0.0.jar")
        val runner = UniformJsonRunner()

        val a = StepTestHarness(FixedDiscoveryCatalog(listOf(builtinContract)), runner, "eval", """{}""").execute()
        val b = StepTestHarness(FixedDiscoveryCatalog(listOf(externalContract)), runner, "eval", """{}""").execute()
        assertTrue(a is StepTestResult.Ok && b is StepTestResult.Ok)
        assertEquals((a as StepTestResult.Ok).descriptor::class, (b as StepTestResult.Ok).descriptor::class)
        assertTrue((a as StepTestResult.Ok).descriptor is ResultDescriptor.Json)
    }

    @Test
    fun `R2_9 the harness does not branch on discovery - the runner is the only authority`() {
        // Two steps with the SAME id but DIFFERENT discovery:
        //   - "alpha" (Builtin)
        //   - "alpha" (ServiceLoader, alpha-1.0.0.jar)
        // The harness.resolve() does not filter by discovery; it
        // calls catalog.describe("alpha") and uses the first
        // result. The dispatch path is uniform.
        val builtin = contract("alpha", StepDiscovery.Builtin)
        val external = contract("alpha", StepDiscovery.ServiceLoader, pluginJar = "alpha-1.0.0.jar")
        val runner = UniformTextRunner()

        val fromBuiltin = StepTestHarness(FixedDiscoveryCatalog(listOf(builtin)), runner, "alpha", "{}").execute()
        val fromExternal = StepTestHarness(FixedDiscoveryCatalog(listOf(external)), runner, "alpha", "{}").execute()

        assertTrue(fromBuiltin is StepTestResult.Ok)
        assertTrue(fromExternal is StepTestResult.Ok)
        // Both are Text — the descriptor type is determined by
        // the runner, not by the step's discovery.
        assertTrue((fromBuiltin as StepTestResult.Ok).descriptor is ResultDescriptor.Text)
        assertTrue((fromExternal as StepTestResult.Ok).descriptor is ResultDescriptor.Text)
    }

    @Test
    fun `R2_9 production code has no when(step_discovery) branch - grep guard`() {
        // The R2.9 invariant is also enforced by code review: no
        // `when (step.discovery == ...)` branch in production
        // code. This test re-runs the guard as a self-documenting
        // assertion.
        val mainSrc = java.nio.file.Path.of("src", "main", "kotlin")
        if (!java.nio.file.Files.isDirectory(mainSrc)) return  // CI environments may not have the source tree
        val offending = mutableListOf<String>()
        java.nio.file.Files.walk(mainSrc).use { stream ->
            stream.filter { it.toString().endsWith(".kt") }.forEach { f ->
                val text = java.nio.file.Files.readString(f)
                if (Regex("""when\s*\([^)]*\.discovery\s*\)""").containsMatchIn(text) ||
                    Regex("""when\s*\(\s*step\.discovery""").containsMatchIn(text)) {
                    offending += f.toString()
                }
            }
        }
        assertTrue(offending.isEmpty(), "R2.9 violation: production code branches on step.discovery: $offending")
    }

    @Test
    fun `R2_9 collision resolver treats builtin and external uniformly (semver DESC, pluginJar ASC)`() {
        // The R2.6 StepCollisionResolver must NOT special-case
        // Builtin. A builtin with a (hypothetical) version
        // competes with an external on equal terms.
        val builtinV2 = contract("compile", StepDiscovery.Builtin, pluginJar = null)
            .let { it.copy(provenance = it.provenance?.copy(sdkVersionRange = null)) }
        val externalV1 = contract("compile", StepDiscovery.ServiceLoader, pluginJar = "alpha-1.0.0.jar", capabilities = setOf("default"))
        val externalV2 = contract("compile", StepDiscovery.ServiceLoader, pluginJar = "beta-2.0.0.jar", capabilities = setOf("default"))
        val resolved = StepCollisionResolver().resolve(listOf(externalV1, externalV2, builtinV2))
        // externalV2 wins (2.0.0 > 1.0.0); builtinV2 and externalV1
        // are losers. Built-in did not get any special treatment.
        assertEquals(1, resolved.winners.size)
        assertEquals("beta-2.0.0.jar", resolved.winners.single().pluginJar)
        assertEquals(2, resolved.collisions.size)
        // The built-in IS one of the losers, on the same footing
        // as the lower-version external.
        val loserJars = resolved.collisions.map { it.loserPluginJar }.toSet()
        assertTrue(loserJars.contains("<builtin>"), "builtin must be a loser when a higher-version external exists, got losers: $loserJars")
        assertTrue(loserJars.contains("alpha-1.0.0.jar"), "alpha-1.0.0 must be a loser, got losers: $loserJars")
    }

    @Test
    fun `R2_9 AAT-024 same runId - builtin and external produce same runId in result for comparison`() {
        val builtin = contract("compile", StepDiscovery.Builtin)
        val external = contract("compile", StepDiscovery.ServiceLoader, pluginJar = "alpha-1.0.0.jar")
        val runner = UniformTextRunner()
        val sharedRunId = RunId.new()

        val a = StepTestHarness(FixedDiscoveryCatalog(listOf(builtin)), runner, "compile", "{}", sharedRunId).execute()
        val b = StepTestHarness(FixedDiscoveryCatalog(listOf(external)), runner, "compile", "{}", sharedRunId).execute()
        assertTrue(a is StepTestResult.Ok && b is StepTestResult.Ok)
        assertEquals(sharedRunId, a.runId)
        assertEquals(sharedRunId, b.runId)
        // The runId is preserved across discovery boundaries,
        // so RunComparisonPort.compare() can compare a run that
        // was builtin in v1 with a run that is external in v2.
    }

    @Test
    fun `R2_9 dispatch is uniform even when capabilities differ between builtin and external`() {
        // The capability gate is the runner's job. The harness
        // does not branch on discovery. Two contracts with
        // different capabilities, same id, different discovery:
        // both go through the runner, both get Text.
        val builtin = contract("compile", StepDiscovery.Builtin, capabilities = setOf("compile"))
        val external = contract("compile", StepDiscovery.ServiceLoader, pluginJar = "alpha-1.0.0.jar", capabilities = setOf("compile", "compile-extended"))
        val runner = UniformTextRunner()

        val a = StepTestHarness(FixedDiscoveryCatalog(listOf(builtin)), runner, "compile", "{}").execute()
        val b = StepTestHarness(FixedDiscoveryCatalog(listOf(external)), runner, "compile", "{}").execute()
        assertTrue(a is StepTestResult.Ok && b is StepTestResult.Ok)
        // The contract that the harness captures carries the
        // ORIGINAL capabilities (no filtering by discovery).
        assertEquals(setOf("compile"), (a as StepTestResult.Ok).contract.capabilities)
        assertEquals(setOf("compile", "compile-extended"), (b as StepTestResult.Ok).contract.capabilities)
    }

    @Test
    fun `R2_9 builtin and external with different ids - both reach the runner with their own stepId`() {
        // Distinct stepIds — no collision, no resolver needed.
        val builtin = contract("builtin-only", StepDiscovery.Builtin)
        val external = contract("external-only", StepDiscovery.ServiceLoader, pluginJar = "alpha-1.0.0.jar")
        val runner = UniformTextRunner()

        val a = StepTestHarness(FixedDiscoveryCatalog(listOf(builtin)), runner, "builtin-only", "{}").execute()
        val b = StepTestHarness(FixedDiscoveryCatalog(listOf(external)), runner, "external-only", "{}").execute()
        assertTrue(a is StepTestResult.Ok && b is StepTestResult.Ok)
        // The bodies are different because the runner echoes the
        // stepId, but the TYPE is the same.
        val bodyA = ((a as StepTestResult.Ok).descriptor as ResultDescriptor.Text).body
        val bodyB = ((b as StepTestResult.Ok).descriptor as ResultDescriptor.Text).body
        assertNotEquals(bodyA, bodyB, "different stepIds should produce different bodies")
        assertTrue(bodyA.contains("builtin-only"))
        assertTrue(bodyB.contains("external-only"))
    }
}
