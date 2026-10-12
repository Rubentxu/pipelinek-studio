package dev.pipelinek.studio.cli

import dev.pipelinek.studio.http.OpenApiDoc
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * B5.2 (2026-10-10): the versioned OpenAPI
 * document + SDK API version pin tests.
 *
 * The S5 mandate says the studio ships a
 * "Contrato versionado OpenAPI y SDK TS
 * generado". The pre-B5.2 OpenAPI doc carried
 * only the `openapi: 3.1.0` field; the studio
 * API version (a separate concept from the
 * OpenAPI spec version) was implicit. B5.2
 * adds `x-studio-api-version` as a top-level
 * field so consumers can pin a build to a
 * specific studio API.
 *
 * The tests pin:
 *  1. Both OpenAPI docs (the full
 *     [dev.pipelinek.studio.http.OpenApiDoc]
 *     and the minimal
 *     [OpenApiDoc]) carry
 *     `x-studio-api-version: 1.0.0`.
 *  2. The TS SDK's header comment includes
 *     the API version (e.g. "// x-studio-api-version: 1.0.0").
 *  3. The TS SDK exports `API_VERSION` as a
 *     const string (the build-time API version
 *     pin).
 *  4. The version is a SemVer-style string
 *     (X.Y.Z) so a future change to a
 *     major version is detectable.
 */
class OpenApiVersionB52Test {

    @Test
    fun `B5_2 OpenAPI doc carries x-studio-api-version = 1_0_0`() {
        // R0.2.1 (2026-10-11): the CLI smoke server and the
        // production studio-app both serve the SAME OpenAPI
        // document (dev.pipelinek.studio.http.OpenApiDoc).
        // The previous 'MinimalOpenApiDoc' subset is gone —
        // there is only one wire surface now.
        val doc = Json.parseToJsonElement(OpenApiDoc.current) as JsonObject
        val v = (doc["x-studio-api-version"] as? JsonPrimitive)
        assertNotNull(v, "OpenAPI doc must carry x-studio-api-version, got: ${doc.keys}")
        assertEquals("1.0.0", v.content)
    }

    @Test
    fun `B5_2 API version follows SemVer shape X dot Y dot Z`() {
        // The version is parsed as a string;
        // the test pins the X.Y.Z shape so a
        // future change to a major version is
        // detectable from a string compare.
        val doc = Json.parseToJsonElement(OpenApiDoc.current) as JsonObject
        val v = (doc["x-studio-api-version"] as JsonPrimitive).content
        val regex = Regex("""^\d+\.\d+\.\d+$""")
        assertTrue(
            regex.matches(v),
            "x-studio-api-version must be SemVer (X.Y.Z), got: $v",
        )
    }

    @Test
    fun `B5_2 TS SDK header includes the API version`() {
        val sdk = GenerateSdk.generate()
        assertTrue(
            sdk.contains("x-studio-api-version: 1.0.0"),
            "TS SDK header must include the API version, got first 5 lines:\n${sdk.lineSequence().take(5).joinToString("\n")}",
        )
    }

    @Test
    fun `B5_2 TS SDK exports API_VERSION as a const string`() {
        val sdk = GenerateSdk.generate()
        assertTrue(
            sdk.contains("""export const API_VERSION = "1.0.0""""),
            "TS SDK must export API_VERSION as a const string, got first 30 lines:\n${sdk.lineSequence().take(30).joinToString("\n")}",
        )
    }

    @Test
    fun `B5_2 TS SDK still exports VERSION (the studio build version) for back-compat`() {
        // The pre-B5.2 SDK exported VERSION
        // (the studio build version from
        // info.version). B5.2 keeps that
        // export for back-compat with
        // existing consumers.
        val sdk = GenerateSdk.generate()
        assertTrue(
            sdk.contains("""export const VERSION = "1.0.0-rc.13""""),
            "TS SDK must keep exporting VERSION for back-compat",
        )
    }

    @Test
    fun `B5_2 OpenAPI doc x-studio-api-version is parseable JSON`() {
        // R0.2.1 (2026-10-11): previously this test asserted
        // that the full and the minimal docs had matching
        // x-studio-api-version. The CLI smoke server now
        // serves the same document as the production
        // studio-app; the 'agreement' invariant is trivially
        // satisfied because there is only one document.
        val doc = Json.parseToJsonElement(OpenApiDoc.current) as JsonObject
        val v = doc["x-studio-api-version"]
        assertNotNull(v, "OpenAPI doc must carry x-studio-api-version")
        assertTrue(v is JsonPrimitive, "x-studio-api-version must be a JSON primitive")
    }
}
