package dev.pipelinek.studio.cli

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/**
 * Tests for the small CLI utility helpers in Main.kt.
 *
 * Audit F-P1-CLI-3 (2026-10-10) replaced the legacy `requireOption`
 * (which returned the next arg verbatim, accepting `--name --json`
 * or `--name ""` as valid input) with `requireOptionValue` which
 * throws `CliOptionError` on four misuse cases. The `requireOption`
 * symbol is preserved as a back-compat alias (see Main.kt:271) so
 * the 9 existing call sites don't have to change. These tests
 * assert the real contract: `CliOptionError` is the thrown type
 * (not its superclass `IllegalArgumentException`), the option name
 * is in the message, and the alias delegates correctly.
 */
class StudioCliUtilsTest {
    @Test
    fun `flagValue returns value after flag`() {
        val args = arrayOf("--host", "127.0.0.1", "--port", "7777")
        assertEquals("127.0.0.1", flagValue(args, "--host"))
        assertEquals("7777", flagValue(args, "--port"))
    }

    @Test
    fun `flagValue returns null for missing`() {
        val args = arrayOf("--host", "127.0.0.1")
        assertNull(flagValue(args, "--port"))
    }

    @Test
    fun `requireOption throws CliOptionError when missing`() {
        // Audit B0 (2026-10-10): the previous test asserted
        // IllegalStateException, which was the legacy contract.
        // F-P1-CLI-3 replaced the implementation with
        // requireOptionValue which throws CliOptionError
        // (IllegalArgumentException subclass). The real
        // contract: CliOptionError is the specific thrown type
        // and the message names the option. assertFailsWith
        // checks the exact class — catching IllegalStateException
        // (legacy) or IllegalArgumentException (super) would
        // both pass under the old test but the new contract
        // names the precise exception.
        val err = assertFailsWith<CliOptionError> {
            requireOption(arrayOf("--host", "127.0.0.1"), "--missing")
        }
        assert(err.message!!.contains("--missing")) { "message did not name option: ${err.message}" }
    }

    @Test
    fun `requireOptionValue rejects empty value`() {
        // F-P1-CLI-3 added this case: --name "" was previously
        // accepted and forwarded to the backend. Now rejected
        // at parse time.
        val err = assertFailsWith<CliOptionError> {
            requireOptionValue(arrayOf("--name", ""), "--name")
        }
        assert(err.message!!.contains("cannot be empty")) { "message did not name the failure mode: ${err.message}" }
    }

    @Test
    fun `requireOptionValue rejects flag as value`() {
        // F-P1-CLI-3: --name --json used to create an experiment
        // named "--json". Now rejected with a specific message.
        val err = assertFailsWith<CliOptionError> {
            requireOptionValue(arrayOf("--name", "--json"), "--name")
        }
        assert(err.message!!.contains("got '--json' as value")) { "message did not name the value: ${err.message}" }
    }

    @Test
    fun `requireOption delegates to requireOptionValue`() {
        // The back-compat alias must delegate the same
        // exception type, not silently behave differently.
        // We pick a case (flag-as-value) that exercises
        // requireOptionValue's body, not just the missing-
        // option early return.
        val err = assertFailsWith<CliOptionError> {
            requireOption(arrayOf("--name", "--json"), "--name")
        }
        assert(err.message!!.contains("got '--json' as value"))
    }
}
