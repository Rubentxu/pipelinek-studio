package dev.pipelinek.studio.build.logic

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Falsifier for the SFW H1 Phase B Part 5 regression: a [org.gradle.api.Project]
 * reference inside a `doLast { ... }` closure breaks Gradle's configuration
 * cache (the cache cannot serialize `Project` instances). This test scans the
 * plugin source for `target.` references inside any `doLast { ... }` block
 * and fails fast if any are found.
 *
 * # Why a static-analysis test (and not a behavioral one)
 *
 * ProjectBuilder-based behavioral tests do not exercise Gradle's
 * configuration cache (it is a CLI feature, not an API). TestKit-based
 * integration tests are heavier (extra dependency) and slower. The static
 * scan is cheap (microsecond cost) and catches the EXACT regression vector:
 * a `target.` reference inside a `doLast { ... }` body.
 *
 * # Why this matters (the user impact)
 *
 * `gradle.properties` ships with `org.gradle.configuration-cache=true`. The
 * `scripts/verify-from-clean-clone.sh` harness always starts a fresh Gradle
 * daemon on a clean machine. A latent `target.` capture inside a `doLast`
 * fails the entire build at the "store configuration cache" step — even
 * though every test passes. The release runbook for `v1.0.0` cannot green a
 * build that fails this way.
 *
 * # How the matcher works
 *
 * The scanner finds every `doLast {` token, walks forward counting brace
 * depth (`{` / `}`), and looks for the substring `target.` inside the
 * matched body. The bodies are single-arity (no nested `doLast { ... }`
 * blocks in this plugin), so a brace-counting walk is reliable.
 */
@DisplayName("FabricContractsPlugin — config-cache compat (SFW H1 Phase B Part 5)")
class FabricContractsPluginConfigCacheTest {

    private companion object {
        // The relative path from the test runtime (studio-build-logic/) to
        // the plugin source. ProjectBuilder uses the same CWD.
        private val PLUGIN_SOURCE = File(
            "src/main/kotlin/dev/pipelinek/studio/build/logic/FabricContractsPlugin.kt"
        )
    }

    @Test
    fun `plugin source exists at the expected path`() {
        assertTrue(
            PLUGIN_SOURCE.exists(),
            "plugin source must exist at ${PLUGIN_SOURCE.absolutePath}"
        )
    }

    @Test
    fun `doLast bodies do NOT reference the Project (config-cache compat)`() {
        val text = PLUGIN_SOURCE.readText()
        val doLastOpeners = Regex("""doLast\s*\{""").findAll(text).toList()
        assertTrue(
            doLastOpeners.isNotEmpty(),
            "must find at least one doLast block in ${PLUGIN_SOURCE.path}"
        )
        doLastOpeners.forEachIndexed { idx, opener ->
            val body = readBraceBody(text, opener.range.last + 1)
            assertFalse(
                Regex("""\btarget\s*\.""").containsMatchIn(body),
                "doLast block #$idx must NOT reference 'target.' " +
                    "(Project is not config-cache-serializable). Body:\n$body"
            )
        }
    }

    /**
     * Reads a brace-balanced body starting at [start] (the position right
     * after the opening `{`). Walks forward counting `{` / `}` depth;
     * returns the substring inside the outer braces.
     */
    private fun readBraceBody(text: String, start: Int): String {
        var depth = 1
        var i = start
        while (i < text.length && depth > 0) {
            when (text[i]) {
                '{' -> depth++
                '}' -> depth--
            }
            i++
        }
        check(depth == 0) { "unbalanced braces reading doLast body" }
        return text.substring(start, i - 1)
    }
}
