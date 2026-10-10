package dev.pipelinek.studio.app

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Audit C-1 (2026-10-10): the studio previously exposed only plain
 * HTTP on 127.0.0.1:7777. The new contract is HTTPS by default. The
 * [TlsConfig] utility generates a self-signed cert on first boot
 * and reuses it on subsequent boots so the operator's trust
 * decision remains valid. This test pins that contract.
 */
class TlsConfigTest {

    @Test
    fun `buildNettySslContext creates a self-signed cert on first boot`(@TempDir tmp: Path) {
        val ctx = TlsConfig.buildNettySslContext(tmp)
        assertNotNull(ctx, "SslContext must be non-null")
        val ksFile = tmp.resolve("keystore.jks").toFile()
        assertTrue(ksFile.exists(), "keystore.jks must be written to <dataDir> on first boot")
        assertTrue(ksFile.length() > 0, "keystore.jks must not be empty")
    }

    @Test
    fun `buildNettySslContext reuses a persisted cert on subsequent boots`(@TempDir tmp: Path) {
        val firstCtx = TlsConfig.buildNettySslContext(tmp)
        val firstBytes = tmp.resolve("keystore.jks").toFile().readBytes()
        val secondCtx = TlsConfig.buildNettySslContext(tmp)
        val secondBytes = tmp.resolve("keystore.jks").toFile().readBytes()
        assertNotNull(firstCtx)
        assertNotNull(secondCtx)
        assertTrue(firstBytes.contentEquals(secondBytes), "keystore bytes must match across boots")
    }

    @Test
    fun `DEFAULT_HTTPS_PORT is 7778 and LEGACY_HTTP_PORT is 7777`() {
        assertEquals(7778, TlsConfig.DEFAULT_HTTPS_PORT)
        assertEquals(7777, TlsConfig.LEGACY_HTTP_PORT)
    }
}
