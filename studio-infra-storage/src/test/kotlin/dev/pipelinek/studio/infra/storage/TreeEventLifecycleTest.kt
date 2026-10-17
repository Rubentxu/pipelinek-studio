package dev.pipelinek.studio.infra.storage

import dev.pipelinek.studio.application.ports.TreeEvent
import dev.pipelinek.studio.domain.ExperimentId
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.test.*

class TreeEventLifecycleTest {
    @Test
    fun `overflow disconnects slow subscriber instead of silently losing changes`() = runBlocking {
        val bus = InMemoryTreeEventBus()
        val slow = bus.subscribe()
        repeat(65) { bus.publish(TreeEvent.ExperimentCreated(0, Instant.EPOCH, ExperimentId.new(), "e")) }
        repeat(64) { assertNotNull(slow.receiveCatching().getOrNull()) }
        assertTrue(slow.tryReceive().isClosed, "overflow must force authoritative refresh on reconnect")
        assertEquals(65, bus.lastId())
        slow.cancel()
    }

    @Test
    fun `cancelled subscriber does not affect subsequent delivery and no history is replayed`() = runBlocking {
        val bus = InMemoryTreeEventBus()
        val old = bus.subscribe()
        old.cancel()
        bus.publish(TreeEvent.ExperimentCreated(0, Instant.EPOCH, ExperimentId.new(), "old"))
        val fresh = bus.subscribe(0)
        assertTrue(fresh.tryReceive().isFailure)
        bus.publish(TreeEvent.ExperimentCreated(0, Instant.EPOCH, ExperimentId.new(), "new"))
        assertEquals(2, fresh.receive().id)
        fresh.cancel()
    }
}
