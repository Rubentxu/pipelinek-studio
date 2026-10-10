package dev.pipelinek.studio.infra.storage

import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertFalse

/**
 * Unit tests for [InMemoryLeaseRegistry] — AAT-SEC-010.
 *
 * The contract: a destructive op (e.g. retention sweep) must
 * be authorised by a freshly-acquired lease. Without a valid
 * lease (wrong op-kind, expired, never issued), the op is
 * refused and no files are touched.
 */
class InMemoryLeaseRegistryTest {

    @Test
    fun `acquire returns a fresh lease with a unique token`() {
        val reg = InMemoryLeaseRegistry()
        val a = reg.acquire("retention-sweep")
        val b = reg.acquire("retention-sweep")
        assertNotNull(a.token)
        assertNotNull(b.token)
        assertTrue(a.token != b.token, "tokens must be unique")
        assertEquals("retention-sweep", a.opKind)
    }

    @Test
    fun `validate returns the lease when token + opKind match`() {
        val reg = InMemoryLeaseRegistry()
        val a = reg.acquire("retention-sweep")
        val v = reg.validate(a.token, "retention-sweep")
        assertEquals(a.token, v?.token)
    }

    @Test
    fun `validate returns null for an unknown token`() {
        val reg = InMemoryLeaseRegistry()
        reg.acquire("retention-sweep")
        val v = reg.validate("not-a-token", "retention-sweep")
        assertNull(v)
    }

    @Test
    fun `validate returns null when the opKind does not match`() {
        val reg = InMemoryLeaseRegistry()
        val a = reg.acquire("retention-sweep")
        val v = reg.validate(a.token, "different-op")
        assertNull(v, "a retention-sweep lease must not authorise a different op")
    }

    @Test
    fun `validate returns null for an expired lease`() {
        // A clock that starts at a fixed instant; we advance
        // it past the lease's TTL and assert validate fails.
        var now = Instant.parse("2026-01-01T00:00:00Z")
        val clock = object : Clock() {
            override fun getZone() = ZoneOffset.UTC
            override fun withZone(zone: java.time.ZoneId?) = this
            override fun instant(): Instant = now
        }
        val reg = InMemoryLeaseRegistry(clock)
        val a = reg.acquire("retention-sweep", ttlSeconds = 60)
        // Within TTL: valid.
        assertNotNull(reg.validate(a.token, "retention-sweep"))
        // Advance past the TTL.
        now = now.plusSeconds(61)
        assertNull(reg.validate(a.token, "retention-sweep"), "expired lease must not validate")
    }

    @Test
    fun `release removes the lease`() {
        val reg = InMemoryLeaseRegistry()
        val a = reg.acquire("retention-sweep")
        assertTrue(reg.release(a.token))
        assertNull(reg.validate(a.token, "retention-sweep"))
    }

    @Test
    fun `release is idempotent`() {
        val reg = InMemoryLeaseRegistry()
        val a = reg.acquire("retention-sweep")
        assertTrue(reg.release(a.token))
        assertFalse(reg.release(a.token))
    }
}
