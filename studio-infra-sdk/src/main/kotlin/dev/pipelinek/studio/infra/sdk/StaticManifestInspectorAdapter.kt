package dev.pipelinek.studio.infra.sdk

import dev.pipelinek.studio.application.usecases.StaticManifestInspectorLike
import dev.pipelinek.studio.application.usecases.StaticPluginManifest
import java.nio.file.Path

/**
 * R2.3 (2026-10-11): adapter that lets the application-layer
 * [PluginAdmissionUseCase] use the existing infra-layer
 * [StaticManifestInspector] without a circular dependency.
 *
 * The interface (`StaticManifestInspectorLike`) is declared in
 * the application layer and exposes ONLY the fields the use case
 * needs. The infra adapter is the only place where the full
 * `PluginManifest` (with services + stepCapabilities + size) is
 * available to other adapters.
 *
 * Per the hexagonal rule: the application layer declares the
 * "what" (a port), the infra layer provides the "how" (the
 * adapter). The inspector does the real work; this class is a
 * thin shape translator.
 */
class StaticManifestInspectorAdapter(
    private val delegate: StaticManifestInspector = StaticManifestInspector(),
) : StaticManifestInspectorLike {
    override fun inspect(jarPath: Path, expectedInternalVendor: String): StaticPluginManifest {
        val full = delegate.inspect(jarPath, expectedInternalVendor)
        return StaticPluginManifest(
            jarPath = full.jarPath,
            sha256 = full.sha256,
            implementationTitle = full.implementationTitle,
            implementationVersion = full.implementationVersion,
            implementationVendor = full.implementationVendor,
        )
    }
}
