package dev.pipelinek.studio.application.ports

import org.junit.jupiter.api.Test
import java.lang.management.ManagementFactory
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * B3.2 (2026-10-10): the 0-effects canary.
 *
 * The S3 mandate says "Detekt en proceso aislado
 * con version matrix y reglas DSL pequeñas" and
 * the DoD says "no liberar función unsafe
 * etiquetada compile-only". The canary is the
 * discriminator: a script that calls
 * [System.exit] in its top level is fed to the
 * analyzer; the parent JVM is still alive after
 * [StaticAnalyzerPort.analyze] returns.
 *
 * The test pins three properties:
 *  1. The analyzer returns a list of diagnostics
 *     (or empty) without throwing.
 *  2. The parent JVM's PID did not change (a
 *     sub-process that took over would not match
 *     the recorded PID, but the [Runtime] hooks
 *     would; the PID + the [ManagementFactory]
 *     uptime are a strong "the JVM is the same
 *     process" signal).
 *  3. The analyzer's process can be re-invoked
 *     after a `System.exit` source — i.e. the
 *     analyzer is reusable, not poisoned.
 *
 * The "no side effects" property the canary proves
 * is the spine of the B3 honesty layer. A future
 * refactor that accidentally introduced a real
 * eval surface (e.g. by shelling out to a script
 * engine that executes the bytes) would surface
 * as a failed canary on the FIRST test invocation
 * (the JVM would die).
 */
class StaticAnalyzerZeroEffectsTest {

    @Test
    fun `B3_2 canary — script with System_exit in top level does not kill the analyzer JVM`() {
        val pidBefore = ProcessHandle.current().pid()
        val uptimeBefore = ManagementFactory.getRuntimeMXBean().uptime
        val source = """
            // A hostile script that would call System.exit(42) at top level.
            // If the analyzer ever eval'd this, the parent JVM would die.
            val exitCode = System.exit(42)
        """.trimIndent()
        val diagnostics = TextOnlyStaticAnalyzer.Default.analyze(source)
        // 1. The analyzer returned a list (did not throw).
        // The default analyzer flags the line that
        // calls System.exit — that's the B3.2 rule.
        assertTrue(
            diagnostics.any { it.message.contains("forbidden-call-system-exit") },
            "expected the analyzer to flag the System.exit call, got: ${diagnostics.map { it.message }}",
        )
        // 2. The parent JVM is still alive and has
        // the same identity.
        val pidAfter = ProcessHandle.current().pid()
        val uptimeAfter = ManagementFactory.getRuntimeMXBean().uptime
        assertEquals(pidBefore, pidAfter, "JVM PID changed — the analyzer is not in-process")
        assertTrue(
            uptimeAfter >= uptimeBefore,
            "JVM uptime went backwards — the analyzer restarted the JVM (got: $uptimeAfter, was: $uptimeBefore)",
        )
    }

    @Test
    fun `B3_2 canary — analyzer is reusable after a System_exit source`() {
        // A second invocation proves the analyzer
        // is not poisoned by the first. A future
        // refactor that flipped a static flag
        // (e.g. "analyzer has seen a System.exit,
        // refuse further calls") would surface
        // here.
        val hostile = "System.exit(7)"
        val benign = "val x = 1 + 2"
        val hostileDiags = TextOnlyStaticAnalyzer.Default.analyze(hostile)
        val benignDiags = TextOnlyStaticAnalyzer.Default.analyze(benign)
        assertTrue(
            hostileDiags.any { it.message.contains("forbidden-call-system-exit") },
            "first call missed the System.exit rule",
        )
        // The second call on a benign source must
        // return an empty diagnostic list (no false
        // positives carried over from the first call).
        assertEquals(
            emptyList(),
            benignDiags,
            "second call on a benign source returned false positives: ${benignDiags.map { it.message }}",
        )
    }

    @Test
    fun `B3_2 canary — analyzer flags System_exit with the correct source location`() {
        val source = """
            line 1: val a = 1
            line 2: val b = System.exit(0)
            line 3: val c = 3
        """.trimIndent()
        val diags = TextOnlyStaticAnalyzer.Default.analyze(source)
        // The default analyzer scans each line and
        // reports the line where System.exit
        // appears. The canary asserts the line
        // number is correct (so the SPA can render
        // a Monaco marker at the right spot).
        val match = diags.firstOrNull { it.message.contains("forbidden-call-system-exit") }
        assertNotEquals(null, match, "expected a forbidden-call diagnostic, got: ${diags.map { it.message }}")
        assertEquals(2, match!!.line, "expected line 2, got ${match.line}")
    }

    @Test
    fun `B3_2 canary — clean source returns empty diagnostics (no false positives)`() {
        val source = """
            val greeting = "hello, world"
            val count = 42
            val pi = 3.14
        """.trimIndent()
        val diags = TextOnlyStaticAnalyzer.Default.analyze(source)
        assertEquals(
            emptyList(),
            diags,
            "clean source returned diagnostics: ${diags.map { it.message }}",
        )
    }
}
