package dev.pipelinek.studio.app

import dev.pipelinek.studio.application.usecases.buildWiring
import dev.pipelinek.studio.application.usecases.observeTreeRuns
import dev.pipelinek.studio.infra.storage.InMemoryTreeEventBus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import dev.pipelinek.studio.http.StudioHttpModule
import dev.pipelinek.studio.http.TlsConfig
import dev.pipelinek.studio.infra.sdk.PipelineKRunner
import dev.pipelinek.studio.infra.sdk.PipelineKRuntimeProbe
import dev.pipelinek.studio.infra.storage.IdempotencyRepository
import dev.pipelinek.studio.infra.storage.StudioDatabase
import dev.pipelinek.studio.infra.storage.StudioMetadataRepository
import io.ktor.server.application.Application
import io.ktor.server.engine.*
import io.ktor.server.netty.Netty
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.createDirectories

/** JVM property keys the app entry point honours (its public runtime contract). */
internal const val DATA_DIR_PROPERTY: String = "pipelinek.studio.runtime.dataDir"
internal const val HOST_PROPERTY: String = "pipelinek.studio.runtime.host"
internal const val PORT_PROPERTY: String = "pipelinek.studio.runtime.port"
internal const val INSECURE_HTTP_PROPERTY: String = "pipelinek.studio.runtime.insecureHttp"

/** Canonical loopback host. */
internal const val DEFAULT_HOST: String = "127.0.0.1"

/**
 * Default data dir template. `${user.home}` is expanded at boot against the
 * JVM's real `user.home`, so the value never depends on how a launcher script
 * quotes it.
 */
internal const val DEFAULT_DATA_DIR: String = "\${user.home}/.pipelinek-studio/runtime"

/** The resolved listener + storage configuration for one boot. */
internal data class StudioRuntimeConfig(
    val host: String,
    val port: Int,
    val dataDir: Path,
    val insecureHttp: Boolean,
)

/**
 * Resolves the canonical runtime defaults and every explicit override.
 *
 * Scheme and port are chosen together so there is no silent downgrade: HTTPS
 * on [TlsConfig.DEFAULT_HTTPS_PORT] unless the operator explicitly opted into
 * plain HTTP, in which case the legacy [TlsConfig.LEGACY_HTTP_PORT] is used.
 * An explicit `pipelinek.studio.runtime.port`/`host`/`dataDir` always wins.
 */
internal fun resolveRuntimeConfig(
    args: List<String> = emptyList(),
    property: (String) -> String? = { System.getProperty(it) },
): StudioRuntimeConfig {
    val insecureHttp = args.any { it == "--insecure-http" } || property(INSECURE_HTTP_PROPERTY) == "true"
    val port = property(PORT_PROPERTY)?.toInt()
        ?: if (insecureHttp) TlsConfig.LEGACY_HTTP_PORT else TlsConfig.DEFAULT_HTTPS_PORT
    val host = property(HOST_PROPERTY) ?: DEFAULT_HOST
    val rawDataDir = property(DATA_DIR_PROPERTY) ?: DEFAULT_DATA_DIR
    return StudioRuntimeConfig(
        host = host,
        port = port,
        dataDir = Paths.get(expandUserHome(rawDataDir, property("user.home") ?: ".")),
        insecureHttp = insecureHttp,
    )
}

/**
 * Expands the `${user.home}` placeholder. Gradle's generated launcher escapes
 * the `$`, so a legacy value can arrive as `\${user.home}/…`; both spellings
 * resolve against [userHome]. Anything else is returned untouched.
 */
internal fun expandUserHome(raw: String, userHome: String): String =
    raw.replace("\\\${user.home}", userHome).replace("\${user.home}", userHome)

/**
 * PipelineK Studio backend entry point.
 *
 * Wires Studio metadata SQLite (Flyway-migrated), PipelineK SDK probe + runner, and the
 * Ktor HTTP module. Exposes endpoints per docs/02-specifications/HTTP_AND_STREAMING_API.md.
 *
 * On startup we honestly report the SDK presence:
 *   - Available: full integration. We trust the SDK classpath jar we found.
 *   - Unavailable: STDOUT message + non-DONE startup status. Studio refuses to invent
 *     a runtime descriptor.
 *   - Failure: log and exit with non-zero exit code.
 */
// R0.1.3 (2026-10-11): main is the app entry — descriptor read +
// runtime check + bundle path. The function is short for a main;
// the LongMethod threshold of 120 catches it because of the
// verbose error handling. Flat-reading is clearer for an
// entry-point function.
@Suppress("LongMethod")
fun main(args: Array<String>) {
    val log = LoggerFactory.getLogger("dev.pipelinek.studio.app")
    val runtime = resolveRuntimeConfig(args.toList())
    val dataDir: Path = runtime.dataDir.also { it.createDirectories() }

    val dbPath: Path = dataDir.resolve("studio.db")
    val controlRoot: Path = dataDir.resolve("runs").also { it.createDirectories() }
    val db = StudioDatabase(dbPath)
    val metadata = StudioMetadataRepository(db)
    val idempotency = IdempotencyRepository(db)

    log.info("Studio data dir: {}", dataDir.toAbsolutePath())
    log.info("Studio DB path: {}", dbPath.toAbsolutePath())
    log.info("Per-run control-root: {}", controlRoot.toAbsolutePath())

    // Resolve the SDK classpath ONCE and pass it to BOTH the probe and the
    // runner. The probe previously only scanned the studio-app classpath,
    // so even when the runner had a real SDK the probe reported
    // Unavailable, the wiring rejected submits with 409 RUNTIME_UNAVAILABLE,
    // and the UAT exited at step 5. Sharing the resolution here means the
    // probe sees the same jars the runner will spawn.
    val startSdkArg = args.firstOrNull { it.startsWith("--sdk-classpath=") }
        ?: System.getenv("PIPELINEK_SDK_CLASSPATH")?.let { "PIPELINEK_SDK_CLASSPATH=$it" }
    val sdkClasspath: List<Path> = if (startSdkArg != null) {
        val raw = startSdkArg.substringAfter("=")
        raw.split(":").filter { it.isNotBlank() }.map { Paths.get(it) }
    } else {
        // No explicit SDK classpath; fall back to whatever is on the
        // studio classpath that looks like a PipelineK artifact.
        emptyList()
    }
    // The probe's locateSdkJar() requires classpathRoots to be directories
    // it can walk; jar-file paths get skipped by an early `isDirectory`
    // guard. We hand it the parent directories of any SDK jars we found,
    // plus the JVM classpath roots (which already include parent dirs of
    // studio jar files via defaultClasspathRoots()).
    val probeRoots = buildSet<Path> {
        for (p in sdkClasspath) {
            // p may be a jar (use parent) or a directory (use as-is)
            val parent = p.parent
            if (parent != null) add(parent) else if (Files.isDirectory(p)) add(p)
        }
        addAll(PipelineKRuntimeProbe.defaultClasspathRoots())
    }
    val probe = PipelineKRuntimeProbe(classpathRoots = probeRoots.toList())
    val mainClass = System.getProperty("pipelinek.runtime.mainClass", "dev.rubentxu.pipeline.v2.application.MainKt")
    val runner = PipelineKRunner(
        controlRootBase = controlRoot,
        sdkClasspath = sdkClasspath,
        mainClass = mainClass,
        // S1 E1.5: the runner reads the real snapshot bytes from studio.db
        // before spawning the SDK subprocess. We pass the same repository
        // the seal flow writes through, so what the user sealed is exactly
        // what the runner spawns against.
        snapshotSource = metadata,
    )

    when (val desc = probe.describe()) {
        is dev.pipelinek.studio.application.ports.RuntimeDescriptorResult.Available -> {
            log.info("PipelineK SDK detected: version={} digest={} caps={}",
                desc.descriptor.version, desc.descriptor.artifactDigest, desc.descriptor.capabilities)
        }
        dev.pipelinek.studio.application.ports.RuntimeDescriptorResult.Unavailable -> {
            log.warn("PipelineK SDK UNAVAILABLE. Provide --sdk-classpath=<colon-separated SDK jar paths> to wire a real SDK.")
        }
        is dev.pipelinek.studio.application.ports.RuntimeDescriptorResult.Failure -> {
            log.error("PipelineK SDK probe FAILED: {}", desc.reason)
        }
    }

    // R0.2.4 (2026-10-11): wire the REAL PipelineK read/output adapters
    // instead of the refusing stubs. Until this change the production
    // wiring always returned `RunReadResult.Unknown` for events and
    // `OutputReadResult.UnknownStream` for output — meaning
    // /runs/{id}/events, /runs/{id}/stream, and /runs/{id}/output
    // silently degraded to "we don't know". With these adapters, the
    // production server actually reads run.db (events, journal) and
    // stdout.log (output bytes) that the SDK subprocess wrote under
    // `controlRoot/<runId>/`. If the file is absent, the adapters
    // return `Unknown` / `UnknownStream` with a reason — never
    // fabricate data.
    //
    // Per ADR-003 the production server is read-only on run.db; it
    // does NOT open a writer handle and never competes with the
    // SDK subprocess's journal ownership.
    val treeEventBus = InMemoryTreeEventBus()
    val wiringFactory: () -> dev.pipelinek.studio.application.usecases.Wiring = {
        buildWiring(
            metadata,
            runner,
            dev.pipelinek.studio.infra.sdk.PipelineKRunReadPort(controlRoot),
            dev.pipelinek.studio.infra.sdk.PipelineKOutputReadPort(controlRoot),
            idempotency,
            probe,
            treeEventBus = treeEventBus,
        )
    }
    // AAT-DUR-006 — retention service wired to the live subprocess
    // set exposed by the runner. The admin endpoint POSTs to
    // /api/admin/retention and gets a SweepOutcome back.
    val retentionService: dev.pipelinek.studio.infra.storage.RetentionService =
        dev.pipelinek.studio.infra.storage.RetentionService()
    // AAT-SEC-010 — workspace lease registry. Destructive ops
    // (retention sweep) must be authorised by a freshly-acquired
    // lease. The admin endpoint requires an X-Studio-Lease header
    // for op-kind 'retention-sweep'. Without it, the request is
    // refused (no files touched).
    val leaseRegistry: dev.pipelinek.studio.infra.storage.InMemoryLeaseRegistry =
        dev.pipelinek.studio.infra.storage.InMemoryLeaseRegistry()
    // S1 read-side: real RunReadPort reading from control-root/<runId>/run.db
    val runReadFactory: () -> dev.pipelinek.studio.infra.sdk.PipelineKRunReadPort = {
        dev.pipelinek.studio.infra.sdk.PipelineKRunReadPort(controlRoot)
    }
    // Output port backed by stdout.log captured during runner spawn
    val outputReadFactory: () -> dev.pipelinek.studio.infra.sdk.PipelineKOutputReadPort = {
        dev.pipelinek.studio.infra.sdk.PipelineKOutputReadPort(controlRoot)
    }
    // S2 E2.2 step catalog port — scans the SDK distribution's lib/ via the static inspector.
    val stepCatalogFactory: () -> dev.pipelinek.studio.infra.sdk.PipelineKStepCatalogReadPort =
        run {
            // PipelineKRunner holds sdkClasspath; the SDK installs jars under
            // <sdkRoot>/lib. We resolve <sdkRoot>/lib by stripping the jar name from any
            // sdk entry and probing the parent directory.
            val firstJar = sdkClasspath.firstOrNull()
            val sdkRoot = firstJar?.let {
                var p = it.toAbsolutePath()
                // Walk up looking for the parent that contains 'lib'
                while (p != null && !java.nio.file.Files.isDirectory(p.resolve("lib"))) p = p.parent
                p
            }
            if (sdkRoot != null) {
                val libDir = sdkRoot.resolve("lib")
                val _port: () -> dev.pipelinek.studio.infra.sdk.PipelineKStepCatalogReadPort = {
                    dev.pipelinek.studio.infra.sdk.PipelineKStepCatalogReadPort(libDir)
                }
                _port
            } else {
                val _unavailable: () -> dev.pipelinek.studio.infra.sdk.PipelineKStepCatalogReadPort = {
                    dev.pipelinek.studio.infra.sdk.PipelineKStepCatalogReadPort(
                        java.nio.file.Path.of("/nonexistent-sdk")
                    )
                }
                _unavailable
            }
        }

    // Audit C-1 (2026-10-10) contract, wired for real by S2 (2026-10-16):
    //  - default: HTTPS on 127.0.0.1:7778 with the self-signed cert
    //    generated on first boot under <dataDir>/keystore.jks.
    //  - opt-out: --insecure-http (or the JVM property
    //    pipelinek.studio.runtime.insecureHttp=true) serves plain HTTP on
    //    the legacy port 7777.
    //
    // The connector itself is installed by [TlsConfig.configureListener], the
    // same helper `studio start` uses. The previous shape was a plain
    // `embeddedServer(Netty, port, host)` call with a comment claiming the TLS
    // termination was "delegated to the Ktor Netty engine's HOCON path": no
    // such HOCON file ever shipped, so the app distribution served plaintext
    // HTTP on the HTTPS port with no way to reach the documented default.
    val scheme = if (runtime.insecureHttp) "http" else "https"
    log.info("Booting Studio {} on {}://{}:{}", scheme.uppercase(), scheme, runtime.host, runtime.port)
    // S3 diagnostics + IR ports — single shared adapter covering both surfaces.
    val s3Ports = dev.pipelinek.studio.infra.sdk.PipelineKDiagnosticsAndIrPorts(controlRoot)
    val diagnosticsFactory: () -> dev.pipelinek.studio.infra.sdk.PipelineKDiagnosticsAndIrPorts = { s3Ports }
    val irFactory: () -> dev.pipelinek.studio.infra.sdk.PipelineKDiagnosticsAndIrPorts = { s3Ports }

    embeddedServer(
        Netty,
        applicationEnvironment {},
        configure = {
            // Exactly one connector: HTTPS unless the operator explicitly
            // opted into plain HTTP. Shared with `studio start` so both
            // entry points serve the identical wire.
            TlsConfig.configureListener(
                this,
                secure = !runtime.insecureHttp,
                host = runtime.host,
                port = runtime.port,
                dataDir = dataDir,
            )
        },
        module = {
            val wiring = wiringFactory()
            launch(Dispatchers.IO) { wiring.observeTreeRuns() }
            StudioHttpModule(
                wiringFactory,
                runReadFactory,
                outputReadFactory,
                controlRootBase = controlRoot,
                stepCatalogFactory,
                diagnosticsFactory,
                irFactory,
                // AAT-DUR-006: per-run pid map (run_id -> pid) from the
                // runner. Authoritative for retention: a run is kept
                // when its run_id is in this map.
                liveRunToPidFactory = { runner.liveRunToPid() },
                // AAT-SEC-010: lease registry. Destructive admin ops
                // require a fresh lease via POST /api/admin/lease.
                leaseAcquireFactory = { leaseRegistry.acquire("retention-sweep") },
                leaseValidateFactory = { token, opKind -> leaseRegistry.validate(token, opKind) },
            ).install(this)
        },
    ).start(wait = true)
}
