@file:Suppress("TooGenericExceptionCaught")

package dev.pipelinek.studio.infra.storage

import dev.pipelinek.studio.application.ports.RetentionPort
import dev.pipelinek.studio.application.ports.RunKeptReason
import dev.pipelinek.studio.application.ports.SweepOutcome
import dev.pipelinek.studio.domain.IdFormat
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant

/**
 * AAT-DUR-006 — Retention / cleanup service.
 *
 * Walks `<controlRootBase>/<runId>/` directories, opens each
 * `<runId>/run.db`, and looks for a `RunFinished` event in the
 * `events` table. If the run is terminal AND no live subprocess
 * is bound to it AND it is older than the configured TTL, the
 * entire per-run directory is deleted.
 *
 * B2.7 (2026-10-10) added the TTL cap and the dry-run mode
 * that makes cleanup "recoverable" per the B2 mandate. The
 * service now refuses to delete a terminal-but-recent run;
 * it keeps the run with [RunKeptReason.TooYoung] so the
 * operator can review the TTL and re-sweep if needed.
 *
 * Crash safety: the deletion is best-effort. A failure to
 * delete one run does not abort the sweep; the rest of the
 * runs are still processed and the failure is logged.
 */
class RetentionService : RetentionPort {

    private val log = LoggerFactory.getLogger(javaClass)

    override fun sweep(
        controlRootBase: Path,
        liveRunToPid: Map<String, Long>,
        ttl: Duration,
        dryRun: Boolean,
        now: Instant,
        runUpdatedAtProvider: (String) -> Instant?,
    ): SweepOutcome {
        if (!Files.isDirectory(controlRootBase)) {
            log.info("retention: controlRoot does not exist: {}", controlRootBase)
            return SweepOutcome(
                runsScanned = 0,
                runsDeleted = 0,
                runsKept = emptyList(),
                dryRun = dryRun,
            )
        }
        val perRunDirs = Files.list(controlRootBase).use { stream ->
            stream.filter { Files.isDirectory(it) }
                .filter {
                    // A per-run dir is identified by its name
                    // being a valid UUID. This skips any
                    // controlRoot siblings (e.g. last-run/,
                    // leases/) that are not runs.
                    val name = it.fileName?.toString() ?: ""
                    isUuidLike(name)
                }
                .toList()
        }
        var scanned = 0
        var deleted = 0
        var wouldBeDeleted = 0
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
            // B2.7 (2026-10-10): TTL check. A terminal run
            // must be at least `ttl` old before it is
            // eligible. The age source is the
            // runUpdatedAtProvider (which reads the
            // run_bindings.updated_at column when wired);
            // when that returns null we fall back to the
            // per-run directory's mtime, which is the
            // last-write time the studio touched the dir.
            val updatedAt = runUpdatedAtProvider(runId)
                ?: readDirMtime(runDir)
            if (updatedAt != null) {
                val ageSeconds = java.time.Duration.between(updatedAt, now).seconds
                if (ttl > Duration.ZERO && ageSeconds < ttl.seconds) {
                    kept += RunKeptReason.TooYoung(runId, ageSeconds)
                    continue
                }
            }
            // No live subprocess bound AND old enough.
            // B2.7 (2026-10-10): dryRun previews the plan
            // without deleting, so the operator can review
            // the would-be deletions and re-sweep with
            // dryRun=false to actually delete. This is the
            // B2 "recoverable" contract — the operator can
            // abort the sweep between dry-run and the real
            // sweep, and the dry-run is itself a recovery
            // signal (the plan is the audit trail).
            if (dryRun) {
                wouldBeDeleted++
                log.info("retention: dry-run would delete run {}", runId)
                continue
            }
            try {
                Files.walk(runDir).use { paths ->
                    paths.sorted(Comparator.reverseOrder()).forEach { p ->
                        Files.deleteIfExists(p)
                    }
                }
                deleted++
                log.info("retention: deleted run {} (terminal, no live subprocess, older than TTL)", runId)
            } catch (e: Exception) {
                log.warn("retention: failed to delete run {}: {}", runId, e.message)
                kept += RunKeptReason.Unreadable(runId, "delete failed: ${e.message}")
            }
        }
        return SweepOutcome(
            runsScanned = scanned,
            runsDeleted = deleted,
            runsWouldBeDeleted = wouldBeDeleted,
            dryRun = dryRun,
            runsKept = kept,
        )
    }

    private fun isRunTerminal(runDir: Path): Pair<Boolean, String?> {
        val db = runDir.resolve("run.db")
        if (!Files.isRegularFile(db)) return false to "no run.db"
        // R0.3.2 (2026-10-11): open run.db via SqliteConnection so
        // PRAGMA foreign_keys=ON is asserted before the query. Studio
        // is a reader of run.db; ADR-003 says the SDK subprocess
        // owns the writer handle.
        return try {
            SqliteConnection.openReadOnly(db).use { c ->
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

    private fun readDirMtime(runDir: Path): Instant? = try {
        Files.getLastModifiedTime(runDir).toInstant()
    } catch (_: Exception) { null }

    private fun isUuidLike(s: String): Boolean =
        // Audit H-25 (2026-10-10): the canonical UUID regex now
        // lives in `dev.pipelinek.studio.domain.IdFormat` so
        // RetentionService and RunReconciler cannot drift apart.
        IdFormat.isUuidLike(s)

    private companion object {
        // Audit H-23 (2026-10-10): the SQL literal was inline;
        // RunReconciler.kt also uses the same string. The constant
        // here pins the wire contract for the retention sweep.
        const val RUN_FINISHED_SQL =
            "SELECT 1 FROM events WHERE kind = 'RunFinished' LIMIT 1"
    }
}
