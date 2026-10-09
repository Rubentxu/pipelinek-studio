package dev.pipelinek.studio.http

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respond
import java.util.UUID

/**
 * S5 E5.4 hardening — strict route param validation. The Studio domain IDs
 * (ExperimentId, RunId, SnapshotId, ScenarioId) are UUID v4 strings; the
 * existing code happily wraps any non-blank string in the value class. This
 * helper enforces a real UUID parse and returns a structured 400 when the
 * caller hands us a malformed ID. The behaviour is uniform across all
 * route-param ID reads in [StudioHttpModule].
 */
object RouteParams {

    /** Matches a canonical 8-4-4-4-12 lowercase UUID with hyphens. */
    private val UUID_REGEX = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")

    /**
     * Validates that [raw] is a well-formed UUID v4-shaped string. Returns the
     * raw string on success; on failure responds 400 and returns null so the
     * caller can early-return from the route handler.
     */
    suspend fun requireUuid(call: ApplicationCall, name: String, raw: String?): String? {
        if (raw.isNullOrBlank() || !UUID_REGEX.matches(raw) || runCatching { UUID.fromString(raw) }.isFailure) {
            call.respond(
                HttpStatusCode.BadRequest,
                ErrorResponse("BAD_REQUEST", "route parameter '$name' is not a valid UUID: '$raw'"),
            )
            return null
        }
        return raw
    }
}
