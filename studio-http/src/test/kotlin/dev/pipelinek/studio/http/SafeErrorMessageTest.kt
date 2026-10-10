package dev.pipelinek.studio.http

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * B2.2 (2026-10-10): the wire-level error envelope is
 * redaction-safe. These tests lock the contract for
 * [SafeErrorMessage.from]. The HTTP catch-all test in
 * [SecurityHardeningTest] asserts the same invariants
 * end-to-end through a running application.
 */
class SafeErrorMessageTest {

    @Test
    fun `null and blank input collapse to a stub`() {
        assertEquals("internal", SafeErrorMessage.from(null))
        assertEquals("internal", SafeErrorMessage.from(""))
        assertEquals("internal", SafeErrorMessage.from("   "))
    }

    @Test
    fun `unix absolute path is redacted`() {
        val out = SafeErrorMessage.from("could not open /var/home/rubentxu/data/studio.db for write")
        assertFalse(out.contains("/var/home/rubentxu"), "path leaked: $out")
        assertTrue(out.contains("***"), "expected redaction marker, got: $out")
        // The high-level shape (without the path) survives.
        assertTrue(out.contains("could not open"), "expected shape to survive, got: $out")
    }

    @Test
    fun `windows absolute path is redacted`() {
        val out = SafeErrorMessage.from("cannot find C:\\Users\\alice\\studio.db")
        assertFalse(out.contains("C:\\"), "windows path leaked: $out")
        assertTrue(out.contains("***"))
    }

    @Test
    fun `class FQN is redacted`() {
        val out = SafeErrorMessage.from("threw dev.pipelinek.studio.infra.storage.StudioDatabase open failure")
        assertFalse(out.contains("dev.pipelinek"), "FQN leaked: $out")
    }

    @Test
    fun `email-like substring is redacted`() {
        val out = SafeErrorMessage.from("notify operator at alice@example.com on failure")
        assertFalse(out.contains("alice@example.com"), "email leaked: $out")
    }

    @Test
    fun `JWT-like substring is redacted`() {
        // Three base64url-ish segments, each 16+ chars, satisfying the
        // length floor in the regex.
        val jwt = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJ0ZXN0dXNlcjEyMzQ1Njc4OTAi.4sE8G1q9H2M5pL3nVbR7tYxK0wQzCf6dA_bEjFiGkJo"
        val out = SafeErrorMessage.from("auth failed: $jwt")
        assertFalse(out.contains("eyJ"), "JWT leaked: $out")
    }

    @Test
    fun `bearer token is redacted`() {
        val out = SafeErrorMessage.from("Authorization: Bearer abcdefghijklmnopqrstuvwxyz123456")
        assertFalse(out.contains("abcdefghijklmnopqrstuvwxyz123456"), "bearer leaked: $out")
    }

    @Test
    fun `multi-line input is collapsed to one line`() {
        val out = SafeErrorMessage.from("line1\nline2\nline3")
        assertFalse(out.contains('\n'), "newline survived: $out")
        assertFalse(out.contains("line2\nline3"), "second+ lines survived: $out")
    }

    @Test
    fun `length is bounded`() {
        val long = "x".repeat(1000)
        val out = SafeErrorMessage.from(long)
        assertTrue(out.length <= 240, "expected <= 240, got ${out.length}")
    }

    @Test
    fun `errorId is opaque, distinct, and hex`() {
        val a = SafeErrorMessage.errorId()
        val b = SafeErrorMessage.errorId()
        assertNotNull(a)
        assertNotNull(b)
        assertEquals(16, a.length, "expected 16 hex chars, got $a")
        assertTrue(a != b, "expected distinct ids, got $a == $b")
        assertTrue(a.all { it in '0'..'9' || it in 'a'..'f' }, "non-hex char: $a")
    }
}
