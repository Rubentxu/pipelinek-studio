package dev.pipelinek.studio.cli

import dev.pipelinek.studio.infra.storage.StudioDatabase
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.nio.file.Path

/**
 * S5 E5.1 — `studio start` boots a *minimal* Studio server. The full feature surface
 * (experiments, runs, events, output, SSE, MCP) is provided by `studio-app`; this
 * command exists for environments where a runnable distribution is not yet installed
 * and the operator wants a smoke server that exposes /health and /api/version backed
 * by the real studio.db (so the same Flyway-migrated schema as the full app).
 *
 * It deliberately does NOT run a PipelineK subprocess — full integration requires
 * `studio-app` (which has the SDK probe + runner). This is documented in `--help`
 * and in the response body of `/api/version`.
 */
fun startServer(args: StartArgs): Int {
    val log = LoggerFactory.getLogger("studio.start")
    val startedAt = System.currentTimeMillis()

    val dbPath: Path = Path.of(args.db)
    val db = StudioDatabase(dbPath)
    log.info("Studio start: db={} port={}", dbPath.toAbsolutePath(), args.port)

    val module = MinimalModule(dbPath)
    val server = embeddedServer(Netty, port = args.port, host = args.host) {
        module.install(this)
    }
    Runtime.getRuntime().addShutdownHook(
        Thread {
            log.info("studio start shutting down (uptime={}ms)", System.currentTimeMillis() - startedAt)
            server.stop(1_000, 5_000)
            db.close()
        },
    )
    server.start(wait = true)
    return 0
}

data class StartArgs(
    val host: String = "127.0.0.1",
    val port: Int = 7777,
    val db: String = "studio.db",
)

/**
 * Minimal Studio HTTP module. Mirrors the `StudioHttpModule.install(this)` pattern used
 * by `studio-app` so the wiring stays consistent across both entry points.
 */
class MinimalModule(@Suppress("UNUSED_PARAMETER") private val dbPath: Path) {
    fun install(app: Application) {
        app.install(ContentNegotiation) {
            json(Json {
                ignoreUnknownKeys = true
                classDiscriminator = "kind"
                encodeDefaults = false
            })
        }
        app.install(CallLogging)
        app.install(StatusPages) {
            exception<Throwable> { call, cause ->
                call.respond(
                    HttpStatusCode.InternalServerError,
                    mapOf("error" to (cause.message ?: (cause::class.simpleName ?: "unknown"))),
                )
            }
        }
        app.routing {
            get("/health") { call.respondText("ok") }
            get("/api/version") {
                call.respond(
                    VersionResponse(
                        version = "0.1.0-alpha.1",
                        server = "studio-cli-minimal",
                        docs = "see studio-app for the full server; this start mode exposes only /health + /api/version",
                    ),
                )
            }
            get("/api/ready") {
                call.respond(VersionResponse(version = "0.1.0-alpha.1", server = "ready", docs = ""))
            }
        }
    }
}

@Serializable
data class VersionResponse(
    val version: String,
    val server: String,
    val docs: String,
)

/** Probe used by `studio doctor`: returns a small map describing the local runtime. */
fun doctorSnapshot(host: String, port: Int): Map<String, String> {
    val out = mutableMapOf<String, String>()
    out["host"] = host
    out["port"] = port.toString()
    out["http"] = runCatching {
        val client = StudioHttpClient("http://$host:$port")
        runBlocking { client.health() }
        "reachable"
    }.getOrElse { "unreachable: ${it.message}" }
    return out
}
