@file:Suppress("TooManyFunctions")

package dev.pipelinek.studio.http

import dev.pipelinek.studio.application.ports.EventCursor
import dev.pipelinek.studio.application.ports.LiveEvent
import dev.pipelinek.studio.application.ports.LiveEventStream
import dev.pipelinek.studio.domain.RunId
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.response.respondText
import io.ktor.server.response.respondTextWriter
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.produceIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withTimeoutOrNull
import java.time.format.DateTimeFormatter

/**
 * SFW-H3.4: the SSE endpoint that streams
 * [LiveEvent]s to web consumers. The HTTP transport
 * for the H3 stream — the load-bearing primitive
 * (H3.1) + the production sources (H3.2 local,
 * H3.3 remote) reach the wire via this route.
 *
 * ## Why SSE, not WebSocket
 *
 * SSE is a one-way stream — server pushes, client
 * receives. The H3.4 contract is a one-way stream
 * (the studio pushes events; the client consumes
 * them). SSE is the load-bearing choice: it is
 * one-way, it is HTTP, it is text-based, and it
 * is well-supported by every proxy and every
 * client library. A future H3 unit may add a
 * WebSocket route for two-way use cases; H3.4
 * ships the one-way SSE.
 *
 * ## Why the route is `/runs/{studioRunId}/stream`,
 *    not `/runs/{studioRunId}/events`
 *
 * The OpenAPI doc already declares
 * `GET /runs/{runId}/events` as "Event Plane
 * (paginated)" — the HTTP events endpoint that
 * returns a JSON page of past events. H3.4's SSE
 * route is `GET /runs/{runId}/stream` — the live
 * stream. The two are siblings: `/events` is "give
 * me the page at cursor=N", `/stream` is
 * "subscribe to the stream from cursor=N". A
 * consumer that wants the past events uses
 * `/events`; a consumer that wants the live stream
 * uses `/stream`.
 *
 * ## Protocol
 *
 * - Each event is `id: <sequence>\nevent:
 *   <kind>\ndata: <json>\n\n`.
 * - Every 30s a `: ping` comment line is sent
 *   (keep-alive) — the consumer can detect a dead
 *   connection by the absence of either an event or
 *   a ping.
 * - The client can use `Last-Event-ID` to
 *   reconnect; the consumer reconnects with
 *   `?cursor=<last-seen-id>` and the polling impl
 *   picks up from there.
 * - The SSE response is `text/event-stream` with
 *   `cache-control: no-cache` and
 *   `x-accel-buffering: no` (so proxies do not
 *   buffer the stream).
 *
 * ## Flow cancellation
 *
 * The handler produces the `Flow<LiveEvent>` into
 * a [ReceiveChannel] via [produceIn]; a client
 * disconnect cancels the coroutine scope, the
 * `produceIn` builder cancels the channel, and the
 * polling impl's `isActive` check propagates the
 * cancellation through the `channelFlow`'s
 * structured concurrency.
 *
 * ## Auth
 *
 * The same gate as the other HTTP routes (Host
 * allow-list + RateLimit). No additional auth is
 * required for the SSE stream itself.
 *
 * @param streamProvider the H3.1
 *   [LiveEventStream] provider. A `null` answer
 *   returns `501 NOT_WIRED` (mirroring
 *   [installTreeEventRoutes]' honest answer when
 *   the bus is not wired in tests).
 */
fun Route.installLiveEventRoutes(
    streamProvider: () -> LiveEventStream?,
) {
    get("/runs/{studioRunId}/stream") {
        val stream = streamProvider()
        if (stream == null) {
            // Per the contract, "501 NOT_WIRED" is the
            // honest answer when the stream is not
            // wired (e.g. tests that don't exercise
            // SSE). The client treats 501 as "polling
            // fallback" rather than a hard error.
            call.respondText(
                """{"code":"NOT_WIRED","message":"live event stream is not wired in this build"}""",
                ContentType.Application.Json,
                HttpStatusCode.NotImplemented,
            )
            return@get
        }

        val studioRunId = RunId(
            call.parameters["studioRunId"]
                ?: error("studioRunId path parameter is required"),
        )
        val fromSequence: Long = call.request.queryParameters["cursor"]?.toLongOrNull() ?: 0L

        call.response.headers.append("Cache-Control", "no-cache")
        call.response.headers.append("X-Accel-Buffering", "no")
        call.respondTextWriter(contentType = ContentType.Text.EventStream) {
            // The polling impl is a Flow<LiveEvent>;
            // the receive-channel adapter lets us
            // race each receive against the
            // keep-alive timeout (the same pattern
            // [installTreeEventRoutes] uses for the
            // tree-event bus).
            val writer = this
            coroutineScope {
                val ch: ReceiveChannel<LiveEvent> = stream
                    .subscribe(studioRunId, EventCursor(fromSequence))
                    .produceIn(this)
                try {
                    // Initial sync: send a `: stream-open`
                    // comment so the client can confirm
                    // the stream is alive (the first
                    // poll may take 250ms).
                    writer.write(": stream-open studioRunId=${studioRunId.value} fromSequence=$fromSequence\n\n")
                    writer.flush()
                    while (isActive) {
                        val result = withTimeoutOrNull(SSE_KEEPALIVE_TIMEOUT_MS) {
                            ch.receiveCatching()
                        }
                        if (result == null) {
                            // No event in 30s — send a
                            // keep-alive ping so the
                            // consumer can distinguish
                            // "server is alive, no
                            // events" from "connection
                            // is dead".
                            writer.write(": ping\n\n")
                            writer.flush()
                            continue
                        }
                        val event = result.getOrNull()
                        if (event == null) {
                            // The channel was closed
                            // (the polling impl's flow
                            // completed — the run has
                            // terminated with an `End`).
                            // Exit the loop and close
                            // the connection cleanly.
                            return@coroutineScope
                        }
                        writeSseEvent(writer, event)
                        writer.flush()
                    }
                } finally {
                    ch.cancel()
                }
            }
        }
    }
}

private fun writeSseEvent(writer: java.io.Writer, event: LiveEvent) {
    writer.write("id: ${event.sequence}\n")
    writer.write("event: ${event.kind}\n")
    val json = encodeEventData(event)
    writer.write("data: $json\n")
    writer.write("\n")
}

private fun encodeEventData(event: LiveEvent): String = buildString {
    append("{\"sequence\":")
    append(event.sequence)
    append(",\"kind\":")
    append(jsonString(event.kind))
    append(",\"occurredAt\":")
    append(jsonString(ISO.format(event.occurredAt)))
    append(",\"payload\":")
    append(encodeMap(event.payload))
    append("}")
}

private fun encodeMap(map: Map<String, String>): String = buildString {
    append("{")
    var first = true
    for ((k, v) in map) {
        if (!first) append(",")
        first = false
        append(jsonString(k))
        append(":")
        append(jsonString(v))
    }
    append("}")
}

/**
 * RFC-8259 §7: codepoints < 0x20 must be escaped
 * in a JSON string.
 */
private const val ASCII_CONTROL_LIMIT = 0x20

private fun jsonString(s: String): String = buildString {
    append("\"")
    for (c in s) {
        when (c) {
            '\\' -> append("\\\\")
            '"' -> append("\\\"")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> if (c.code < ASCII_CONTROL_LIMIT) {
                append("\\u%04x".format(c.code))
            } else {
                append(c)
            }
        }
    }
    append("\"")
}

private val ISO: DateTimeFormatter = DateTimeFormatter.ISO_INSTANT

/**
 * SFW-H3.4 (per ADR-013 §"Wire format"): SSE
 * keep-alive cadence. The client uses a `: ping`
 * line to distinguish "server is alive, no
 * events" from "connection is dead". 30s matches
 * the tree-event route's keep-alive; a future
 * H3 unit may make this configurable.
 */
private const val SSE_KEEPALIVE_TIMEOUT_MS = 30_000L
