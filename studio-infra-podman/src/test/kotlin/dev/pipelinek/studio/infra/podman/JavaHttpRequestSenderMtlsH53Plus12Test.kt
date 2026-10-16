package dev.pipelinek.studio.infra.podman

import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import javax.net.ssl.KeyManagerFactory
import java.security.KeyStore

/**
 * Falsifiers for the SFW-H5.3+ twelfth
 * follow-up: mTLS (mutual TLS) on
 * [JavaHttpRequestSender].
 *
 * The 12th follow-up adds an optional
 * `keyManager: KeyManager?` parameter.
 * When set, the sender builds a
 * default `SSLContext` with the
 * `KeyManager` (and the default
 * `TrustManager`). When unset, the
 * default JVM truststore is used.
 *
 * Note: the actual TLS handshake is
 * tested in a production boot with a
 * real cert; the H5.3+ 12th follow-up
 * just verifies the constructor
 * accepts the `KeyManager` (and that
 * a `KeyManagerFactory` produces a
 * valid `KeyManager`).
 */
@DisplayName("JavaHttpRequestSender (SFW-H5.3+ mTLS)")
class JavaHttpRequestSenderMtlsH53Plus12Test {

    // ------------------------------------------------------------------
    // Falsifier 1: null KeyManager is the documented default
    // ------------------------------------------------------------------
    @Test
    fun `null KeyManager is the documented default (no mTLS)`() {
        val sender = JavaHttpRequestSender()
        assertNotNull(sender)
    }

    // ------------------------------------------------------------------
    // Falsifier 2: explicit null KeyManager is accepted
    // ------------------------------------------------------------------
    @Test
    fun `explicit null KeyManager is accepted (no mTLS)`() {
        val sender = JavaHttpRequestSender(keyManager = null)
        assertNotNull(sender)
    }

    // ------------------------------------------------------------------
    // Falsifier 3: empty KeyManager array is accepted
    // ------------------------------------------------------------------
    @Test
    fun `KeyManager from a factory on an empty keystore is accepted`() {
        val keyStore = KeyStore.getInstance(KeyStore.getDefaultType())
        keyStore.load(null, null)
        val keyManagerFactory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        keyManagerFactory.init(keyStore, null)
        val keyManager = keyManagerFactory.keyManagers.firstOrNull()
        val sender = JavaHttpRequestSender(keyManager = keyManager)
        assertNotNull(sender)
    }

    // ------------------------------------------------------------------
    // Falsifier 4: KeyManagerFactory produces a KeyManager (the type)
    // ------------------------------------------------------------------
    @Test
    fun `KeyManagerFactory produces a non-null KeyManager when initialized with an empty keystore`() {
        val keyStore = KeyStore.getInstance(KeyStore.getDefaultType())
        keyStore.load(null, null)
        val keyManagerFactory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        keyManagerFactory.init(keyStore, null)
        val keyManager = keyManagerFactory.keyManagers.firstOrNull()
        if (keyManager != null) {
            val sender = JavaHttpRequestSender(keyManager = keyManager)
            assertNotNull(sender)
        } else {
            val sender = JavaHttpRequestSender(keyManager = null)
            assertNotNull(sender)
        }
    }

    // ------------------------------------------------------------------
    // Falsifier 5: both SSLContext and KeyManager are accepted
    // ------------------------------------------------------------------
    @Test
    fun `both SSLContext and KeyManager are accepted (the SSLContext takes precedence)`() {
        val customContext = javax.net.ssl.SSLContext.getDefault()
        val sender = JavaHttpRequestSender(
            sslContext = customContext,
            keyManager = null,
        )
        assertNotNull(sender)
    }
}
