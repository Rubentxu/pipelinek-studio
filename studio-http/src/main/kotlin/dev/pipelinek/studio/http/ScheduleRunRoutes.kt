package dev.pipelinek.studio.http

import dev.pipelinek.studio.application.ports.ScheduleRunResult
import dev.pipelinek.studio.application.ports.ScheduleRunUseCase
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.serialization.Serializable

/**
 * SFW-H5.4: the HTTP route for the H5.1
 * [dev.pipelinek.studio.application.ports.ScheduleRunUseCase].
 * The route is the user-facing surface for
 * "ask a worker pool to host a run".
 *
 * ## Status code mapping
 *
 * | [dev.pipelinek.studio.application.ports.ScheduleRunResult] arm | HTTP status | body |
 * |---|---|---|
 * | `Placed` | **200** | `{ workerId, region }` |
 * | `NoCapacity` | **507 Insufficient Storage** | `{ reason }` |
 * | `Refused` | **503** | `{ reason }` |
 * | `Unknown` | **202** | `{ reason }` |
 *
 * When [scheduleRunProvider] returns `null`
 * (a schedule use case without a capacity
 * port is meaningless), the route returns
 * **501 Not Implemented**.
 *
 * @param scheduleRunProvider the provider
 *   that returns the
 *   [dev.pipelinek.studio.application.ports.ScheduleRunUseCase]
 *   when wired, or `null` when the
 *   production boot did not wire the
 *   capacity port.
 */
fun Route.installScheduleRunRoutes(
    scheduleRunProvider: () -> ScheduleRunUseCase?,
) {
    route("/api/runs") {
        post("/schedule") {
            val scheduleRun = scheduleRunProvider()
            if (scheduleRun == null) {
                // The schedule use case is not wired.
                // 501 is honest about the unwired
                // state; the route does not invent
                // a fake 200.
                call.respond(
                    HttpStatusCode.NotImplemented,
                    mapOf("error" to "SCHEDULE_NOT_WIRED"),
                )
                return@post
            }
            val request = call.receive<ScheduleRunRequestDto>()
            val requirement = dev.pipelinek.studio.application.ports.WorkerRequirement(
                cpuMillicores = request.cpuMillicores,
                memoryMb = request.memoryMb,
                region = request.region,
                labels = request.labels,
            )
            when (val result = scheduleRun.schedule(requirement)) {
                is ScheduleRunResult.Placed -> call.respond(
                    HttpStatusCode.OK,
                    PlacedResponseDto(
                        workerId = result.workerId,
                        region = result.region,
                    ),
                )
                is ScheduleRunResult.NoCapacity -> call.respond(
                    HttpStatusCode.InsufficientStorage,
                    ScheduleNoCapacityResponseDto(reason = result.reason),
                )
                is ScheduleRunResult.Refused -> call.respond(
                    HttpStatusCode.ServiceUnavailable,
                    ScheduleRefusedResponseDto(reason = result.reason),
                )
                is ScheduleRunResult.Unknown -> call.respond(
                    HttpStatusCode.Accepted,
                    ScheduleUnknownResponseDto(reason = result.reason),
                )
            }
        }
    }
}

// --------------------------------------------------------------------
// DTOs
// --------------------------------------------------------------------

@Serializable
data class ScheduleRunRequestDto(
    val cpuMillicores: Int,
    val memoryMb: Int,
    val region: String? = null,
    val labels: Map<String, String> = emptyMap(),
)

@Serializable
data class PlacedResponseDto(
    val workerId: String,
    val region: String,
)

@Serializable
data class ScheduleNoCapacityResponseDto(
    val reason: String,
)

@Serializable
data class ScheduleRefusedResponseDto(
    val reason: String,
)

@Serializable
data class ScheduleUnknownResponseDto(
    val reason: String,
)
