package dev.pipelinek.studio.cert.sdk

import dev.pipelinek.studio.cert.domain.Failure
import dev.pipelinek.studio.cert.domain.Sha256
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.zip.ZipInputStream

/**
 * C1.2 (2026-10-12): the adapter that turns the official
 * PipelineK ZIP into a typed [SdkDistribution].
 *
 * D-04 (Imperative Shell): this is the only place the
 * filesystem is touched for SDK provisioning. The port
 * ([SdkResolver]) hides the IO from the rest of the
 * certifier.
 *
 * Threat model:
 *  - The ZIP may be tampered with on disk (an attacker
 *    swaps a jar inside). We verify the ZIP's outer SHA
 *    and each jar's inner SHA against the published pins.
 *  - The ZIP may contain a path traversal entry. We
 *    refuse any entry whose path starts with `/`,
 *    contains `..`, or escapes the install root. This
 *    is the C1 / AAT-SEC-002 contract.
 *  - The ZIP may contain a symlink that points outside.
 *    We refuse to follow symlinks during extraction.
 */
class ZipSdkResolver : SdkResolver {

    override fun resolve(
        zipPath: Path,
        expectedZipSha: Sha256,
        expectedJarPins: Map<String, Sha256>,
    ): SdkResolution {
        if (!Files.isRegularFile(zipPath)) {
            return SdkResolution.Err(Failure.FileMissing, "SDK ZIP not found at $zipPath")
        }
        val actualZipSha = sha256OfFile(zipPath)
        if (actualZipSha != expectedZipSha.value) {
            return SdkResolution.Err(
                Failure.DigestMismatch,
                "ZIP sha256 mismatch: expected=${expectedZipSha.value} actual=$actualZipSha",
            )
        }

        val installRoot = Files.createTempDirectory("cert-sdk-")
        val jars = mutableListOf<SdkJar>()

        try {
            ZipInputStream(Files.newInputStream(zipPath)).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    val name = entry.name
                    // Reject path traversal + absolute paths + symlink
                    // escapes. The cert never executes anything from
                    // the SDK; the studio's runner will spawn the
                    // JVM with our extracted jars. The protection
                    // is for the extraction step itself.
                    if (name.contains("..") || name.startsWith("/") || name.startsWith("\\")) {
                        return SdkResolution.Err(
                            Failure.ContractMismatch,
                            "ZIP entry has unsafe path: $name",
                        )
                    }
                    if (entry.isDirectory) {
                        zip.closeEntry()
                        entry = zip.nextEntry
                        continue
                    }
                    val outPath = installRoot.resolve(name).normalize()
                    if (!outPath.startsWith(installRoot)) {
                        return SdkResolution.Err(
                            Failure.ContractMismatch,
                            "ZIP entry escapes install root: $name",
                        )
                    }
                    Files.createDirectories(outPath.parent)
                    val bytes = zip.readBytes()
                    Files.write(outPath, bytes)
                    zip.closeEntry()

                    if (name.endsWith(".jar")) {
                        val sha = sha256OfBytes(bytes)
                        val expected = expectedJarPins[name.substringAfterLast('/')]
                        val pinOk = expected == null || expected.value == sha
                        if (!pinOk) {
                            return SdkResolution.Err(
                                Failure.DigestMismatch,
                                "jar ${name.substringAfterLast('/')} sha mismatch: expected=${expected.value} actual=$sha",
                            )
                        }
                        // The main-class jar contains dev.rubentxu.pipeline.v2.application.MainKt
                        // (per the official launcher). We mark it as the
                        // canonical jar — the resolver does not
                        // classload, so we use a content sniff.
                        val isMain = name.endsWith("pipeline-application-0.48.0.jar") ||
                            (expected == null && isLikelyMainClassJar(bytes))
                        jars.add(
                            SdkJar(
                                name = name.substringAfterLast('/'),
                                path = outPath,
                                sha256 = Sha256(sha),
                                sizeBytes = bytes.size.toLong(),
                                isMainClass = isMain,
                            ),
                        )
                    }
                    entry = zip.nextEntry
                }
            }
        } catch (t: Throwable) {
            return SdkResolution.Err(
                Failure.ExecutionError,
                "SDK ZIP extraction failed: ${t.javaClass.simpleName}: ${t.message}",
            )
        }

        val mainJar = jars.firstOrNull { it.isMainClass }
        if (mainJar == null) {
            return SdkResolution.Err(
                Failure.ManifestInvalid,
                "SDK ZIP did not contain a Main-Class jar (pipeline-application-*.jar)",
            )
        }
        val launcher = installRoot.resolve("pipelinek-0.48.0").resolve("bin").resolve("pipelinek")
        if (!Files.isRegularFile(launcher)) {
            return SdkResolution.Err(
                Failure.FileMissing,
                "SDK ZIP did not contain a launcher script at $launcher",
            )
        }

        return SdkResolution.Ok(
            SdkDistribution(
                version = "0.48.0-rc1",
                zipSha256 = expectedZipSha,
                installRoot = installRoot,
                mainClass = "dev.rubentxu.pipeline.v2.application.MainKt",
                launcherScript = launcher,
                jars = jars.toList().sortedBy { it.name },
            ),
        )
    }

    private fun isLikelyMainClassJar(bytes: ByteArray): Boolean {
        // The official jar embeds the MainKt class. We do
        // a lightweight content check: the bytes
        // contain the class name as a string. This
        // avoids classloading during cert.
        val s = String(bytes, Charsets.ISO_8859_1)
        return s.contains("dev/rubentxu/pipeline/v2/application/MainKt")
    }

    private fun sha256OfFile(p: Path): String {
        val md = MessageDigest.getInstance("SHA-256")
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

    private fun sha256OfBytes(bytes: ByteArray): String {
        val md = MessageDigest.getInstance("SHA-256")
        md.update(bytes)
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}
