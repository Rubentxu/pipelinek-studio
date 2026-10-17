package dev.pipelinek.studio.http

import dev.pipelinek.studio.application.ports.*
import dev.pipelinek.studio.application.usecases.CreateExperimentUseCase
import dev.pipelinek.studio.application.usecases.SealSnapshotUseCase
import dev.pipelinek.studio.infra.storage.*
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.channels.Channel
import org.junit.jupiter.api.Test
import java.nio.file.Files
import kotlin.test.*

class TreeEventDeliveryTest {
    @Test
    fun `persisted mutations reach SSE and closed subscription ends response`() {
        val temp = Files.createTempDirectory("studio-tree-events-")
        try {
            StudioDatabase(temp.resolve("studio.db")).use { db ->
                val metadata = StudioMetadataRepository(db)
                val bus = InMemoryTreeEventBus()
                val source = "// sealed, never executed".toByteArray()
                val finiteBus = object : TreeEventBusPort by bus {
                    override fun subscribe(afterId: Long?): Channel<TreeEvent> {
                        val channel = bus.subscribe(afterId) as Channel<TreeEvent>
                        val experiment = CreateExperimentUseCase(metadata, treeEventBus = bus).execute("quote\"\nname")
                        val snapshot = SealSnapshotUseCase(metadata, treeEventBus = bus).execute(experiment.id, source, "test")
                        assertNotNull(metadata.findExperiment(experiment.id))
                        assertContentEquals(source, metadata.loadSnapshotSource(snapshot.id))
                        channel.close()
                        return channel
                    }
                }
                testApplication {
                    application { routing { installTreeEventRoutes { finiteBus } } }
                    val response = kotlinx.coroutines.withTimeout(2_000) { client.get("/events") }
                    assertEquals(HttpStatusCode.OK, response.status)
                    assertTrue(response.headers["Content-Type"]!!.startsWith("text/event-stream"))
                    val body = response.bodyAsText()
                    assertTrue(body.contains("id: 1\nevent: experiment.created\n"), body)
                    assertTrue(body.contains("\"name\":\"quote\\\"\\nname\""), body)
                    assertTrue(body.contains("id: 2\nevent: snapshot.created\n"), body)
                    assertTrue(body.contains("\"bytes\":${source.size}"), body)
                    assertFalse(body.contains(": ping"), "closed subscriptions must not become idle heartbeat loops")
                }
            }
        } finally {
            temp.toFile().deleteRecursively()
        }
    }
}
