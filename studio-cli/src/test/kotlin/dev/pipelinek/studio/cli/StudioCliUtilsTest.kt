package dev.pipelinek.studio.cli

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

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
    fun `requireOption throws when missing`() {
        val args = arrayOf("--host", "127.0.0.1")
        try {
            requireOption(args, "--missing")
            error("should have thrown")
        } catch (e: IllegalStateException) {
            assertEquals(true, e.message?.contains("--missing"))
        }
    }
}
