package dev.pipelinek.studio.infra.sdk

import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarFile
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * R2.1 (2026-10-11): adversarial test that the external SDK fixture
 * (`examples/sdk-external-execution/`) produces three JARs whose
 * static manifest + META-INF/services + canary marker meet the
 * AAT-020 / AAT-021 contract.
 *
 * The fixture is a STANDALONE Gradle subproject that does NOT
 * depend on the studio modules. It is built with:
 *   ./gradlew :examples:sdk-external-execution:assemble
 * The three JARs land in:
 *   examples/sdk-external-execution/build/libs/
 *
 * If the JARs are missing, this test is skipped (not failed) so the
 * studio-infra-sdk test suite can run independently. The README in
 * the fixture project documents the build-first step.
 *
 * Properties pinned:
 *   1. valid-plugin: ServiceLoader declaration for
 *      `dev.rubentxu.pipeline.v2.step.StepCodec` with the right
 *      impl class; vendor matches SDK vendor; version is inside
 *      range; capabilities are read from META-INF/pipelinek/
 *      plugin.properties. (AAT-020 happy path.)
 *
 *   2. incompatible-api-plugin: vendor matches; version is
 *      "99.0.0" (way outside [0.0.0, 1.0.0)). A future admission
 *      gate will detect this; here we pin that the static
 *      inspector surfaces the raw version string honestly (no
 *      fabrication, no "version unknown" substitution). (AAT-021
 *      unhappy path.)
 *
 *   3. canary-initializer-plugin: the inspector MUST NOT
 *      classload. The JAR ships a fake .class file in
 *      `com/example/CanaryInit.class` whose bytes include the
 *      string "CANARY_RAN". The marker text file
 *      `META-INF/pipelinek/canary-marker.txt` also contains
 *      "CANARY_RAN". Neither the class file nor the marker text
 *      file may surface in the inspector's typed output. The
 *      static inspector only reads MANIFEST.MF, META-INF/services
 *      and plugin.properties; the class file and the marker text
 *      file are not surfaced. (AAT-021 canary invariant.)
 */
class ExternalFixtureR21Test {

    private val inspector = StaticManifestInspector()

    private val sdkVendor = "dev.rubentxu.pipeline.v2"

    private fun fixtureLibsDir(): Path {
        // The test runs from studio-infra-sdk/build/classes/.../test/;
        // walk up to the repo root and into the fixture.
        var dir: Path? = Path.of(".").toAbsolutePath().normalize()
        while (dir != null && !Files.isRegularFile(dir.resolve("gradle.properties"))) {
            dir = dir.parent
        }
        requireNotNull(dir) { "could not walk up to find gradle.properties" }
        return dir.resolve("examples/sdk-external-execution/build/libs")
    }

    private fun jarOrSkip(name: String): Path? {
        val p = fixtureLibsDir().resolve(name)
        if (!Files.isRegularFile(p)) {
            // Skip (not fail): the fixture must be built separately.
            println("[ExternalFixtureR21Test] SKIP — $p not found. Build with: ./gradlew :examples:sdk-external-execution:assemble")
            return null
        }
        return p
    }

    @Test
    fun `R2_1 fixture valid-plugin is discovered as a StepCodec with SDK vendor`() {
        val jar = jarOrSkip("valid-plugin-0.1.0.jar") ?: return
        val manifest = inspector.inspect(jar, expectedInternalVendor = sdkVendor)

        // Vendor + version + title.
        assertEquals("valid-plugin", manifest.implementationTitle)
        assertEquals("0.1.0", manifest.implementationVersion)
        assertEquals(sdkVendor, manifest.implementationVendor)

        // ServiceLoader declaration read.
        val stepCodec = manifest.services.firstOrNull {
            it.interfaceName == "dev.rubentxu.pipeline.v2.step.StepCodec" ||
                it.interfaceName.endsWith(".StepCodec")
        }
        assertNotNull(stepCodec, "StepCodec service declaration must be discovered: ${manifest.services}")
        assertEquals(listOf("com.example.scmm.GitStepCodec"), stepCodec.implClassNames)

        // Capabilities from META-INF/pipelinek/plugin.properties.
        assertEquals(
            setOf("source-control", "git"),
            manifest.stepCapabilities.toSet(),
            "capabilities must come from plugin.properties, not fabricated",
        )
    }

    @Test
    fun `R2_1 fixture incompatible-api-plugin is discovered honestly — version surfaced raw`() {
        val jar = jarOrSkip("incompatible-api-plugin-99.0.0.jar") ?: return
        val manifest = inspector.inspect(jar, expectedInternalVendor = sdkVendor)

        // Vendor matches; version is way out of range. The static
        // inspector MUST NOT substitute "unknown" or "compatible"
        // — the raw version string is the admission signal.
        assertEquals(sdkVendor, manifest.implementationVendor)
        assertEquals("99.0.0", manifest.implementationVersion, "version must surface raw, no fabrication")

        // The plugin IS discoverable (a JAR with a ServiceLoader
        // declaration is on the candidate list). Refusal is the
        // job of the admission gate (R2.3), not the static
        // inspector. Here we pin that the inspector does not
        // pre-emptively filter.
        val stepCodec = manifest.services.firstOrNull {
            it.interfaceName.endsWith(".StepCodec")
        }
        assertNotNull(stepCodec, "inspector must not silently drop version-out-of-range plugins")
        assertEquals(listOf("com.example.scmm.SvnStepCodec"), stepCodec.implClassNames)
    }

    @Test
    fun `R2_1 fixture canary-initializer-plugin — static inspector does not surface CANARY_RAN marker`() {
        val jar = jarOrSkip("canary-initializer-plugin-0.1.0.jar") ?: return

        // First, assert the canary marker ACTUALLY EXISTS in the
        // JAR bytes. If it does not, the fixture is broken and
        // the canary invariant is vacuously true (the test would
        // pass for the wrong reason). Pin the fixture setup.
        val (classHasMarker, textHasMarker) = JarFile(jar.toFile()).use { j ->
            var classHas = false
            var textHas = false
            val entries = j.entries()
            while (entries.hasMoreElements()) {
                val e = entries.nextElement()
                when (e.name) {
                    "com/example/CanaryInit.class" -> {
                        val bytes = j.getInputStream(e).use { it.readBytes() }
                        classHas = String(bytes, Charsets.ISO_8859_1).contains("CANARY_RAN")
                    }
                    "META-INF/pipelinek/canary-marker.txt" -> {
                        val text = j.getInputStream(e).use { it.readBytes().toString(Charsets.UTF_8) }
                        textHas = text.contains("CANARY_RAN")
                    }
                }
            }
            classHas to textHas
        }
        assertTrue(classHasMarker, "fixture setup error: CanaryInit.class must contain 'CANARY_RAN' (so a classload would surface it)")
        assertTrue(textHasMarker, "fixture setup error: canary-marker.txt must contain 'CANARY_RAN' (so a file-read would surface it)")

        // Now the real assertion: after running the static
        // inspector, the typed output must NOT contain the marker
        // anywhere. If it did, the inspector would have either
        // (a) classloaded the JAR or (b) read the marker file as
        // a surfaced field. Both are forbidden.
        val manifest = inspector.inspect(jar, expectedInternalVendor = sdkVendor)

        val allSurfacedStrings = buildList {
            add(manifest.implementationTitle ?: "")
            add(manifest.implementationVersion ?: "")
            add(manifest.implementationVendor ?: "")
            manifest.services.forEach { svc ->
                add(svc.interfaceName)
                svc.implClassNames.forEach { add(it) }
            }
            manifest.stepCapabilities.forEach { add(it) }
        }
        for (s in allSurfacedStrings) {
            assertTrue(
                !s.contains("CANARY_RAN"),
                "static inspector must not surface the canary marker in any typed field, got: '$s'",
            )
        }
    }
}
