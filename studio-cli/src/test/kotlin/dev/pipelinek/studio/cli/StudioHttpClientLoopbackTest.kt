package dev.pipelinek.studio.cli

import org.junit.jupiter.api.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Audit F-P1-CLI-1 (2026-10-10): the trust-all X509 manager must
 * only apply to loopback hosts. The test seam is
 * StudioHttpClient.isLoopbackHost(baseUrl) — pure, no network.
 */
class StudioHttpClientLoopbackTest {

    @Test
    fun `localhost is loopback`() {
        assertTrue(StudioHttpClient.isLoopbackHost("https://localhost:7778"))
    }

    @Test
    fun `127_0_0_1 is loopback`() {
        assertTrue(StudioHttpClient.isLoopbackHost("https://127.0.0.1:7778"))
    }

    @Test
    fun `loopback in the 127 range is loopback`() {
        assertTrue(StudioHttpClient.isLoopbackHost("https://127.0.0.42:7778"))
    }

    @Test
    fun `IPv6 loopback is loopback`() {
        assertTrue(StudioHttpClient.isLoopbackHost("https://[::1]:7778"))
    }

    @Test
    fun `localhost is case-insensitive`() {
        assertTrue(StudioHttpClient.isLoopbackHost("https://LOCALHOST:7778"))
    }

    @Test
    fun `a routable host is NOT loopback`() {
        assertFalse(StudioHttpClient.isLoopbackHost("https://studio.example.com"))
    }

    @Test
    fun `a private RFC1918 host is NOT loopback (would be a real P1 footgun)`() {
        // 10.0.0.1 is private but not loopback. Treating private
        // addresses as "safe to trust-all" would re-introduce the
        // bug this slice closes: an operator on a corporate
        // network pointing the CLI at 10.0.0.1 (e.g. a colleague's
        // laptop) would silently MITM themselves.
        assertFalse(StudioHttpClient.isLoopbackHost("https://10.0.0.1:7778"))
    }

    @Test
    fun `a malformed URL is treated as non-loopback (safer default)`() {
        assertFalse(StudioHttpClient.isLoopbackHost("not a url"))
    }

    @Test
    fun `an http URL to a non-loopback host is still non-loopback`() {
        // The trust manager is only one half of the security
        // story; a real deployment also needs https. But for the
        // isLoopbackHost classification, scheme is irrelevant.
        assertFalse(StudioHttpClient.isLoopbackHost("http://attacker.example"))
    }
}
