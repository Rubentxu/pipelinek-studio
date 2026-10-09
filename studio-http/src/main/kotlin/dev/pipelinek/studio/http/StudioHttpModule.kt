package dev.pipelinek.studio.http

import dev.pipelinek.studio.application.usecases.WiringExt.createExperiment
import dev.pipelinek.studio.application.usecases.WiringExt.sealSnapshot
import dev.pipelinek.studio.application.usecases.WiringExt.startRun
import dev.pipelinek.studio.application.usecases.WiringExt.inspectRun
import dev.pipelinek.studio.application.usecases.buildWiring
import dev.pipelinek.studio.domain.*
import dev.pipelinek.studio.application.ports.EventCursor
import dev.pipelinek.studio.application.ports.RunReadResult
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
import io.ktor.server.sse.SSE
import io.ktor.server.sse.sse
import kotlinx.coroutines.delay
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
    /**
     * Optional: when provided, the module wires a real `PipelineKRunReadPort` for
     * `GET /runs/{id}/events` and `/runs/{id}` so S1 read-side returns real events
     * from `run.db` instead of the S0 stub refusal. The wiring factory is consulted
     * first; if no SDK-backed read port is present, the factory's port is used.
     */
    private val runReadFactory: (() -> dev.pipelinek.studio.application.ports.RunReadPort)? = null,
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
        app.install(SSE)
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
                // Persist the run binding to Studio metadata so it survives restart. This is the
                // minimum-viable persistence per DATA_MODEL_AND_PERSISTENCE.md §"RunBinding".
                @Suppress("UNCHECKED_CAST")
                val metadataRepo = wiring.metadata as? dev.pipelinek.studio.infra.storage.StudioMetadataRepository
                metadataRepo?.persistRunBinding(binding)
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
                val reader = runReadFactory?.invoke()
                when (val s = wiring.inspectRun().status(runId)) {
                    is dev.pipelinek.studio.application.ports.RunHandleStatus.Live ->
                        call.respond(RunStatusResponse(runId.value, "RUNNING", s.upstreamRunId))
                    is dev.pipelinek.studio.application.ports.RunHandleStatus.Terminal ->
                        call.respond(RunStatusResponse(runId.value, s.finalState))
                    is dev.pipelinek.studio.application.ports.RunHandleStatus.Unknown -> {
                        // Real state from run.db if runReadFactory was wired in.
                        val probe = if (reader != null) reader.state(runId)
                            else wiring.inspectRun().events(runId, null, 1)
                        when (probe) {
                            is dev.pipelinek.studio.application.ports.RunReadResult.Ok ->
                                call.respond(RunStatusResponse(runId.value, "STARTING"))
                            else -> call.respond(HttpStatusCode.Accepted, RunStatusResponse(runId.value, "UNKNOWN"))
                        }
                    }
                }
            }

            get("/runs/{runId}/events") {
                val runId = RunId(call.parameters["runId"]!!)
                val reader = runReadFactory?.invoke()
                val wiring = wiringFactory()
                val res = if (reader != null) reader.events(runId, null, 50)
                    else wiring.inspectRun().events(runId, null, 50)
                when (res) {
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

            /**
             * SSE stream of events for a run. Closes after EOF or after 5 minutes of idleness.
             * For S1 this replaces the polling pattern; S2 adds backpressure + reconnect tokens.
             */
            sse("/runs/{runId}/stream") {
                val runId = RunId(call.parameters["runId"]!!)
                val reader = runReadFactory?.invoke()
                    ?: run {
                        send(
                            data = "{\"code\":\"READ_PORT_UNAVAILABLE\",\"message\":\"no SDK-backed RunReadPort wired\"}",
                            event = "error",
                        )
                        return@sse
                    }
                var cursor: EventCursor? = null
                val maxIterations = 600
                var emptyReads = 0
                val maxEmptyReads = 10 // close stream after 10 consecutive empty reads (5s idle)
                for (i in 0 until maxIterations) {
                    val res = reader.events(runId, cursor, 50)
                    if (res is RunReadResult.Ok) {
                        if (res.events.isEmpty()) {
                            emptyReads += 1
                            if (emptyReads >= maxEmptyReads) {
                                send(data = "{\"code\":\"SSE_IDLE_CLOSE\"}", event = "end")
                                return@sse
                            }
                            delay(500)
                        } else {
                            emptyReads = 0
                            for (event in res.events) {
                                val json = "{\"sequence\":${event.sequence},\"kind\":\"${event.kind}\",\"occurredAt\":\"${event.occurredAt}\",\"payload\":${'"'}${event.payload.toString().replace("\"", "'")}${'"'}}"
                                send(data = json, event = event.kind)
                                cursor = EventCursor(event.sequence)
                            }
                            cursor = res.nextSequence ?: cursor
                        }
                    } else {
                        send(data = res.toString(), event = "end")
                        return@sse
                    }
                }
                send(data = "{\"code\":\"SSE_TIMEOUT\"}", event = "end")
            }
        }
    }
}
