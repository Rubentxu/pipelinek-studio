package dev.pipelinek.studio.infra.storage

import org.flywaydb.core.Flyway
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager

/**
 * Studio metadata SQLite owner.
 *
 * Per docs/01-architecture/DATA_MODEL_AND_PERSISTENCE.md §"Dos bases de datos SQLite":
 * - Studio owns `studio.db`.
 * - run.db is owned by PipelineK under controlRoot.
 * - This class NEVER opens run.db in writer mode.
 */
class StudioDatabase(
    val dbPath: Path,
) : AutoCloseable {
    private val log = LoggerFactory.getLogger(javaClass)

    init {
        runMigrations()
    }

    private fun runMigrations() {
        val url = "jdbc:sqlite:${dbPath.toAbsolutePath()}"
        log.info("Migrating Studio SQLite at {}", dbPath.toAbsolutePath())
        val flyway = Flyway.configure()
            .dataSource(url, "", "")
            .locations("classpath:db/migration")
            .baselineOnMigrate(true)
            // S5 polish: our V1__init_studio_schema.sql has no SQL placeholders,
            // and Flyway 10 introduced a "environment" + "resolvers" mechanism
            // that errors out trying to resolve the legacy default `user`
            // placeholder. Disable placeholder replacement to opt out cleanly
            // — we only carry schema SQL, not templated migrations.
            .placeholderReplacement(false)
            .load()
        val result = flyway.migrate()
        log.info("Studio migrations applied: {} migrations, target={}", result.migrationsExecuted, result.targetSchemaVersion)
    }

    fun open(): Connection = DriverManager.getConnection("jdbc:sqlite:${dbPath.toAbsolutePath()}")

    override fun close() {
        // DriverManager connections are closed per-op. Nothing to release here; SQLite has
        // no server-side connection. Keep the method for AutoCloseable idiom.
    }

    companion object {
        fun openInMemory(): StudioDatabase {
            // Used in unit tests; Flyway supports the in-memory JDBC URL with shared cache.
            val tmp = Files.createTempFile("studio-test-", ".db")
            tmp.toFile().deleteOnExit()
            return StudioDatabase(tmp)
        }
    }
}
