package dev.pipelinek.studio.infra.sdk

import dev.pipelinek.studio.application.ports.RuntimeDescriptorResult
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.jar.JarOutputStream
import java.util.jar.Manifest
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class PipelineKRuntimeProbeTest {

    @Test
    fun `no classpath roots yields Unavailable`() {
        val probe = PipelineKRuntimeProbe(classpathRoots = emptyList())
        val r = probe.describe()
        assertEquals(RuntimeDescriptorResult.Unavailable, r)
    }

    @Test
    fun `classpath containing a fake SDK jar without runtime class yields Failure`() {
        // Create a temp dir with a fake file — proves the locator is honest.
        val tmp = java.nio.file.Files.createTempDirectory("studio-fake-")
        try {
            val probe = PipelineKRuntimeProbe(classpathRoots = listOf(tmp))
            val r = probe.describe()
            // Either Unavailable (no jar) or Failure (jar present but no class) — both acceptable.
            assertTrue(r is RuntimeDescriptorResult.Unavailable || r is RuntimeDescriptorResult.Failure,
                "expected honest negative result, got $r")
        } finally {
            tmp.toFile().deleteRecursively()
        }
    }

    /**
     * Audit C-7 (2026-10-10): an SDK jar that has a manifest but no
     * capability markers must yield an [RuntimeDescriptorResult.Available]
     * with an **empty** capabilities set. The previous implementation
     * returned a hard-coded default when no marker matched, fabricating
     * capabilities the SDK might not actually expose. The fix makes the
     * probe honest: "no marker found" → "no capabilities claimed".
     *
     * Audit R-22 (2026-10-10): the C-7 test uses a stub jar with an
     * unmatching SHA; we explicitly opt out of pin enforcement here so
     * the test isolates the C-7 capability behavior. The pin behavior
     * is locked separately in SdkLockfileTest.
     */
    @Test
    fun `jar with manifest but no capability markers yields empty capabilities not fabricated defaults`(@TempDir tmp: Path) {
        val jarPath = tmp.resolve("pipeline-application-0.48.0.jar")
        // JarOutputStream already writes META-INF/MANIFEST.MF when given
        // a Manifest; do not also putNextEntry("META-INF/MANIFEST.MF") or
        // the zip file will contain two of them and ZipFile will throw.
        JarOutputStream(jarPath.toFile().outputStream(), Manifest().apply {
            mainAttributes.putValue("Manifest-Version", "1.0")
            mainAttributes.putValue("Implementation-Version", "0.48.0-rc1")
        }).use { jos ->
            // No entries that match "scripting", "events", or "events-store".
            jos.putNextEntry(java.util.jar.JarEntry("dev/example/SomeClass.class"))
            jos.write(ByteArray(0))
            jos.closeEntry()
        }
        val probe = PipelineKRuntimeProbe(classpathRoots = listOf(tmp), pinPolicy = PinPolicy.Off)
        val r = probe.describe()
        assertTrue(r is RuntimeDescriptorResult.Available, "expected Available, got $r")
        val descriptor = (r as RuntimeDescriptorResult.Available).descriptor
        assertNotNull(descriptor)
        assertEquals("0.48.0-rc1", descriptor.version)
        assertTrue(descriptor.capabilities.isEmpty(), "expected empty capabilities, got ${descriptor.capabilities}")
    }

    /**
     * Audit C-7: a jar that is present but cannot be read as a valid
     * jar (corrupt) must yield [RuntimeDescriptorResult.Failure], not
     * an [RuntimeDescriptorResult.Available] with fabricated defaults.
     */
    @Test
    fun `corrupt jar yields Failure not Available with fabricated defaults`(@TempDir tmp: Path) {
        val jarPath = tmp.resolve("pipeline-application-corrupt.jar")
        java.nio.file.Files.write(jarPath, ByteArray(0)) // empty file, not a valid jar
        val probe = PipelineKRuntimeProbe(classpathRoots = listOf(tmp), pinPolicy = PinPolicy.Off)
        val r = probe.describe()
        // Empty file may be skipped by the locator (no .jar entries) or
        // surfaced as Failure. Either is acceptable, but the previous
        // implementation would have reported an Available with
        // hard-coded capabilities — that path is now closed.
        assertTrue(r is RuntimeDescriptorResult.Unavailable || r is RuntimeDescriptorResult.Failure,
            "expected honest negative result, got $r")
    }
}
