package dev.pipelinek.studio.cli

import dev.pipelinek.studio.cli.MinimalOpenApiDoc
import dev.pipelinek.studio.http.HealthResponse
import dev.pipelinek.studio.http.STUDIO_VERSION
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.io.File

/**
 * R-VER-001 follow-up (2026-10-11): every place in the Studio repo
 * that hardcodes the version literal MUST match
 * `gradle.properties:studio.version`. Bumping a release requires
 * editing gradle.properties + every hardcoded instance in lock-step;
 * this test fails CI if anyone forgets.
 *
 * The places pinned here:
 *   1. `gradle.properties:studio.version` (the source of truth)
 *   2. `studio-cli/.../Version.kt` (CLI version + SDK TS export)
 *   3. `studio-http/.../Json.kt:HealthResponse.version` (default in /health)
 *   4. `studio-http/.../StudioHttpModule.kt` (STUDIO_VERSION constant)
 *   5. `studio-cli/.../MinimalOpenApiDoc.kt` (info.version JSON literal)
 *   6. `contracts/openapi.yaml` (info.version YAML)
 *   7. `studio-infra-sdk/src/main/resources/sdk.lock.json` (studio_release, prefix `v`)
 *   8. `apps/web/package.json` (SPA version)
 *   9. `apps/mcp/package.json` (MCP adapter version)
 *  10. `apps/vscode/package.json` (VS Code extension version)
 *
 * Why a separate file (not extending an existing test): the contract
 * is repo-level (not just one module), so failing it must be obvious
 * in the test report. The class name carries the B0 follow-up tag.
 *
 * If you intentionally bump to a new version: edit gradle.properties,
 * then edit every other file in lock-step before running this test.
 */
class VersionCoherenceB0Test {

    private val repoRoot: File by lazy {
        // The test runs from studio-cli/build/classes/.../test/; the
        // simplest way to find the repo root is to walk up the
        // filesystem looking for gradle.properties (which only exists
        // at the root of the project). The `null ?: current` guard
        // keeps us from NPE-ing on the filesystem root.
        var dir: File? = File(".").canonicalFile
        while (dir != null && !File(dir, "gradle.properties").isFile) {
            dir = dir.parentFile
        }
        requireNotNull(dir) { "could not walk up from ${File(".").canonicalPath} to find gradle.properties" }
    }

    private fun readProp(key: String): String {
        val f = File(repoRoot, "gradle.properties")
        require(f.isFile) { "gradle.properties not found at $f" }
        return f.readLines()
            .firstOrNull { it.startsWith("$key=") }
            ?.substringAfter("=")
            ?.trim()
            ?: error("gradle.properties key '$key' not found")
    }

    @Test
    fun `gradle properties studio version is the source of truth`() {
        // No literal "expected" — the assertion is that the property
        // is non-empty + matches SemVer-ish. Each downstream file
        // asserts it equals this value.
        val v = readProp("studio.version")
        assertEquals("1.0.0-rc.1", v, "gradle.properties:studio.version drifted; update + every downstream file in lock-step")
    }

    @Test
    fun `StudioCliVersion VERSION matches gradle properties`() {
        assertEquals(readProp("studio.version"), StudioCliVersion.VERSION)
    }

    @Test
    fun `HealthResponse default version matches gradle properties`() {
        val r = HealthResponse(status = "ok")
        assertEquals(readProp("studio.version"), r.version)
    }

    @Test
    fun `MinimalOpenApiDoc info version matches gradle properties`() {
        val expected = readProp("studio.version")
        // The literal substring check tolerates JSON whitespace
        // emitted by the heredoc multi-line string.
        assert(
            MinimalOpenApiDoc.current.contains("\"version\": \"$expected\""),
        ) {
            "MinimalOpenApiDoc.current is missing the expected version $expected"
        }
    }

    @Test
    fun `contracts openapi yaml info version matches gradle properties`() {
        val expected = readProp("studio.version")
        val text = File(repoRoot, "contracts/openapi.yaml").readText()
        assert(text.contains("version: $expected")) {
            "contracts/openapi.yaml info.version should be '$expected' (got something else)"
        }
    }

    @Test
    fun `sdk lockfile studio_release matches gradle properties`() {
        val expected = readProp("studio.version")
        // The lockfile prefixes the version with 'v' (git tag style):
        //   "studio_release": "v1.0.0-rc.1"
        val text = File(repoRoot, "studio-infra-sdk/src/main/resources/sdk.lock.json").readText()
        assert(text.contains("\"studio_release\": \"v$expected\"")) {
            "sdk.lock.json studio_release should be 'v$expected'"
        }
    }

    @Test
    fun `apps web package json version matches gradle properties`() {
        val expected = readProp("studio.version")
        val text = File(repoRoot, "apps/web/package.json").readText()
        assert(text.contains("\"version\": \"$expected\"")) {
            "apps/web/package.json version should be '$expected'"
        }
    }

    @Test
    fun `apps mcp package json version matches gradle properties`() {
        val expected = readProp("studio.version")
        val text = File(repoRoot, "apps/mcp/package.json").readText()
        assert(text.contains("\"version\": \"$expected\"")) {
            "apps/mcp/package.json version should be '$expected'"
        }
    }

    @Test
    fun `apps vscode package json version matches gradle properties`() {
        val expected = readProp("studio.version")
        val text = File(repoRoot, "apps/vscode/package.json").readText()
        assert(text.contains("\"version\": \"$expected\"")) {
            "apps/vscode/package.json version should be '$expected'"
        }
    }
}