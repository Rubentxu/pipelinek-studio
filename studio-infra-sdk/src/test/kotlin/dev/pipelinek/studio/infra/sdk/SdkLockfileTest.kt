package dev.pipelinek.studio.infra.sdk

import dev.pipelinek.studio.application.ports.RuntimeDescriptorResult
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.jar.JarOutputStream
import java.util.jar.Manifest
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SdkLockfileTest {

    @Test
    fun `parseLockfile reads the canonical format`() {
        val text = """
            // Pin file (test fixture)
            {
              "format_version": 1,
              "pinned_sdks": [
                {
                  "version": "0.48.0-rc1",
                  "sha256": "0811e7b60460e6b15a31cd3f312b1b5b3c36f8d1aa6dce7a7fd04193e362eace",
                  "status": "current",
                  "studio_release": "v0.2.0-alpha.2"
                }
              ]
            }
        """.trimIndent()
        val lf = SdkLockfileVerifier.parseLockfile(text)
        assertEquals(1, lf.formatVersion)
        assertEquals(1, lf.pinnedSdks.size)
        val pin = lf.pinnedSdks.first()
        assertEquals("0.48.0-rc1", pin.version)
        assertEquals("0811e7b60460e6b15a31cd3f312b1b5b3c36f8d1aa6dce7a7fd04193e362eace", pin.sha256)
        assertEquals("current", pin.status)
        assertEquals("v0.2.0-alpha.2", pin.studioRelease)
    }

    @Test
    fun `parseLockfile ignores dollar-prefixed comment keys`() {
        val text = """
            {
              "${'$'}comment": "ignored",
              "${'$'}schema_doc": ["line1", "line2"],
              "format_version": 1,
              "pinned_sdks": []
            }
        """.trimIndent()
        val lf = SdkLockfileVerifier.parseLockfile(text)
        assertEquals(1, lf.formatVersion)
        assertTrue(lf.pinnedSdks.isEmpty())
    }

    @Test
    fun `parseLockfile returns empty on malformed JSON`() {
        val lf = SdkLockfileVerifier.parseLockfile("not json at all")
        assertEquals(SdkLockfile.EMPTY, lf)
    }

    @Test
    fun `verify returns Match when SHA matches`() {
        val lf = SdkLockfile(
            formatVersion = 1,
            pinnedSdks = listOf(
                SdkLockfile.PinnedSdk(
                    version = "0.48.0-rc1",
                    sha256 = "deadbeef",
                    status = "current",
                    studioRelease = "v0.2.0-alpha.2",
                ),
            ),
        )
        val v = SdkLockfileVerifier(lf).verify("0.48.0-rc1", "DEADBEEF") // case-insensitive
        assertTrue(v is PinVerdict.Match)
        assertEquals("0.48.0-rc1", v.pinned.version)
    }

    @Test
    fun `verify returns Mismatch when SHA differs`() {
        val lf = SdkLockfile(
            formatVersion = 1,
            pinnedSdks = listOf(
                SdkLockfile.PinnedSdk(
                    version = "0.48.0-rc1",
                    sha256 = "deadbeef",
                    status = "current",
                    studioRelease = null,
                ),
            ),
        )
        val v = SdkLockfileVerifier(lf).verify("0.48.0-rc1", "cafebabe")
        assertTrue(v is PinVerdict.Mismatch, "expected Mismatch, got $v")
        assertEquals("deadbeef", v.expectedSha256)
        assertEquals("cafebabe", v.actualSha256)
    }

    @Test
    fun `verify returns Unknown when version is not in lockfile`() {
        val lf = SdkLockfile(
            formatVersion = 1,
            pinnedSdks = listOf(
                SdkLockfile.PinnedSdk(
                    version = "0.48.0-rc1",
                    sha256 = "deadbeef",
                    status = "current",
                    studioRelease = null,
                ),
            ),
        )
        val v = SdkLockfileVerifier(lf).verify("0.49.0-rc1", "cafebabe")
        assertTrue(v is PinVerdict.Unknown)
        assertEquals("0.49.0-rc1", v.version)
    }

    @Test
    fun `policyFromEnv maps values correctly`() {
        assertEquals(PinPolicy.Off, SdkLockfileVerifier.policyFromEnv(mapOf("STUDIO_SDK_PIN" to "off")))
        assertEquals(PinPolicy.Off, SdkLockfileVerifier.policyFromEnv(mapOf("STUDIO_SDK_PIN" to "0")))
        assertEquals(PinPolicy.Off, SdkLockfileVerifier.policyFromEnv(mapOf("STUDIO_SDK_PIN" to "disabled")))
        assertEquals(PinPolicy.Warn, SdkLockfileVerifier.policyFromEnv(mapOf("STUDIO_SDK_PIN" to "warn")))
        assertEquals(PinPolicy.Enforce, SdkLockfileVerifier.policyFromEnv(mapOf("STUDIO_SDK_PIN" to "enforce")))
        assertEquals(PinPolicy.Enforce, SdkLockfileVerifier.policyFromEnv(emptyMap()))
        assertEquals(PinPolicy.Enforce, SdkLockfileVerifier.policyFromEnv(mapOf("STUDIO_SDK_PIN" to "bogus")))
    }

    // -- end-to-end: the probe honors the lockfile + policy ----------

    @Test
    fun `probe with Enforce policy returns Available when SHA matches the lockfile`(@TempDir tmp: Path) {
        val jarPath = tmp.resolve("pipeline-application-0.48.0.jar")
        // The probe computes the SHA from the jar bytes. We don't
        // know the SHA until we write the jar, so we write it once
        // with random bytes, compute the SHA, then build a lockfile
        // that pins that exact SHA.
        val payload = ByteArray(64) { (it * 31).toByte() }
        JarOutputStream(jarPath.toFile().outputStream(), Manifest().apply {
            mainAttributes.putValue("Manifest-Version", "1.0")
            mainAttributes.putValue("Implementation-Version", "0.48.0-rc1")
        }).use { jos ->
            jos.putNextEntry(java.util.jar.JarEntry("dev/example/SomeClass.class"))
            jos.write(payload)
            jos.closeEntry()
        }
        val actualSha = sha256Of(jarPath)
        val pin = SdkLockfile.PinnedSdk(
            version = "0.48.0-rc1",
            sha256 = actualSha,
            status = "current",
            studioRelease = "v0.2.0-alpha.2",
        )
        val probe = PipelineKRuntimeProbe(
            classpathRoots = listOf(tmp),
            pinPolicy = PinPolicy.Enforce,
            pinVerifier = SdkLockfileVerifier(SdkLockfile(1, listOf(pin))),
        )
        val r = probe.describe()
        assertTrue(r is RuntimeDescriptorResult.Available, "expected Available, got $r")
        val descriptor = (r as RuntimeDescriptorResult.Available).descriptor
        assertEquals("0.48.0-rc1", descriptor.version)
        assertEquals(actualSha, descriptor.artifactDigest)
    }

    @Test
    fun `probe with Enforce policy returns Failure when SHA does not match the lockfile`(@TempDir tmp: Path) {
        val jarPath = tmp.resolve("pipeline-application-0.48.0.jar")
        JarOutputStream(jarPath.toFile().outputStream(), Manifest().apply {
            mainAttributes.putValue("Manifest-Version", "1.0")
            mainAttributes.putValue("Implementation-Version", "0.48.0-rc1")
        }).use { jos ->
            jos.putNextEntry(java.util.jar.JarEntry("dev/example/SomeClass.class"))
            jos.write(ByteArray(16) { 0x42 })
            jos.closeEntry()
        }
        // Lockfile pins a SHA that does NOT match the jar.
        val wrongPin = SdkLockfile.PinnedSdk(
            version = "0.48.0-rc1",
            sha256 = "0000000000000000000000000000000000000000000000000000000000000000",
            status = "current",
            studioRelease = "v0.2.0-alpha.2",
        )
        val probe = PipelineKRuntimeProbe(
            classpathRoots = listOf(tmp),
            pinPolicy = PinPolicy.Enforce,
            pinVerifier = SdkLockfileVerifier(SdkLockfile(1, listOf(wrongPin))),
        )
        val r = probe.describe()
        assertTrue(r is RuntimeDescriptorResult.Failure, "expected Failure, got $r")
        val reason = (r as RuntimeDescriptorResult.Failure).reason
        assertTrue(reason.contains("pin mismatch"), "reason should mention 'pin mismatch', got: $reason")
    }

    @Test
    fun `probe with Enforce policy returns Failure when version is not in the lockfile`(@TempDir tmp: Path) {
        val jarPath = tmp.resolve("pipeline-application-0.49.0.jar")
        JarOutputStream(jarPath.toFile().outputStream(), Manifest().apply {
            mainAttributes.putValue("Manifest-Version", "1.0")
            mainAttributes.putValue("Implementation-Version", "0.49.0-rc1")
        }).use { jos ->
            jos.putNextEntry(java.util.jar.JarEntry("dev/example/SomeClass.class"))
            jos.write(ByteArray(8) { 0x01 })
            jos.closeEntry()
        }
        // Lockfile only knows 0.48.0-rc1.
        val oldPin = SdkLockfile.PinnedSdk(
            version = "0.48.0-rc1",
            sha256 = "anything",
            status = "current",
            studioRelease = "v0.2.0-alpha.2",
        )
        val probe = PipelineKRuntimeProbe(
            classpathRoots = listOf(tmp),
            pinPolicy = PinPolicy.Enforce,
            pinVerifier = SdkLockfileVerifier(SdkLockfile(1, listOf(oldPin))),
        )
        val r = probe.describe()
        assertTrue(r is RuntimeDescriptorResult.Failure, "expected Failure, got $r")
        val reason = (r as RuntimeDescriptorResult.Failure).reason
        assertTrue(reason.contains("0.49.0-rc1") && reason.contains("sdk.lock.json"),
            "reason should mention the missing version and the lockfile, got: $reason")
    }

    @Test
    fun `probe with Off policy accepts any SHA even when lockfile would Mismatch`(@TempDir tmp: Path) {
        val jarPath = tmp.resolve("pipeline-application-0.48.0.jar")
        JarOutputStream(jarPath.toFile().outputStream(), Manifest().apply {
            mainAttributes.putValue("Manifest-Version", "1.0")
            mainAttributes.putValue("Implementation-Version", "0.48.0-rc1")
        }).use { jos ->
            jos.putNextEntry(java.util.jar.JarEntry("dev/example/SomeClass.class"))
            jos.write(ByteArray(4) { 0x55 })
            jos.closeEntry()
        }
        val wrongPin = SdkLockfile.PinnedSdk(
            version = "0.48.0-rc1",
            sha256 = "0000000000000000000000000000000000000000000000000000000000000000",
            status = "current",
            studioRelease = "v0.2.0-alpha.2",
        )
        val probe = PipelineKRuntimeProbe(
            classpathRoots = listOf(tmp),
            pinPolicy = PinPolicy.Off,
            pinVerifier = SdkLockfileVerifier(SdkLockfile(1, listOf(wrongPin))),
        )
        val r = probe.describe()
        assertTrue(r is RuntimeDescriptorResult.Available, "Off policy should accept any SHA, got $r")
    }

    private fun sha256Of(p: Path): String {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        java.nio.file.Files.newInputStream(p).use { ins ->
            val buf = ByteArray(8192)
            while (true) {
                val n = ins.read(buf)
                if (n <= 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}
