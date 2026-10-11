package dev.pipelinek.studio.infra.sdk

import dev.pipelinek.studio.application.ports.AdmittedPlugin
import dev.pipelinek.studio.application.ports.PluginAdmissionLockPort
import dev.pipelinek.studio.application.ports.PluginAdmissionState
import dev.pipelinek.studio.application.ports.StepDiscovery
import dev.pipelinek.studio.application.ports.StepProvenance
import dev.pipelinek.studio.application.usecases.PluginAdmissionUseCase
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarOutputStream
import java.util.jar.Manifest
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * R2.3 closure + R2.7 cierre (2026-10-11): the read impl of
 * [PluginAdmissionPort] and the catalog provenance surface. Two
 * surfaces tested here:
 *
 *   1. `DirectoryPluginAdmissionPort.listAll` scans a directory
 *      and returns one `PluginAdmissionState` per JAR, including
 *      `Discovered`/`Compatible`/`Refused`/`Unavailable` paths.
 *
 *   2. The `StepContract` extension with `provenance` and
 *      `refusalReason` survives the catalog read path: the
 *      `PipelineKStepCatalogReadPort` populates provenance from
 *      the static manifest, and the consumer (HTTP / SPA) sees
 *      the field without losing the existing `affordances` etc.
 */
class DirectoryPluginAdmissionPortR23ImplTest {

    private val sdkVendor = "dev.rubentxu.pipeline.v2"
    private val sdkVersion = "0.50.0"
    private val fixedNow: Instant = Instant.parse("2026-10-11T20:00:00Z")
    private val actor: String = "ops:test"

    private class InMemoryLock : PluginAdmissionLockPort {
        private var entries: List<AdmittedPlugin> = emptyList()
        override fun load(): List<AdmittedPlugin> = entries
        override fun save(admitted: List<AdmittedPlugin>) { entries = admitted }
    }

    private fun makeJar(dir: Path, jarName: String, version: String, sdkRange: String?, withServiceLoader: Boolean = false): Path {
        val jar = dir.resolve(jarName)
        val manifest = Manifest().apply {
            mainAttributes.putValue("Manifest-Version", "1.0")
            mainAttributes.putValue("Implementation-Title", jarName.removeSuffix(".jar"))
            mainAttributes.putValue("Implementation-Version", version)
            mainAttributes.putValue("Implementation-Vendor", sdkVendor)
            if (sdkRange != null) {
                mainAttributes.putValue("X-PipelineK-Sdk-Version-Range", sdkRange)
            }
        }
        JarOutputStream(Files.newOutputStream(jar), manifest).use { jos ->
            if (withServiceLoader) {
                // PipelineKStepCatalogReadPort only surfaces steps
                // declared via META-INF/services/<StepCodec FQN>.
                // Without this, the catalog returns empty.
                jos.putNextEntry(java.util.jar.JarEntry("META-INF/services/dev.rubentxu.pipeline.v2.step.StepCodec"))
                jos.write("com.example.TestStepCodec\n".toByteArray())
                jos.closeEntry()
            }
        }
        return jar
    }

    @Test
    fun `R2_3 impl listAll returns one state per JAR in the directory`(@TempDir tmp: Path) {
        makeJar(tmp, "valid-0.1.0.jar", "0.1.0", "[0.0.0, 1.0.0)")
        makeJar(tmp, "bad-99.0.0.jar", "99.0.0", "[2.0.0, 3.0.0)")
        // A non-jar file should be skipped.
        Files.writeString(tmp.resolve("readme.txt"), "noise")

        val lock = InMemoryLock()
        val uc = PluginAdmissionUseCase(
            inspector = StaticManifestInspectorAdapter(),
            lock = lock,
            sdkVersion = sdkVersion,
            sdkVendor = sdkVendor,
            clock = { fixedNow },
        )
        val port = DirectoryPluginAdmissionPort(tmp, uc)

        val states = port.listAll()
        assertEquals(2, states.size, "listAll must include one entry per .jar, ignoring non-jars")
        val byName = states.associateBy { it.pluginJar }
        assertTrue(byName["valid-0.1.0.jar"] is PluginAdmissionState.Compatible)
        assertTrue(byName["bad-99.0.0.jar"] is PluginAdmissionState.Refused)
    }

    @Test
    fun `R2_3 impl listAll on missing dir returns empty list`(@TempDir tmp: Path) {
        val ghost = tmp.resolve("does-not-exist")
        val lock = InMemoryLock()
        val uc = PluginAdmissionUseCase(
            inspector = StaticManifestInspectorAdapter(),
            lock = lock,
            sdkVersion = sdkVersion,
            sdkVendor = sdkVendor,
        )
        val port = DirectoryPluginAdmissionPort(ghost, uc)
        assertEquals(emptyList(), port.listAll(), "missing dir must NOT crash; empty list is honest")
    }

    @Test
    fun `R2_3 impl stateOf on missing JAR returns Unavailable`(@TempDir tmp: Path) {
        val lock = InMemoryLock()
        val uc = PluginAdmissionUseCase(
            inspector = StaticManifestInspectorAdapter(),
            lock = lock,
            sdkVersion = sdkVersion,
            sdkVendor = sdkVendor,
        )
        val port = DirectoryPluginAdmissionPort(tmp, uc)
        val state = port.stateOf("ghost.jar")
        assertTrue(state is PluginAdmissionState.Unavailable, "expected Unavailable, got $state")
    }

    @Test
    fun `R2_7 StepProvenance surfaces all fields`(@TempDir tmp: Path) {
        val jar = makeJar(tmp, "plug-0.1.0.jar", "0.1.0", "[0.0.0, 1.0.0)", withServiceLoader = true)
        val port = PipelineKStepCatalogReadPort(tmp)
        val result = port.list() as dev.pipelinek.studio.application.ports.StepCatalogResult.Ok
        assertEquals(1, result.steps.size)
        val step = result.steps.single()
        val prov = step.provenance
        assertNotNull(prov, "StepContract must surface provenance for external plugin steps")
        assertEquals("plug-0.1.0.jar", prov.pluginJar)
        assertEquals(sdkVendor, prov.vendor)
        assertEquals("[0.0.0, 1.0.0)", prov.sdkVersionRange)
        assertEquals(StepDiscovery.ServiceLoader, prov.discovery)
        // The raw catalog has no gate context, so refusalReason stays null.
        assertEquals(null, step.refusalReason)
    }
}
