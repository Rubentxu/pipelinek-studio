package dev.pipelinek.studio.http

import dev.pipelinek.studio.application.ports.TreeEvent
import dev.pipelinek.studio.domain.ExperimentId
import dev.pipelinek.studio.domain.RunId
import dev.pipelinek.studio.domain.SnapshotId
import dev.pipelinek.studio.infra.storage.InMemoryTreeEventBus
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * R5 EVO-C.0 (2026-10-11): integration tests for the
 * `GET /events` SSE endpoint.
 *  - 501 NOT_WIRED when the bus is null.
 *  - Bus contract: monotonic id, replay of last id, taxonomy.
 */
class TreeEventRoutesEvoC0Test {

    @Test
    fun `EVO-C0 not-wired returns 501 NOT_WIRED`() = testApplication {
        application {
            install(ContentNegotiation) { json(Json) }
            routing { installTreeEventRoutes(busProvider = { null }) }
        }
        val response = client.get("/events")
        assertEquals(HttpStatusCode.NotImplemented, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("NOT_WIRED"), "body must include NOT_WIRED, got: $body")
    }

    @Test
    fun `EVO-C0 event kinds match the EVO-C spec taxonomy`() {
        val expId = ExperimentId.new()
        val runId = RunId.new()
        val snapId = SnapshotId.new()
        val at = Instant.now()
        assertEquals("experiment.created", TreeEvent.ExperimentCreated(0, at, expId, "x").kind)
        assertEquals("experiment.deleted", TreeEvent.ExperimentDeleted(0, at, expId).kind)
        assertEquals("run.started", TreeEvent.RunStarted(0, at, expId, runId).kind)
        assertEquals("run.step.changed", TreeEvent.RunStepChanged(0, at, runId, "compile", "RUNNING", 100).kind)
        assertEquals("run.completed", TreeEvent.RunCompleted(0, at, runId, "OK").kind)
        assertEquals("snapshot.created", TreeEvent.SnapshotCreated(0, at, expId, snapId, 42).kind)
    }

    @Test
    fun `EVO-C0 bus assigns monotonic id on publish`() = runBlocking {
        val bus = InMemoryTreeEventBus()
        val expId = ExperimentId.new()
        bus.publish(TreeEvent.ExperimentCreated(0, Instant.now(), expId, "first"))
        bus.publish(TreeEvent.ExperimentCreated(0, Instant.now(), expId, "second"))
        bus.publish(TreeEvent.ExperimentCreated(0, Instant.now(), expId, "third"))
        // Subscribe AFTER the publishes — the in-memory bus does
        // not buffer past events in the MVP. Verify the assign
        // path by subscribing and publishing, then reading.
        val ch: Channel<TreeEvent> = Channel(8)
        val sub = bus.subscribe()
        bus.publish(TreeEvent.ExperimentCreated(0, Instant.now(), expId, "after-subscribe"))
        val received = withTimeoutOrNull(2_000) { sub.receive() }
        assertNotNull(received)
        assertEquals(4L, received.id, "id must be 1+1+1+1=4 (monotonic per process)")
    }

    @Test
    fun `EVO-C0 bus lastId is null when no events yet`() {
        val bus = InMemoryTreeEventBus()
        assertEquals(null, bus.lastId(), "empty bus must report null lastId")
    }

    @Test
    fun `EVO-C0 bus lastId reflects last published`() = runBlocking {
        val bus = InMemoryTreeEventBus()
        val expId = ExperimentId.new()
        assertEquals(null, bus.lastId())
        bus.publish(TreeEvent.ExperimentCreated(0, Instant.now(), expId, "x"))
        assertEquals(1L, bus.lastId())
        bus.publish(TreeEvent.ExperimentDeleted(0, Instant.now(), expId))
        assertEquals(2L, bus.lastId())
    }

    @Test
    fun `EVO-C0 bus delivers to multiple subscribers`() = runBlocking {
        val bus = InMemoryTreeEventBus()
        val expId = ExperimentId.new()
        val subA = bus.subscribe()
        val subB = bus.subscribe()
        bus.publish(TreeEvent.ExperimentCreated(0, Instant.now(), expId, "broadcast"))
        val a = withTimeout(2_000) { subA.receive() }
        val b = withTimeout(2_000) { subB.receive() }
        assertEquals("experiment.created", a.kind)
        assertEquals("experiment.created", b.kind)
        assertEquals(1L, a.id, "both subscribers must see the same monotonic id")
        assertEquals(1L, b.id)
    }
}
