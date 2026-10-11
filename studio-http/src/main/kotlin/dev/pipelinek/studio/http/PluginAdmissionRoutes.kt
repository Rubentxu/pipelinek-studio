package dev.pipelinek.studio.http

import dev.pipelinek.studio.application.ports.PluginAdmissionState
import dev.pipelinek.studio.application.usecases.PluginAdmissionUseCase
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.serialization.Serializable
import java.nio.file.Path

/**
 * R2.10 (2026-10-11): HTTP routes for the plugin admission lifecycle.
 *
 * Three operations on each plugin JAR:
 *   - GET  /api/plugins                       → list all (state per JAR)
 *   - GET  /api/plugins/{jar}                 → state of a single JAR
 *   - POST /api/plugins/{jar}/admit           → COMPATIBLE → ADMITTED
 *   - POST /api/plugins/{jar}/refuse          → COMPATIBLE → REFUSED (per-invocation)
 *   - POST /api/plugins/{jar}/revoke          → ADMITTED → COMMITTED (lock entry removed)
 *
 * When [pluginAdmission] is not wired (null in [Wiring]), the routes
 * return 501 Not Implemented. The HTTP layer must call
 * [installPluginAdmissionRoutes] only when the wiring is present
 * — the routes themselves check and return 501 if not.
 *
 * Per ROADMAP R2 item 10: "Add HTTP endpoints + MCP tools only when
 * really connected." The 501 fallback is the honest way to expose
 * the route surface without forcing every consumer to handle the
 * "no admission gate wired" case explicitly.
 */
@Serializable
data class PluginAdmissionStateDto(
    val pluginJar: String,
    val state: String,
    val reason: String? = null,
    val sha256: String? = null,
    val admittedAt: String? = null,
    val sdkVersion: String? = null,
    val vendorMatch: Boolean? = null,
)

@Serializable
data class AdmitRequestDto(
    val admittedBy: String,
)

@Serializable
data class RefuseRequestDto(
    val reason: String,
)

private fun PluginAdmissionState.toDto(): PluginAdmissionStateDto = when (this) {
    is PluginAdmissionState.Discovered -> PluginAdmissionStateDto(
        pluginJar = pluginJar,
        state = "discovered",
        sha256 = sha256,
    )
    is PluginAdmissionState.Compatible -> PluginAdmissionStateDto(
        pluginJar = pluginJar,
        state = "compatible",
        sdkVersion = sdkVersion,
        vendorMatch = vendorMatch,
    )
    is PluginAdmissionState.Admitted -> PluginAdmissionStateDto(
        pluginJar = pluginJar,
        state = "admitted",
        sha256 = sha256,
        admittedAt = admittedAt.toString(),
    )
    is PluginAdmissionState.Refused -> PluginAdmissionStateDto(
        pluginJar = pluginJar,
        state = "refused",
        reason = reason,
    )
    is PluginAdmissionState.Unavailable -> PluginAdmissionStateDto(
        pluginJar = pluginJar,
        state = "unavailable",
        reason = reason,
    )
}

/**
 * R2.10 (2026-10-11): install the plugin admission routes. The
 * caller passes the wiring; routes route to the use case when
 * present, otherwise 501.
 *
 * The `pluginDir` is the on-disk directory the use case inspects;
 * the route handlers resolve `pluginJar` (a filename) into the
 * full path via `<pluginDir>/<pluginJar>`. The `listAll` scan
 * is inlined here (rather than via [DirectoryPluginAdmissionPort])
 * to avoid the cross-module dep: studio-http does not depend on
 * studio-infra-sdk (and cannot, because the latter already
 * depends on studio-http).
 */
fun Route.installPluginAdmissionRoutes(
    useCaseProvider: () -> PluginAdmissionUseCase?,
    pluginDir: () -> Path,
) {
    route("/api/plugins") {
        get {
            val uc = useCaseProvider()
            if (uc == null) {
                call.respond(HttpStatusCode.NotImplemented, mapOf("code" to "NOT_WIRED", "message" to "plugin admission is not wired in this build"))
                return@get
            }
            val dir = pluginDir()
            val states = if (java.nio.file.Files.isDirectory(dir)) {
                java.nio.file.Files.list(dir).use { stream ->
                    stream.filter { it.fileName.toString().endsWith(".jar") }
                        .filter { java.nio.file.Files.isRegularFile(it) }
                        .map { uc.inspect(it).toDto() }
                        .toList()
                }
            } else emptyList()
            call.respond(mapOf("plugins" to states))
        }

        route("/{jar}") {
            get {
                val uc = useCaseProvider()
                if (uc == null) {
                    call.respond(HttpStatusCode.NotImplemented, mapOf("code" to "NOT_WIRED"))
                    return@get
                }
                val jar = call.parameters["jar"] ?: return@get call.respond(HttpStatusCode.BadRequest, mapOf("code" to "MISSING_JAR"))
                val state = uc.inspect(pluginDir().resolve(jar)).toDto()
                call.respond(state)
            }

            post("/admit") {
                val uc = useCaseProvider()
                if (uc == null) {
                    call.respond(HttpStatusCode.NotImplemented, mapOf("code" to "NOT_WIRED"))
                    return@post
                }
                val jar = call.parameters["jar"] ?: return@post call.respond(HttpStatusCode.BadRequest, mapOf("code" to "MISSING_JAR"))
                val req = try { call.receive<AdmitRequestDto>() } catch (e: Exception) {
                    return@post call.respond(HttpStatusCode.BadRequest, mapOf("code" to "BAD_REQUEST", "message" to (e.message ?: "bad request")))
                }
                val state = uc.admit(pluginDir().resolve(jar), req.admittedBy).toDto()
                call.respond(state)
            }

            post("/refuse") {
                val uc = useCaseProvider()
                if (uc == null) {
                    call.respond(HttpStatusCode.NotImplemented, mapOf("code" to "NOT_WIRED"))
                    return@post
                }
                val jar = call.parameters["jar"] ?: return@post call.respond(HttpStatusCode.BadRequest, mapOf("code" to "MISSING_JAR"))
                val req = try { call.receive<RefuseRequestDto>() } catch (e: Exception) {
                    return@post call.respond(HttpStatusCode.BadRequest, mapOf("code" to "BAD_REQUEST", "message" to (e.message ?: "bad request")))
                }
                val state = uc.refuse(pluginDir().resolve(jar), req.reason).toDto()
                call.respond(state)
            }

            post("/revoke") {
                val uc = useCaseProvider()
                if (uc == null) {
                    call.respond(HttpStatusCode.NotImplemented, mapOf("code" to "NOT_WIRED"))
                    return@post
                }
                val jar = call.parameters["jar"] ?: return@post call.respond(HttpStatusCode.BadRequest, mapOf("code" to "MISSING_JAR"))
                val state = uc.revoke(pluginDir().resolve(jar)).toDto()
                call.respond(state)
            }
        }
    }
}
