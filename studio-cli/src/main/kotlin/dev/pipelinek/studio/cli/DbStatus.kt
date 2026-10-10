package dev.pipelinek.studio.cli

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.flywaydb.core.Flyway
import java.nio.file.Path

/**
 * B5.6 (2026-10-10): the `studio db status` +
 * `studio db upgrade` per ROADMAP §"S5:
 * upgrade DB, backup/restore".
 *
 * The studio uses Flyway for migrations
 * (R-22 SHA-pinned SDK is upstream of
 * Studio; the studio's own DB has its own
 * Flyway-managed schema with V1__init
 * applied at boot). B5.6 wraps Flyway's
 * `info()` + `migrate()` into a typed
 * contract the CLI can read + report.
 *
 * The status carries:
 *  - the current schema version (the last
 *    applied migration)
 *  - the list of pending migrations
 *  - the target schema version (the latest
 *    available migration)
 *  - whether the schema is up-to-date
 *
 * The upgrade applies every pending migration
 * in order, idempotently (Flyway handles
 * the no-op when the schema is current).
 */
@Serializable
data class DbStatus(
    val dbPath: String,
    val currentVersion: String,
    val targetVersion: String,
    val pending: List<String>,
    val upToDate: Boolean,
) {
    fun toJson(): String = Json { prettyPrint = true }.encodeToString(serializer(), this)

    fun toHumanReadable(): String = buildString {
        appendLine("studio db: $dbPath")
        appendLine("  current:   $currentVersion")
        appendLine("  target:    $targetVersion")
        appendLine("  pending:   ${pending.size} migration(s)")
        if (pending.isNotEmpty()) {
            for (m in pending) {
                appendLine("    - $m")
            }
        }
        appendLine("  up-to-date: $upToDate")
    }
}

object DbMigrations {
    /**
     * Read the DB's current + target version
     * via Flyway's `info()`. Returns a
     * [DbStatus] with the four key fields.
     *
     * The call is non-mutating: `info()`
     * reports state, it does not change the
     * schema. The CLI uses this for
     * `studio db status`.
     */
    fun status(dbPath: Path): DbStatus {
        val flyway = Flyway.configure()
            .dataSource("jdbc:sqlite:${dbPath.toAbsolutePath()}", "", "")
            .baselineOnMigrate(true)
            .load()
        val info = flyway.info()
        val current = info.current()?.version?.toString() ?: "0"
        // Flyway 10 does not expose a `target()`
        // method; the target version is the
        // highest version among all known
        // migrations, or the current version
        // when no migrations are pending.
        val all = info.all()
        val target = all.lastOrNull()?.version?.toString() ?: current
        val pending = info.pending().map { it.version.toString() }
        return DbStatus(
            dbPath = dbPath.toString(),
            currentVersion = current,
            targetVersion = target,
            pending = pending,
            upToDate = pending.isEmpty(),
        )
    }

    /**
     * Apply every pending migration in order.
     * Idempotent: when the schema is current,
     * Flyway returns immediately without
     * mutating the DB.
     */
    fun upgrade(dbPath: Path): DbStatus {
        val flyway = Flyway.configure()
            .dataSource("jdbc:sqlite:${dbPath.toAbsolutePath()}", "", "")
            .baselineOnMigrate(true)
            .load()
        flyway.migrate()
        return status(dbPath)
    }
}
