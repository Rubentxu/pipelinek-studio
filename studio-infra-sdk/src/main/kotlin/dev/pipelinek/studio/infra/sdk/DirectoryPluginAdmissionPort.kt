package dev.pipelinek.studio.infra.sdk

import dev.pipelinek.studio.application.ports.PluginAdmissionPort
import dev.pipelinek.studio.application.ports.PluginAdmissionState
import dev.pipelinek.studio.application.usecases.PluginAdmissionUseCase
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path

/**
 * R2.3 closure (2026-10-11): the read impl for [PluginAdmissionPort].
 *
 * Scans a directory of plugin JARs (typically the SDK's `lib/`
 * distribution), runs [PluginAdmissionUseCase.inspect] on each, and
 * surfaces the resulting state. The impl is read-only — the
 * `admit` / `refuse` / `revoke` transitions go through
 * [PluginAdmissionUseCase] directly, not through this port.
 *
 * Missing dir = empty list. The port is honest: a fresh install
 * with no plugins has nothing to list.
 */
class DirectoryPluginAdmissionPort(
    private val pluginDir: Path,
    private val useCase: PluginAdmissionUseCase,
) : PluginAdmissionPort {

    private val log = LoggerFactory.getLogger(javaClass)

    override fun stateOf(pluginJar: String): PluginAdmissionState {
        val jarPath = pluginDir.resolve(pluginJar)
        if (!Files.isRegularFile(jarPath)) {
            return PluginAdmissionState.Unavailable(
                pluginJar = pluginJar,
                reason = "plugin JAR not found in $pluginDir",
            )
        }
        return useCase.inspect(jarPath)
    }

    override fun listAll(): List<PluginAdmissionState> {
        if (!Files.isDirectory(pluginDir)) {
            log.debug("plugin dir does not exist: {} (returning empty list)", pluginDir)
            return emptyList()
        }
        val out = mutableListOf<PluginAdmissionState>()
        Files.list(pluginDir).use { stream ->
            stream.filter { it.fileName.toString().endsWith(".jar") }
                .filter { Files.isRegularFile(it) }
                .forEach { jarPath ->
                    out.add(useCase.inspect(jarPath))
                }
        }
        return out
    }
}
