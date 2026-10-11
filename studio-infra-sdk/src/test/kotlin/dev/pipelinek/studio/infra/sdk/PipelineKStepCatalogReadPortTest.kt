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

    // ----- R2 mandate item 4: Core*Step.class name detection is NOT an
    // admission signal. The pre-R2 adapter used to walk the JAR's
    // central directory and surface any class file whose path matched
    // `*/Core*Step.class`. That was a class-name heuristic, not a real
    // admission check — any JAR could ship a class named `CoreFooStep.class`
    // and Studio would advertise it as a Step. R2 removes that source
    // entirely; the catalog now trusts only ServiceLoader declarations +
    // the SDK's authoritative StepRegistry snapshot (when wired). This
    // test is the regression guard: a JAR containing ONLY Core*Step.class
    // entries (no ServiceLoader) MUST surface an empty catalog, not a
    // fabricated Step list.

    private fun writeCoreStepOnlyJar(dir: Path, jarName: String): Path {
        val jar = dir.resolve(jarName)
        val manifest = Manifest().apply {
            mainAttributes.putValue("Manifest-Version", "1.0")
            mainAttributes.putValue("Implementation-Title", jarName.removeSuffix(".jar"))
            mainAttributes.putValue("Implementation-Version", "1.0")
            mainAttributes.putValue("Implementation-Vendor", "dev.rubentxu.pipeline.v2")
        }
        JarOutputStream(Files.newOutputStream(jar), manifest).use { jos ->
            // Class entries only — NO META-INF/services declaration.
            // The class content is not required for the catalog; we only
            // need the JAR entries to exist.
            for (fqcn in listOf("dev/example/CoreEchoStep", "dev/example/CoreTransformService")) {
                jos.putNextEntry(java.util.jar.JarEntry("${fqcn}.class"))
                jos.write(byteArrayOf()) // empty body
                jos.closeEntry()
            }
        }
        return jar
    }

    @Test
    fun `R2 catalog rejects CoreStep class as admission signal (no ServiceLoader yields empty)`(@TempDir tmp: Path) {
        writeCoreStepOnlyJar(tmp, "core-only-1.0.jar")
        val port = PipelineKStepCatalogReadPort(tmp)
        val res = port.list()
        assertTrue(res is StepCatalogResult.Ok, "catalog must return Ok, not Failure")
        val ok = res as StepCatalogResult.Ok
        assertEquals(0, ok.steps.size, "Core*Step.class alone is NOT an admission signal; catalog must be empty")
    }

    @Test
    fun `R2 catalog mixes ServiceLoader + CoreStep class and only reports ServiceLoader`(@TempDir tmp: Path) {
        writePluginJar(tmp, "scm-git-1.0.jar", "scm-git", "1.0")
        writeCoreStepOnlyJar(tmp, "core-only-1.0.jar")
        val port = PipelineKStepCatalogReadPort(tmp)
        val res = port.list() as StepCatalogResult.Ok
        // Only the ServiceLoader-declared step is admitted; the Core*
        // class file in the sibling jar must NOT be surfaced.
        assertEquals(1, res.steps.size, "only ServiceLoader-declared step should appear")
        assertEquals("scm-git", res.steps[0].id)
        // Defensive: no step in the catalog should derive from the
        // Core* heuristic (e.g. no id ends with 'CoreEchoStep').
        for (s in res.steps) {
            assertTrue(s.id.contains("scm-git") || s.id.contains("Core").not(),
                "step id ${s.id} must not be a Core* class-name artifact")
        }
    }
}
