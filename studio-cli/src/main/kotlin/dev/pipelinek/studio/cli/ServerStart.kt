package dev.pipelinek.studio.cli

import dev.pipelinek.studio.application.usecases.buildWiring
import dev.pipelinek.studio.application.usecases.observeTreeRuns
import dev.pipelinek.studio.infra.storage.InMemoryTreeEventBus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import dev.pipelinek.studio.http.StudioHttpModule
import dev.pipelinek.studio.http.TlsConfig
import dev.pipelinek.studio.infra.sdk.PipelineKDiagnosticsAndIrPorts
import dev.pipelinek.studio.infra.sdk.PipelineKOutputReadPort
import dev.pipelinek.studio.infra.sdk.PipelineKRunner
import dev.pipelinek.studio.infra.sdk.PipelineKRunReadPort
import dev.pipelinek.studio.infra.sdk.PipelineKRuntimeProbe
import dev.pipelinek.studio.infra.storage.IdempotencyRepository
import dev.pipelinek.studio.infra.storage.InMemoryLeaseRegistry
import dev.pipelinek.studio.infra.storage.StudioDatabase
import dev.pipelinek.studio.infra.storage.StudioMetadataRepository
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.engine.applicationEnvironment
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories

/**
 * R0.2.1.b (2026-10-11) — `studio start` boots the same HTTP module
 * that `studio-app/Main.kt` boots. The previous `MinimalModule` (a
 * parallel 5-route subset) was retired because:
 *
 *  - the OpenAPI document was already unified by R0.2.1.a (sha
 *    `bfc59bc`),
 *  - the runtime surface divergence is now closed: every route
 *    StudioHttpModule mounts (experiments, snapshots, runs, events,
 *    output, diagnostics, IR, steps, admin/lease, admin/retention,
 *    recovery, static-analyze-capability, version, metrics, health,
 *    openapi.json) is served by `studio start` with the same
 *    StatusPages + Host/Origin allow-list + rate-limit + SSE
 *    configuration the production server has.
 *
 * Without `--sdk-classpath`, the runner cannot spawn a real PipelineK
 * subprocess; submits return 409 RUNTIME_UNAVAILABLE with a clear
 * reason (the existing runner's `SubmissionResult.Refused` path). The
 * CLI smoke remains honest about what it can and cannot do.
 *
 * With `--sdk-classpath=<colon-separated SDK jar paths>` the CLI
 * behaves identically to `studio-app` (same factories, same probes,
 * same lease registry, same RetentionService), except it binds on a
 * plain HTTP port chosen by the operator and writes the same
 * `dataDir/.studio.lock` record so `studio stop`/`studio status`
 * work the same way.
 */
// R0.1.3 (2026-10-11): startServer is the server boot sequence —
// every step is a small block (config, lock, Ktor install,
// routes, healthcheck). Extracting would add 10+ private functions
// that all read the same `args` and `log`; the table form is
// clearer for a boot path that runs once per process.
@Suppress("LongMethod")
fun startServer(args: StartArgs): Int {
    val log = LoggerFactory.getLogger("studio.start")
    val startedAt = System.currentTimeMillis()

    val dbPath: Path = Path.of(args.db)
    val dataDir: Path = args.dataDir
    val db = StudioDatabase(dbPath)
    val metadata = StudioMetadataRepository(db)
    val idempotency = IdempotencyRepository(db)
    log.info("Studio start: db={} port={} dataDir={}", dbPath.toAbsolutePath(), args.port, dataDir.toAbsolutePath())

    // B5.0 (2026-10-10): refuse to overwrite a live lock.
    val existing = StudioLock.read(dataDir)
    if (existing != null && StudioLock.isLive(existing)) {
        System.err.println(
            "error: a live studio server is already running " +
                "(pid=${existing.pid}, port=${existing.port}). " +
                "Run `studio stop` first, or remove $dataDir/${StudioLock.LOCK_FILENAME}.",
        )
        return 3
    }

    val controlRoot: Path = dataDir.resolve("runs").also { it.createDirectories() }

    // SDK classpath from --sdk-classpath; empty when the operator runs the CLI smoke
    // without a real SDK. The probe + runner still build; submissions return
    // SubmissionResult.Refused with a clear reason, surfacing 409 RUNTIME_UNAVAILABLE.
    val sdkClasspath: List<Path> = StartArgs.parseSdkClasspath(args.sdkClasspath)
    val probeRoots = buildSet<Path> {
        for (p in sdkClasspath) {
            val parent = p.parent
            if (parent != null) add(parent) else if (Files.isDirectory(p)) add(p)
        }
        addAll(PipelineKRuntimeProbe.defaultClasspathRoots())
    }
    val probe = PipelineKRuntimeProbe(classpathRoots = probeRoots.toList())
    val mainClass = args.mainClass ?: "dev.rubentxu.pipeline.v2.application.MainKt"
    val runner = PipelineKRunner(
        controlRootBase = controlRoot,
        sdkClasspath = sdkClasspath,
        mainClass = mainClass,
        snapshotSource = metadata,
    )
    when (val desc = probe.describe()) {
        is dev.pipelinek.studio.application.ports.RuntimeDescriptorResult.Available ->
            log.info("PipelineK SDK detected (cli smoke): version={} digest={}", desc.descriptor.version, desc.descriptor.artifactDigest)
        dev.pipelinek.studio.application.ports.RuntimeDescriptorResult.Unavailable ->
            log.warn("PipelineK SDK UNAVAILABLE in cli smoke; passes routed via probe.report = Unavailable")
        is dev.pipelinek.studio.application.ports.RuntimeDescriptorResult.Failure ->
            log.error("PipelineK SDK probe FAILED in cli smoke: {}", desc.reason)
    }

    val treeEventBus = InMemoryTreeEventBus()
    val leaseRegistry = InMemoryLeaseRegistry()
    val s3Ports = PipelineKDiagnosticsAndIrPorts(controlRoot, dbPath)
    // Step catalog: resolves the SDK install dir the same way studio-app does. With
    // no SDK (cli smoke without --sdk-classpath) the lib dir is absent and the
    // catalog port reports itself as empty.
    val stepCatalogFactory: () -> dev.pipelinek.studio.infra.sdk.PipelineKStepCatalogReadPort = run {
        val firstJar = sdkClasspath.firstOrNull()
        val sdkRoot = firstJar?.let {
            var p = it.toAbsolutePath()
            while (p != null && !Files.isDirectory(p.resolve("lib"))) p = p.parent
            p
        }
        if (sdkRoot != null) {
            val libDir = sdkRoot.resolve("lib")
            val port: () -> dev.pipelinek.studio.infra.sdk.PipelineKStepCatalogReadPort = {
                dev.pipelinek.studio.infra.sdk.PipelineKStepCatalogReadPort(libDir)
            }
            port
        } else {
            val port: () -> dev.pipelinek.studio.infra.sdk.PipelineKStepCatalogReadPort = {
                dev.pipelinek.studio.infra.sdk.PipelineKStepCatalogReadPort(Path.of("/nonexistent-sdk"))
            }
            port
        }
    }

    // R0.2.2/S2 (2026-10-11, 2026-10-16): exactly one connector, chosen by the
    // shared [TlsConfig.configureListener] helper — the same helper
    // `studio-app`/Main.kt uses, so the two entry points cannot drift again.
    // Default = HTTPS on the supplied port with the self-signed loopback cert
    // (persisted under <dataDir>/keystore.jks); --insecure-http = explicit
    // plain-HTTP opt-in on the operator's port (R0.2.3).
    val environment = applicationEnvironment {}
    val server = embeddedServer(
        Netty,
        environment,
        configure = {
            TlsConfig.configureListener(
                this,
                secure = !args.insecureHttp,
                host = args.host,
                port = args.port,
                dataDir = dataDir,
            )
            if (args.insecureHttp) {
                // R0.2.3: explicit opt-in for plain HTTP.
                log.info(
                    "studio start: --insecure-http opt-in; plain HTTP on http://{}:{} (R0.2.3)",
                    args.host, args.port,
                )
            } else {
                // R0.2.2: a real TLS connector — not a keystore-only stub.
                log.info(
                    "studio start: TLS connector bound on https://{}:{} (R0.2.2)",
                    args.host, args.port,
                )
            }
        },
        module = {
            // `this` is `Application`. The Ktor module is the same one
            // studio-app mounts (see R0.2.1.b unification).
            val wiring = buildWiring(
                metadata,
                runner,
                PipelineKRunReadPort(controlRoot),
                PipelineKOutputReadPort(controlRoot),
                idempotency,
                probe,
                treeEventBus = treeEventBus,
            )
            launch(Dispatchers.IO) { wiring.observeTreeRuns() }
            StudioHttpModule(
                wiringFactory = { wiring },
                runReadFactory = { PipelineKRunReadPort(controlRoot) },
                outputReadFactory = { PipelineKOutputReadPort(controlRoot) },
                controlRootBase = controlRoot,
                stepCatalogFactory = stepCatalogFactory,
                diagnosticsFactory = { s3Ports },
                irFactory = { s3Ports },
                staticAnalyzeCapabilityFactory = null,
                hostAllowList = null,
                originAllowList = null,
                liveRunToPidFactory = { runner.liveRunToPid() },
                leaseAcquireFactory = { leaseRegistry.acquire("retention-sweep") },
                leaseValidateFactory = { token, opKind -> leaseRegistry.validate(token, opKind) },
            ).install(this)
        },
    )

    val lock = StudioLock(
        pid = ProcessHandle.current().pid(),
        host = args.host,
        port = args.port,
        startedAt = startedAt,
        uid = System.getProperty("user.name"),
    )
    StudioLock.write(dataDir, lock)
    Runtime.getRuntime().addShutdownHook(
        Thread {
            log.info("studio start shutting down (uptime={}ms)", System.currentTimeMillis() - startedAt)
            server.stop(SERVER_STOP_GRACE_MS, SERVER_STOP_HARD_MS)
            db.close()
            StudioLock.clear(dataDir)
        },
    )
    server.start(wait = true)
    return 0
}

/** Grace period for in-flight requests when stopping the server. */
private const val SERVER_STOP_GRACE_MS: Long = 1_000

/** Hard kill ceiling after the grace period elapses. */
private const val SERVER_STOP_HARD_MS: Long = 5_000

data class StartArgs(
    val host: String = "127.0.0.1",
    val port: Int = 7778,
    val db: String = "studio.db",
    /**
     * B5.0 (2026-10-10): the data directory.
     */
    val dataDir: Path = run {
        val home = System.getProperty("user.home") ?: "."
        Path.of(home, ".pipelinek-studio")
    },
    /**
     * Audit C-1 (2026-10-10): opt out of HTTPS. Default false.
     */
    val insecureHttp: Boolean = false,
    /**
     * R0.2.1.b (2026-10-11): optional colon-separated SDK classpath. When
     * absent, submissions to /runs return 409 RUNTIME_UNAVAILABLE (the
     * runner cannot spawn a PipelineK subprocess). When present, the CLI
     * smoke behaves identically to `studio-app` with the same factories.
     */
    val sdkClasspath: String = "",
    /**
     * R0.2.1.b (2026-10-11): the PipelineK main class to spawn. Default
     * matches `studio-app/Main.kt:95` so passing just `--sdk-classpath=`
     * is sufficient for most operators.
     */
    val mainClass: String? = null,
) {
    companion object {
        /**
         * R0.2.1.b: parse `--sdk-classpath=a.jar:b.jar:c.jar` into a list
         * of absolute paths. The matcher tolerates both POSIX `:` and
         * Windows `;` so the same flag works across platforms (the JDK
         * uses `File.pathSeparatorChar`, but operators frequently write
         * POSIX `:` regardless; we accept either to avoid surprise).
         */
        fun parseSdkClasspath(raw: String): List<Path> {
            if (raw.isBlank()) return emptyList()
            val parts = raw.split(Regex("[;:]"))
                .filter { it.isNotBlank() }
                .map { Path.of(it) }
            return parts
        }
    }
}

/**
 * Probe used by `studio doctor`: returns a small map describing the local runtime.
 *
 * Audit C-1 (2026-10-10): the previous implementation hardcoded
 * `http://$host:$port`, which fails to talk to a server running
 * with the post-C-1 HTTPS default. The StudioHttpClient already
 * ships a trust-all X509TrustManager so it can talk to the
 * self-signed cert, so the only change needed here is the scheme.
 * The [insecureHttp] flag mirrors StartArgs.insecureHttp — when
 * the operator opted out of HTTPS on the server, the client must
 * also drop the S, otherwise the probe fails closed on the wrong
 * port for the wrong protocol.
 */
fun doctorSnapshot(
    host: String,
    port: Int,
    insecureHttp: Boolean = false,
): Map<String, String> {
    val out = mutableMapOf<String, String>()
    out["host"] = host
    out["port"] = port.toString()
    out["scheme"] = if (insecureHttp) "http" else "https"
    out["http"] = runCatching {
        val scheme = if (insecureHttp) "http" else "https"
        val client = StudioHttpClient("$scheme://$host:$port")
        runBlocking { client.health() }
        "reachable"
    }.getOrElse { "unreachable: ${it.message}" }
    return out
}
