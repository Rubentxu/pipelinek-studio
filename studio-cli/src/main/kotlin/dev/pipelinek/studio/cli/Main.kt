package dev.pipelinek.studio.cli

import dev.pipelinek.studio.infra.storage.StudioBackup
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.file.Path

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
    // Audit F-P1-CLI-3 (2026-10-10): the option parser throws
    // CliOptionError on missing/empty/flag-as-value. Convert to
    // a clean stderr + exit 2 so the operator gets a usable
    // diagnostic instead of a Kotlin stack trace.
    try {
        mainInner(args)
    } catch (e: CliOptionError) {
        System.err.println("error: ${e.message}")
        kotlin.system.exitProcess(2)
    }
}

private fun mainInner(args: Array<String>) {

    val host = flagValue(args, "--host") ?: "127.0.0.1"
    // Audit C-1 (2026-10-10): the studio now defaults to HTTPS on
    // port 7778 with a self-signed cert. The flag --insecure-http
    // opts back to plain HTTP on the legacy port 7777.
    val insecureHttp = args.contains("--insecure-http")
    val defaultPort = if (insecureHttp) "7777" else "7778"
    val port = flagValue(args, "--port") ?: defaultPort
    val scheme = if (insecureHttp) "http" else "https"
    val baseUrl = "$scheme://$host:$port"
    val asJson = args.contains("--json")
    val positional = args.filter { !it.startsWith("--") }
    val cmd = positional.firstOrNull()

    val client = StudioHttpClient(baseUrl)
    if (cmd == "start") {
        // start blocks until SIGINT and returns the server's exit code; handle outside the
        // String when-expression so the return type stays Unit-returning.
        val startArgs = StartArgs(
            host = flagValue(args, "--host") ?: "127.0.0.1",
            port = (flagValue(args, "--port") ?: defaultPort).toInt(),
            db = flagValue(args, "--db") ?: "studio.db",
            insecureHttp = insecureHttp,
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
        when {
            toIdx < 0 -> {
                // No --to: emit to stdout so the operator can pipe
                // it (`studio generate-sdk-ts > sdk.ts`).
                print(sdk)
            }
            toIdx + 1 >= args.size -> {
                // --to present but no following path. The previous
                // implementation silently fell through to print-to-
                // stdout, which made `--to` typos look like
                // "nothing happened" to the operator. Now we
                // surface a clear error and exit non-zero.
                System.err.println("error: --to requires a file path argument")
                kotlin.system.exitProcess(2)
            }
            else -> {
                val path = java.nio.file.Path.of(args[toIdx + 1])
                java.nio.file.Files.createDirectories(path.parent ?: java.nio.file.Path.of("."))
                java.nio.file.Files.writeString(path, sdk)
                System.err.println("wrote ${path} (${sdk.length} bytes)")
            }
        }
        return
    }

    val output: String = when (cmd) {
        null, "doctor" -> {
            // Audit (2026-10-10): the previous doctor was a
            // "hit /health" stub. The new Doctor runs offline
            // diagnostics (lockfile, db path, JVM, node) and
            // exits non-zero when any check fails. The old
            // `client.health()` round-trip is still available
            // via the `version` subcommand for connectivity
            // checks against a running server.
            val report = Doctor.run(
                Doctor.DoctorEnv(
                    env = System.getenv(),
                    dbPath = Path.of(flagValue(args, "--db") ?: "studio.db"),
                ),
            )
            if (report.exitCode != 0) {
                // Defer the exit until after the JSON/human
                // output is on stdout so the operator can read
                // the failure mode.
                println(if (asJson) Json.encodeToString(report) else report.toHumanReadable())
                kotlin.system.exitProcess(report.exitCode)
            }
            if (asJson) Json.encodeToString(report) else report.toHumanReadable()
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
            // Audit F-P1-CLI-2 (2026-10-10): the previous
            // implementation generated a fresh UUID per call. Two
            // invocations with the same --experiment / --snapshot
            // / --scenario would create two distinct runs, which
            // contradicts the idempotency contract the studio
            // already advertises (AAT-API-008). The new key is
            // stable per (experiment, snapshot, scenario) triple;
            // the operator can still force a new run by passing
            // --idem-key=... explicitly. Changing any of the
            // three inputs yields a new key, which is the correct
            // semantic: a different input is a new intent.
            val explicitKey = flagValue(args, "--idem-key")
            val idemKey = explicitKey ?: "cli-${exp}-${snap}-${sc}"
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
        "list-runs" -> {
            val state = flagValue(args, "--state")
            val limit = flagValue(args, "--limit")?.toIntOrNull()
            val offset = flagValue(args, "--offset")?.toIntOrNull()
            val r = runBlocking { client.listRuns(state, limit, offset) }
            if (asJson) Json.encodeToString(r) else (r["raw"] ?: "{}")
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

/**
 * Audit F-P1-CLI-3 (2026-10-10): the previous `requireOption`
 * returned the next argument verbatim, which silently accepted
 *
 *   studio create-experiment --name --json
 *
 * and created an experiment literally named "--json" (the
 * string, not the flag). It also accepted `--name ""` and
 * forwarded the empty string to the backend. Both are
 * P1s: the first creates nonsense data, the second hides
 * a user error behind a network round trip.
 *
 * The new helper fails fast when:
 *   - the option is missing
 *   - the option is the last arg (no value follows)
 *   - the value starts with "--" and is not the literal
 *     "--" (i.e. the user almost certainly forgot the
 *     value and the parser hit the next flag)
 *   - the value is empty after trim
 *
 * The error message names the option and the failure mode
 * so the operator can fix the call without reading source.
 */
class CliOptionError(message: String) : IllegalArgumentException(message)

internal fun requireOptionValue(args: Array<String>, key: String): String {
    val i = args.indexOf(key)
    if (i < 0) throw CliOptionError("missing required option: $key")
    if (i + 1 >= args.size) throw CliOptionError("option $key requires a value")
    val raw = args[i + 1]
    if (raw.isBlank()) throw CliOptionError("option $key cannot be empty")
    if (raw.startsWith("--") && raw != "--") {
        throw CliOptionError(
            "option $key got '$raw' as value; the next flag was likely " +
                "captured by mistake. Pass a non-flag value or quote it.",
        )
    }
    return raw
}

/**
 * Back-compat alias kept for the existing call sites. New code
 * should call `requireOptionValue` directly; the alias exists
 * so the diff for the F-P1-CLI-3 fix stays narrow.
 */
internal fun requireOption(args: Array<String>, key: String): String = requireOptionValue(args, key)

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
        """studio — PipelineK Studio CLI (S0 bootstrap; S5 E5.x polish)

Usage:
  studio [--host=127.0.0.1] [--port=7778] [--insecure-http] [--json] <command>

Global flags:
  --insecure-http    Talk plain HTTP to the studio instead of HTTPS
                     (port defaults to 7777 in this mode; required when
                     the studio was started with --insecure-http)

Commands:
  doctor                                    Offline diagnostics (lockfile, db, JVM, node, HTTPS)
  create-experiment --name <name>           Create an experiment
  snapshot --experiment <id> --source <s>   Seal a source snapshot
  run --experiment <id> --snapshot <id> --scenario <id>
  inspect --run <id>                       Read run status
  steps                                     List admitted Step plugin catalog (S2)
  version                                  Read /api/version
  backup --db <path> --dest <file>          Safe online backup of studio.db (S5 E5.2)
  restore --from <backup> --to <db>        Restore a backup after integrity check (S5 E5.2)
  start [--host=127.0.0.1] [--port=7778] [--db=studio.db] [--insecure-http]
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
