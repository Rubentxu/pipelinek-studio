package dev.pipelinek.studio.http

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * R0.6 sync (2026-10-17): the curated surface in `contracts/openapi.yaml`
 * and the runtime-served surface in `OpenApiDoc.kt` MUST share the same
 * path set. Routes from sibling route files (`PluginAdmissionRoutes`,
 * `ScheduleRunRoutes`, `RecoveryRoutes`, `LiveEventRoutes`,
 * `TreeEventRoutes`) are intentionally out of scope and ship with their
 * own contracts in follow-up commits.
 *
 * The yaml may declare an additional path with `deprecated: true` to mark
 * a never-wired legacy route (currently
 * `/runs/{runId}/operations/{operationId}/output`). The parity check
 * ignores deprecated routes when comparing against OpenApiDoc.kt.
 *
 * This test reads `contracts/openapi.yaml` from the working directory
 * (Gradle runs tests with the project root as cwd). If the build ever
 * moves the contract to a different path, update this test too.
 *
 * Path extraction uses a regex over the raw text instead of a YAML parser
 * to avoid pulling in a parser dependency for what is structurally a flat
 * list of `  /path:` lines (YAML's path map keys at indent 2).
 */
class OpenApiDocPathParityTest {

    private val json = Json { ignoreUnknownKeys = true }

    private val deprecatedPaths = setOf(
        "/runs/{runId}/operations/{operationId}/output",
    )

    @Test
    fun `contracts openapi yaml and OpenApiDoc share the same path set`() {
        val yamlText = resolveContractsFile().readText()
        val yamlPaths = extractYamlTopLevelPaths(yamlText) - deprecatedPaths
        val kotlinPaths = extractOpenApiDocPaths(OpenApiDoc.current) - deprecatedPaths

        val onlyInYaml = yamlPaths - kotlinPaths
        val onlyInKotlin = kotlinPaths - yamlPaths

        assertEquals(
            emptySet(), onlyInYaml,
            "openapi.yaml declares paths that OpenApiDoc.kt does not",
        )
        assertEquals(
            emptySet(), onlyInKotlin,
            "OpenApiDoc.kt declares paths that openapi.yaml does not",
        )
        assertTrue(yamlPaths.isNotEmpty(), "openapi.yaml path set must be non-empty")
    }

    @Test
    fun `every active path declares at least one HTTP method`() {
        val kotlinDoc = json.parseToJsonElement(OpenApiDoc.current).jsonObject
        val paths = kotlinDoc["paths"] as JsonObject
        for ((path, rawOp) in paths) {
            if (path in deprecatedPaths) continue
            val op = rawOp as JsonObject
            val verbs = op.keys.filter { it in HTTP_VERBS }
            assertTrue(
                verbs.isNotEmpty(),
                "path $path declares no HTTP method; OpenAPI 3.1 requires at least one of " +
                    HTTP_VERBS.joinToString(),
            )
        }
    }

    @Test
    fun `deprecated routes in openapi yaml carry the deprecated flag`() {
        val yamlText = resolveContractsFile().readText()
        val paths = extractYamlTopLevelPaths(yamlText)
        for (deprecated in deprecatedPaths) {
            assertTrue(
                deprecated in paths,
                "expected $deprecated in openapi.yaml so it can be marked deprecated",
            )
            // Regex check that the path's verb block carries `deprecated: true`.
            // The legacy block is a single GET, so a window check is enough.
            val block = Regex(
                """(?ms)^  ${Regex.escape(deprecated)}:\s*\n    get:.*?(?=\n  /|\z)""",
            ).find(yamlText)?.value
                ?: error("could not locate the $deprecated block in openapi.yaml")
            assertTrue(
                block.contains("deprecated: true"),
                "path $deprecated block must declare deprecated: true, got:\n$block",
            )
        }
    }

    @Test
    fun `deprecated routes in OpenApiDoc carry the deprecated flag`() {
        val kotlinDoc = json.parseToJsonElement(OpenApiDoc.current).jsonObject
        val paths = kotlinDoc["paths"] as JsonObject
        for (deprecated in deprecatedPaths) {
            assertTrue(
                deprecated in paths,
                "expected $deprecated in OpenApiDoc.kt so SDK generators see the deprecation",
            )
            val op = paths.getValue(deprecated)
            val verbs = op as JsonObject
            val hasDeprecated = verbs.values.any { verb ->
                verb is JsonObject && verb["deprecated"] == kotlinx.serialization.json.JsonPrimitive(true)
            }
            assertTrue(
                hasDeprecated,
                "OpenApiDoc path $deprecated must carry deprecated: true on at least one verb",
            )
        }
    }

    /**
     * Catch the contract ↔ implementation drift that D5 / D6 surfaced: the
     * OpenAPI document promises a path and the SDK exposes a wrapper for it,
     * but the Ktor routing in `StudioHttpModule` (and its sibling files) may
     * not actually serve the path. The R0.6.1 cycle found
     * `/snapshots/{snapshotId}` declared in the contract and the SDK but
     * missing as a route — a 404 in production. This test enumerates the
     * live route paths via the brace-aware source scanner over the parent
     * module + the three sibling files actually installed in production
     * (`TreeEventRoutes` → `GET /events`, `RecoveryRoutes` →
     * `POST /api/admin/recovery`, `ScheduleRunRoutes` →
     * `POST /api/runs/schedule`) and asserts that every active
     * (non-deprecated) contract path is wired.
     *
     * Sibling files whose `install*Routes` function is NOT called from
     * `StudioHttpModule` (currently `PluginAdmissionRoutes` and
     * `LiveEventRoutes`) are intentionally out of scope: their routes are
     * dead code in production, and including them in the wired set would
     * mask that fact. The D5 carry-forward documents the 3 missing paths
     * this scan was extended to catch.
     */
    @Test
    fun `every active StudioHttpModule path is wired`() {
        val kotlinDoc = json.parseToJsonElement(OpenApiDoc.current).jsonObject
        val paths = (kotlinDoc["paths"] as JsonObject) - deprecatedPaths
        val wiredPaths = discoverWiredPaths()
        val unwired = paths.keys - wiredPaths
        assertTrue(
            unwired.isEmpty(),
            "OpenAPI declares paths that no installed route file wires: $unwired",
        )
    }

    /**
     * Resolve `contracts/openapi.yaml` by walking up from the test JVM's
     * working directory. Gradle sets cwd to the module directory for
     * `:studio-http:test`; this climbs until it finds the contract. If
     * the contract ever moves out of the repo root, this resolver will
     * fail loudly rather than silently misreport.
     */
    private fun resolveContractsFile(): java.io.File {
        val name = "contracts/openapi.yaml"
        var dir: java.io.File? = java.io.File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            val candidate = java.io.File(dir, name)
            if (candidate.isFile) return candidate
            dir = dir.parentFile
        }
        error("$name not found walking up from ${System.getProperty("user.dir")}")
    }

    /**
     * Extract the top-level path keys from an OpenAPI 3.1 YAML document.
     * Each path is a YAML mapping key at indent 2 (2 spaces) under
     * `paths:`. The regex tolerates trailing whitespace and a `:` suffix
     * on the key line.
     */
    private fun extractYamlTopLevelPaths(yamlText: String): Set<String> {
        // Find the `paths:` block and capture lines starting with exactly
        // two spaces, then a slash, until the next top-level sibling.
        val blockMatch = Regex(
            """(?ms)^paths:\s*\n(.*?)(?=^\S|\z)""",
        ).find(yamlText) ?: error("openapi.yaml has no top-level `paths:` block")
        val block = blockMatch.groupValues[1]
        return Regex("""^  (/[\w{}/:_\-.]*):""", RegexOption.MULTILINE)
            .findAll(block)
            .map { it.groupValues[1] }
            .toSet()
    }

    /**
     * Extract path keys from the runtime-served `OpenApiDoc.current` JSON
     * via kotlinx.serialization (already used by SnapshotRedactionTest and
     * TreeEventRoutesEvoC0Test). Top-level keys under `paths`.
     */
    private fun extractOpenApiDocPaths(text: String): Set<String> {
        val root = json.parseToJsonElement(text).jsonObject
        val paths = root["paths"] as? JsonObject
            ?: error("OpenApiDoc.current has no `paths` object")
        return paths.keys
    }

    private companion object {
        private val HTTP_VERBS = setOf("get", "post", "put", "delete", "patch", "head", "options")
    }

    /**
     * Enumerate the path strings wired by `StudioHttpModule` by scanning its
     * source for the Ktor DSL verbs and composing nested `route(...)`
     * contexts. Sibling files are excluded by the test comment above.
     *
     * The scanner is brace-aware: every `route("/prefix") { ... }` opens a
     * context that is prepended to any inner `get/post/...("/inner")`
     * until the matching `}` closes it. This way a nested declaration like
     *   route("/experiments/{experimentId}") { get("/snapshots") { ... } }
     * surfaces as `/experiments/{experimentId}/snapshots`, matching the
     * contract's literal path. A coarse regex would have produced
     * `/snapshots` only and falsely reported the parent route as missing.
     */
    /**
     * Union the wired route paths from the parent module + the three
     * sibling files actually installed in production
     * (`TreeEventRoutes`, `RecoveryRoutes`, `ScheduleRunRoutes`).
     * Each scan is brace-aware: nested `route(...)` contexts compose
     * their inner `get`/`post` paths. A coarse regex would have
     * reported `/snapshots` for a nested declaration
     * `route("/experiments/{experimentId}") { get("/snapshots") { ... } }`
     * and falsely flagged the parent route as missing.
     */
    private fun discoverWiredPaths(): Set<String> {
        val files = listOf(
            "src/main/kotlin/dev/pipelinek/studio/http/StudioHttpModule.kt",
            "src/main/kotlin/dev/pipelinek/studio/http/TreeEventRoutes.kt",
            "src/main/kotlin/dev/pipelinek/studio/http/RecoveryRoutes.kt",
            "src/main/kotlin/dev/pipelinek/studio/http/ScheduleRunRoutes.kt",
        )
        val out = mutableSetOf<String>()
        for (relPath in files) {
            val f = java.io.File(relPath)
            if (!f.isFile) continue // Defensive: a sibling may be added later
            out += scanKtorDsl(f.readText())
        }
        return out
    }

    private fun scanKtorDsl(src: String): Set<String> {
        val dslCall = Regex("""\b(route|get|post|put|delete|patch|sse)\(\s*"([^"]+)"""")
        val out = mutableSetOf<String>()
        val context = ArrayDeque<String>()
        var pendingRoute: String? = null
        for (raw in src.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("//") || line.startsWith("*")) continue
            // Strip line comments without disturbing a path arg that contains "//".
            val codeOnly = line.substringBefore("//")
            for (m in dslCall.findAll(codeOnly)) {
                val verb = m.groupValues[1]
                val arg = m.groupValues[2]
                if (verb == "route") {
                    pendingRoute = arg
                } else {
                    val composed = (context + arg).joinToString("")
                    out.add(composed)
                }
            }
            // Apply the pending route as a context opener only when the
            // opening brace for THIS route statement appears on this line.
            // That avoids mis-classifying a function-body line that happens
            // to start with `route(`. Nested routes on the same line use
            // repeated matches; we apply them in source order.
            val openBraces = codeOnly.count { it == '{' }
            val closeBraces = codeOnly.count { it == '}' }
            if (pendingRoute != null && openBraces > 0) {
                repeat(openBraces) { context.addLast(pendingRoute) }
                pendingRoute = null
            }
            // Close as many contexts as we have `}` on the line; the test
            // source does not interleave a `}` before its opener, so
            // popping in reverse is safe.
            repeat(closeBraces) {
                if (context.isNotEmpty()) context.removeLast()
            }
        }
        return out
    }
}