package dev.pipelinek.studio.http

import io.ktor.network.tls.certificates.buildKeyStore
import io.ktor.network.tls.certificates.saveToFile
import io.ktor.network.tls.extensions.HashAlgorithm
import io.ktor.network.tls.extensions.SignatureAlgorithm
import io.ktor.server.engine.ApplicationEngine
import io.ktor.server.engine.connector
import io.ktor.server.engine.sslConnector
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
 * R0.2.2 closes that gap for `studio start`: the keystore built here is
 * wired by Ktor's [io.ktor.server.engine.EngineSSLConnectorBuilder]. The cert
 * is loopback-only and self-signed; a real deployment is a follow-up in
 * R6 (matrix de rotación ACME/CA).
 *
 * S2 (prior user audit, 2026-10-16): the claim above was only ever true for
 * `studio start`. `studio-app`/`Main.kt` generated the keystore, logged
 * `https://…`, and then called `embeddedServer(Netty, port, host)`, which is a
 * plaintext connector — so the app distribution served HTTP on the HTTPS
 * port. [configureListener] is now the single place that decides the
 * connector, and both entry points call it.
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
     * Key strength of the generated self-signed cert.
     *
     * Ktor's [buildKeyStore] default is 1024-bit RSA with a SHA-1 signature,
     * which OpenSSL 3 clients reject outright (`EE certificate key too weak`)
     * — so the documented `curl --cacert` trust path could never work on a
     * modern host, and browsers refuse the cert as well.
     */
    const val KEY_SIZE_BITS: Int = 2048

    /**
     * Validity of the generated cert, in days.
     *
     * Ktor's default is 3 days, which silently breaks a persisted installation:
     * the cert is reused across boots, so a short window turns every client into
     * a handshake failure a few days after first boot. 825 days matches the
     * widest window still accepted by mainstream TLS clients.
     */
    const val CERT_DAYS_VALID: Long = 825

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
                // SANs cover both loopback spellings; Ktor maps IP literals in
                // `domains` to an iPAddress SAN entry, so hostname verification
                // (`curl`, browsers, check_hostname) succeeds for 127.0.0.1.
                domains = listOf("127.0.0.1", "localhost")
                keySizeInBits = KEY_SIZE_BITS
                hash = HashAlgorithm.SHA256
                sign = SignatureAlgorithm.RSA
                daysValid = CERT_DAYS_VALID
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
     * Installs EXACTLY ONE listener on [config].
     *
     * S2 (prior user audit): both entry points (`studio-app` `Main.kt` and
     * `studio start`) must serve the same wire, so the connector choice lives
     * here instead of being copied per entry point. `studio-app` used to keep
     * its own plain `embeddedServer(Netty, port, host)` call while generating
     * a keystore and logging HTTPS — the documented default could be neither
     * reached nor trusted.
     *
     * - `secure = true`: a real TLS connector ([sslConnector]) using the
     *   self-signed loopback keystore persisted under `dataDir`.
     * - `secure = false`: plain HTTP ([connector]) on the operator's port. Only
     *   reachable through the explicit `--insecure-http` opt-in, never as a
     *   silent fallback.
     */
    fun configureListener(
        config: ApplicationEngine.Configuration,
        secure: Boolean,
        host: String,
        port: Int,
        dataDir: Path,
    ) {
        // Bind the caller's values, not the builder's own properties: inside
        // the connector lambdas an unqualified `port`/`host` would resolve to
        // the receiver (EngineConnectorBuilder) and silently bind its default
        // endpoint instead of the requested one.
        val bindHost = host
        val bindPort = port
        if (secure) {
            val keyStore: KeyStore = loadOrCreateKeyStore(dataDir)
            config.sslConnector(
                keyStore = keyStore,
                keyAlias = KEY_ALIAS,
                keyStorePassword = { KEY_PASSWORD.toCharArray() },
                privateKeyPassword = { KEY_PASSWORD.toCharArray() },
            ) {
                this.port = bindPort
                this.host = bindHost
            }
        } else {
            config.connector {
                this.port = bindPort
                this.host = bindHost
            }
        }
    }

    /**
     * Returns a Netty [io.netty.handler.ssl.SslContext] built from the
     * persisted keystore. Reserved for callers that want the raw context
     * (e.g. tests asserting the handshake against the generated cert).
     * Production callers use [configureListener].
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
