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
    val host = flagValue(args, "--host") ?: "127.0.0.1"
    val port = flagValue(args, "--port") ?: "7777"
    val baseUrl = "http://$host:$port"
    val asJson = args.contains("--json")
    val positional = args.filter { !it.startsWith("--") }
    val cmd = positional.firstOrNull()

    val client = StudioHttpClient(baseUrl)
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
""",
    )
}
