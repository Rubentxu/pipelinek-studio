package dev.pipelinek.studio.cli

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path

/**
 * B5.0 (2026-10-10): the `studio start` /
 * `studio stop` / `studio status` lock file.
 *
 * The S5 mandate says `studio start` boots a
 * server. The operator also needs `studio stop`
 * (terminate a running server) and `studio
 * status` (query whether the server is running).
 * The simplest cross-platform mechanism is a
 * JSON lock file under the data directory:
 *
 *   ~/.pipelinek-studio/.studio.lock
 *
 * The lock carries the PID, host, port, and
 * startedAt timestamp. `stop` reads the lock,
 * checks whether the PID is still alive, and
 * sends a graceful destroy. `status` reads the
 * lock and reports whether the PID is alive.
 *
 * Cross-platform PID liveness check: JDK 9+
 * [ProcessHandle.of] returns an Optional that
 * is present iff the PID is alive on Linux,
 * macOS, and Windows. This avoids the JVM-
 * specific `kill -0` and works on the
 * studio's documented Windows install path.
 *
 * The lock is a record of intent: `start`
 * writes it; `stop` deletes it on graceful
 * shutdown. A crash leaves the lock in place;
 * `status` reports the crash and `start`
 * refuses to overwrite a live lock.
 */
@Serializable
data class StudioLock(
    val pid: Long,
    val host: String,
    val port: Int,
    val startedAt: Long,
    /**
     * B5.0 follow-up (2026-10-10): the OS
     * user name of the process that wrote
     * the lock (System.getProperty("user.name")).
     * The doctor compares this to the
     * current user.name and warns when they
     * differ AND the data dir is the
     * default (~/.pipelinek-studio) — that
     * combination means another account on
     * this host previously ran the studio
     * here, and the current operator is
     * about to either overwrite or start
     * against the wrong data. The field is
     * optional for backward compat with
     * older locks.
     */
    val uid: String? = null,
) {
    fun toJson(): String = Json.encodeToString(serializer(), this)

    companion object {
        const val LOCK_FILENAME: String = ".studio.lock"

        /**
         * Read the lock from [dataDir]. Returns
         * null when the lock file is missing OR
         * unreadable. The caller decides whether
         * "no lock" means "no server running" or
         * "stale lock from a crashed run".
         */
        fun read(dataDir: Path): StudioLock? {
            val lockFile = dataDir.resolve(LOCK_FILENAME)
            if (!Files.isRegularFile(lockFile)) return null
            return try {
                val text = Files.readString(lockFile)
                Json.decodeFromString(serializer(), text)
            } catch (_: Throwable) {
                // Corrupt or unreadable lock: treat
                // as "no server". The operator can
                // `rm` the file if they want a
                // hard reset.
                null
            }
        }

        /**
         * Write the lock. The data dir is created
         * if missing. Existing lock is overwritten
         * (the caller must check [isLive] first
         * to refuse overwriting a live process).
         */
        fun write(dataDir: Path, lock: StudioLock) {
            if (!Files.exists(dataDir)) {
                Files.createDirectories(dataDir)
            }
            val lockFile = dataDir.resolve(LOCK_FILENAME)
            Files.writeString(lockFile, lock.toJson())
        }

        /**
         * Delete the lock. Safe to call when the
         * lock is already missing (no-op).
         */
        fun clear(dataDir: Path) {
            Files.deleteIfExists(dataDir.resolve(LOCK_FILENAME))
        }

        /**
         * True when the PID in [lock] is alive
         * on this OS. JDK 9+ ProcessHandle works
         * on Linux / macOS / Windows.
         */
        fun isLive(lock: StudioLock): Boolean {
            return ProcessHandle.of(lock.pid).isPresent
        }
    }
}

/**
 * B5.0 (2026-10-10): the result of a `studio
 * status` query. The shape is small on purpose:
 * the operator wants to know "is the server
 * running?" + "what's the URL?" + "when did it
 * start?". Anything else is noise.
 */
@Serializable
data class StudioStatus(
    val running: Boolean,
    val pid: Long? = null,
    val host: String? = null,
    val port: Int? = null,
    val startedAt: Long? = null,
    val baseUrl: String? = null,
) {
    fun toJson(): String = Json.encodeToString(serializer(), this)

    fun toHumanReadable(): String = buildString {
        if (!running) {
            appendLine("studio: not running (no live lock under ${StudioLock.LOCK_FILENAME})")
            return@buildString
        }
        appendLine("studio: running")
        appendLine("  pid:       $pid")
        appendLine("  base url:  $baseUrl")
        appendLine("  started:   $startedAt")
    }
}
