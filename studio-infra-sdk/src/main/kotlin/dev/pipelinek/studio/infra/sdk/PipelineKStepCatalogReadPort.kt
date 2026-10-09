package dev.pipelinek.studio.infra.sdk

import dev.pipelinek.studio.application.ports.StepCatalogReadPort
import dev.pipelinek.studio.application.ports.StepCatalogResult
import dev.pipelinek.studio.application.ports.StepAffordance
import dev.pipelinek.studio.application.ports.StepContract
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
            val toProcess = stream.filter { jar ->
                val name = jar.fileName.toString()
                name.endsWith(".jar") && !name.endsWith("-sources.jar") && !name.endsWith("-javadoc.jar")
            }.toList()
            for (jar in toProcess) {
                val jarName = jar.fileName.toString()
                runCatching {
                    val manifest = inspector.inspect(jar)
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
