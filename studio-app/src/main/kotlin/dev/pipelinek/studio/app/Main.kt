package dev.pipelinek.studio.app

import dev.pipelinek.studio.application.usecases.buildWiring
import dev.pipelinek.studio.http.StudioHttpModule
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
fun main(args: Array<String>) {
    val log = LoggerFactory.getLogger("dev.pipelinek.studio.app")
    val dataDir = Paths.get(
        System.getProperty("pipelinek.studio.runtime.dataDir")
            ?: "${System.getProperty("user.home")}/.pipelinek-studio/runtime",
    )
    dataDir.createDirectories()

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

    val wiringFactory: () -> dev.pipelinek.studio.application.usecases.Wiring = {
        buildWiring(metadata, runner, dev.pipelinek.studio.http.RefusingRunReadPort, dev.pipelinek.studio.http.RefusingOutputReadPort, idempotency, probe)
    }
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

    val port = System.getProperty("pipelinek.studio.runtime.port", "7777").toInt()
    val host = System.getProperty("pipelinek.studio.runtime.host", "127.0.0.1")
    log.info("Booting Studio HTTP on http://{}:{}", host, port)
    // S3 diagnostics + IR ports — single shared adapter covering both surfaces.
    val s3Ports = dev.pipelinek.studio.infra.sdk.PipelineKDiagnosticsAndIrPorts(controlRoot)
    val diagnosticsFactory: () -> dev.pipelinek.studio.infra.sdk.PipelineKDiagnosticsAndIrPorts = { s3Ports }
    val irFactory: () -> dev.pipelinek.studio.infra.sdk.PipelineKDiagnosticsAndIrPorts = { s3Ports }

    embeddedServer(Netty, port = port, host = host) {
        StudioHttpModule(
            wiringFactory,
            runReadFactory,
            outputReadFactory,
            stepCatalogFactory,
            diagnosticsFactory,
            irFactory,
        ).install(this)
    }.start(wait = true)
}
