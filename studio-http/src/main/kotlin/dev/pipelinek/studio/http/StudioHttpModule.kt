package dev.pipelinek.studio.http

import dev.pipelinek.studio.application.usecases.WiringExt.createExperiment
import dev.pipelinek.studio.application.usecases.WiringExt.sealSnapshot
import dev.pipelinek.studio.application.usecases.WiringExt.startRun
import dev.pipelinek.studio.application.usecases.WiringExt.inspectRun
import dev.pipelinek.studio.application.usecases.buildWiring
import dev.pipelinek.studio.domain.*
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.header
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.*
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory

/**
 * Ktor module wiring HTTP routes to the application use cases.
 *
 * Per docs/02-specifications/HTTP_AND_STREAMING_API.md and R3 release protocol: routes
 * never invent outcomes. All errors are typed via [ErrorResponse].
 */
class StudioHttpModule(
    private val wiringFactory: () -> dev.pipelinek.studio.application.usecases.Wiring,
) {
    private val log = LoggerFactory.getLogger(javaClass)

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
            exception<IllegalArgumentException> { call, cause ->
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("BAD_REQUEST", cause.message ?: "bad request"))
            }
            exception<IllegalStateException> { call, cause ->
                call.respond(HttpStatusCode.Conflict, ErrorResponse("RUNTIME_UNAVAILABLE", cause.message ?: "runtime error"))
            }
            exception<Throwable> { call, cause ->
                log.error("uncaught", cause)
                call.respond(HttpStatusCode.InternalServerError, ErrorResponse("INTERNAL", cause.message ?: "internal"))
            }
        }
        app.routing {
            get("/health") {
                call.respond(HealthResponse(status = "ok"))
            }
            get("/api/version") {
                val wiring = wiringFactory()
                val versionResp = when (val r = wiring.runtime.describe()) {
                    is dev.pipelinek.studio.application.ports.RuntimeDescriptorResult.Available -> VersionResponse(
                        studio = "0.1.0-alpha.1",
                        pipelinekRuntime = r.descriptor.version,
                        pipelinekRuntimeDigest = r.descriptor.artifactDigest,
                        pipelinekRuntimeCapabilities = r.descriptor.capabilities.toList(),
                    )
                    dev.pipelinek.studio.application.ports.RuntimeDescriptorResult.Unavailable -> VersionResponse(studio = "0.1.0-alpha.1")
                    is dev.pipelinek.studio.application.ports.RuntimeDescriptorResult.Failure -> VersionResponse(studio = "0.1.0-alpha.1")
                }
                call.respond(versionResp)
            }

            post("/experiments") {
                val req = call.receive<CreateExperimentRequest>()
                val wiring = wiringFactory()
                val uc = wiring.createExperiment
                val created = uc.execute(req.name)
                call.respond(
                    CreateExperimentResponse(
                        experimentId = created.id.value,
                        name = created.name,
                        createdAt = created.createdAt.toString(),
                    ),
                )
            }

            route("/experiments/{experimentId}") {
                post("/snapshots") {
                    val experimentId = ExperimentId(call.parameters["experimentId"]!!)
                    val req = call.receive<SealSnapshotRequest>()
                    val wiring = wiringFactory()
                    val uc = wiring.sealSnapshot
                    val snap = uc.execute(experimentId, req.source.toByteArray(Charsets.UTF_8), req.creator)
                    call.respond(
                        SealSnapshotResponse(
                            snapshotId = snap.id.value,
                            experimentId = snap.experimentId.value,
                            sha256 = snap.sha256.value,
                            createdAt = snap.createdAt.toString(),
                        ),
                    )
                }
            }

            post("/runs") {
                val idempotencyKey = call.request.header("Idempotency-Key")
                    ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("MISSING_HEADER", "Idempotency-Key required"))
                val req = call.receive<StartRunRequest>()
                val wiring = wiringFactory()
                val runId = RunId.new()
                val idemKey = IdempotencyKey(idempotencyKey)
                val digest = Sha256.digest("${req.experimentId}|${req.snapshotId}|${req.scenarioId}".toByteArray())
                val uc = wiring.startRun()
                val binding = uc.execute(
                    SnapshotId(req.snapshotId),
                    ScenarioId(req.scenarioId),
                    runId,
                    idemKey,
                    digest,
                )
                call.respond(
                    StartRunResponse(
                        runId = binding.runId.value,
                        state = binding.state.name,
                        upstreamRunId = binding.upstreamRunId?.value,
                    ),
                )
            }

            get("/runs/{runId}") {
                val runId = RunId(call.parameters["runId"]!!)
                val wiring = wiringFactory()
                when (val s = wiring.inspectRun().status(runId)) {
                    is dev.pipelinek.studio.application.ports.RunHandleStatus.Live ->
                        call.respond(RunStatusResponse(runId.value, "RUNNING", s.upstreamRunId))
                    is dev.pipelinek.studio.application.ports.RunHandleStatus.Terminal ->
                        call.respond(RunStatusResponse(runId.value, s.finalState))
                    is dev.pipelinek.studio.application.ports.RunHandleStatus.Unknown -> {
                        // Real state is in metadata — fall through to read port
                        when (val rs = wiring.inspectRun().events(runId, null, 1)) {
                            is dev.pipelinek.studio.application.ports.RunReadResult.Ok ->
                                call.respond(RunStatusResponse(runId.value, "STARTING"))
                            else -> call.respond(HttpStatusCode.Accepted, RunStatusResponse(runId.value, "UNKNOWN"))
                        }
                    }
                }
            }

            get("/runs/{runId}/events") {
                val runId = RunId(call.parameters["runId"]!!)
                val wiring = wiringFactory()
                when (val res = wiring.inspectRun().events(runId, null, 50)) {
                    is dev.pipelinek.studio.application.ports.RunReadResult.Ok ->
                        call.respond(res.events)
                    is dev.pipelinek.studio.application.ports.RunReadResult.Unknown -> {
                        call.respond(HttpStatusCode.Accepted, ErrorResponse("EVENTS_UNKNOWN", res.reason))
                    }
                }
            }

            get("/runs/{runId}/output") {
                val runId = RunId(call.parameters["runId"]!!)
                val wiring = wiringFactory()
                when (val res = wiring.inspectRun().output(runId, null, 16384)) {
                    is dev.pipelinek.studio.application.ports.OutputReadResult.Ok ->
                        call.respond(res.chunks)
                    is dev.pipelinek.studio.application.ports.OutputReadResult.Refused ->
                        call.respond(HttpStatusCode.Accepted, ErrorResponse("OUTPUT_REFUSED", res.reason))
                    is dev.pipelinek.studio.application.ports.OutputReadResult.UnknownStream ->
                        call.respond(HttpStatusCode.Accepted, ErrorResponse("OUTPUT_UNKNOWN_STREAM", res.reason))
                    is dev.pipelinek.studio.application.ports.OutputReadResult.RecoveryRequired ->
                        call.respond(HttpStatusCode.Accepted, ErrorResponse("OUTPUT_RECOVERY_REQUIRED", res.reason))
                }
            }
        }
    }
}
