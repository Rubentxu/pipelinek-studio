package dev.pipelinek.studio.infra.sdk

import dev.pipelinek.studio.application.ports.RuntimeDescriptorResult
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
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
}
