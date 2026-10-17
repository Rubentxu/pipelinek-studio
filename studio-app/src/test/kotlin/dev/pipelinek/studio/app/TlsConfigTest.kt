package dev.pipelinek.studio.app

import dev.pipelinek.studio.http.TlsConfig
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.security.interfaces.RSAPublicKey
import java.time.Duration
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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

    /**
     * S2 installed-distribution proof (2026-10-16): the generated cert used
     * Ktor's defaults — 1024-bit RSA, SHA-1, 3 days — and a real OpenSSL 3
     * client rejected it (`EE certificate key too weak`), so `curl --cacert`
     * could never trust the studio and the persisted cert expired in days.
     * These are the properties that make the wire trustable at all.
     */
    @Test
    fun `generated cert uses a modern key and signature so standard TLS clients accept it`(@TempDir tmp: Path) {
        val keyStore = TlsConfig.loadOrCreateKeyStore(tmp)
        val cert = keyStore.getCertificate(TlsConfig.KEY_ALIAS) as java.security.cert.X509Certificate
        val publicKey = cert.publicKey

        assertTrue(publicKey is RSAPublicKey, "expected an RSA key, got ${publicKey.algorithm}")
        assertTrue(
            (publicKey as RSAPublicKey).modulus.bitLength() >= 2048,
            "cert key must be >= 2048 bits; got ${publicKey.modulus.bitLength()} (OpenSSL 3 rejects weaker)",
        )
        assertFalse(
            cert.sigAlgName.contains("sha1", ignoreCase = true),
            "cert must not be SHA-1 signed; got ${cert.sigAlgName}",
        )
        assertTrue(
            cert.notAfter.toInstant().isAfter(Instant.now().plus(Duration.ofDays(365))),
            "persisted cert must outlive a year of operator trust; expires ${cert.notAfter.toInstant()}",
        )
    }

    @Test
    fun `generated cert carries an IP SAN for 127_0_0_1 so hostname verification passes`(@TempDir tmp: Path) {
        val keyStore = TlsConfig.loadOrCreateKeyStore(tmp)
        val cert = keyStore.getCertificate(TlsConfig.KEY_ALIAS) as java.security.cert.X509Certificate
        val sans = cert.subjectAlternativeNames?.map { (type, value) -> type to value.toString() } ?: emptyList()

        assertTrue(
            sans.any { (type, value) -> type == 7 && value == "127.0.0.1" },
            "expected an iPAddress SAN for 127.0.0.1 (type 7); got $sans",
        )
        assertTrue(
            sans.any { (_, value) -> value == "localhost" },
            "expected a localhost SAN; got $sans",
        )
    }

    // S2 (2026-10-16): the canonical boot defaults live in
    // `resolveRuntimeConfig`, not in the distribution's JVM args (which used
    // to force the legacy plain-HTTP port 7777 and a literally escaped
    // ${user.home} data dir). These are deterministic checks of the resolved
    // pair; the wire-level proof is TlsHandshakeR02BTest.

    @Test
    fun `defaults are HTTPS on the canonical port with the expanded home data dir`() {
        val config = resolveRuntimeConfig(args = emptyList(), property = { HOME_PROPS[it] })

        assertFalse(config.insecureHttp, "HTTPS is the default; plain HTTP needs an explicit opt-in")
        assertEquals(TlsConfig.DEFAULT_HTTPS_PORT, config.port)
        assertEquals(7778, config.port, "the default must never be the legacy plain-HTTP port")
        assertEquals("127.0.0.1", config.host)
        assertEquals(Path.of("/home/operator/.pipelinek-studio/runtime"), config.dataDir)
    }

    @Test
    fun `explicit opt-in switches to the legacy plain HTTP port`() {
        val byArg = resolveRuntimeConfig(args = listOf("--insecure-http"), property = { HOME_PROPS[it] })
        val byProperty = resolveRuntimeConfig(
            args = emptyList(),
            property = { if (it == INSECURE_HTTP_PROPERTY) "true" else HOME_PROPS[it] },
        )

        assertEquals(TlsConfig.LEGACY_HTTP_PORT, byArg.port)
        assertTrue(byArg.insecureHttp)
        assertEquals(TlsConfig.LEGACY_HTTP_PORT, byProperty.port)
        assertTrue(byProperty.insecureHttp)
    }

    @Test
    fun `explicit port host and data dir are honored without changing the default scheme`() {
        val config = resolveRuntimeConfig(
            args = emptyList(),
            property = { key ->
                when (key) {
                    PORT_PROPERTY -> "9443"
                    HOST_PROPERTY -> "0.0.0.0"
                    DATA_DIR_PROPERTY -> "/srv/studio-data"
                    else -> HOME_PROPS[key]
                }
            },
        )

        assertEquals(9443, config.port)
        assertEquals("0.0.0.0", config.host)
        assertEquals(Path.of("/srv/studio-data"), config.dataDir)
        assertFalse(config.insecureHttp, "an explicit port must not silently change the scheme")
    }

    @Test
    fun `the user home placeholder expands in both quoted and escaped spellings`() {
        assertEquals("/home/operator/x", expandUserHome("\${user.home}/x", "/home/operator"))
        assertEquals("/home/operator/x", expandUserHome("\\\${user.home}/x", "/home/operator"))
        assertEquals("/srv/x", expandUserHome("/srv/x", "/home/operator"))

        // The launcher-escaped spelling must never reach the path layer
        // literally (that was the distribution defect).
        val config = resolveRuntimeConfig(
            args = emptyList(),
            property = { key ->
                if (key == DATA_DIR_PROPERTY) "\\\${user.home}/runtime-probe" else HOME_PROPS[key]
            },
        )
        assertEquals(Path.of("/home/operator/runtime-probe"), config.dataDir)
    }

    private companion object {
        /** A synthetic operator home: these tests never touch the real one. */
        val HOME_PROPS: Map<String, String> = mapOf("user.home" to "/home/operator")
    }
}
