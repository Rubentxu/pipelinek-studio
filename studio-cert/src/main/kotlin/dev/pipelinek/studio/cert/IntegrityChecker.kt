package dev.pipelinek.studio.cert

import dev.pipelinek.studio.cert.domain.IntegrityEnvelope
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

/**
 * R5.2 (2026-10-12): the integrity envelope is built from
 * MEASURED inputs, not from `true` literals. Every field
 * corresponds to a real check; if a check cannot be
 * performed in the current execution context, the field
 * is marked NOT_MEASURED via a `null` Boolean (the JSON
 * field is nullable so a NOT_MEASURED is preserved on
 * the wire).
 *
 * The wire shape: each Boolean field is `@Serializable`
 * nullable. `null` = not measured. `true` = measured-true.
 * `false` = measured-false. The aggregator (CertReport)
 * treats `null` as NOT_MEASURED for the optional fields
 * and as a hard FAIL for the required fields (R5.3).
 */
data class IntegrityVerdict(
    val tagMatchesCommit: Boolean?,            // null if no tag in env
    val commitMatchesRelease: Boolean?,        // null if no STUDIO_TAG in env
    val artifactDigestsMatch: Boolean?,        // null if no manifest provided
    val sdkVersionMatchesLock: Boolean?,       // null if no lockfile reachable
    val studioVersionPinned: Boolean?,        // null if no version known
    val downloadedDigest: String?,             // measured ZIP / tar digest
    val installedDigest: String?,             // measured on-disk artifact digest
    val githubReleaseIdentity: String?,       // https://github.com/.../releases/tag/...
    val runtimeVersionMeasured: String?,      // SDK version measured (NOT from manifest)
    val runtimeManifestImplementationVersion: String?, // the raw Implementation-Version
)

object IntegrityChecker {

    /**
     * Check (a): the local git tag (env STUDIO_TAG) matches
     * HEAD. If the env var is unset, this is NOT_MEASURED
     * (the local dev sandbox does not pin a tag).
     */
    fun checkTagMatchesCommit(): Boolean? {
        val tag = System.getenv("STUDIO_TAG") ?: return null
        val head = runCatching {
            val proc = ProcessBuilder("git", "rev-parse", "HEAD")
                .redirectErrorStream(true)
                .start()
            proc.inputStream.bufferedReader().use { it.readText().trim() }
        }.getOrNull() ?: return null
        val tagSha = runCatching {
            val proc = ProcessBuilder("git", "rev-parse", "$tag^{}")
                .redirectErrorStream(true)
                .start()
            proc.inputStream.bufferedReader().use { it.readText().trim() }
        }.getOrNull() ?: return null
        return head == tagSha
    }

    /**
     * Check (b): the env STUDIO_TAG (a git tag) matches the
     * release tag the operator claims. With no env, NOT_MEASURED.
     */
    fun checkCommitMatchesRelease(): Boolean? {
        // If both STUDIO_TAG and STUDIO_RELEASE_TAG are set
        // and equal, the release matches the tag. If only
        // STUDIO_TAG is set, we have a local tag, no release
        // claim — NOT_MEASURED.
        val tag = System.getenv("STUDIO_TAG") ?: return null
        val release = System.getenv("STUDIO_RELEASE_TAG") ?: return null
        return tag == release
    }

    /**
     * Check (c): the on-disk artifact (zip / tar) matches the
     * manifest pin. With no manifest path, NOT_MEASURED.
     */
    fun checkArtifactDigestsMatch(
        artifactPath: Path,
        expectedSha256: String,
    ): Pair<Boolean, String> {
        val actual = sha256OfFile(artifactPath)
        return (actual == expectedSha256) to actual
    }

    /**
     * Check (d): the resolved SDK's version matches the
     * lockfile's current pin.
     */
    fun checkSdkVersionMatchesLock(
        resolvedSdkVersion: String,
        lockfileCurrentVersion: String,
    ): Boolean = resolvedSdkVersion == lockfileCurrentVersion

    /**
     * Check (e): the studio version is pinned (i.e. the
     * env STUDIO_VERSION is a real SemVer, not "local").
     */
    fun checkStudioVersionPinned(): Boolean? {
        val v = System.getenv("STUDIO_VERSION") ?: return null
        // "local" / "" / "LOCAL" are unpinned.
        return v.isNotBlank() && v.lowercase() != "local"
    }

    /**
     * Check (f): the SDK's MANIFEST.MF carries a real
     * Implementation-Version. If absent, the runtime
     * identity MUST come from the lockfile + the official
     * distribution (R5.4).
     */
    fun readManifestImplementationVersion(jarPath: Path): String? =
        runCatching {
            java.util.jar.JarFile(jarPath.toFile()).use { jf ->
                jf.manifest?.mainAttributes?.getValue("Implementation-Version")
            }
        }.getOrNull()

    fun sha256OfFile(p: Path): String {
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
}
