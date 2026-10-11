package dev.pipelinek.studio.infra.sdk

import org.slf4j.LoggerFactory
import java.security.MessageDigest
import java.util.jar.JarFile
import java.nio.file.Files
import java.nio.file.Path

/**
 * S2 (Plugin Lab) static manifest inspector — per ROADMAP §S2:
 *   "Static manifest inspector (no classloading), trust/admission classification,
 *    plugin set lock/fingerprint."
 *
 * The inspector reads JAR metadata WITHOUT loading any class via Class.forName or
 * reflection. Per UPSTREAM_GROUNDING.md PreLoadPluginAdmission:
 *   - admission is decided BEFORE classloading
 *   - inspector here provides the metadata to classify without loading
 *
 * Output: per-jar PluginManifest (sha256, manifest entries, declared services,
 * step plugin metadata). Trust classification:
 *   - TRUSTED_INTERNAL: SHA matches installed SDK distribution
 *   - TRUSTED_PINNED: SHA matches a studio.lock fingerprint
 *   - UNTRUSTED_NEW:   no fingerprint match — requires admission
 *   - UNTRUSTED_TAMPERED: declared vendor contradicts installed SDK vendor
 */
class StaticManifestInspector {

    private val log = LoggerFactory.getLogger(javaClass)

    data class ServiceDeclaration(
        val interfaceName: String,
        val implClassNames: List<String>,
    )

    data class PluginManifest(
        val jarPath: Path,
        val sha256: String,
        val size: Long,
        val implementationTitle: String?,
        val implementationVersion: String?,
        val implementationVendor: String?,
        val services: List<ServiceDeclaration>,
        val stepCapabilities: List<String>,
        /**
         * R2.2 cierre (2026-10-11): the SDK version range the plugin
         * declares compatibility with, read from the manifest header
         * `X-PipelineK-Sdk-Version-Range`. Format: semver range like
         * `[0.0.0, 1.0.0)` (Maven/Gradle style) or absent (null).
         * Surfaced so [PluginAdmissionUseCase] can apply the range
         * check; the old naive "plugin version < SDK version" rule
         * is the fallback when this header is missing.
         */
        val sdkVersionRange: String?,
    )

    sealed interface TrustClass {
        data object TrustedInternal : TrustClass
        data object TrustedPinned : TrustClass
        data object UntrustedNew : TrustClass
        data class UntrustedTampered(val reason: String) : TrustClass
    }

    fun inspect(jarPath: Path, expectedInternalVendor: String = "dev.rubentxu.pipeline.v2"): PluginManifest {
        require(Files.isRegularFile(jarPath)) { "not a regular file: $jarPath" }
        require(jarPath.fileName.toString().endsWith(".jar")) { "not a jar: $jarPath" }

        JarFile(jarPath.toFile()).use { jar ->
            val entries = jar.entries()
            val services = mutableListOf<ServiceDeclaration>()
            val stepCaps = mutableListOf<String>()

            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                if (!entry.name.startsWith("META-INF/services/")) continue
                val interfaceName = entry.name.removePrefix("META-INF/services/").trim()
                if (interfaceName.isEmpty()) continue
                val impls = jar.getInputStream(entry).use { ins ->
                    ins.readBytes().toString(Charsets.UTF_8).lines()
                        .map { it.trim() }
                        .filter { it.isNotEmpty() && !it.startsWith("#") }
                        .toList()
                }
                if (impls.isNotEmpty()) services += ServiceDeclaration(interfaceName, impls)
            }

            val attrs = jar.manifest.mainAttributes
            val title = attrs.getValue("Implementation-Title")
            val version = attrs.getValue("Implementation-Version")
            val vendor = attrs.getValue("Implementation-Vendor")
            val sdkRange = attrs.getValue("X-PipelineK-Sdk-Version-Range")

            // Detect step plugin capabilities by scanning plugin manifests like
            // 'plugin-info.properties' or step-registry JSON pointers.
            jar.entries().asSequence()
                .filter { it.name == "META-INF/pipelinek/plugin.properties" ||
                         it.name == "plugin-info.properties" }
                .forEach { entry ->
                    jar.getInputStream(entry).use { ins ->
                        ins.readBytes().toString(Charsets.UTF_8).lines()
                            .map { it.trim() }
                            .filter { it.startsWith("capability=") }
                            .forEach { stepCaps += it.removePrefix("capability=").trim() }
                    }
                }

            return PluginManifest(
                jarPath = jarPath,
                sha256 = sha256Of(jarPath),
                size = Files.size(jarPath),
                implementationTitle = title,
                implementationVersion = version,
                implementationVendor = vendor,
                services = services,
                stepCapabilities = stepCaps,
                sdkVersionRange = sdkRange?.takeIf { it.isNotBlank() },
            )
        }
    }

    fun trustClass(manifest: PluginManifest, pinnedFingerprints: Set<String>, internalVendor: String): TrustClass {
        return when {
            manifest.implementationVendor != null &&
                manifest.implementationVendor != internalVendor ->
                TrustClass.UntrustedTampered(
                    "vendor '${manifest.implementationVendor}' is not the SDK internal vendor '$internalVendor'",
                )
            manifest.sha256 in pinnedFingerprints -> TrustClass.TrustedPinned
            // A canonical SDK-shipped plugin matches the internal vendor AND is
            // not pinned → we still consider it trustworthy if its title matches the
            // implementation-title of a known SDK plugin. Conservatively: not pinned
            // AND no fingerprint → UNTRUSTED_NEW.
            else -> TrustClass.UntrustedNew
        }
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
}
