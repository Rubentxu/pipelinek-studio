package dev.pipelinek.studio.cli

import dev.pipelinek.studio.infra.storage.StudioBackup
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.file.Path
import java.util.UUID

/**
 * Minimal CLI dispatcher. S0 bootstrap — handful of subcommands wired to the HTTP API.
 * Later (S5 polish) we will replace with clikt if the surface grows; for now a 30-line
 * hand-rolled dispatcher avoids another plugin/version surface area.
 *
 * S5 E5.2 added `backup` and `restore` subcommands which operate on the local
 * `studio.db` file (no HTTP). Default location: `./studio.db` (override with `--db`).
 */
fun main(args: Array<String>) {
    // S5 polish: short-circuit for the bunded version. We deliberately
    // do NOT need a running server for this — it answers the operator's
    // "which build is on this machine?" question offline.
    if (args.contains("--version") || args.contains("-v")) {
        println("studio ${StudioCliVersion.VERSION} (kotlin ${StudioCliVersion.KOTLIN_VERSION}, ktor ${StudioCliVersion.KTOR_VERSION})")
        return
    }

    val host = flagValue(args, "--host") ?: "127.0.0.1"
    val port = flagValue(args, "--port") ?: "7777"
    val baseUrl = "http://$host:$port"
    val asJson = args.contains("--json")
    val positional = args.filter { !it.startsWith("--") }
    val cmd = positional.firstOrNull()

    val client = StudioHttpClient(baseUrl)
    if (cmd == "start") {
        // start blocks until SIGINT and returns the server's exit code; handle outside the
        // String when-expression so the return type stays Unit-returning.
        val startArgs = StartArgs(
            host = flagValue(args, "--host") ?: "127.0.0.1",
            port = (flagValue(args, "--port") ?: "7777").toInt(),
            db = flagValue(args, "--db") ?: "studio.db",
        )
        val code = startServer(startArgs)
        kotlin.system.exitProcess(code)
    }

    if (cmd == "mcp") {
        // MCP server lives in apps/mcp/ as a Node.js v2 stdio adapter. We don't bundle
        // node in the CLI; we either exec it (when a built dist/server.js is locatable)
        // or print the one-liner the operator should run. Honoring D-005 / D-007: the
        // CLI does not pull network or third-party packages on the operator's behalf.
        val mcp = findMcpServer()
        val code = runMcp(mcp, args)
        kotlin.system.exitProcess(code)
    }

    if (cmd == "generate-sdk-ts") {
        // S5 E5.3 — emit a TypeScript client from the OpenAPI document. Without
        // --to, prints to stdout (for piping into a file). With --to, writes
        // the file and reports the byte count on stderr.
        val sdk = GenerateSdk.generate()
        val toIdx = args.indexOf("--to")
        if (toIdx >= 0 && toIdx + 1 < args.size) {
            val path = java.nio.file.Path.of(args[toIdx + 1])
            java.nio.file.Files.createDirectories(path.parent ?: java.nio.file.Path.of("."))
            java.nio.file.Files.writeString(path, sdk)
            System.err.println("wrote ${path} (${sdk.length} bytes)")
        } else {
            print(sdk)
        }
        return
    }

    val output: String = when (cmd) {
        null, "doctor" -> {
            val h = runBlocking { client.health() }
            if (asJson) Json.encodeToString(mapOf("health" to h)) else "health: $h"
        }
        "create-experiment" -> {
            val name = requireOption(args, "--name")
            val res = runBlocking { client.createExperiment(name) }
            if (asJson) Json.encodeToString(mapOf("experiment_id" to res.experiment_id, "name" to res.name))
            else "experiment_id: ${res.experiment_id}\nname: ${res.name}"
        }
        "snapshot" -> {
            val exp = requireOption(args, "--experiment")
            val src = requireOption(args, "--source")
            val creator = flagValue(args, "--creator") ?: "studio-cli"
            val res = runBlocking { client.sealSnapshot(exp, src, creator) }
            if (asJson) Json.encodeToString(mapOf("snapshot_id" to res.snapshot_id, "sha256" to res.sha256))
            else "snapshot_id: ${res.snapshot_id}\nsha256: ${res.sha256}"
        }
        "run" -> {
            val exp = requireOption(args, "--experiment")
            val snap = requireOption(args, "--snapshot")
            val sc = requireOption(args, "--scenario")
            val idemKey = UUID.randomUUID().toString()
            val res = runBlocking { client.startRun(exp, snap, sc, idemKey) }
            if (asJson) Json.encodeToString(mapOf("run_id" to res.run_id, "state" to res.state, "upstream_run_id" to res.upstream_run_id))
            else "run_id: ${res.run_id}\nstate: ${res.state}"
        }
        "inspect" -> {
            val runId = requireOption(args, "--run")
            val status = runBlocking { client.runStatus(runId) }
            if (asJson) Json.encodeToString(mapOf("run_id" to runId, "status" to status))
            else "run $runId -> $status"
        }
        "steps" -> {
            val steps = runBlocking { client.listSteps() }
            (steps.firstOrNull()?.get("raw") ?: "{}") + "\n"
        }
        "version" -> {
            val v = runBlocking { client.version() }
            if (asJson) Json.encodeToString(v) else (v["raw"] ?: "{}")
        }
        "metrics" -> {
            val m = runBlocking { client.metrics() }
            if (asJson) Json.encodeToString(m) else (m["raw"] ?: "{}")
        }
        "backup" -> {
            val db = Path.of(flagValue(args, "--db") ?: "studio.db")
            val dest = Path.of(requireOption(args, "--dest"))
            val out = StudioBackup.backup(db, dest)
            if (asJson) Json.encodeToString(
                BackupResult(
                    db = db.toString(),
                    dest = dest.toString(),
                    bytesCopied = out.bytesCopied,
                    sourcePages = out.sourcePages,
                ),
            ) else "backup: src=${db} dst=${dest} pages=${out.sourcePages} bytes=${out.bytesCopied}"
        }
        "restore" -> {
            val backupPath = Path.of(requireOption(args, "--from"))
            val dest = Path.of(requireOption(args, "--to"))
            StudioBackup.restore(backupPath, dest)
            if (asJson) Json.encodeToString(
                RestoreResult(from = backupPath.toString(), to = dest.toString(), ok = true),
            ) else "restore: ${backupPath} -> ${dest}"
        }
        else -> {
            printUsage()
            return
        }
    }
    println(output)
}

internal fun flagValue(args: Array<String>, key: String): String? {
    val i = args.indexOf(key)
    return if (i >= 0 && i + 1 < args.size) args[i + 1] else null
}

internal fun requireOption(args: Array<String>, key: String): String {
    return flagValue(args, key) ?: error("missing required option: $key")
}

@Serializable
private data class BackupResult(
    val db: String,
    val dest: String,
    val bytesCopied: Long,
    val sourcePages: Int,
)

@Serializable
private data class RestoreResult(
    val from: String,
    val to: String,
    val ok: Boolean,
)

private fun printUsage() {
    println(
        """studio — PipelineK Studio CLI (S0 bootstrap)

Usage:
  studio [--host=127.0.0.1] [--port=7777] [--json] <command>

Commands:
  doctor                                    Hit /health on the backend
  create-experiment --name <name>           Create an experiment
  snapshot --experiment <id> --source <s>   Seal a source snapshot
  run --experiment <id> --snapshot <id> --scenario <id>
  inspect --run <id>                       Read run status
  steps                                     List admitted Step plugin catalog (S2)
  version                                  Read /api/version
  backup --db <path> --dest <file>          Safe online backup of studio.db (S5 E5.2)
  restore --from <backup> --to <db>        Restore a backup after integrity check (S5 E5.2)
  start [--host=127.0.0.1] [--port=7777] [--db=studio.db]
                                            Boot a minimal Studio server (S5 E5.1)
  mcp                                          Run the MCP v2 stdio adapter (S5 E5.1)
  generate-sdk-ts [--to <file>]                Emit a TypeScript client from /openapi.json (S5 E5.3)
  --version                                    Print the bundled CLI version and exit (no server needed)
""",
    )
}

/**
 * Locate the MCP server build. Search order:
 *  1. `$PIPELINEK_STUDIO_MCP` env var (absolute path to server.js)
 *  2. `./apps/mcp/dist/server.js` relative to the working directory
 *  3. `~/.pipelinek-studio/mcp/server.js`
 *
 * Returns null when no candidate is found; callers should print guidance.
 */
internal fun findMcpServer(): java.nio.file.Path? {
    val env = System.getenv("PIPELINEK_STUDIO_MCP")
    if (!env.isNullOrBlank()) {
        val p = java.nio.file.Path.of(env)
        if (java.nio.file.Files.isRegularFile(p)) return p
    }
    val cwd = java.nio.file.Path.of("apps/mcp/dist/server.js").toAbsolutePath()
    if (java.nio.file.Files.isRegularFile(cwd)) return cwd
    val home = System.getProperty("user.home")
    val userPath = java.nio.file.Path.of(home, ".pipelinek-studio", "mcp", "server.js")
    if (java.nio.file.Files.isRegularFile(userPath)) return userPath
    return null
}

private fun runMcp(mcp: java.nio.file.Path?, args: Array<String>): Int {
    if (mcp == null) {
        System.err.println(
            """studio mcp: could not locate the MCP v2 stdio adapter.

Build it once from the repo root:
  cd apps/mcp && npm install && npm run build

Then either run directly (recommended for stdio transports):
  node apps/mcp/dist/server.js
or set PIPELINEK_STUDIO_MCP to the absolute path of server.js and re-run
`studio mcp`.

The CLI does not start the MCP server itself: that would require a Node runtime
on the operator machine, which is out of scope for the Kotlin CLI.""",
        )
        return 2
    }
    val baseUrl = "http://${flagValue(args, "--host") ?: "127.0.0.1"}:${flagValue(args, "--port") ?: "7777"}"
    val pb = ProcessBuilder("node", mcp.toString())
        .redirectError(ProcessBuilder.Redirect.INHERIT)
        .directory(mcp.parent.parent.parent.toFile())
    pb.environment()["PIPELINEK_STUDIO_HTTP"] = baseUrl
    val proc = pb.start()
    Runtime.getRuntime().addShutdownHook(Thread { proc.destroy() })
    return proc.waitFor()
}
