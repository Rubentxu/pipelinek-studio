package dev.pipelinek.studio.http

import dev.pipelinek.studio.application.ports.TreeEvent
import dev.pipelinek.studio.application.ports.TreeEventBusPort
import io.ktor.http.ContentType
import io.ktor.server.application.call
import io.ktor.server.response.respondText
import io.ktor.server.response.respondTextWriter
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.withTimeoutOrNull
import java.time.format.DateTimeFormatter

/**
 * R5 EVO-C.0 (2026-10-11): the SSE endpoint that streams
 * [TreeEvent]s to the VS Code extension. Per the EVO-C spec,
 * the endpoint is `GET /events` with content-type
 * `text/event-stream`. The extension is the first consumer
 * beyond the SPA's polling.
 *
 * Protocol:
 *  - Each event is `id: <sequence>\nevent: <kind>\ndata: <json>\n\n`
 *  - Every 30s a `: ping` comment line is sent (keep-alive)
 *  - Live notifications only: Last-Event-ID does not replay history.
 *    Clients refresh authoritative HTTP reads on every connection.
 *
 * Auth: the same gate as the other HTTP routes (Host allow-list
 * + RateLimit). No additional auth is required for the SSE
 * stream itself.
 */
fun Route.installTreeEventRoutes(
    busProvider: () -> TreeEventBusPort?,
) {
    get("/events") {
        val bus = busProvider()
        if (bus == null) {
            // Per the spec, "501 NOT_WIRED" is the honest answer
            // when the bus is not wired (e.g. tests that don't
            // exercise SSE). The extension treats 501 as "polling
            // fallback" rather than a hard error.
            call.respondText(
                """{"code":"NOT_WIRED","message":"tree event bus is not wired in this build"}""",
                ContentType.Application.Json,
                io.ktor.http.HttpStatusCode.NotImplemented,
            )
            return@get
        }
        call.respondTextWriter(contentType = ContentType.Text.EventStream) {
            val ch: ReceiveChannel<TreeEvent> = bus.subscribe()
            try {
                // Diagnostic sequence only, not a snapshot or replay cursor.
                val lastId = bus.lastId() ?: 0L
                write(": stream-open lastId=$lastId\n\n")
                flush()
                while (true) {
                    // 30s keep-alive: a `withTimeoutOrNull` gives us
                    // a heartbeat tick even when no events flow.
                    val received = withTimeoutOrNull(SSE_KEEPALIVE_TIMEOUT_MS) { ch.receiveCatching() }
                    if (received?.isClosed == true) break
                    val ev = received?.getOrNull()
                    if (ev == null) {
                        write(": ping\n\n")
                        flush()
                        continue
                    }
                    writeSseEvent(this, ev)
                    flush()
                }
            } finally {
                ch.cancel()
            }
        }
    }
}

private fun writeSseEvent(writer: java.io.Writer, ev: TreeEvent) {
    // The `id` line is the monotonic sequence (used by Last-Event-ID).
    writer.write("id: ${ev.id}\n")
    // The `event` line is the discriminator (used by the client's
    // addEventListener to dispatch).
    writer.write("event: ${ev.kind}\n")
    // The `data` line is the JSON payload.
    val json = encodeEventData(ev)
    writer.write("data: $json\n")
    writer.write("\n")
}

private fun encodeEventData(ev: TreeEvent): String = when (ev) {
    is TreeEvent.ExperimentCreated ->
        """{"id":"${ev.experimentId.value}","name":${jsonString(ev.name)},"createdAt":"${isoAt(ev.at)}"}"""
    is TreeEvent.ExperimentDeleted ->
        """{"id":"${ev.experimentId.value}"}"""
    is TreeEvent.RunStarted ->
        """{"experimentId":"${ev.experimentId.value}","runId":"${ev.runId.value}","startedAt":"${isoAt(ev.at)}"}"""
    is TreeEvent.RunStepChanged ->
        """{"runId":"${ev.runId.value}","stepId":${jsonString(ev.stepId)},"status":${jsonString(ev.status)},"durationMs":${ev.durationMs}}"""
    is TreeEvent.RunCompleted ->
        """{"runId":"${ev.runId.value}","status":${jsonString(ev.status)},"completedAt":"${isoAt(ev.at)}"}"""
    is TreeEvent.SnapshotCreated ->
        """{"experimentId":"${ev.experimentId.value}","snapshotId":"${ev.snapshotId.value}","bytes":${ev.bytes},"createdAt":"${isoAt(ev.at)}"}"""
}

/**
 * R5 EVO-C.0 (2026-10-11): SSE keep-alive cadence per
 * ADR-013 §"Wire format". The client uses a `: ping` line
 * to distinguish "server is alive, no events" from
 * "connection is dead".
 */
private const val SSE_KEEPALIVE_TIMEOUT_MS = 30_000L

private val ISO = DateTimeFormatter.ISO_INSTANT

private fun isoAt(at: java.time.Instant): String = ISO.format(at)

/** RFC-8259 §7: codepoints < 0x20 must be escaped in a JSON string. */
private const val ASCII_CONTROL_LIMIT = 0x20

private fun jsonString(s: String): String {
    val sb = StringBuilder("\"")
    for (c in s) {
        when (c) {
            '\\' -> sb.append("\\\\")
            '"' -> sb.append("\\\"")
            '\n' -> sb.append("\\n")
            '\r' -> sb.append("\\r")
            '\t' -> sb.append("\\t")
            else -> if (c.code < ASCII_CONTROL_LIMIT) sb.append("\\u%04x".format(c.code)) else sb.append(c)
        }
    }
    sb.append("\"")
    return sb.toString()
}
