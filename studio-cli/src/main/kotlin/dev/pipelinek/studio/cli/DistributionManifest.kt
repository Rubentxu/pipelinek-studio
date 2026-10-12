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

/**
 * B5.1 follow-up (2026-10-10): a strict
 * CycloneDX 1.5 SBOM emitter.
 *
 * The plain `DistributionSbom` (above) is
 * the operator-readable summary — it
 * carries (group, name, version, sha256)
 * per jar, in alphabetical order, and
 * nothing else. That format is sufficient
 * for human inspection but is NOT a
 * CycloneDX-compliant SBOM, so it cannot
 * be consumed by tooling that requires
 * the CycloneDX spec (GUAC,
 * Dependency-Track, Syft, Grype, etc.).
 *
 * The CycloneDX 1.5 spec (see
 * https://cyclonedx.org/specification/overview/)
 * requires:
 *  - `bomFormat: "CycloneDX"`
 *  - `specVersion: "1.5"`
 *  - `serialNumber: "urn:uuid:..."` (we
 *    generate a v4 UUID per build; the
 *    UUID is deterministic only when the
 *    build is reproducible, otherwise
 *    each build gets a new one — the
 *    field is required by the spec)
 *  - `version: 1` (the SBOM revision, not
 *    the component version)
 *  - `metadata.timestamp` (ISO-8601 UTC)
 *  - `metadata.tools[]` (the tool that
 *    produced the SBOM)
 *  - `metadata.component` (the root
 *    component the SBOM is about)
 *  - `components[]` with per-component
 *    `type`, `group`, `name`, `version`,
 *    `purl`, `hashes[]`, and `bom-ref`
 *  - `dependencies[]` (the dependency
 *    graph; in our case the root
 *    component depends on every entry
 *    in `components`)
 *
 * The plain sbom.json stays for back-
 * compat; the strict output lands at
 * sbom.cdx.json. The B5.1 test pins the
 * field set + the format constants.
 */
@Serializable
data class CycloneDxSbom(
    val bomFormat: String = "CycloneDX",
    val specVersion: String = "1.5",
    val serialNumber: String,
    val version: Int = 1,
    val metadata: Metadata,
    val components: List<Component>,
    val dependencies: List<Dependency>,
) {
    @Serializable
    data class Metadata(
        val timestamp: String,
        val tools: List<Tool>,
        val component: ToolComponent,
    )

    @Serializable
    data class Tool(
        val vendor: String,
        val name: String,
        val version: String,
    )

    @Serializable
    data class ToolComponent(
        val type: String = "application",
        val group: String,
        val name: String,
        val version: String,
        val bomRef: String,
    )

    @Serializable
    data class Hash(
        val alg: String = "SHA-256",
        val content: String,
    )

    @Serializable
    data class Component(
        val type: String = "library",
        val group: String,
        val name: String,
        val version: String,
        val purl: String,
        val hashes: List<Hash>,
        val bomRef: String,
    )

    @Serializable
    data class Dependency(
        val ref: String,
        val dependsOn: List<String>,
    )

    fun toJson(): String = Json {
        prettyPrint = true
        encodeDefaults = true
    }.encodeToString(serializer(), this)

    companion object {
        /**
         * B5.1 (2026-10-10): build a strict
         * CycloneDX 1.5 SBOM.
         *
         * @param version the studio version
         *   (e.g. "1.0.0-rc.16")
         * @param rootGroup the root
         *   component's group ("dev.pipelinek")
         * @param rootName the root
         *   component's name ("studio-cli")
         * @param toolVersion the version of
         *   the studio tool that produced
         *   the SBOM (same as `version` for
         *   the studio itself)
         * @param entries the jar entries
         *   from the plain
         *   `DistributionSbom.fromComponents`
         *   call
         * @param timestampMillis the
         *   `System.currentTimeMillis()`
         *   value to embed in metadata;
         *   exposed as a parameter for
         *   testability
         * @param serialNumber the
         *   `urn:uuid:...` for the SBOM;
         *   exposed as a parameter for
         *   testability (the production
         *   caller generates a v4 UUID per
         *   build)
         */
        fun fromComponents(
            version: String,
            rootGroup: String,
            rootName: String,
            toolVersion: String,
            entries: List<DistributionSbom.ComponentEntry>,
            timestampMillis: Long,
            serialNumber: String,
        ): CycloneDxSbom {
            val rootBomRef = purl(rootGroup, rootName, version)
            val components = entries
                .sortedWith(compareBy({ it.group }, { it.name }, { it.version }))
                .map { e ->
                    val sha = DistributionManifest.sha256(e.jarPath)
                    val ref = purl(e.group, e.name, e.version)
                    Component(
                        group = e.group,
                        name = e.name,
                        version = e.version,
                        purl = ref,
                        hashes = listOf(Hash(content = sha)),
                        bomRef = ref,
                    )
                }
            val dependencies = listOf(
                Dependency(
                    ref = rootBomRef,
                    dependsOn = components.map { it.bomRef },
                ),
            )
            val metadata = Metadata(
                timestamp = isoUtc(timestampMillis),
                tools = listOf(
                    Tool(
                        vendor = "Rubentxu",
                        name = "pipelinek-studio",
                        version = toolVersion,
                    ),
                ),
                component = ToolComponent(
                    group = rootGroup,
                    name = rootName,
                    version = version,
                    bomRef = rootBomRef,
                ),
            )
            return CycloneDxSbom(
                serialNumber = serialNumber,
                metadata = metadata,
                components = components,
                dependencies = dependencies,
            )
        }

        /**
         * B5.1 (2026-10-10): the
         * PackageURL for a Maven coordinate.
         * Spec: pkg:maven/<group>:<name>@<version>
         * — the group is dot-separated, NOT
         * slash-separated. We do not encode
         * scope, classifier, or type
         * (sufficient for our purposes;
         * CycloneDX purl qualifier is a
         * follow-up).
         */
        internal fun purl(group: String, name: String, version: String): String =
            "pkg:maven/$group:$name@$version"

        /**
         * B5.1 (2026-10-10): ISO-8601 UTC
         * timestamp (e.g.
         * "2026-10-10T18:00:00Z"). The
         * CycloneDX spec requires
         * metadata.timestamp to be ISO-8601;
         * we use the Z (UTC) form because
         * the build host's timezone is
         * irrelevant to the SBOM.
         */
        internal fun isoUtc(epochMillis: Long): String {
            val instant = java.time.Instant.ofEpochMilli(epochMillis)
            val formatter = java.time.format.DateTimeFormatter.ISO_INSTANT
            return formatter.format(instant)
        }
    }
}
