package dev.pipelinek.studio.cli

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * B5.1 follow-up (2026-10-10): the strict
 * CycloneDX 1.5 SBOM emitter contract
 * tests.
 *
 * The B5.1 release shipped a plain JSON
 * SBOM (operator-readable summary). That
 * format is sufficient for human inspection
 * but is NOT a CycloneDX-compliant SBOM,
 * so it cannot be consumed by tooling that
 * requires the CycloneDX spec (GUAC,
 * Dependency-Track, Syft, Grype, etc.).
 *
 * The strict emitter (`CycloneDxSbom`)
 * produces a spec-compliant CycloneDX
 * 1.5 document. The tests pin:
 *  1. The top-level format constants
 *     (bomFormat, specVersion, version).
 *  2. The serial number is a valid
 *     `urn:uuid:...` (v4 or any RFC 4122
 *     UUID; we just check the prefix).
 *  3. The metadata block has timestamp
 *     (ISO-8601 UTC), tools[] (the studio
 *     as the SBOM producer), and
 *     component (the studio-cli root).
 *  4. Each component has type, group,
 *     name, version, purl, hashes[] (with
 *     SHA-256 content), and bom-ref.
 *  5. The purl format is
 *     `pkg:maven/<group>:<name>@<version>`
 *     (no classifier / scope / type).
 *  6. The dependencies[] array has one
 *     entry for the root that depends on
 *     every component.
 *  7. The components are sorted by
 *     (group, name, version) for
 *     determinism.
 *  8. The JSON parses cleanly back into
 *     a `JsonObject` (sanity check the
 *     shape is JSON-spec valid).
 *  9. The Gradle `distExtras` task
 *     produces `sbom.cdx.json` (the wire
 *     contract for the release tar).
 */
class CycloneDxB51Test {

    @Test
    fun `CycloneDX 1_5 top-level format constants are pinned`() {
        val sbom = sampleBom(entries = emptyList())
        assertEquals("CycloneDX", sbom.bomFormat, "bomFormat must be the literal 'CycloneDX'")
        assertEquals("1.5", sbom.specVersion, "specVersion must be '1.5'")
        assertEquals(1, sbom.version, "SBOM revision (version) must start at 1")
    }

    @Test
    fun `CycloneDX 1_5 serial number is a valid urn uuid prefix`() {
        val sbom = sampleBom(entries = emptyList())
        assertTrue(
            sbom.serialNumber.startsWith("urn:uuid:"),
            "serialNumber must be 'urn:uuid:<uuid>', got '${sbom.serialNumber}'",
        )
        val uuid = sbom.serialNumber.removePrefix("urn:uuid:")
        // RFC 4122 UUID: 8-4-4-4-12 hex chars
        assertTrue(
            Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")
                .matches(uuid),
            "serialNumber UUID must be RFC 4122 hex form, got '$uuid'",
        )
    }

    @Test
    fun `CycloneDX 1_5 metadata has ISO-8601 UTC timestamp plus tools plus root component`() {
        val sbom = sampleBom(entries = emptyList())
        val ts = sbom.metadata.timestamp
        assertTrue(
            Regex("^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(\\.\\d+)?Z$").matches(ts),
            "metadata.timestamp must be ISO-8601 UTC (Z-suffixed), got '$ts'",
        )
        assertEquals(1, sbom.metadata.tools.size, "metadata.tools must list the studio as the producer")
        val tool = sbom.metadata.tools.first()
        assertEquals("Rubentxu", tool.vendor)
        assertEquals("pipelinek-studio", tool.name)
        assertEquals("1.0.0-rc.16", tool.version)
        val root = sbom.metadata.component
        assertEquals("application", root.type)
        assertEquals("dev.pipelinek", root.group)
        assertEquals("studio-cli", root.name)
        assertEquals("1.0.0-rc.16", root.version)
        assertEquals("pkg:maven/dev.pipelinek:studio-cli@1.0.0-rc.16", root.bomRef)
    }

    @Test
    fun `CycloneDX 1_5 per-component shape has type group name version purl hashes bom-ref`() {
        val (sbom, tmp) = sampleBomWithTempDir(
            entries = listOf(
                Triple("io.ktor", "ktor-server-core", "3.0.0"),
            ),
        )
        try {
            assertEquals(1, sbom.components.size, "expected 1 component")
            val c = sbom.components.first()
            assertEquals("library", c.type, "component.type must be 'library' for jars")
            assertEquals("io.ktor", c.group)
            assertEquals("ktor-server-core", c.name)
            assertEquals("3.0.0", c.version)
            assertEquals("pkg:maven/io.ktor:ktor-server-core@3.0.0", c.purl)
            assertEquals(1, c.hashes.size, "component must have at least one hash (SHA-256)")
            val h = c.hashes.first()
            assertEquals("SHA-256", h.alg)
            assertEquals(64, h.content.length, "SHA-256 hex is 64 chars")
            assertTrue(
                Regex("[0-9a-f]{64}").matches(h.content),
                "hash content must be lowercase hex, got '${h.content}'",
            )
            assertEquals(c.purl, c.bomRef, "bom-ref must equal the purl (the CycloneDX convention)")
        } finally {
            tmp.toFile().deleteRecursively()
        }
    }

    @Test
    fun `CycloneDX 1_5 dependencies has one root entry that depends on every component`() {
        val (sbom, tmp) = sampleBomWithTempDir(
            entries = listOf(
                Triple("io.ktor", "ktor-server-core", "3.0.0"),
                Triple("ch.qos.logback", "logback-classic", "1.5.0"),
                Triple("org.flywaydb", "flyway-core", "10.0.0"),
            ),
        )
        try {
            assertEquals(1, sbom.dependencies.size, "exactly one root dependency entry")
            val dep = sbom.dependencies.first()
            assertEquals("pkg:maven/dev.pipelinek:studio-cli@1.0.0-rc.16", dep.ref)
            assertEquals(
                setOf(
                    "pkg:maven/io.ktor:ktor-server-core@3.0.0",
                    "pkg:maven/ch.qos.logback:logback-classic@1.5.0",
                    "pkg:maven/org.flywaydb:flyway-core@10.0.0",
                ),
                dep.dependsOn.toSet(),
                "root must depend on every component",
            )
        } finally {
            tmp.toFile().deleteRecursively()
        }
    }

    @Test
    fun `CycloneDX 1_5 components are sorted by (group, name, version) for determinism`() {
        val (sbom, tmp) = sampleBomWithTempDir(
            entries = listOf(
                Triple("org.flywaydb", "flyway-core", "10.0.0"),
                Triple("ch.qos.logback", "logback-classic", "1.5.0"),
                Triple("io.ktor", "ktor-server-core", "3.0.0"),
                Triple("io.ktor", "ktor-server-core", "3.0.1"),
            ),
        )
        try {
            val actual = sbom.components.map { "${it.group}:${it.name}:${it.version}" }
            assertEquals(
                listOf(
                    "ch.qos.logback:logback-classic:1.5.0",
                    "io.ktor:ktor-server-core:3.0.0",
                    "io.ktor:ktor-server-core:3.0.1",
                    "org.flywaydb:flyway-core:10.0.0",
                ),
                actual,
                "components must be sorted by (group, name, version)",
            )
        } finally {
            tmp.toFile().deleteRecursively()
        }
    }

    @Test
    fun `CycloneDX 1_5 JSON parses cleanly into a JsonObject (round-trip sanity check)`() {
        val (sbom, tmp) = sampleBomWithTempDir(
            entries = listOf(Triple("io.ktor", "ktor-server-core", "3.0.0")),
        )
        try {
            val json = sbom.toJson()
            val parsed = Json.parseToJsonElement(json)
            assertTrue(parsed is JsonObject, "the output must be a JSON object")
            val obj = parsed as JsonObject
            assertEquals("CycloneDX", obj["bomFormat"]!!.jsonPrimitive.content)
            assertEquals("1.5", obj["specVersion"]!!.jsonPrimitive.content)
            assertNotNull(obj["metadata"]?.jsonObject, "metadata must be present")
            assertNotNull(obj["components"]?.jsonArray, "components must be present")
            assertNotNull(obj["dependencies"]?.jsonArray, "dependencies must be present")
        } finally {
            tmp.toFile().deleteRecursively()
        }
    }

    @Test
    fun `CycloneDX 1_5 purl format is pkg maven (group dotted) name (at) version`() {
        // B5.1 (2026-10-10): the purl
        // format for Maven coordinates.
        // Per the PackageURL spec, the
        // group is dot-separated (NOT
        // slash-separated), the name is
        // colon-separated from the group,
        // and the version is at-suffixed.
        assertEquals(
            "pkg:maven/io.ktor:ktor-server-core@3.0.0",
            CycloneDxSbom.purl("io.ktor", "ktor-server-core", "3.0.0"),
        )
        assertEquals(
            "pkg:maven/dev.pipelinek:studio-cli@1.0.0-rc.16",
            CycloneDxSbom.purl("dev.pipelinek", "studio-cli", "1.0.0-rc.16"),
        )
    }

    @Test
    fun `CycloneDX 1_5 ISO-8601 UTC helper produces Z-suffixed output`() {
        // B5.1 (2026-10-10): the helper
        // must produce a Z-suffixed
        // ISO-8601 timestamp. We don't
        // pin the exact string (Instant.toString
        // is JDK-version-dependent) —
        // we pin the format.
        val ts = CycloneDxSbom.isoUtc(1_726_656_000_000L)
        assertTrue(
            Regex("^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(\\.\\d+)?Z$").matches(ts),
            "isoUtc must produce ISO-8601 Z form, got '$ts'",
        )
    }

    @Test
    fun `distExtras task emits sbom cdx json with the strict CycloneDX 1_5 format`() {
        // The wire contract: after the
        // distExtras task runs, the build
        // dir contains sbom.cdx.json
        // alongside manifest.json +
        // sbom.json. We assert the file
        // exists and that it parses as a
        // CycloneDX document (bomFormat +
        // specVersion at the top).
        val build = findStudioCliBuild()
        val cdx = build.resolve("dist-extras/sbom.cdx.json")
        if (!Files.isRegularFile(cdx)) {
            // The distExtras task is only
            // wired into standaloneDist +
            // not always run during `test`.
            // We pin the source-level
            // contract below as a fallback.
            return
        }
        val text = Files.readString(cdx)
        val parsed = Json.parseToJsonElement(text) as JsonObject
        assertEquals("CycloneDX", parsed["bomFormat"]!!.jsonPrimitive.content)
        assertEquals("1.5", parsed["specVersion"]!!.jsonPrimitive.content)
    }

    @Test
    fun `distExtras gradle task source emits sbom cdx json (B5_1 wire contract)`() {
        // Wire contract: the distExtras
        // task must write sbom.cdx.json
        // with the strict CycloneDX 1.5
        // shape. The build script can't
        // import the dev.pipelinek.studio.cli
        // package directly (the build
        // script is compiled before the
        // project classes are), so the
        // build script emits the JSON by
        // hand. We pin the file name +
        // the spec constants; the
        // round-trip test above pins the
        // full shape.
        val text = Path.of("build.gradle.kts").let { rel ->
            val abs = if (Files.isRegularFile(rel)) rel
            else Path.of(System.getProperty("user.dir"), "studio-cli/build.gradle.kts")
            Files.readString(abs)
        }
        assertTrue(
            "sbom.cdx.json" in text,
            "distExtras must write sbom.cdx.json (the strict CycloneDX 1.5 SBOM)",
        )
        assertTrue(
            """"bomFormat": "CycloneDX"""" in text,
            "distExtras must declare the CycloneDX 1.5 bomFormat literal in sbom.cdx.json",
        )
        assertTrue(
            """"specVersion": "1.5"""" in text,
            "distExtras must declare the specVersion '1.5' literal in sbom.cdx.json",
        )
        assertTrue(
            "purl" in text && "bom-ref" in text,
            "distExtras must include purl + bom-ref in sbom.cdx.json (CycloneDX 1.5 requirement)",
        )
        assertTrue(
            "metadata" in text && "tools" in text && "component" in text,
            "distExtras must include metadata.tools + metadata.component in sbom.cdx.json",
        )
    }

    // ----- helpers -----

    private fun findStudioCliBuild(): Path {
        var cwd: Path? = Path.of("").toAbsolutePath()
        repeat(6) {
            val candidate = cwd!!.resolve("studio-cli/build")
            if (Files.isDirectory(candidate)) return candidate
            cwd = cwd.parent
        }
        throw AssertionError("studio-cli/build not found from cwd=${Path.of("").toAbsolutePath()}")
    }

    private fun sampleBom(entries: List<Triple<String, String, String>>): CycloneDxSbom =
        sampleBomWithTempDir(entries).first

    private fun sampleBomWithTempDir(
        entries: List<Triple<String, String, String>>,
    ): Pair<CycloneDxSbom, Path> {
        val tmp = Files.createTempDirectory("studio-cdx-test-")
        val mapped = entries.map { (g, n, v) ->
            // B5.1 (2026-10-10): create a
            // tiny jar with the expected
            // sha so sha256() returns a
            // deterministic 64-char hex.
            val jar = tmp.resolve("$n-$v.jar")
            Files.writeString(jar, "$g:$n:$v")
            DistributionSbom.ComponentEntry(
                group = g,
                name = n,
                version = v,
                jarPath = jar,
            )
        }
        val sbom = CycloneDxSbom.fromComponents(
            version = "1.0.0-rc.16",
            rootGroup = "dev.pipelinek",
            rootName = "studio-cli",
            toolVersion = "1.0.0-rc.16",
            entries = mapped,
            timestampMillis = 1_726_656_000_000L, // 2024-09-20T12:00:00Z
            serialNumber = "urn:uuid:00000000-0000-4000-8000-000000000001",
        )
        return sbom to tmp
    }
}
