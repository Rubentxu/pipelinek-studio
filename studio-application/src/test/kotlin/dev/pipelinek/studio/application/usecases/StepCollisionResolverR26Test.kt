package dev.pipelinek.studio.application.usecases

import dev.pipelinek.studio.application.ports.StepContract
import dev.pipelinek.studio.application.ports.StepProvenance
import dev.pipelinek.studio.application.ports.StepDiscovery
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * R2.6 (2026-10-11): tests for [StepCollisionResolver].
 *
 *  1.  Empty input → empty result.
 *  2.  All-unique stepKeys → 0 collisions; order preserved.
 *  3.  Two plugins with same stepKey, different versions →
 *      higher version wins; loser reported.
 *  4.  Two plugins with same stepKey, same version → tie
 *      broken by `pluginJar` ascending; first wins.
 *  5.  Three plugins with same stepKey → 1 winner + 2 losers.
 *  6.  Built-in step (no pluginJar) loses to a plugin step with
 *      a higher version.
 *  7.  Built-in step (no pluginJar) loses to a plugin step with
 *      the SAME version (because no_version sorts last).
 *  8.  Two plugins with same stepKey, neither has a version →
 *      tie by pluginJar; reason = "no_version".
 *  9.  Determinism: same input twice → same output.
 *  10. Input order does NOT change the winner (sort is by
 *      semver, not by input order).
 */
class StepCollisionResolverR26Test {

    private fun step(
        id: String,
        jar: String? = null,
        version: String? = null,
    ): StepContract {
        val provenance = if (jar != null) {
            StepProvenance(
                pluginJar = jar,
                vendor = "dev.rubentxu.pipeline.v2",
                sdkVersionRange = "[0.0.0, 2.0.0)",
                discovery = StepDiscovery.ServiceLoader,
            )
        } else null
        return StepContract(
            id = id,
            name = id,
            pluginJar = jar,
            capabilities = emptySet(),
            inputSchema = null,
            outputSchema = null,
            affordances = emptyList(),
            provenance = provenance,
            refusalReason = null,
        )
    }

    @Test
    fun `R2_6 empty input yields empty result`() {
        val out = StepCollisionResolver().resolve(emptyList())
        assertEquals(emptyList(), out.winners)
        assertEquals(emptyList(), out.collisions)
    }

    @Test
    fun `R2_6 all-unique stepKeys produce zero collisions`() {
        val input = listOf(
            step("a", "alpha-1.0.0.jar", "1.0.0"),
            step("b", "beta-1.0.0.jar", "1.0.0"),
            step("c", "gamma-1.0.0.jar", "1.0.0"),
        )
        val out = StepCollisionResolver().resolve(input)
        assertEquals(3, out.winners.size)
        assertEquals(emptyList(), out.collisions)
        // Order preserved
        assertEquals(listOf("a", "b", "c"), out.winners.map { it.id })
    }

    @Test
    fun `R2_6 two same-key different versions - higher version wins`() {
        val input = listOf(
            step("compile", "alpha-1.0.0.jar", "1.0.0"),
            step("compile", "beta-2.0.0.jar", "2.0.0"),
        )
        val out = StepCollisionResolver().resolve(input)
        assertEquals(1, out.winners.size)
        assertEquals("compile", out.winners.single().id)
        assertEquals("beta-2.0.0.jar", out.winners.single().pluginJar)
        assertEquals(1, out.collisions.size)
        val col = out.collisions.single()
        assertEquals("compile", col.stepKey)
        assertEquals("beta-2.0.0.jar", col.winnerPluginJar)
        assertEquals("2.0.0", col.winnerVersion)
        assertEquals("alpha-1.0.0.jar", col.loserPluginJar)
        assertEquals("1.0.0", col.loserVersion)
        assertEquals("lower_version", col.reason)
    }

    @Test
    fun `R2_6 two same-key same version - tie broken by pluginJar ascending`() {
        val input = listOf(
            step("compile", "zeta-1.0.0.jar", "1.0.0"),
            step("compile", "alpha-1.0.0.jar", "1.0.0"),
        )
        val out = StepCollisionResolver().resolve(input)
        assertEquals("alpha-1.0.0.jar", out.winners.single().pluginJar, "alpha < zeta alphabetically, so alpha wins")
        assertEquals(1, out.collisions.size)
        assertEquals("same_version_tie", out.collisions.single().reason)
    }

    @Test
    fun `R2_6 three same-key - one winner + two losers`() {
        val input = listOf(
            step("compile", "alpha-1.0.0.jar", "1.0.0"),
            step("compile", "beta-2.0.0.jar", "2.0.0"),
            step("compile", "gamma-1.5.0.jar", "1.5.0"),
        )
        val out = StepCollisionResolver().resolve(input)
        assertEquals(1, out.winners.size)
        assertEquals("beta-2.0.0.jar", out.winners.single().pluginJar)
        assertEquals(2, out.collisions.size)
        val losers = out.collisions.map { it.loserPluginJar }.toSet()
        assertEquals(setOf("alpha-1.0.0.jar", "gamma-1.5.0.jar"), losers)
        // Reasons are all lower_version (the winner is strictly higher)
        assertTrue(out.collisions.all { it.reason == "lower_version" })
    }

    @Test
    fun `R2_6 builtin step loses to higher-version plugin`() {
        val input = listOf(
            step("compile", null, null),  // built-in, no JAR
            step("compile", "alpha-1.0.0.jar", "1.0.0"),
        )
        val out = StepCollisionResolver().resolve(input)
        assertEquals(1, out.winners.size)
        assertEquals("alpha-1.0.0.jar", out.winners.single().pluginJar, "plugin with version beats built-in without")
        assertEquals(1, out.collisions.size)
        assertEquals("<builtin>", out.collisions.single().loserPluginJar)
        assertEquals(null, out.collisions.single().loserVersion)
    }

    @Test
    fun `R2_6 builtin step loses to same-version plugin (no_version sorts last)`() {
        val input = listOf(
            step("compile", null, null),  // built-in
            step("compile", "alpha-1.0.0.jar", "1.0.0"),
        )
        val out = StepCollisionResolver().resolve(input)
        assertEquals("alpha-1.0.0.jar", out.winners.single().pluginJar)
        // The reason is no_version because the loser has no version
        assertEquals("no_version", out.collisions.single().reason)
    }

    @Test
    fun `R2_6 two plugins with no version - tie by pluginJar`() {
        val input = listOf(
            step("compile", "zeta.jar", null),
            step("compile", "alpha.jar", null),
        )
        val out = StepCollisionResolver().resolve(input)
        assertEquals("alpha.jar", out.winners.single().pluginJar)
        assertEquals(1, out.collisions.size)
        assertEquals("no_version", out.collisions.single().reason)
    }

    @Test
    fun `R2_6 determinism - same input twice yields same output`() {
        val input = listOf(
            step("a", "alpha-1.0.0.jar", "1.0.0"),
            step("a", "alpha-1.5.0.jar", "1.5.0"),
            step("a", "alpha-2.0.0.jar", "2.0.0"),
            step("b", "beta-1.0.0.jar", "1.0.0"),
        )
        val a = StepCollisionResolver().resolve(input)
        val b = StepCollisionResolver().resolve(input)
        assertEquals(a.winners.map { it.id to it.pluginJar }, b.winners.map { it.id to it.pluginJar })
        assertEquals(a.collisions.map { it.loserPluginJar }, b.collisions.map { it.loserPluginJar })
    }

    @Test
    fun `R2_6 input order does NOT change the winner`() {
        val a = listOf(
            step("compile", "low-1.0.0.jar", "1.0.0"),
            step("compile", "high-2.0.0.jar", "2.0.0"),
        )
        val b = listOf(
            step("compile", "high-2.0.0.jar", "2.0.0"),
            step("compile", "low-1.0.0.jar", "1.0.0"),
        )
        val ra = StepCollisionResolver().resolve(a)
        val rb = StepCollisionResolver().resolve(b)
        assertEquals(ra.winners.single().pluginJar, rb.winners.single().pluginJar)
        assertEquals("high-2.0.0.jar", ra.winners.single().pluginJar)
    }

    @Test
    fun `R2_6 winner list preserves first-appearance order for unique keys`() {
        val input = listOf(
            step("b", "beta-1.0.0.jar", "1.0.0"),
            step("a", "alpha-1.0.0.jar", "1.0.0"),
            step("c", "gamma-1.0.0.jar", "1.0.0"),
        )
        val out = StepCollisionResolver().resolve(input)
        assertEquals(listOf("b", "a", "c"), out.winners.map { it.id }, "first-appearance order is stable")
    }
}
