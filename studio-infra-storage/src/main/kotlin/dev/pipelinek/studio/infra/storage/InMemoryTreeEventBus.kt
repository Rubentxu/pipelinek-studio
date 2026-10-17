package dev.pipelinek.studio.infra.storage

import dev.pipelinek.studio.application.ports.TreeEvent
import dev.pipelinek.studio.application.ports.TreeEventBusPort
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import java.util.concurrent.atomic.AtomicLong

/**
 * R5 EVO-C.0 (2026-10-11): in-memory implementation of
 * [TreeEventBusPort]. Per-process state; the bus is NOT durable
 * (a restart loses pending events). Reconnect requires authoritative
 * HTTP reads; Last-Event-ID does not request replay.
 *
 * Implementation: one [Channel] per subscriber; the bus fans
 * out each published event to every open channel. Channels
 * buffer up to 64 events without suspension; if a consumer is
 * slower than that, the channel closes and the consumer must
 * reconnect.
 *
 * Publication is serialized so concurrent writers deliver IDs in order.
 * Cancelled and overflowing subscriptions are removed immediately.
 */
class InMemoryTreeEventBus : TreeEventBusPort {
    private val counter = AtomicLong(0)
    private val lastId = AtomicLong(0)
    private val channels = java.util.concurrent.ConcurrentHashMap.newKeySet<Channel<TreeEvent>>()

    @Synchronized
    override fun publish(event: TreeEvent) {
        val assigned = counter.incrementAndGet()
        // Replace the event's id with the assigned sequence. The
        // publisher-supplied id is ignored (single-writer invariant
        // for the sequence).
        val withId = when (event) {
            is TreeEvent.ExperimentCreated -> event.copy(id = assigned)
            is TreeEvent.ExperimentDeleted -> event.copy(id = assigned)
            is TreeEvent.RunStarted -> event.copy(id = assigned)
            is TreeEvent.RunStepChanged -> event.copy(id = assigned)
            is TreeEvent.RunCompleted -> event.copy(id = assigned)
            is TreeEvent.SnapshotCreated -> event.copy(id = assigned)
        }
        lastId.set(assigned)
        for (ch in channels) {
            if (ch.trySend(withId).isFailure) {
                channels.remove(ch)
                ch.close()
            }
        }
    }

    override fun subscribe(afterId: Long?): ReceiveChannel<TreeEvent> {
        val ch = Channel<TreeEvent>(capacity = 64)
        channels.add(ch)
        ch.invokeOnClose { channels.remove(ch) }
        // Live notifications only; afterId is not a replay cursor.
        // Reconnecting consumers must refresh authoritative HTTP reads.
        return ch
    }

    override fun lastId(): Long? = lastId.get().takeIf { it > 0 }
}
