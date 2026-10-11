package dev.pipelinek.studio.http

import io.ktor.network.tls.certificates.buildKeyStore
import io.ktor.network.tls.certificates.saveToFile
import io.netty.handler.ssl.SslContextBuilder
import java.io.File
import java.nio.file.FileSystemException
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import java.security.KeyStore

/**
 * R0.2.2 (2026-10-11) — TlsConfig relocated from
 * [dev.pipelinek.studio.app] (where it lived at S5 E5.x and was a
 * keystore-builder only) to [dev.pipelinek.studio.http] so both the
 * CLI's `studio start` and the production `studio-app` can wire it
 * the same way.
 *
 * The previous shape generated a keystore but never wired a TLS
 * connector — the production server comment in
 * `studio-app/Main.kt` said "delegated to the Ktor Netty engine's
 * HOCON path" but no HOCON file ever shipped. Both `studio-app` and
 * `studio start` ran plain HTTP on port 7778, regardless of
 * `--insecure-http` semantics.
 *
 * R0.2.2 closes that gap: the keystore built here is now wired by
 * Ktor's [io.ktor.server.engine.EngineSSLConnectorBuilder] in both
 * entry points (`studio start` and `studio-app`). The cert is
 * loopback-only and self-signed; a real deployment is a follow-up in
 * R6 (matrix de rotación ACME/CA).
 */
object TlsConfig {

    /** Default port for the HTTPS listener. */
    const val DEFAULT_HTTPS_PORT: Int = 7778

    /** Legacy HTTP port. Opt-in via `--insecure-http`. */
    const val LEGACY_HTTP_PORT: Int = 7777

    /** Default key alias. Re-used on every boot so the operator's
     *  trust decision (`curl --cacert` / trust-all) remains valid. */
    const val KEY_ALIAS: String = "studio-loopback"

    /** Default key password. The keystore file is persisted with
     *  0600 permissions on POSIX; the password is hard-coded for
     *  loopback use only. */
    const val KEY_PASSWORD: String = "studio-loopback-change-me"

    /** The name of the keystore file inside [dataDir]. */
    const val KEYSTORE_FILENAME: String = "keystore.jks"

    /**
     * The path of the keystore file under [dataDir]. The file is
     * created on first boot by [loadOrCreateKeyStore]; subsequent
     * boots reuse it.
     */
    fun keyStorePath(dataDir: Path): Path = dataDir.resolve(KEYSTORE_FILENAME)

    /**
     * Loads the keystore from disk if present; otherwise builds a
     * fresh self-signed cert and persists the new keystore under
     * `dataDir/<KEYSTORE_FILENAME>` with 0600 POSIX permissions.
     */
    fun loadOrCreateKeyStore(dataDir: Path): KeyStore {
        val ksFile: File = keyStorePath(dataDir).toFile()
        if (ksFile.exists()) {
            val ks = KeyStore.getInstance(KeyStore.getDefaultType())
            ksFile.inputStream().use { ks.load(it, KEY_PASSWORD.toCharArray()) }
            return ks
        }
        val ks = buildKeyStore {
            certificate(KEY_ALIAS) {
                password = KEY_PASSWORD
                domains = listOf("127.0.0.1", "localhost")
            }
        }
        ksFile.parentFile?.mkdirs()
        ks.saveToFile(ksFile, KEY_PASSWORD)
        try {
            java.nio.file.Files.setPosixFilePermissions(
                ksFile.toPath(),
                setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
            )
        } catch (_: FileSystemException) {
            // Non-POSIX (Windows): the JDK KeyStore.load() password
            // check on the next boot is sufficient.
        }
        return ks
    }

    /**
     * Returns a Netty [io.netty.handler.ssl.SslContext] built from the
     * persisted keystore. Reserved for back-compat callers that want
     * the raw context (e.g. tests asserting the wire handshake).
     * Production callers use the Ktor EngineSSLConnectorBuilder path
     * with [loadOrCreateKeyStore].
     */
    fun buildNettySslContext(dataDir: Path): io.netty.handler.ssl.SslContext {
        val keyStore: KeyStore = loadOrCreateKeyStore(dataDir)
        val key = keyStore.getKey(KEY_ALIAS, KEY_PASSWORD.toCharArray()) as java.security.PrivateKey
        val certs: Array<java.security.cert.X509Certificate> = (keyStore.getCertificateChain(KEY_ALIAS)
            ?: error("keystore has no certificate chain for alias $KEY_ALIAS"))
            .map { it as java.security.cert.X509Certificate }
            .toTypedArray()
        // `SslContextBuilder.forServer` is a Java varargs overload;
        // the `*certs` spread is the idiomatic Kotlin forwarding
        // expression. Detekt flags SpreadOperator; the suppression is
        // narrowly scoped to this single line.
        @Suppress("SpreadOperator")
        return SslContextBuilder.forServer(key, *certs).build()
    }
}
