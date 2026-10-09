package dev.pipelinek.studio.http

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.response.respondText

/**
 * S5 E5.4 — request body size limit. The full body limit plugin (ktor-server-body-limit)
 * is not on the classpath, so we enforce the policy in a single pre-routing interceptor
 * that consults `Content-Length`. Requests larger than [maxBytes] are rejected with
 * `413 Payload Too Large` and a JSON body. We respond with plain `respondText` rather
 * than `respond(map)` so the rejection works regardless of the ContentNegotiation
 * configuration installed by the caller.
 *
 * D-005 security profile: this is the bare minimum defense against a single client
 * DoSing the studio with multi-GB JSON bodies. Per-request validation of the parsed
 * payload (e.g. snapshot content size, scenario size) is still the responsibility of
 * the use case.
 */
fun Application.installBodySizeLimit(maxBytes: Long = DEFAULT_MAX_BYTES) {
    intercept(ApplicationCallPipeline.Plugins) {
        val cl = call.request.headers["Content-Length"]?.toLongOrNull()
        if (cl != null && cl > maxBytes) {
            call.response.status(HttpStatusCode.PayloadTooLarge)
            call.respondText(
                """{"code":"BODY_TOO_LARGE","message":"request body $cl bytes exceeds limit $maxBytes"}""",
                contentType = ContentType.Application.Json,
            )
            finish()
        }
    }
}

/** Default 1 MiB — generous for a JSON experiment / snapshot, defensive against abuse. */
const val DEFAULT_MAX_BYTES: Long = 1L * 1024 * 1024
