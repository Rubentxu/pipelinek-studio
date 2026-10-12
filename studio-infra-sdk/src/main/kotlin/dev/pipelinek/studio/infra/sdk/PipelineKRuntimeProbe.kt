@file:Suppress("TooGenericExceptionCaught")

package dev.pipelinek.studio.infra.sdk

import dev.pipelinek.studio.application.ports.*
import org.slf4j.LoggerFactory
import java.security.MessageDigest
import java.nio.file.Files
import java.nio.file.Path

/**
 * Introspects the installed PipelineK SDK at runtime.
 *
 * Resolution strategy (D-002): the SDK is consumed as a pinned Maven coordinate and
 * downloaded at build time into the Gradle classpath. We do NOT auto-fetch at runtime.
 *
 * Detection: if any class from the SDK package is reachable on the JVM classpath,
 * version + digest are pulled via reflection without instantiating. If not reachable,
 * [RuntimeUnavailable] is returned honestly — never a fake descriptor.
 *
 * Per ROADMAP R-01 and ADR-006: we must NEVER claim "static analysis" backed by Kotlin
 * eval. The descriptor only reports capabilities the SDK actually exposes.
 */
class PipelineKRuntimeProbe(
    private val classpathRoots: List<Path> = defaultClasspathRoots(),
    private val sdkClassLoader: ClassLoader? = null,
    private val pinPolicy: PinPolicy = SdkLockfileVerifier.policyFromEnv(),
    private val pinVerifier: SdkLockfileVerifier = SdkLockfileVerifier(
        SdkLockfileVerifier.loadFromClasspath(),
    ),
) : PipelineKRuntimePort {

    private val log = LoggerFactory.getLogger(javaClass)

    private data class FoundJar(
        val path: Path,
        val sha256: String,
    )

    override fun describe(): RuntimeDescriptorResult {
        val found = locateSdkJar()
        if (found == null) {
            log.warn("PipelineK SDK not found on classpath. Marked Unavailable. Install with: ./gradlew :studio-infra-sdk:sdkInstall (TBD) or include pipelinek-app dependency.")
            return RuntimeDescriptorResult.Unavailable
        }
        // Per S0 design, the probe confirms the SDK *jar* is present. We do
        // NOT load the SDK's classes into the studio classloader (the SDK is
        // a subprocess dependency of the runner, not a Studio classpath
        // dependency). The version + capabilities are read from the jar
        // manifest + jar listing. Previously this method called
        // `Class.forName` on `dev.rubentxu.pipeline.v2.application.MainKt`,
        // which always failed when the SDK was supplied via
        // `PIPELINEK_SDK_CLASSPATH` because that path is only seen by the
        // runner's spawned JVM, not by Studio itself. The new flow reads
        // the version straight from the jar's MANIFEST.MF and probes the
        // jar listing for capability artifacts.
        val version = readJarManifestVersion(found.path)
        // R5.4 (2026-10-12): when the manifest has no
        // version, fall back to the lockfile-by-SHA. This is
        // the only honest recovery path: the lockfile is the
        // pinned identity matrix; if the jar SHA matches a
        // pin, we adopt the pinned version and mark the
        // descriptor accordingly so the cert can see the
        // recovery. If the lockfile has no matching entry,
        // we return BLOCKED — we will NEVER invent a
        // version. (Previous behaviour returned Failure here,
        // which is the same outcome from the studio's POV
        // but loses the lockfile-recovery path the R5.4
        // mandate requires.)
        val (resolvedVersion, source) = when {
            version != null -> version to VersionSource.Manifest
            else -> {
                val recovery = pinVerifier.verifyBySha256(found.sha256)
                when (recovery) {
                    is PinVerdict.RecoveredBySha -> {
                        log.warn(
                            "SDK jar {} has no Implementation-Version; recovered version {} from lockfile by SHA match.",
                            found.path, recovery.pinned.version,
                        )
                        recovery.pinned.version to VersionSource.LockfileBySha
                    }
                    is PinVerdict.Mismatch -> {
                        val msg = "SDK SHA ${found.sha256.take(16)}… does not match any lockfile entry; manifest has no Implementation-Version; cannot identify SDK (BLOCKED)"
                        log.error(msg)
                        return RuntimeDescriptorResult.Failure(msg)
                    }
                    is PinVerdict.Unknown -> {
                        val msg = "Lockfile is empty; SDK jar ${found.path} has no Implementation-Version; cannot identify SDK (BLOCKED)"
                        log.error(msg)
                        return RuntimeDescriptorResult.Failure(msg)
                    }
                    else -> {
                        // PinVerdict.Match cannot come from
                        // verifyBySha256 (no pinned entry was
                        // located by version). Treat the same
                        // as Unknown for safety.
                        val msg = "SDK jar ${found.path} has no Implementation-Version and lockfile lookup returned no match (BLOCKED)"
                        log.error(msg)
                        return RuntimeDescriptorResult.Failure(msg)
                    }
                }
            }
        }
        // Honour the existing PinPolicy: if the operator
        // requested Enforce, the resolvedVersion + found.sha256
        // must still match a lockfile entry. With the
        // lockfile-by-SHA fallback this is a redundant check
        // (we already matched by SHA), but it preserves the
        // audit invariant for jars where the manifest DID
        // carry a version.
        if (pinPolicy != PinPolicy.Off) {
            when (val verdict = pinVerifier.verify(resolvedVersion, found.sha256)) {
                is PinVerdict.Match -> { /* proceed */ }
                is PinVerdict.Mismatch -> {
                    val msg = "SDK SHA pin mismatch for $resolvedVersion: expected=${verdict.expectedSha256.take(16)}…, actual=${verdict.actualSha256.take(16)}…"
                    log.error(msg)
                    return RuntimeDescriptorResult.Failure(msg)
                }
                is PinVerdict.Unknown -> {
                    val msg = "SDK version $resolvedVersion not in sdk.lock.json; refusing to admit runs (set STUDIO_SDK_PIN=off to override)"
                    log.error(msg)
                    return RuntimeDescriptorResult.Failure(msg)
                }
                is PinVerdict.RecoveredBySha -> {
                    // Should not happen via verify(version, sha)
                    // — only verifyBySha256 emits this. Defensive
                    // log + pass.
                    log.warn("Unexpected RecoveredBySha from verify(version, sha); treating as Match.")
                }
            }
        }
        val caps = probeCapabilitiesFromJar(found.path)
        return RuntimeDescriptorResult.Available(
            RuntimeDescriptor(
                version = resolvedVersion,
                artifactDigest = found.sha256,
                pluginSdkVersion = resolvedVersion,
                capabilities = caps,
                versionSource = source,
            ),
        )
    }

    private fun readJarManifestVersion(jar: Path): String? {
        return try {
            java.util.jar.JarFile(jar.toFile()).use { jf ->
                val attrs = jf.manifest?.mainAttributes ?: return null
                attrs.getValue("Implementation-Version")
                    ?: attrs.getValue("Bundle-Version")
                    ?: attrs.getValue("Version")
            }
        } catch (e: Throwable) {
            log.warn("jar manifest read failed for {}: {}", jar, e.toString())
            null
        }
    }

    private fun probeCapabilitiesFromJar(jar: Path): Set<String> {
        // Audit C-7: never fabricate capabilities. The jar entry
        // markers below are the only honest evidence we have. If the
        // jar is unreadable, throw so the caller sees a Failure; if
        // the jar is readable but no marker is present, return an
        // empty set. The previous implementation returned a hard-
        // coded default when the jar was missing markers OR when an
        // exception was thrown, which caused the SPA to display
        // "compile + eval + durable + run-cancel-partial" for SDKs
        // that may not actually support any of them.
        val caps = mutableSetOf<String>()
        java.util.jar.JarFile(jar.toFile()).use { jf ->
            val entries = jf.entries().asSequence().map { it.name }.toSet()
            if (entries.any { it.contains("scripting") }) caps.add("compile")
            if (entries.any { it.contains("scripting") }) caps.add("eval")
            if (entries.any { it.contains("events-store") || it.contains("event-store") }) caps.add("durable")
            if (entries.any { it.contains("events") }) caps.add("run-cancel-partial")
        }
        if (caps.isEmpty()) {
            log.info("SDK jar {} has no capability markers; reporting empty set (not fabricated defaults)", jar)
        }
        return caps
    }

    private fun locateSdkJar(): FoundJar? {
        for (root: Path in classpathRoots) {
            if (!Files.isDirectory(root)) continue
            // Use Java stream API directly so we don't trip Kotlin's SAM conversion inference.
            val s: java.util.stream.Stream<Path> = Files.walk(root)
            s.use { stream ->
                val matches = stream
                    .filter { p: Path -> Files.isRegularFile(p) }
                    .filter { p: Path -> p.fileName.toString().endsWith(".jar") }
                    .filter { p: Path ->
                        val n = p.fileName.toString()
                        // Accept both the v1 (`pipeline-kotlin-*`) and the v2
                        // (`pipeline-application-*`) naming conventions. The v2
                        // upstream dropped the "kotlin" infix; the probe must
                        // recognise both so a freshly-installed v2 SDK is
                        // discoverable.
                        n.contains("pipeline-kotlin-application")
                            || n.contains("pipeline-application-")
                            || n.contains("pipeline-kotlin")
                    }
                    .filter { p: Path -> !p.fileName.toString().endsWith("-sources.jar") }
                    .filter { p: Path -> !p.fileName.toString().endsWith("-javadoc.jar") }
                    .collect(java.util.stream.Collectors.toList<Path>())
                for (match: Path in matches) {
                    return FoundJar(match, sha256Of(match))
                }
            }
        }
        return null
    }

    private fun sha256Of(p: Path): String {
        val md = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(p).use { ins ->
            val buf = ByteArray(8192)
            while (true) {
                val n = ins.read(buf)
                if (n <= 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    // Note: locatePipelineKPackage + readModuleVersion + probeCapabilities
    // were removed in S0 — the probe no longer Class.forName()'s SDK
    // classes (those are subprocess deps of the runner, not Studio
    // classpath deps). The describe() flow now reads the jar manifest
    // and inspects entry names — see readJarManifestVersion +
    // probeCapabilitiesFromJar above.

    companion object {
        /**
         * Compute the default classpath roots for the
         * probe. R5.4 (2026-10-12): include both the
         * studio's own `java.class.path` (the classpath
         * the JVM was started with) and the
         * `PIPELINEK_SDK_CLASSPATH` env var (the
         * subprocess classpath supplied to the runner, e.g.
         * `pipelinek-0.48.0/lib/<glob>`).
         *
         * The studio's classpath is what the probe
         * historically scanned; for the standalone
         * `studio-cli` distribution the SDK jars are NOT
         * there, so without `PIPELINEK_SDK_CLASSPATH` the
         * probe returned `Unavailable` and `/api/version`
         * surfaced no runtime identity. The R5.4 mandate
         * forbids accepting that absence, so the probe
         * now scans both sources and the cert asserts
         * `pipelinek_runtime` is non-null in either case.
         */
        fun defaultClasspathRoots(): List<Path> {
            val sep = System.getProperty("path.separator") ?: ":"
            val fromSystemClasspath = (System.getProperty("java.class.path") ?: "")
                .split(sep)
            // R5.4: PIPELINEK_SDK_CLASSPATH is the
            // subprocess classpath the runner uses. We
            // accept either the standard colon-separated
            // form or a glob (e.g.
            // `pipelinek-0.48.0/lib/*`); globs are
            // expanded by `Files.walk` inside the probe.
            val fromSdkEnv = (System.getenv("PIPELINEK_SDK_CLASSPATH") ?: "")
                .split(sep)
            val roots = (fromSystemClasspath + fromSdkEnv)
                .filter { it.isNotBlank() }
                .map { Path.of(it) }
                .mapNotNull {
                    runCatching {
                        when {
                            Files.isDirectory(it) -> it
                            it.toString().endsWith(".jar") -> it.parent ?: return@runCatching null
                            // A glob like `/path/lib/*` is
                            // the directory; we let the
                            // walk inside `locateSdkJar`
                            // expand it.
                            it.toString().endsWith("/*") -> it.parent ?: return@runCatching null
                            else -> null
                        }
                    }.getOrNull()
                }
                .distinct()
            return roots
        }
    }
}
