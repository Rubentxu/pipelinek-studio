package dev.pipelinek.studio.infra.storage

import dev.pipelinek.studio.application.ports.RetentionPort
import dev.pipelinek.studio.application.ports.RunKeptReason
import dev.pipelinek.studio.application.ports.SweepOutcome
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager

/**
 * AAT-DUR-006 — Retention / cleanup service.
 *
 * Walks `<controlRootBase>/<runId>/` directories, opens each
 * `<runId>/run.db`, and looks for a `RunFinished` event in the
 * `events` table. If the run is terminal AND no live subprocess
 * is bound to it, the entire per-run directory is deleted.
 *
 * Crash safety: the deletion is best-effort. A failure to delete
 * one run does not abort the sweep; the rest of the runs are
 * still processed and the failure is logged.
 */
class RetentionService : RetentionPort {

    private val log = LoggerFactory.getLogger(javaClass)

    override fun sweep(controlRootBase: Path, liveRunToPid: Map<String, Long>): SweepOutcome {
        if (!Files.isDirectory(controlRootBase)) {
            log.info("retention: controlRoot does not exist: {}", controlRootBase)
            return SweepOutcome(runsScanned = 0, runsDeleted = 0, runsKept = emptyList())
        }
        val perRunDirs = Files.list(controlRootBase).use { stream ->
            stream.filter { Files.isDirectory(it) }
                .filter {
                    // A per-run dir is identified by its name being a
                    // valid UUID. This skips any controlRoot siblings
                    // (e.g. last-run/, leases/) that are not runs.
                    val name = it.fileName?.toString() ?: ""
                    isUuidLike(name)
                }
                .toList()
        }
        var scanned = 0
        var deleted = 0
        val kept = mutableListOf<RunKeptReason>()
        for (runDir in perRunDirs) {
            scanned++
            val runId = runDir.fileName.toString()
            val (isTerminal, error) = isRunTerminal(runDir)
            if (error != null) {
                kept += RunKeptReason.Unreadable(runId, error)
                continue
            }
            if (!isTerminal) {
                kept += RunKeptReason.NotTerminal(runId)
                continue
            }
            // Terminal run: per-run pid binding. A terminal run is
            // eligible for deletion only when the runner does NOT
            // report a live subprocess for THIS run_id. The map is
            // authoritative (sourced from PipelineKRunner.liveProcs);
            // we never fall back to a global /proc scan here, because
            // a global scan would conflate unrelated processes
            // (e.g. the studio JVM itself, which has run.db open)
            // with the actual run subprocesses.
            val boundPid = liveRunToPid[runId]
            if (boundPid != null) {
                kept += RunKeptReason.HasLiveSubprocess(runId, boundPid)
                continue
            }
            // No live subprocess bound. Delete the entire per-run dir.
            try {
                Files.walk(runDir).use { paths ->
                    paths.sorted(Comparator.reverseOrder()).forEach { p ->
                        Files.deleteIfExists(p)
                    }
                }
                deleted++
                log.info("retention: deleted run {} (terminal, no live subprocess)", runId)
            } catch (e: Exception) {
                log.warn("retention: failed to delete run {}: {}", runId, e.message)
                kept += RunKeptReason.Unreadable(runId, "delete failed: ${e.message}")
            }
        }
        return SweepOutcome(runsScanned = scanned, runsDeleted = deleted, runsKept = kept)
    }

    private fun isRunTerminal(runDir: Path): Pair<Boolean, String?> {
        val db = runDir.resolve("run.db")
        if (!Files.isRegularFile(db)) return false to "no run.db"
        return try {
            DriverManager.getConnection("jdbc:sqlite:${db.toAbsolutePath()}").use { c ->
                c.prepareStatement(
                    "SELECT 1 FROM events WHERE kind = 'RunFinished' LIMIT 1"
                ).use { ps ->
                    ps.executeQuery().use { rs ->
                        if (rs.next()) true to null
                        else false to null
                    }
                }
            }
        } catch (e: Exception) {
            false to "read failed: ${e.message}"
        }
    }

    private fun isUuidLike(s: String): Boolean {
        // 8-4-4-4-12 hex pattern
        if (s.length != 36) return false
        for ((i, c) in s.withIndex()) {
            when (i) {
                8, 13, 18, 23 -> if (c != '-') return false
                else -> if (!c.isLetterOrDigit() || c !in '0'..'9' && c !in 'a'..'f' && c !in 'A'..'F') return false
            }
        }
        return true
    }
}
