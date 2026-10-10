package dev.pipelinek.studio.cli

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Audit F-P1-CLI-3 (2026-10-10): requireOptionValue must fail
 * fast on the four documented misuse cases. The previous
 * implementation accepted all of them and forwarded nonsense
 * to the backend.
 */
class CliOptionTest {

    @Test
    fun `returns the value when present`() {
        assertEquals("foo", requireOptionValue(arrayOf("--name", "foo"), "--name"))
    }

    @Test
    fun `accepts a value that happens to start with -- only when it is the literal --`() {
        // The literal "--" is a common POSIX convention for "stdin"
        // / end-of-options. A value like "--json" is a flag, not a
        // value. The previous code accepted both; the new code
        // rejects the flag-as-value case.
        assertEquals("--", requireOptionValue(arrayOf("--stdin", "--"), "--stdin"))
    }

    @Test
    fun `throws CliOptionError when the option is missing`() {
        val err = assertFailsWith<CliOptionError> {
            requireOptionValue(arrayOf("--other", "x"), "--name")
        }
        assert(err.message!!.contains("missing required option: --name"))
    }

    @Test
    fun `throws CliOptionError when the option is the last arg`() {
        val err = assertFailsWith<CliOptionError> {
            requireOptionValue(arrayOf("--name"), "--name")
        }
        assert(err.message!!.contains("requires a value"))
    }

    @Test
    fun `throws CliOptionError when the value is empty`() {
        val err = assertFailsWith<CliOptionError> {
            requireOptionValue(arrayOf("--name", ""), "--name")
        }
        assert(err.message!!.contains("cannot be empty"))
    }

    @Test
    fun `throws CliOptionError when the value is whitespace-only`() {
        val err = assertFailsWith<CliOptionError> {
            requireOptionValue(arrayOf("--name", "   "), "--name")
        }
        assert(err.message!!.contains("cannot be empty"))
    }

    @Test
    fun `throws CliOptionError when the next arg is a flag (the typical typo)`() {
        // studio create-experiment --name --json
        val err = assertFailsWith<CliOptionError> {
            requireOptionValue(arrayOf("create-experiment", "--name", "--json"), "--name")
        }
        assert(err.message!!.contains("got '--json' as value"))
    }

    @Test
    fun `returns the value even when followed by other flags`() {
        assertEquals(
            "foo",
            requireOptionValue(arrayOf("--name", "foo", "--json", "--verbose"), "--name"),
        )
    }

    @Test
    fun `finds the option in the middle of many args`() {
        assertEquals(
            "bar",
            requireOptionValue(
                arrayOf("--json", "--other", "x", "--name", "bar", "--verbose"),
                "--name",
            ),
        )
    }
}
