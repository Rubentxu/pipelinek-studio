package dev.pipelinek.studio.cli

import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * CI guard for the SDK ↔ contract surface.
 *
 * `GenerateSdk.generate()` reads the curated `OpenApiDoc.current`
 * document and emits the full TypeScript SDK source. The committed
 * `apps/vscode/src/sdk.ts` is a snapshot of that emission, and the
 * VS Code extension imports it directly. If the OpenAPI surface
 * changes but the SDK file is not regenerated and re-committed, the
 * extension silently diverges from the contract.
 *
 * The 170eb52 cycle caught two real bugs (root path → `get` shadowed
 * the JS built-in; hyphen in `static-analyze-capability` produced
 * an invalid function name) that would have been caught by this
 * test if it had existed. The 43063cf cycle found two more routes
 * that the contract promised but the Ktor module did not wire; that
 * drift is now caught by `OpenApiDocPathParityTest` (test 5). This
 * test closes the OTHER half of the same loop: the SDK stays in
 * sync with the contract.
 *
 * The test does NOT shell out to a CLI; it imports `GenerateSdk`
 * directly so the same logic the operator runs by hand is the logic
 * that the test verifies. The committed file is read from the
 * module's working directory; the resolution walks up parents so
 * the test passes whether the JVM cwd is the module dir or the
 * project root.
 */
class GenerateSdkParityTest {

    @Test
    fun `committed sdk ts is the exact emission of GenerateSdk`() {
        val expected = GenerateSdk.generate()
        val committed = Files.readString(resolveCommittedSdk())
        assertEquals(
            expected,
            committed,
            "apps/vscode/src/sdk.ts is stale. Regenerate with `studio-cli generate-sdk-ts > apps/vscode/src/sdk.ts` and commit the result.",
        )
    }

    @Test
    fun `sdk ts has no shadowing function names`() {
        // Defensive check independent of the byte-equality assertion:
        // even if the file is regenerated, it should not contain a
        // top-level function named `get` or `post` (those would
        // shadow the JS built-in semantics and read like no-ops).
        val src = Files.readString(resolveCommittedSdk())
        assertTrue(
            !Regex("""\basync get\(""").containsMatchIn(src),
            "sdk.ts must not contain `async get(` (shadows JS get)",
        )
        assertTrue(
            !Regex("""\basync post\(""").containsMatchIn(src),
            "sdk.ts must not contain `async post(` (shadows JS post)",
        )
    }

    /**
     * Resolve `apps/vscode/src/sdk.ts` by walking up from the test
     * JVM's working directory. Mirrors the same pattern in
     * `OpenApiDocPathParityTest.resolveContractsFile`.
     */
    private fun resolveCommittedSdk(): Path {
        val name = "apps/vscode/src/sdk.ts"
        var dir: Path? = Path.of(System.getProperty("user.dir") ?: ".").toAbsolutePath()
        while (dir != null) {
            val candidate = dir.resolve(name)
            if (Files.isRegularFile(candidate)) return candidate
            dir = dir.parent
        }
        error("$name not found walking up from ${System.getProperty("user.dir")}")
    }
}
