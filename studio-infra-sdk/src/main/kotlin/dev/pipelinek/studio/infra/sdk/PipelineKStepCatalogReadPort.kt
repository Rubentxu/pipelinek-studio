package dev.pipelinek.studio.infra.sdk

import dev.pipelinek.studio.application.ports.StepCatalogReadPort
import dev.pipelinek.studio.application.ports.StepCatalogResult
import dev.pipelinek.studio.application.ports.StepAffordance
import dev.pipelinek.studio.application.ports.StepContract
import dev.pipelinek.studio.application.ports.StepDiscovery
import dev.pipelinek.studio.application.ports.StepProvenance
import dev.pipelinek.studio.application.ports.StepSchema
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path

/**
 * StepCatalogReadPort implementation: scans a directory of JARs (the SDK distribution's
 * `lib/`) using [StaticManifestInspector] and constructs [StepContract]s.
 *
 * This is S2 (Plugin Lab) E2.2: catalog discovery without code loading. Names of Step
 * implementations are extracted from `META-INF/services/dev.rubentxu.pipeline.v2.step.StepCodec`
 * declarations (Java ServiceLoader convention). Capabilities come from
 * `META-INF/pipelinek/plugin.properties`.
 *
 * R2 (2026-04-11): Source 2 (the `Core*Step.class` name-based detection) is REMOVED.
 * Per the S2 mandate item 4, class-name detection is not a reliable admission check —
 * a JAR could contain any class file named `CoreSomethingStep.class` without actually
 * being admitted by the SDK. The admission signal MUST come from the SDK's
 * authoritative `StepRegistry` (per docs/02-specifications/STEP_PLUGIN_LAB.md), which
 * is published as a separate snapshot at SDK startup. Until that snapshot is wired
 * (it requires the SDK subprocess alive, which is BLOCKED per R0.3.4/R0.3.5), this
 * adapter reads ONLY from Source 1 — ServiceLoader declarations + the manifest
 * capabilities published in `META-INF/pipelinek/plugin.properties`. The catalog
 * may be empty for SDKs that do not advertise via ServiceLoader; that is the honest
 * signal, not a fabricated Core*Step fallback.
 *
 * Per ADR-009 (generic extensions), the catalog carries ONLY information that the
 * static manifest makes accessible. Steps discovered this way that have no declared
 * schema are reported as StepSchema.NoSchema per R-06.
 */
class PipelineKStepCatalogReadPort(
    private val sdkLibDir: Path,
) : StepCatalogReadPort {

    private val log = LoggerFactory.getLogger(javaClass)

    override fun list(): StepCatalogResult {
        if (!Files.isDirectory(sdkLibDir)) {
            return StepCatalogResult.Failure("sdk lib dir not found: $sdkLibDir")
        }
        val inspector = StaticManifestInspector()
        val contracts = mutableListOf<StepContract>()
        Files.list(sdkLibDir).use { stream ->
            val jars = stream.filter { jar ->
                val name = jar.fileName.toString()
                name.endsWith(".jar") && !name.endsWith("-sources.jar") && !name.endsWith("-javadoc.jar")
            }.toList()
            for (jar in jars) {
                val jarName = jar.fileName.toString()
                runCatching {
                    val manifest = inspector.inspect(jar)
                    // Source 1: META-INF/services entries (Java ServiceLoader convention).
                    // This is the only admission signal the adapter trusts today. The
                    // SDK's authoritative StepRegistry snapshot (Source 3) will join when
                    // the SDK subprocess is wired (see R0.3.4/R0.3.5 blockers).
                    val stepCodecSvc = manifest.services.firstOrNull {
                        it.interfaceName == "dev.rubentxu.pipeline.v2.step.StepCodec" ||
                            it.interfaceName.endsWith(".StepCodec") ||
                            it.interfaceName.endsWith(".StepHandler")
                    }
                    if (stepCodecSvc != null) {
                        for (impl in stepCodecSvc.implClassNames) {
                            val id = deriveStepId(manifest, impl)
                            contracts += StepContract(
                                id = id,
                                name = manifest.implementationTitle ?: id,
                                pluginJar = jar.fileName.toString(),
                                capabilities = manifest.stepCapabilities.toSet(),
                                inputSchema = StepSchema.NoSchema,
                                outputSchema = StepSchema.NoSchema,
                                affordances = listOf(
                                    StepAffordance(kind = "exec", label = "execute"),
                                    StepAffordance(kind = "inspect-manifest", label = "manifest"),
                                ),
                                // R2.7 cierre (2026-10-11): surface
                                // the plugin's provenance so the
                                // catalog can render "this step
                                // comes from JAR X via
                                // ServiceLoader". The
                                // `refusalReason` is filled in by
                                // the admission-aware catalog
                                // adapter (R2.10 follow-up); the
                                // raw catalog read port has no
                                // gate context, so it stays null.
                                provenance = StepProvenance(
                                    pluginJar = jar.fileName.toString(),
                                    vendor = manifest.implementationVendor,
                                    sdkVersionRange = manifest.sdkVersionRange,
                                    discovery = StepDiscovery.ServiceLoader,
                                ),
                            )
                        }
                    }
                }.onFailure { e ->
                    log.warn("manifest inspection failed for {}: {}", jarName, e.message)
                }
            }
        }
        return StepCatalogResult.Ok(contracts)
    }

    override fun describe(id: String): StepCatalogResult {
        val all = list()
        if (all is StepCatalogResult.Ok) {
            val match = all.steps.firstOrNull { it.id == id || it.name == id }
            if (match != null) return StepCatalogResult.Ok(listOf(match))
            return StepCatalogResult.Failure("step '$id' not admitted by the SDK distribution; verify plugin set lock")
        }
        return all
    }

    /**
     * Derives a stable step id from the manifest title (preferred) or implementation
     * class name. Falls back to the jar name minus version suffix.
     */
    private fun deriveStepId(manifest: StaticManifestInspector.PluginManifest, impl: String): String {
        val title = manifest.implementationTitle
        if (!title.isNullOrBlank()) return title
        val ver = manifest.implementationVersion?.let { "-$it" } ?: ""
        return manifest.jarPath.fileName.toString().removeSuffix(".jar").removeSuffix(ver)
    }
}
