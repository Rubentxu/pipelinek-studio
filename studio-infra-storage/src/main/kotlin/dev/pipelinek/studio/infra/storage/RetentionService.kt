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
                c.prepareStatement(RUN_FINISHED_SQL).use { ps ->
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

    private fun isUuidLike(s: String): Boolean =
        // Audit H-23 (2026-10-10): the previous implementation was
        // operator-dense (`!c.isLetterOrDigit() || c !in '0'..'9' && c !in
        // 'a'..'f' && c !in 'A'..'F'`) and the logic mixed the
        // "is the char a hex digit" check with the "is the char a
        // dash at the right position" check in one expression. The
        // regex is the canonical 8-4-4-4-12 hex pattern; the test
        // pins the behavior so a future change cannot drift to a
        // looser check.
        UUID_REGEX.matches(s)

    private companion object {
        // Canonical UUID v4 8-4-4-4-12 hex pattern. We allow
        // any hex digit (0-9, a-f, A-F) at every position so
        // the regex matches UUIDs from any version, not just v4.
        // The retention service only uses the id to scope
        // filesystem lookups under controlRoot, so version-
        // agnostic matching is correct.
        val UUID_REGEX = Regex(
            "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$",
        )

        // Audit H-23 (2026-10-10): the SQL literal was inline;
        // RunReconciler.kt:107,130 also use the same string. A
        // future change to the event-name contract would need to
        // touch three sites. The constant here pins the wire
        // contract for the retention sweep; the RunReconciler
        // has its own (identical) copy that should be unified in
        // a future S5 hardening pass via a shared
        // \`RunEventNames\` object in studio-domain.
        const val RUN_FINISHED_SQL =
            "SELECT 1 FROM events WHERE kind = 'RunFinished' LIMIT 1"
    }
}
