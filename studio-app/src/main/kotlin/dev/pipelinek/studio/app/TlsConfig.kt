package dev.pipelinek.studio.app

import io.ktor.network.tls.certificates.buildKeyStore
import io.ktor.network.tls.certificates.saveToFile
import io.netty.handler.ssl.SslContextBuilder
import java.io.File
import java.nio.file.FileSystemException
import java.nio.file.attribute.PosixFilePermission
import java.security.KeyStore

/**
 * Audit C-1 (2026-10-10): the studio previously ran on plain HTTP for
 * loopback use, with the rationale "single operator on a local
 * machine". That rationale ignores:
 *  - secrets in the source code that the runner writes to disk,
 *  - the Idempotency-Key header that carries operator state across
 *    requests,
 *  - the fact that the lease header is the only thing gating the
 *    destructive /api/admin/retention endpoint (AAT-SEC-010).
 *
 * This file generates a self-signed certificate on first boot,
 * persists it under `<dataDir>/keystore.jks`, and exposes a Netty
 * [io.netty.handler.ssl.SslContext] that the Ktor Netty engine can
 * install on the `ServerBootstrap.childHandler`. The cert is
 * self-signed (loopback only) — a future S5 hardening pass can
 * add a proper ACME/CA flow.
 *
 * The CLI defaults to https://127.0.0.1:7778 so a fresh operator
 * can `curl --insecure https://127.0.0.1:7778/api/version` and
 * reach the studio. The flag `--insecure-http` is the explicit
 * opt-out for operators who want plain HTTP.
 */
object TlsConfig {

    /** Default port for the HTTPS listener. Different from the legacy
     *  HTTP port (7777) so the two can coexist during a migration. */
    const val DEFAULT_HTTPS_PORT: Int = 7778

    /** Legacy HTTP port. Kept as a constant so callers can opt back
     *  in explicitly with --insecure-http without remembering the
     *  magic number. */
    const val LEGACY_HTTP_PORT: Int = 7777

    /** Default key alias. The same alias is used to load + persist
     *  the keystore so re-boots can find the entry. */
    private const val KEY_ALIAS = "studio-loopback"

    /** Default key password. The keystore file is persisted to disk
     *  with restricted permissions; the password is hard-coded for
     *  loopback use only. A real deployment would read this from
     *  an env var or a secrets manager. */
    private const val KEY_PASSWORD = "studio-loopback-change-me"

    /**
     * Returns a Netty [io.netty.handler.ssl.SslContext] built from a
     * self-signed cert persisted under `<dataDir>/keystore.jks`. The
     * cert is generated on first boot; subsequent boots reuse the
     * same file so the operator's `--insecure` trust decision
     * remains valid.
     */
    fun buildNettySslContext(dataDir: java.nio.file.Path): io.netty.handler.ssl.SslContext {
        val keyStore: KeyStore = loadOrCreateKeyStore(dataDir)
        val key = keyStore.getKey(KEY_ALIAS, KEY_PASSWORD.toCharArray()) as java.security.PrivateKey
        val certs: Array<java.security.cert.X509Certificate> = (keyStore.getCertificateChain(KEY_ALIAS)
            ?: error("keystore has no certificate chain for alias $KEY_ALIAS"))
            .map { it as java.security.cert.X509Certificate }
            .toTypedArray()
        return SslContextBuilder.forServer(key, *certs).build()
    }

    private fun loadOrCreateKeyStore(dataDir: java.nio.file.Path): KeyStore {
        val ksFile: File = dataDir.resolve("keystore.jks").toFile()
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
        // Persist with restricted permissions; the key is for
        // loopback use only and the file should not be world-
        // readable. A real deployment would do this through a
        // secrets manager.
        ksFile.parentFile?.mkdirs()
        ks.saveToFile(ksFile, KEY_PASSWORD)
        try {
            java.nio.file.Files.setPosixFilePermissions(
                ksFile.toPath(),
                setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
            )
        } catch (_: FileSystemException) {
            // Non-POSIX filesystem (e.g. Windows). The keystore file is
            // still protected by the JDK's KeyStore.load() password
            // check on the next boot.
        }
        return ks
    }
}
