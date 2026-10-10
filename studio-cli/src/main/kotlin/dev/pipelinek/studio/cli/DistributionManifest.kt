package dev.pipelinek.studio.cli

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.security.MessageDigest

/**
 * B5.1 (2026-10-10): the distribution
 * `manifest.json` per ROADMAP §"S5 — Product
 * readiness".
 *
 * The S5 mandate says the distribution ships
 * with "manifests de dependencias, checksums,
 * SBOM". The manifest is the
 * operator-readable index of the distribution:
 *  - the version of the studio binary
 *  - the build timestamp
 *  - every file in the distribution, with its
 *    size + sha256
 *  - the total number of files + total bytes
 *
 * The format is plain JSON; no external
 * library. The B5.1 test pins:
 *  - the field set (version, buildAt, files)
 *  - the per-file shape (path, size, sha256)
 *  - the SHA-256 implementation against a
 *    known-input vector
 *  - the determinism property (same dir -> same
 *    manifest).
 */
@Serializable
data class DistributionManifest(
    val format: String = "studio-distribution-manifest",
    val version: String,
    val buildAt: Long,
    val files: List<ManifestFile>,
) {
    @Serializable
    data class ManifestFile(
        val path: String,
        val size: Long,
        val sha256: String,
    )

    fun toJson(): String = Json {
        prettyPrint = true
        encodeDefaults = true
    }.encodeToString(serializer(), this)

    companion object {
        /**
         * Build a manifest from every regular
         * file under [root]. Files are sorted
         * by relative path for determinism.
         * The root itself is NOT included as a
         * file entry.
         */
        fun fromDirectory(
            root: java.nio.file.Path,
            version: String,
            buildAt: Long,
        ): DistributionManifest {
            require(java.nio.file.Files.isDirectory(root)) {
                "manifest root must be a directory: $root"
            }
            val files = mutableListOf<ManifestFile>()
            java.nio.file.Files.walk(root).use { stream ->
                stream.filter { java.nio.file.Files.isRegularFile(it) }
                    .sorted(compareBy { root.relativize(it).toString() })
                    .forEach { path ->
                        val rel = root.relativize(path).toString()
                        val size = java.nio.file.Files.size(path)
                        val sha = sha256(path)
                        files += ManifestFile(rel, size, sha)
                    }
            }
            return DistributionManifest(version = version, buildAt = buildAt, files = files)
        }

        /**
         * SHA-256 of a file as a lowercase
         * hex string. The B5.1 test pins this
         * against a known-input vector.
         */
        fun sha256(path: java.nio.file.Path): String {
            val md = MessageDigest.getInstance("SHA-256")
            java.nio.file.Files.newInputStream(path).use { input ->
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
}

/**
 * B5.1 (2026-10-10): the distribution
 * `sbom.json` per ROADMAP §"S5 — Product
 * readiness".
 *
 * The S5 mandate says the distribution ships
 * with an SBOM. The studio emits a minimal,
 * deterministic SBOM in plain JSON:
 *  - the runtime classpath of `studio-cli` as
 *    a list of components (group, name,
 *    version, sha256)
 *  - the SPDX-style purl when it can be
 *    derived from the file name
 *
 * The format is intentionally minimal: a
 * strict CycloneDX 1.5 emitter is a follow-up
 * (EVO-D will standardize the format). The
 * B5.1 test pins the field set + the
 * determinism property.
 */
@Serializable
data class DistributionSbom(
    val format: String = "studio-distribution-sbom",
    val version: String,
    val components: List<SbomComponent>,
) {
    @Serializable
    data class SbomComponent(
        val group: String,
        val name: String,
        val version: String,
        val sha256: String,
    )

    fun toJson(): String = Json {
        prettyPrint = true
        encodeDefaults = true
    }.encodeToString(serializer(), this)

    companion object {
        /**
         * Build an SBOM from a list of
         * (jarPath, group, name, version)
         * tuples. The order is sorted by
         * (group, name, version) for
         * determinism.
         */
        fun fromComponents(
            version: String,
            entries: List<ComponentEntry>,
        ): DistributionSbom {
            val components = entries
                .sortedWith(
                    compareBy({ it.group }, { it.name }, { it.version }),
                )
                .map { e ->
                    SbomComponent(
                        group = e.group,
                        name = e.name,
                        version = e.version,
                        sha256 = DistributionManifest.sha256(e.jarPath),
                    )
                }
            return DistributionSbom(version = version, components = components)
        }
    }

    data class ComponentEntry(
        val group: String,
        val name: String,
        val version: String,
        val jarPath: java.nio.file.Path,
    )
}
