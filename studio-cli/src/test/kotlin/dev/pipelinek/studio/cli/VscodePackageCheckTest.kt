package dev.pipelinek.studio.cli

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.util.Comparator
import java.util.concurrent.TimeUnit
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * T12 / S1+S4 — `scripts/vscode-package-check.sh` must reject a real duplicate
 * command declaration and accept the shipped manifest.
 *
 * ## Why this file exists
 *
 * The checker collected every `"command":` occurrence in `package.json`, so the
 * ids that `contributes.menus` re-references under `view/title` and
 * `view/item/context` were reported as duplicate *declarations*. On the real
 * `apps/vscode/package.json` that produced a permanent red gate naming four
 * ids that are not duplicates at all, which carried no signal: a gate that
 * always fails is as useless as one that cannot fail.
 *
 * The pair below is the discriminating regression:
 *  - a manifest that declares the same `contributes.commands[].command` twice
 *    must exit non-zero and name the id (the invariant is still enforced);
 *  - the shipped `apps/vscode/package.json` — which legitimately re-references
 *    four of its commands from `contributes.menus` — must exit zero. This is
 *    the assertion that goes red on the pre-fix checker.
 *
 * Both cases drive the shipped script through its public CLI (path argument,
 * exit code, stdout), so the rule can be re-implemented without touching the
 * test. Scratch lives under `<repo>/build/operational-package-check/` and is
 * deleted after each case.
 */
class VscodePackageCheckTest {

    private companion object {
        const val SCRIPT = "scripts/vscode-package-check.sh"
    }

    private lateinit var scratch: Path

    @BeforeEach
    fun setUp() {
        scratch = Files.createDirectories(repoRoot().resolve("build/operational-package-check/${System.nanoTime()}"))
    }

    @AfterEach
    fun tearDown() {
        deleteRecursively(scratch)
    }

    /** Result of one public-script invocation. */
    private data class Run(val exit: Int, val output: String)

    @Test
    fun `rejects a manifest that declares the same contributes command id twice`() {
        val manifest = scratch.resolve("duplicate-command.json")
        Files.writeString(
            manifest,
            """
            {
              "name": "duplicate-command-fixture",
              "version": "1.0.0",
              "publisher": "fixture",
              "description": "fixture manifest with a genuinely duplicated contributed command id",
              "main": "dist/extension.js",
              "engines": { "vscode": "^1.85.0" },
              "categories": ["Other"],
              "activationEvents": ["onCommand:fixture.alpha"],
              "contributes": {
                "commands": [
                  { "command": "fixture.alpha", "title": "Fixture: Alpha" },
                  { "command": "fixture.alpha", "title": "Fixture: Alpha (declared twice)" }
                ],
                "menus": {
                  "view/title": [ { "command": "fixture.alpha", "when": "view == fixture.tree" } ]
                }
              }
            }
            """.trimIndent(),
        )

        val run = runChecker(manifest)

        assertEquals(1, run.exit, "a duplicated contributes.commands id must fail the gate:\n${run.output}")
        assertContains(run.output, "duplicate command ids in contributes.commands", message = run.output)
        assertContains(run.output, "fixture.alpha", message = run.output)
    }

    @Test
    fun `accepts the shipped manifest whose menus re-reference declared commands`() {
        val manifest = repoRoot().resolve("apps/vscode/package.json")
        val run = runChecker(manifest)

        assertEquals(0, run.exit, "the shipped VS Code manifest must pass the packaging gate:\n${run.output}")
        assertContains(run.output, "all contributes.commands ids are unique", message = run.output)
        assertContains(run.output, "contributes.menus", message = run.output)
        assertFalse(
            run.output.contains("duplicate command ids"),
            "menu re-references must not be reported as duplicate declarations:\n${run.output}",
        )
    }

    private fun runChecker(manifest: Path): Run {
        val stamp = System.nanoTime()
        val outFile = scratch.resolve("stdout-$stamp.log")
        val started = System.nanoTime()
        val proc = ProcessBuilder("/bin/bash", repoRoot().resolve(SCRIPT).toString(), manifest.toString())
            .directory(repoRoot().toFile())
            .redirectErrorStream(true)
            .redirectOutput(outFile.toFile())
            .start()
        proc.outputStream.close()
        val finished = proc.waitFor(120, TimeUnit.SECONDS)
        val elapsed = (System.nanoTime() - started) / 1_000_000
        if (!finished) {
            proc.destroyForcibly()
            proc.waitFor(10, TimeUnit.SECONDS)
        }
        val output = outFile.toFile().readText()
        check(finished) { "$SCRIPT exceeded 120s and was killed (elapsed=${elapsed}ms)\n$output" }
        return Run(proc.exitValue(), output)
    }

    private fun repoRoot(): Path {
        var dir: Path? = Path.of(".").toAbsolutePath().normalize()
        while (dir != null && !Files.isRegularFile(dir.resolve("gradle.properties"))) dir = dir.parent
        return requireNotNull(dir) { "could not locate the repo root (gradle.properties) from ${Path.of(".").toAbsolutePath()}" }
    }

    private fun deleteRecursively(dir: Path) {
        if (!Files.exists(dir)) return
        Files.walk(dir).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) } }
    }
}
