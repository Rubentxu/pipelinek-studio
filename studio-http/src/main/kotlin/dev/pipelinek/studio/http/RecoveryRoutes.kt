package dev.pipelinek.studio.http

import dev.pipelinek.studio.application.ports.RecoveryBindingFailure
import dev.pipelinek.studio.application.ports.RecoveryResult
import dev.pipelinek.studio.application.ports.RecoveryTransition
import dev.pipelinek.studio.application.ports.RecoveryUseCase
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.serialization.Serializable

/**
 * SFW-H4.6: the HTTP route for the H4.5
 * [RecoveryUseCase]. The route is the
 * user-facing surface for "I have N bindings,
 * what does Fabric think about them?"; the
 * operator hits `POST /api/admin/recovery`
 * and gets back a 4-arm [RecoveryResult]
 * rendered as 4 status codes.
 *
 * ## Status code mapping
 *
 * | [RecoveryResult] arm | HTTP status | body |
 * |---|---|---|
 * | `Recovered` | **200** | `{ total, unchanged, transitioned: [...] }` |
 * | `PartiallyRecovered` | **207 Multi-Status** | `{ total, unchanged, transitioned: [...], unknown: [...], refused: [...] }` |
 * | `NoBindings` | **200** | `{ total: 0, unchanged: 0 }` |
 * | `Refused` | **503 Service Unavailable** | `{ reason }` |
 *
 * When [recoveryProvider] returns `null`
 * (a recovery use case without a bindings
 * query is meaningless), the route returns
 * **501 Not Implemented**. The 501 is
 * honest about the unwired state; the route
 * does not invent a fake 200.
 *
 * ## Why `NoBindings` is 200 (not 204)
 *
 * The operator wants the body
 * (`{ total: 0, unchanged: 0 }`) so they
 * can distinguish `NoBindings` from
 * `Recovered(0, 0, [])`. 204 No Content
 * would hide the body.
 *
 * ## Why `PartiallyRecovered` is 207 (not 200)
 *
 * 207 Multi-Status is the standard way to
 * signal a mixed-result response (some
 * bindings succeeded, some were refused).
 * 200 would hide the breakdown from the
 * status code.
 *
 * @param recoveryProvider the provider
 *   that returns the [RecoveryUseCase]
 *   when wired, or `null` when the
 *   production boot did not wire the
 *   recovery use case. The production
 *   boot passes
 *   `{ wiringFactory().recoverAll() }`;
 *   tests pass a fake provider.
 */
fun Route.installRecoveryRoutes(
    recoveryProvider: () -> RecoveryUseCase?,
) {
    route("/api/admin") {
        post("/recovery") {
            val recovery = recoveryProvider()
            if (recovery == null) {
                // The recovery use case is not wired.
                // 501 is honest about the unwired state;
                // the route does not invent a fake 200.
                call.respond(
                    HttpStatusCode.NotImplemented,
                    mapOf("error" to "RECOVERY_NOT_WIRED"),
                )
                return@post
            }
            when (val result = recovery.recoverAll()) {
                is RecoveryResult.Recovered -> call.respond(
                    HttpStatusCode.OK,
                    result.toRecoveredDto(),
                )
                is RecoveryResult.PartiallyRecovered -> call.respond(
                    HttpStatusCode.MultiStatus,
                    result.toPartiallyRecoveredDto(),
                )
                is RecoveryResult.NoBindings -> call.respond(
                    HttpStatusCode.OK,
                    NoBindingsResponseDto(total = 0, unchanged = 0),
                )
                is RecoveryResult.Refused -> call.respond(
                    HttpStatusCode.ServiceUnavailable,
                    RefusedResponseDto(reason = result.reason),
                )
            }
        }
    }
}

// --------------------------------------------------------------------
// DTOs
// --------------------------------------------------------------------

@Serializable
data class RecoveryTransitionDto(
    val studioRunId: String,
    val previousState: String,
    val newState: String,
    val reason: String,
)

@Serializable
data class RecoveryBindingFailureDto(
    val studioRunId: String,
    val reason: String,
)

@Serializable
data class RecoveredResponseDto(
    val total: Int,
    val unchanged: Int,
    val transitioned: List<RecoveryTransitionDto>,
)

@Serializable
data class PartiallyRecoveredResponseDto(
    val total: Int,
    val unchanged: Int,
    val transitioned: List<RecoveryTransitionDto>,
    val unknown: List<RecoveryBindingFailureDto>,
    val refused: List<RecoveryBindingFailureDto>,
)

@Serializable
data class NoBindingsResponseDto(
    val total: Int,
    val unchanged: Int,
)

@Serializable
data class RefusedResponseDto(
    val reason: String,
)

// --------------------------------------------------------------------
// Mappers
// --------------------------------------------------------------------

private fun RecoveryTransition.toDto(): RecoveryTransitionDto =
    RecoveryTransitionDto(
        studioRunId = studioRunId.value,
        previousState = previousState.name,
        newState = newState.name,
        reason = reason,
    )

private fun RecoveryBindingFailure.toDto(): RecoveryBindingFailureDto =
    RecoveryBindingFailureDto(
        studioRunId = studioRunId.value,
        reason = reason,
    )

private fun RecoveryResult.Recovered.toRecoveredDto(): RecoveredResponseDto =
    RecoveredResponseDto(
        total = total,
        unchanged = unchanged,
        transitioned = transitioned.map { it.toDto() },
    )

private fun RecoveryResult.PartiallyRecovered.toPartiallyRecoveredDto(): PartiallyRecoveredResponseDto =
    PartiallyRecoveredResponseDto(
        total = total,
        unchanged = unchanged,
        transitioned = transitioned.map { it.toDto() },
        unknown = unknown.map { it.toDto() },
        refused = refused.map { it.toDto() },
    )
