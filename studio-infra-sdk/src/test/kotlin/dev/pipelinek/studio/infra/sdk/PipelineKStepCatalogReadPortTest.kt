package dev.pipelinek.studio.infra.sdk

import dev.pipelinek.studio.application.ports.StepCatalogResult
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarOutputStream
import java.util.jar.Manifest
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PipelineKStepCatalogReadPortTest {

    private fun writePluginJar(dir: Path, jarName: String, title: String, version: String): Path {
        val jar = dir.resolve(jarName)
        val manifest = Manifest().apply {
            mainAttributes.putValue("Manifest-Version", "1.0")
            mainAttributes.putValue("Implementation-Title", title)
            mainAttributes.putValue("Implementation-Version", version)
            mainAttributes.putValue("Implementation-Vendor", "dev.rubentxu.pipeline.v2")
        }
        JarOutputStream(Files.newOutputStream(jar), manifest).use { jos ->
            jos.putNextEntry(java.util.jar.JarEntry("META-INF/services/dev.rubentxu.pipeline.v2.step.StepCodec"))
            jos.write("com.example.${title.replace("-", "")}Step\n".toByteArray())
            jos.closeEntry()
            jos.putNextEntry(java.util.jar.JarEntry("META-INF/pipelinek/plugin.properties"))
            jos.write("""
                capability=source-control
                capability=git
                """.trimIndent().toByteArray())
        }
        return jar
    }

    @Test
    fun `lists step contracts from plugin jars in sdk lib dir`(@TempDir tmp: Path) {
        writePluginJar(tmp, "scm-git-1.0.jar", "scm-git", "1.0")
        writePluginJar(tmp, "http-2.0.jar", "http", "2.0")
        // Add an unrelated jar that should be ignored
        Files.writeString(tmp.resolve("readme.txt"), "noise")

        val port = PipelineKStepCatalogReadPort(tmp)
        val res = port.list()
        assertTrue(res is StepCatalogResult.Ok, "got $res")
        val ok = res as StepCatalogResult.Ok
        assertEquals(2, ok.steps.size)
        val byName = ok.steps.associateBy { it.id }
        assertTrue("scm-git" in byName, "missing scm-git step: $byName")
        assertTrue("http" in byName, "missing http step: $byName")
        val scm = byName.getValue("scm-git")
        assertEquals(setOf("source-control", "git"), scm.capabilities)
        assertEquals("scm-git-1.0.jar", scm.pluginJar)
    }

    @Test
    fun `describe unknown id fails with FAILURE not ok`(@TempDir tmp: Path) {
        writePluginJar(tmp, "scm-git-1.0.jar", "scm-git", "1.0")
        val port = PipelineKStepCatalogReadPort(tmp)
        val res = port.describe("nonexistent-step")
        assertTrue(res is StepCatalogResult.Failure, "got $res")
        assertTrue((res as StepCatalogResult.Failure).reason.contains("not admitted"))
    }

    @Test
    fun `missing lib dir returns Failure not crash`(@TempDir tmp: Path) {
        val port = PipelineKStepCatalogReadPort(Path.of("does-not-exist"))
        val res = port.list()
        assertTrue(res is StepCatalogResult.Failure)
    }

    @Test
    fun `skips sources and javadoc jars`(@TempDir tmp: Path) {
        writePluginJar(tmp, "scm-git-1.0.jar", "scm-git", "1.0")
        // add a sources jar that should be filtered out
        val m = Manifest().apply { mainAttributes.putValue("Implementation-Title", "scm-git-sources") }
        JarOutputStream(Files.newOutputStream(tmp.resolve("scm-git-1.0-sources.jar")), m).use { /* empty */ }
        val port = PipelineKStepCatalogReadPort(tmp)
        val res = port.list() as StepCatalogResult.Ok
        assertEquals(1, res.steps.size)
        assertEquals("scm-git", res.steps[0].id)
    }
}
