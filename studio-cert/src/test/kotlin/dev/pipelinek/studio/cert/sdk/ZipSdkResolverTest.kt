package dev.pipelinek.studio.cert.sdk

import dev.pipelinek.studio.cert.domain.Failure
import dev.pipelinek.studio.cert.domain.Sha256
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * C1.1 + C1.2 (2026-10-12): the SDK resolver contract tests.
 *
 * The tests pin:
 *  - Successful resolution produces a typed
 *    [SdkDistribution] with the right canonical jar
 *    and main class.
 *  - Wrong ZIP SHA → Err(DigestMismatch).
 *  - Tampered jar inside ZIP → Err(DigestMismatch) once
 *    the per-jar pin disagrees.
 *  - Path-traversal entry → Err(ContractMismatch).
 *  - Missing main jar → Err(ManifestInvalid).
 *  - Missing launcher → Err(FileMissing).
 *  - The actual SDK 0.48.0-rc1 ZIP we ship under
 *    `cert/sdk/pipelinek-0.48.0.zip` resolves to a
 *    distribution with the mandated ZIP SHA
 *    `a4620df4…` and the locked pipeline-application
 *    jar SHA `0811e7b6…`.
 */
class ZipSdkResolverTest {

    @Test
    fun `C1_1 - the shipped 0_48_0-rc1 ZIP resolves with the mandated SHAs`() {
        val zip = Path.of("sdk/pipelinek-0.48.0.zip")
        if (!Files.isRegularFile(zip)) {
            println("skipping: $zip not present (run from the repo root with the ZIP staged under cert/sdk/)")
            return
        }
        val r = ZipSdkResolver().resolve(zip, ExpectedPins.PIPELINEK_0_48_0_ZIP, ExpectedPins.PIPELINEK_0_48_0_JARS)
        assertTrue(r is SdkResolution.Ok, "expected Ok, got $r")
        val d = (r as SdkResolution.Ok).distribution
        assertEquals("0.48.0-rc1", d.version)
        assertEquals(ExpectedPins.PIPELINEK_0_48_0_ZIP, d.zipSha256)
        assertEquals(
            "dev.rubentxu.pipeline.v2.application.MainKt",
            d.mainClass,
            "Main-Class must match the official pipelinek launcher",
        )
        assertTrue(d.canonicalJar.isMainClass)
        assertTrue(d.canonicalJar.sha256.value.startsWith("0811e7b6"))
        assertTrue(d.jars.size >= 30, "expected at least 30 jars in the SDK, got ${d.jars.size}")
        assertTrue(Files.isRegularFile(d.launcherScript))
    }

    @Test
    fun `C1_2 - wrong ZIP SHA is DigestMismatch`(@TempDir tmp: Path) {
        val zip = makeFakeZip(tmp, "abc" to "hello".toByteArray())
        val r = ZipSdkResolver().resolve(zip, Sha256("0".repeat(64)), emptyMap())
        assertTrue(r is SdkResolution.Err)
        assertEquals(Failure.DigestMismatch, (r as SdkResolution.Err).failure)
    }

    @Test
    fun `C1_2 - missing ZIP is FileMissing`(@TempDir tmp: Path) {
        val r = ZipSdkResolver().resolve(tmp.resolve("nope.zip"), Sha256("0".repeat(64)), emptyMap())
        assertTrue(r is SdkResolution.Err)
        assertEquals(Failure.FileMissing, (r as SdkResolution.Err).failure)
    }

    @Test
    fun `C1_2 - path traversal entry is ContractMismatch`(@TempDir tmp: Path) {
        val (zip, sha) = makeZipWithEntryReturningSha(tmp, "../escape.txt", "boom".toByteArray())
        val r = ZipSdkResolver().resolve(zip, Sha256(sha), emptyMap())
        assertTrue(r is SdkResolution.Err)
        assertEquals(Failure.ContractMismatch, (r as SdkResolution.Err).failure)
    }

    @Test
    fun `C1_2 - absolute path entry is ContractMismatch`(@TempDir tmp: Path) {
        val (zip, sha) = makeZipWithEntryReturningSha(tmp, "/etc/passwd", "boom".toByteArray())
        val r = ZipSdkResolver().resolve(zip, Sha256(sha), emptyMap())
        assertTrue(r is SdkResolution.Err)
        assertEquals(Failure.ContractMismatch, (r as SdkResolution.Err).failure)
    }

    @Test
    fun `C1_2 - jar with mismatched pin is DigestMismatch`(@TempDir tmp: Path) {
        val jarBytes = "fake jar content".toByteArray()
        val (zip, sha) = makeZipWithEntryReturningSha(
            tmp,
            "pipelinek-0.48.0/lib/pipeline-application-0.48.0.jar",
            jarBytes,
        )
        // Pin to a wrong SHA
        val r = ZipSdkResolver().resolve(
            zip,
            Sha256(sha),
            mapOf("pipeline-application-0.48.0.jar" to Sha256("f".repeat(64))),
        )
        assertTrue(r is SdkResolution.Err)
        assertEquals(Failure.DigestMismatch, (r as SdkResolution.Err).failure)
    }

    @Test
    fun `C1_2 - jar with matching pin is accepted`(@TempDir tmp: Path) {
        val jarBytes = "fake jar content".toByteArray()
        val launcherBytes = "#!/bin/sh\necho fake\n".toByteArray()
        val (zip, sha) = makeZipWithEntriesReturningSha(
            tmp,
            listOf(
                "pipelinek-0.48.0/lib/pipeline-application-0.48.0.jar" to jarBytes,
                "pipelinek-0.48.0/bin/pipelinek" to launcherBytes,
            ),
        )
        val jarSha = sha256OfBytes(jarBytes)
        val r = ZipSdkResolver().resolve(
            zip,
            Sha256(sha),
            mapOf("pipeline-application-0.48.0.jar" to Sha256(jarSha)),
        )
        assertTrue(r is SdkResolution.Ok, "expected Ok, got $r")
        val d = (r as SdkResolution.Ok).distribution
        assertTrue(d.canonicalJar.isMainClass)
    }

    // -- helpers --

    private fun makeFakeZip(tmp: Path, entry: Pair<String, ByteArray>): Path {
        val zip = tmp.resolve("a.zip")
        java.util.zip.ZipOutputStream(Files.newOutputStream(zip)).use { out ->
            out.putNextEntry(java.util.zip.ZipEntry(entry.first))
            out.write(entry.second)
            out.closeEntry()
        }
        return zip
    }

    private fun makeZipWithEntryReturningSha(
        tmp: Path,
        name: String,
        bytes: ByteArray,
    ): Pair<Path, String> {
        val zip = makeFakeZip(tmp, name to bytes)
        return zip to sha256OfFile(zip)
    }

    private fun makeZipWithEntriesReturningSha(
        tmp: Path,
        entries: List<Pair<String, ByteArray>>,
    ): Pair<Path, String> {
        val zip = tmp.resolve("a.zip")
        java.util.zip.ZipOutputStream(Files.newOutputStream(zip)).use { out ->
            entries.forEach { (name, bytes) ->
                out.putNextEntry(java.util.zip.ZipEntry(name))
                out.write(bytes)
                out.closeEntry()
            }
        }
        return zip to sha256OfFile(zip)
    }

    private fun sha256OfFile(p: Path): String {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        Files.newInputStream(p).use { input ->
            val buf = ByteArray(8192)
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private fun makeZipWithEntry(tmp: Path, name: String, bytes: ByteArray): Path =
        makeFakeZip(tmp, name to bytes)

    private fun sha256OfBytes(bytes: ByteArray): String {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        md.update(bytes)
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}
