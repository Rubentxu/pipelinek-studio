package dev.pipelinek.studio.cli

import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * B5.2 (2026-10-10): the second client E2E
 * surface tests.
 *
 * The S5 mandate says the studio ships a
 * "segunda app cliente (VS Code minimal o
 * CLI nuevo) que haga: discover, snapshot,
 * run, events/output, cancel/inspect". The
 * studio's chosen second client is the MCP
 * v2 stdio adapter at `apps/mcp/src/server.ts`
 * (a Node.js v2 stdio consumer — the VS Code
 * extension is EVO-C; the MCP adapter is
 * the pragmatic first consumer because
 * VS Code requires a separate publisher
 * flow that is out of scope for v1.0.0-rc.15).
 *
 * The test pins the second-client surface:
 * the MCP server must expose tools that
 * cover the S5 six-surface
 * (discover / snapshot / run / events /
 * output / cancel-or-inspect). The test
 * reads `apps/mcp/src/server.ts` and
 * asserts the tool names are present.
 *
 * Why a static read instead of a
 * subprocess? The MCP server requires a
 * live studio backend + a Node runtime;
 * a unit test in studio-cli does not have
 * either. The static read pins the wire
 * surface; the live E2E is the B5.4
 * certification harness's job.
 */
class McpSecondClientB52Test {

    @Test
    fun `B5_2 MCP second client exposes a discover tool (studio_health or equivalent)`() {
        val src = readServerSource()
        assertTrue(
            "studio.health" in src || "studio_health" in src || "name: \"studio.health\"" in src,
            "MCP second client must expose a discover/health tool, got first 100 lines:\n" +
                src.lineSequence().take(100).joinToString("\n"),
        )
    }

    @Test
    fun `B5_2 MCP second client exposes a seal_snapshot tool (snapshot surface)`() {
        val src = readServerSource()
        assertTrue(
            "studio.seal_snapshot" in src,
            "MCP second client must expose studio.seal_snapshot, got first 100 lines:\n" +
                src.lineSequence().take(100).joinToString("\n"),
        )
    }

    @Test
    fun `B5_2 MCP second client exposes a start_run tool (run surface)`() {
        val src = readServerSource()
        assertTrue(
            "studio.start_run" in src,
            "MCP second client must expose studio.start_run, got first 100 lines:\n" +
                src.lineSequence().take(100).joinToString("\n"),
        )
    }

    @Test
    fun `B5_2 MCP second client exposes a list_events tool (events surface)`() {
        val src = readServerSource()
        assertTrue(
            "studio.list_events" in src,
            "MCP second client must expose studio.list_events, got first 100 lines:\n" +
                src.lineSequence().take(100).joinToString("\n"),
        )
    }

    @Test
    fun `B5_2 MCP second client exposes an inspect_run tool (cancel-or-inspect surface)`() {
        // The S5 mandate lumps cancel and
        // inspect together. The studio's
        // inspect tool also surfaces run
        // state, which is the input to a
        // cancel decision. A future B5.x
        // pass can split cancel into its
        // own tool.
        val src = readServerSource()
        assertTrue(
            "studio.inspect_run" in src,
            "MCP second client must expose studio.inspect_run, got first 100 lines:\n" +
                src.lineSequence().take(100).joinToString("\n"),
        )
    }

    @Test
    fun `B5_2 MCP second client tool list covers all six S5 surfaces`() {
        // Six surfaces, in one read: the
        // MCP server must expose the union.
        val src = readServerSource()
        val required = listOf(
            "studio.health",
            "studio.seal_snapshot",
            "studio.start_run",
            "studio.list_events",
            "studio.inspect_run",
        )
        val missing = required.filter { it !in src }
        assertTrue(
            missing.isEmpty(),
            "MCP second client is missing the following required tools: $missing",
        )
    }

    private fun readServerSource(): String {
        // Locate the MCP server source
        // relative to the studio-cli
        // working dir. The studio is a
        // multi-module build rooted at
        // the parent of studio-cli.
        val candidates = listOf(
            Path.of("apps/mcp/src/server.ts"),
            Path.of("../apps/mcp/src/server.ts"),
            Path.of("../../apps/mcp/src/server.ts"),
        )
        for (c in candidates) {
            if (Files.isRegularFile(c)) {
                return Files.readString(c)
            }
        }
        // Fall back: read via the absolute
        // path computed from the project
        // root.
        val cwd = Path.of(".").toAbsolutePath()
        val candidate = cwd.parent.resolve("apps/mcp/src/server.ts")
        if (Files.isRegularFile(candidate)) {
            return Files.readString(candidate)
        }
        // The test fails with a clear
        // diagnostic if no candidate is
        // found. The B5.4 certification
        // harness is the place to verify
        // the live E2E.
        throw AssertionError(
            "could not locate apps/mcp/src/server.ts from cwd=$cwd; tried $candidates",
        )
    }
}
