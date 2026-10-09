package dev.pipelinek.studio.infra.sdk

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarOutputStream
import java.util.jar.Manifest
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.assertFalse

class StaticManifestInspectorTest {

    @Test
    fun `inspect real SDK jar without classloading`(@TempDir tmp: Path) {
        // We construct a synthetic plugin jar inline so the test is hermetic.
        val jar = tmp.resolve("fake-plugin.jar")
        val manifest = Manifest().apply {
            mainAttributes.putValue("Manifest-Version", "1.0")
            mainAttributes.putValue("Implementation-Title", "fake-plugin")
            mainAttributes.putValue("Implementation-Version", "0.1.0")
            mainAttributes.putValue("Implementation-Vendor", "dev.rubentxu.pipeline.v2")
        }
        JarOutputStream(Files.newOutputStream(jar), manifest).use { jos ->
            jos.putNextEntry(java.util.jar.JarEntry("META-INF/services/dev.rubentxu.pipeline.v2.step.StepCodec"))
            jos.write("com.example.PluginStepCodec\n".toByteArray())
            jos.closeEntry()
            jos.putNextEntry(java.util.jar.JarEntry("META-INF/pipelinek/plugin.properties"))
            jos.write("""
                capability=source-control
                capability=git
                """.trimIndent().toByteArray())
            jos.closeEntry()
            jos.putNextEntry(java.util.jar.JarEntry("com/example/PluginStepCodec.class"))
            jos.write(byteArrayOf(0xCA.toByte(), 0xFE.toByte(), 0xBA.toByte(), 0xBE.toByte()))
        }
        val inspector = StaticManifestInspector()
        val m = inspector.inspect(jar)
        assertEquals("dev.rubentxu.pipeline.v2", m.implementationVendor)
        assertEquals("fake-plugin", m.implementationTitle)
        assertEquals("0.1.0", m.implementationVersion)
        assertEquals(64, m.sha256.length, "sha256 must be 64 hex chars")
        assertEquals(1, m.services.size, "should detect 1 META-INF/services entry")
        val svc = m.services[0]
        assertEquals("dev.rubentxu.pipeline.v2.step.StepCodec", svc.interfaceName)
        assertEquals(listOf("com.example.PluginStepCodec"), svc.implClassNames)
        assertEquals(true, m.stepCapabilities.contains("source-control"))
        assertEquals(true, m.stepCapabilities.contains("git"))
        assertEquals(jar, m.jarPath)
    }

    @Test
    fun `trust classification flags tampered vendor`(@TempDir tmp: Path) {
        val jar = tmp.resolve("bad.jar")
        val manifest = Manifest().apply {
            mainAttributes.putValue("Manifest-Version", "1.0")
            mainAttributes.putValue("Implementation-Title", "bad")
            mainAttributes.putValue("Implementation-Vendor", "evil.corp")
        }
        JarOutputStream(Files.newOutputStream(jar), manifest).use { /* empty */ }
        val inspector = StaticManifestInspector()
        val m = inspector.inspect(jar)
        val trust = inspector.trustClass(m, pinnedFingerprints = emptySet(), internalVendor = "dev.rubentxu.pipeline.v2")
        assertTrue(trust is StaticManifestInspector.TrustClass.UntrustedTampered, "got $trust")
        assertTrue((trust as StaticManifestInspector.TrustClass.UntrustedTampered).reason.contains("evil.corp"))
    }

    @Test
    fun `trust classification honours pinned fingerprints`(@TempDir tmp: Path) {
        val jar = tmp.resolve("pinned.jar")
        val manifest = Manifest().apply {
            mainAttributes.putValue("Manifest-Version", "1.0")
            mainAttributes.putValue("Implementation-Vendor", "dev.rubentxu.pipeline.v2")
        }
        JarOutputStream(Files.newOutputStream(jar), manifest).use { /* empty */ }
        val inspector = StaticManifestInspector()
        val m = inspector.inspect(jar)
        val trust = inspector.trustClass(m, pinnedFingerprints = setOf(m.sha256), internalVendor = "dev.rubentxu.pipeline.v2")
        assertEquals(StaticManifestInspector.TrustClass.TrustedPinned, trust)
    }

    @Test
    fun `inspect rejects non-jar file`(@TempDir tmp: Path) {
        val notjar = tmp.resolve("readme.txt")
        Files.writeString(notjar, "hello")
        val inspector = StaticManifestInspector()
        try {
            inspector.inspect(notjar)
            error("should have thrown")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message?.contains("not a jar") == true)
        }
    }
}
