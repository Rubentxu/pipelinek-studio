package dev.pipelinek.studio.cli

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * B5.1 (2026-10-10): the `manifest.json` and
 * `sbom.json` distribution contract tests.
 *
 * The S5 mandate says the distribution ships
 * with "manifests de dependencias, checksums,
 * SBOM". The studio emits both as plain JSON
 * (no external CycloneDX library; the strict
 * CycloneDX 1.5 emitter is an EVO-D follow-up).
 *
 * The tests pin:
 *  1. `manifest.json` field set
 *     (format, version, buildAt, files).
 *  2. The per-file shape (path, size,
 *     sha256).
 *  3. The SHA-256 implementation against a
 *     known-input vector (the empty string).
 *  4. The determinism property: same dir ->
 *     same manifest byte-for-byte.
 *  5. The `sbom.json` field set
 *     (format, version, components).
 *  6. The per-component shape
 *     (group, name, version, sha256).
 *  7. The SBOM is sorted by (group, name,
 *     version) for determinism.
 */
class DistributionExtrasB51Test {

    @Test
    fun `B5_1 manifest field set is the wire contract`() {
        val m = DistributionManifest(
            version = "1.0.0-rc.13",
            buildAt = 1_700_000_000L,
            files = emptyList(),
        )
        val text = m.toJson()
        val obj = Json.parseToJsonElement(text) as JsonObject
        assertEquals(
            setOf("format", "version", "buildAt", "files"),
            obj.keys,
            "manifest keys must be exactly (format, version, buildAt, files), got: ${obj.keys}",
        )
        // The format field pins the wire version.
        assertEquals("studio-distribution-manifest", m.format)
    }

    @Test
    fun `B5_1 manifest per-file shape is (path, size, sha256)`() {
        val file = DistributionManifest.ManifestFile(
            path = "lib/foo.jar", size = 123L, sha256 = "abc",
        )
        val obj = Json.parseToJsonElement(
            Json.encodeToString(DistributionManifest.ManifestFile.serializer(), file),
        ) as JsonObject
        assertEquals(
            setOf("path", "size", "sha256"),
            obj.keys,
            "file entry keys must be exactly (path, size, sha256), got: ${obj.keys}",
        )
    }

    @Test
    fun `B5_1 SHA-256 of empty string is the known vector`() {
        // RFC 6234 / FIPS 180-4: SHA-256 of
        // the empty byte sequence is
        // e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855
        val empty = Files.createTempFile("sha256-empty-", ".bin")
        Files.write(empty, ByteArray(0))
        val hash = DistributionManifest.sha256(empty)
        Files.deleteIfExists(empty)
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            hash,
            "SHA-256 of empty input must be the known vector",
        )
    }

    @Test
    fun `B5_1 SHA-256 of 'abc' is the known vector`() {
        // RFC 6234 / FIPS 180-4: SHA-256 of
        // the ASCII string "abc" is
        // ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad
        val f = Files.createTempFile("sha256-abc-", ".bin")
        Files.write(f, "abc".toByteArray(Charsets.UTF_8))
        val hash = DistributionManifest.sha256(f)
        Files.deleteIfExists(f)
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            hash,
            "SHA-256 of 'abc' must be the known vector",
        )
    }

    @Test
    fun `B5_1 manifest fromDirectory lists every regular file with size and sha256`(
        @TempDir tmp: Path,
    ) {
        Files.writeString(tmp.resolve("a.txt"), "alpha")
        Files.writeString(tmp.resolve("b.txt"), "beta")
        Files.createDirectories(tmp.resolve("lib"))
        Files.writeString(tmp.resolve("lib/c.jar"), "jar-bytes")
        val m = DistributionManifest.fromDirectory(
            root = tmp,
            version = "0.1.0",
            buildAt = 0L,
        )
        val paths = m.files.map { it.path }.toSet()
        assertTrue("a.txt" in paths, "manifest must include a.txt, got: $paths")
        assertTrue("b.txt" in paths, "manifest must include b.txt, got: $paths")
        assertTrue("lib/c.jar" in paths, "manifest must include lib/c.jar, got: $paths")
        // Every entry has a size + a 64-char hex sha256.
        for (f in m.files) {
            assertEquals(64, f.sha256.length, "sha256 must be 64 hex chars, got: ${f.sha256}")
            assertTrue(f.size >= 0, "size must be non-negative, got: ${f.size}")
        }
    }

    @Test
    fun `B5_1 manifest is sorted by relative path (determinism)`(@TempDir tmp: Path) {
        // The walk order is filesystem-
        // dependent; the manifest MUST sort
        // entries so two builds produce the
        // same manifest byte-for-byte.
        Files.writeString(tmp.resolve("zeta.txt"), "z")
        Files.writeString(tmp.resolve("alpha.txt"), "a")
        Files.writeString(tmp.resolve("middle.txt"), "m")
        val m = DistributionManifest.fromDirectory(tmp, "v1", 0L)
        val paths = m.files.map { it.path }
        assertEquals(listOf("alpha.txt", "middle.txt", "zeta.txt"), paths)
    }

    @Test
    fun `B5_1 manifest is deterministic (same dir, two calls, same JSON)`(@TempDir tmp: Path) {
        Files.writeString(tmp.resolve("a.txt"), "a")
        Files.writeString(tmp.resolve("b.txt"), "b")
        val a = DistributionManifest.fromDirectory(tmp, "v1", 0L).toJson()
        val b = DistributionManifest.fromDirectory(tmp, "v1", 0L).toJson()
        assertEquals(a, b, "two calls on the same dir must produce the same manifest JSON")
    }

    @Test
    fun `B5_1 sbom field set is the wire contract`() {
        val s = DistributionSbom(version = "0.1.0", components = emptyList())
        val obj = Json.parseToJsonElement(s.toJson()) as JsonObject
        assertEquals(
            setOf("format", "version", "components"),
            obj.keys,
            "sbom keys must be exactly (format, version, components), got: ${obj.keys}",
        )
        assertEquals("studio-distribution-sbom", s.format)
    }

    @Test
    fun `B5_1 sbom per-component shape is (group, name, version, sha256)`() {
        val c = DistributionSbom.SbomComponent(
            group = "io.ktor", name = "ktor-server-core", version = "3.0.1", sha256 = "0".repeat(64),
        )
        val obj = Json.parseToJsonElement(Json.encodeToString(DistributionSbom.SbomComponent.serializer(), c)) as JsonObject
        assertEquals(
            setOf("group", "name", "version", "sha256"),
            obj.keys,
            "component keys must be exactly (group, name, version, sha256), got: ${obj.keys}",
        )
    }

    @Test
    fun `B5_1 sbom fromComponents is sorted by (group, name, version)`(@TempDir tmp: Path) {
        val jarA = tmp.resolve("a.jar").also { Files.write(it, byteArrayOf(1)) }
        val jarB = tmp.resolve("b.jar").also { Files.write(it, byteArrayOf(2)) }
        val jarC = tmp.resolve("c.jar").also { Files.write(it, byteArrayOf(3)) }
        // Insert in a non-sorted order.
        val sbom = DistributionSbom.fromComponents(
            version = "v1",
            entries = listOf(
                DistributionSbom.ComponentEntry("org.zeta", "zeta", "1.0.0", jarC),
                DistributionSbom.ComponentEntry("io.ktor", "alpha", "3.0.1", jarA),
                DistributionSbom.ComponentEntry("io.ktor", "beta", "2.0.0", jarB),
            ),
        )
        val names = sbom.components.map { "${it.group}:${it.name}:${it.version}" }
        // The expected order is sorted by
        // group, then name, then version.
        assertEquals(
            listOf("io.ktor:alpha:3.0.1", "io.ktor:beta:2.0.0", "org.zeta:zeta:1.0.0"),
            names,
        )
        // Every component has a 64-char hex sha256.
        for (c in sbom.components) {
            assertEquals(64, c.sha256.length, "component sha256 must be 64 hex chars, got: ${c.sha256}")
        }
    }

    // Local alias removed; the per-file test
    // uses DistributionManifest.ManifestFile
    // directly.
}
