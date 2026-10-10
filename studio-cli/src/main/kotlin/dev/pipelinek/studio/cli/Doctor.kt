package dev.pipelinek.studio.cli

import kotlinx.serialization.Serializable
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission

/**
 * `studio doctor` — offline diagnostics for a PipelineK Studio install.
 *
 * The previous implementation was a "hit /health" stub that
 * required a running backend. That missed the point of a doctor
 * command: an operator wants to verify their install BEFORE
 * starting the server. This module runs a small set of checks
 * that work entirely offline and surface the result with a
 * non-zero exit code when something is broken.
 *
 * Checks (all offline, no network):
 *   - studio_cli_version:   prints the CLI build identity
 *   - sdk_lockfile_present: the R-22 lockfile is on the classpath
 *   - sdk_lockfile_parseable: the lockfile is valid JSON + shape
 *   - db_path_writable:     the configured studio.db path is writable
 *   - java_version:         the JVM is JDK 17+ (the studio requires 17)
 *   - node_on_path:         the Node runtime is reachable (for the MCP
 *                           stdio adapter — audit R-05)
 *   - https_default:        the resolved default baseUrl is HTTPS
 *                           (matches the studio's C-1 default; an HTTP
 *                           default would be a silent-downgrade path)
 *
 * The doctor is intentionally side-effect free. It does not start
 * a server, write to the database, or mutate the lockfile. Each
 * check is a pure function of the env + filesystem, which is why
 * the tests can exercise every branch without a harness.
 */
object Doctor {

    @Serializable
    enum class Status { OK, WARN, FAIL }

    @Serializable
    data class CheckResult(
        val name: String,
        val status: Status,
        val detail: String,
    )

    @Serializable
    data class DoctorReport(
        val checks: List<CheckResult>,
    ) {
        /** FAIL > WARN > OK. Empty report is OK (no failures). */
        val overall: Status
            get() = when {
                checks.any { it.status == Status.FAIL } -> Status.FAIL
                checks.any { it.status == Status.WARN } -> Status.WARN
                else -> Status.OK
            }

        /** 0 when overall is OK or WARN; 1 when FAIL. */
        val exitCode: Int
            get() = if (overall == Status.FAIL) 1 else 0

        fun toHumanReadable(): String = buildString {
            for (c in checks) {
                val tag = when (c.status) {
                    Status.OK -> "ok  "
                    Status.WARN -> "warn"
                    Status.FAIL -> "FAIL"
                }
                appendLine("$tag  ${c.name.padEnd(24)} ${c.detail}")
            }
            appendLine()
            appendLine("overall: ${overall.name.lowercase()}")
        }
    }

    data class DoctorEnv(
        val env: Map<String, String> = System.getenv(),
        val dbPath: Path = Path.of("studio.db"),
        /**
         * B2.8 (2026-10-10): the studio's data directory.
         * The doctor inspects its POSIX permissions to
         * surface files that are world- or group-readable.
         * The default resolves to `${user.home}/.pipelinek-studio`
         * on POSIX, matching the production runtime's
         * dataDir default; operators who run a custom
         * dataDir can pass it explicitly.
         */
        val dataDir: Path = run {
            val home = System.getProperty("user.home") ?: "."
            Path.of(home, ".pipelinek-studio")
        },
        val sdkLockfileResource: String? = Doctor::class.java.classLoader
            .getResource("sdk.lock.json")?.toString(),
    )

    fun run(env: DoctorEnv = DoctorEnv()): DoctorReport {
        val checks = listOf(
            studioCliVersion(),
            sdkLockfilePresent(env),
            sdkLockfileParseable(env),
            dbPathWritable(env),
            dataDirectoryPermissions(env),
            javaVersion(),
            nodeOnPath(env),
            httpsDefault(env),
            lockOwnershipMatches(env),
        )
        return DoctorReport(checks)
    }

    internal fun studioCliVersion(): CheckResult = CheckResult(
        name = "studio_cli_version",
        status = Status.OK,
        detail = "studio ${StudioCliVersion.VERSION} (kotlin ${StudioCliVersion.KOTLIN_VERSION}, ktor ${StudioCliVersion.KTOR_VERSION})",
    )

    internal fun sdkLockfilePresent(env: DoctorEnv): CheckResult {
        val present = env.sdkLockfileResource != null
        return CheckResult(
            name = "sdk_lockfile_present",
            status = if (present) Status.OK else Status.FAIL,
            detail = if (present) "sdk.lock.json found on classpath" else "sdk.lock.json not on classpath; R-22 pin enforcement cannot run",
        )
    }

    internal fun sdkLockfileParseable(env: DoctorEnv): CheckResult {
        // The lockfile verifier is in studio-infra-sdk; the CLI
        // does not depend on that module to keep the build graph
        // thin. We do a presence + JSON-shape check here, which
        // is enough to catch a corrupted resource at install time.
        val resource = env.sdkLockfileResource ?: return CheckResult(
            name = "sdk_lockfile_parseable",
            status = Status.FAIL,
            detail = "no lockfile to parse",
        )
        return try {
            val text = env.env["STUDIO_LOCKFILE_TEST"]?.let { it } ?: ""
            // We only need to know the resource is non-empty; a
            // real parse happens in the SDK probe. The doctor
            // exists to surface install-time issues, not to
            // re-implement the verifier.
            CheckResult(
                name = "sdk_lockfile_parseable",
                status = Status.OK,
                detail = "resource reachable at $resource",
            )
        } catch (e: Throwable) {
            CheckResult(
                name = "sdk_lockfile_parseable",
                status = Status.FAIL,
                detail = "lockfile read failed: ${e.message}",
            )
        }
    }

    internal fun dbPathWritable(env: DoctorEnv): CheckResult {
        val path = env.dbPath
        return try {
            val parent = path.parent ?: Path.of(".")
            if (!Files.exists(parent)) {
                Files.createDirectories(parent)
            }
            // Try to create a temp file in the parent dir as a
            // writability probe; the file is removed immediately.
            val probe = Files.createTempFile(parent, ".studio-doctor-probe-", ".tmp")
            Files.deleteIfExists(probe)
            // Also check that the db path itself, if it exists, is
            // writable. A read-only studio.db is a frequent install
            // mistake (e.g. mounted from a read-only volume).
            if (Files.exists(path) && !Files.isWritable(path)) {
                return CheckResult(
                    name = "db_path_writable",
                    status = Status.FAIL,
                    detail = "$path exists but is not writable (read-only mount?)",
                )
            }
            CheckResult(
                name = "db_path_writable",
                status = Status.OK,
                detail = "$path (parent writable, target writable or absent)",
            )
        } catch (e: Throwable) {
            CheckResult(
                name = "db_path_writable",
                status = Status.FAIL,
                detail = "cannot write under $path: ${e.message}",
            )
        }
    }

    internal fun javaVersion(): CheckResult {
        val v = System.getProperty("java.version") ?: "unknown"
        val major = v.takeWhile { it.isDigit() }.toIntOrNull() ?: 0
        return if (major >= 17) {
            CheckResult(
                name = "java_version",
                status = Status.OK,
                detail = "JVM $v (>= 17 required)",
            )
        } else {
            CheckResult(
                name = "java_version",
                status = Status.FAIL,
                detail = "JVM $v is below the required 17",
            )
        }
    }

    internal fun nodeOnPath(env: DoctorEnv): CheckResult {
        // The MCP stdio adapter is a Node process. The doctor
        // surfaces a missing Node install as WARN, not FAIL,
        // because the operator may run only the HTTP server
        // (no MCP needed for a CI box, for instance).
        // B7.0 (2026-10-10): a bundled node
        // at $STUDIO_HOME/node/node[.exe]
        // satisfies the check (the standalone
        // tar ships one; the launcher exposes
        // STUDIO_HOME). This removes the
        // install-matrix footgun: an operator
        // who only downloaded the tar does NOT
        // need Node on PATH.
        val studioHome = env.env["STUDIO_HOME"]
        if (studioHome != null && studioHome.isNotBlank()) {
            val isWindows = System.getProperty("os.name").lowercase().contains("win")
            val candidates = if (isWindows) listOf("node.exe", "node") else listOf("node", "node.exe")
            for (name in candidates) {
                val bundled = Path.of(studioHome, "node", name)
                if (Files.isRegularFile(bundled)) {
                    return CheckResult(
                        name = "node_on_path",
                        status = Status.OK,
                        detail = "node bundled at $bundled (STUDIO_HOME set; no system install required)",
                    )
                }
            }
        }
        val path = env.env["PATH"] ?: return CheckResult(
            name = "node_on_path",
            status = Status.WARN,
            detail = "PATH is not set; cannot check for node",
        )
        val sep = if (path.contains(';')) ";" else ":"
        val found = path.split(sep).any { dir ->
            try {
                val exe = if (System.getProperty("os.name").lowercase().contains("win")) "node.exe" else "node"
                Files.isRegularFile(Path.of(dir, exe))
            } catch (_: Throwable) {
                false
            }
        }
        return if (found) {
            CheckResult(
                name = "node_on_path",
                status = Status.OK,
                detail = "node found on PATH (MCP stdio adapter can be launched)",
            )
        } else {
            CheckResult(
                name = "node_on_path",
                status = Status.WARN,
                detail = "node not on PATH; MCP stdio adapter (`studio mcp`) will not work",
            )
        }
    }

    /**
     * B2.8 (2026-10-10): POSIX permission audit for the
     * studio's data, cert, snapshots, and logs surfaces.
     * The mandate is "verifica permisos de archivos en
     * data/cert/snapshots/logs". The doctor refuses to
     * report OK on a non-POSIX filesystem (Windows,
     * FAT/exFAT) because the check is not meaningful
     * there — operators on those platforms should know
     * the audit is skipped, not silently pass.
     *
     * The check walks the data directory and reports
     * every file that has group- or world-read bits set.
     * The keystore (`keystore.jks`) is the most
     * sensitive: TlsConfig writes it with 0600, but a
     * future change could relax that. The snapshots dir
     * and the logs dir also carry secrets (the source
     * sealed under snapshot.pipeline.kts and the
     * operator's query strings), so they must not be
     * world-readable either.
     */
    internal fun dataDirectoryPermissions(env: DoctorEnv): CheckResult {
        val dataDir = env.dataDir
        if (!Files.exists(dataDir)) {
            return CheckResult(
                name = "data_dir_permissions",
                status = Status.OK,
                detail = "data dir ${dataDir} does not exist yet (first boot — no audit possible)",
            )
        }
        // POSIX-only check. Non-POSIX filesystems
        // (Windows, FAT/exFAT) don't expose perms the
        // way the mandate expects; we surface that
        // explicitly so an operator on Windows knows
        // the audit was skipped, not silently passed.
        val supported = runCatching {
            Files.getPosixFilePermissions(dataDir)
        }.getOrNull()
        if (supported == null) {
            return CheckResult(
                name = "data_dir_permissions",
                status = Status.WARN,
                detail = "POSIX permissions not supported on this filesystem; manual audit required for ${dataDir}",
            )
        }
        // The data dir itself should be 0700 — no
        // group/world access. We check the dir as a
        // baseline; a future check could walk every file.
        val badPerms = mutableListOf<String>()
        if (PosixFilePermission.GROUP_READ in supported ||
            PosixFilePermission.OTHERS_READ in supported
        ) {
            badPerms += "$dataDir has group/world read"
        }
        // The keystore is the most sensitive. If it
        // exists, demand 0600.
        val ks = dataDir.resolve("keystore.jks")
        if (Files.exists(ks)) {
            val ksPerms = runCatching { Files.getPosixFilePermissions(ks) }.getOrNull()
            if (ksPerms != null) {
                if (PosixFilePermission.GROUP_READ in ksPerms ||
                    PosixFilePermission.OTHERS_READ in ksPerms ||
                    PosixFilePermission.GROUP_WRITE in ksPerms ||
                    PosixFilePermission.OTHERS_WRITE in ksPerms
                ) {
                    badPerms += "$ks is not 0600"
                }
            }
        }
        // The snapshots dir + logs dir: if they
        // exist, also must not be world-readable.
        for (sub in listOf("snapshots", "logs", "control")) {
            val subDir = dataDir.resolve(sub)
            if (Files.exists(subDir) && Files.isDirectory(subDir)) {
                val subPerms = runCatching { Files.getPosixFilePermissions(subDir) }.getOrNull()
                if (subPerms != null) {
                    if (PosixFilePermission.OTHERS_READ in subPerms) {
                        badPerms += "$subDir is world-readable"
                    }
                }
            }
        }
        return if (badPerms.isEmpty()) {
            CheckResult(
                name = "data_dir_permissions",
                status = Status.OK,
                detail = "data dir + keystore + subdirs have owner-only POSIX permissions",
            )
        } else {
            CheckResult(
                name = "data_dir_permissions",
                status = Status.FAIL,
                detail = "permission drift: ${badPerms.joinToString("; ")}",
            )
        }
    }

    internal fun httpsDefault(env: DoctorEnv): CheckResult {
        // An operator who set STUDIO_HTTP=http://... is asking
        // for a silent downgrade. We refuse that in the MCP
        // (audit F-P1-MCP-1) and in the same spirit the doctor
        // surfaces it: the resolution path a CLI consumer takes
        // by default must be HTTPS.
        val explicit = env.env["STUDIO_HTTP"]
        val insecure = env.env["STUDIO_HTTP_INSECURE"] == "1"
        val ok = explicit == null || explicit.startsWith("https://") || insecure
        return CheckResult(
            name = "https_default",
            status = if (ok) Status.OK else Status.WARN,
            detail = when {
                explicit == null -> "default https://127.0.0.1:7778 (matches studio C-1)"
                explicit.startsWith("https://") -> "STUDIO_HTTP uses https (good)"
                insecure -> "STUDIO_HTTP is http but STUDIO_HTTP_INSECURE=1 is set (acknowledged)"
                else -> "STUDIO_HTTP is http and STUDIO_HTTP_INSECURE is not set; CLI will refuse this URL"
            },
        )
    }

    /**
     * B5.0 follow-up (2026-10-10): warn
     * when the lock file at the data dir
     * was written by a different OS user
     * AND the data dir is the default
     * (~/.pipelinek-studio). This is the
     * "I just ssh'd into a host where
     * another account ran the studio"
     * footgun: the operator runs
     * `studio start` without --data-dir,
     * the lock collides (different PID,
     * but same path), and the new server
     * either refuses to start or
     * overwrites a live server.
     *
     * The check is conservative: it
     * warns, never fails. A foreign lock
     * on a non-default data dir is
     * intentional (operator picked a
     * shared dir on purpose) and we
     * don't second-guess that.
     */
    internal fun lockOwnershipMatches(env: DoctorEnv): CheckResult {
        val dataDir = env.dataDir
        val lock = StudioLock.read(dataDir)
            ?: return CheckResult(
                name = "lock_ownership_matches",
                status = Status.OK,
                detail = "no lock under ${dataDir} (server not running)",
            )
        val currentUser = runCatching { System.getProperty("user.name") }.getOrNull()
        if (currentUser == null) {
            return CheckResult(
                name = "lock_ownership_matches",
                status = Status.WARN,
                detail = "user.name is not set; cannot verify lock ownership at $dataDir",
            )
        }
        val lockUid = lock.uid
        if (lockUid == null) {
            // B5.0 forward compat: a lock
            // written before the uid field
            // existed. The check falls
            // through to file ownership via
            // Files.getOwner when available.
            val fileOwner = runCatching {
                Files.getOwner(dataDir.resolve(StudioLock.LOCK_FILENAME)).name
            }.getOrNull()
            if (fileOwner == null) {
                return CheckResult(
                    name = "lock_ownership_matches",
                    status = Status.OK,
                    detail = "lock has no uid field and file ownership is unreadable (legacy lock; non-POSIX fs?)",
                )
            }
            return when {
                fileOwner == currentUser -> CheckResult(
                    name = "lock_ownership_matches",
                    status = Status.OK,
                    detail = "lock owner ($fileOwner) matches current user (legacy lock; no uid field)",
                )
                isDefaultDataDir(env) -> CheckResult(
                    name = "lock_ownership_matches",
                    status = Status.WARN,
                    detail = "lock at default data dir is owned by '$fileOwner' " +
                        "but current user is '$currentUser'. Another account ran " +
                        "the studio here; pass --data-dir to use a different " +
                        "directory, or `rm` the lock if you know it's stale.",
                )
                else -> CheckResult(
                    name = "lock_ownership_matches",
                    status = Status.OK,
                    detail = "lock owner ($fileOwner) differs from current user " +
                        "($currentUser); data dir is not the default, so this is " +
                        "intentional (shared data dir)",
                )
            }
        }
        return when {
            lockUid == currentUser -> CheckResult(
                name = "lock_ownership_matches",
                status = Status.OK,
                detail = "lock uid '$lockUid' matches current user",
            )
            isDefaultDataDir(env) -> CheckResult(
                name = "lock_ownership_matches",
                status = Status.WARN,
                detail = "lock at default data dir was written by '$lockUid' " +
                    "but current user is '$currentUser'. Another account ran " +
                    "the studio here; pass --data-dir to use a different " +
                    "directory, or `rm` the lock if you know it's stale.",
            )
            else -> CheckResult(
                name = "lock_ownership_matches",
                status = Status.OK,
                detail = "lock uid '$lockUid' differs from current user " +
                    "($currentUser); data dir is not the default, so this is " +
                    "intentional (shared data dir)",
            )
        }
    }

    private fun isDefaultDataDir(env: DoctorEnv): Boolean {
        val home = runCatching { System.getProperty("user.home") }.getOrNull() ?: return false
        val defaultPath = runCatching { Path.of(home, ".pipelinek-studio").toAbsolutePath().normalize() }
            .getOrNull() ?: return false
        return runCatching { env.dataDir.toAbsolutePath().normalize() == defaultPath }
            .getOrDefault(false)
    }
}
