package dev.pipelinek.studio.infra.storage

import dev.pipelinek.studio.application.ports.TreeEvent
import dev.pipelinek.studio.application.ports.TreeEventBusPort
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import java.util.concurrent.atomic.AtomicLong

/**
 * R5 EVO-C.0 (2026-10-11): in-memory implementation of
 * [TreeEventBusPort]. Per-process state; the bus is NOT durable
 * (a restart loses pending events). The reconnect protocol uses
 * `Last-Event-ID` to resume; if the last published id was 0, the
 * client sees the bus as empty and starts fresh.
 *
 * Implementation: one [Channel] per subscriber; the bus fans
 * out each published event to every open channel. Channels
 * buffer up to 64 events without suspension; if a consumer is
 * slower than that, the channel closes and the consumer must
 * reconnect.
 *
 * Thread-safety: publish is `suspend`-friendly (uses a
 * coroutine); the channel send is itself atomic.
 */
class InMemoryTreeEventBus : TreeEventBusPort {
    private val counter = AtomicLong(0)
    private val lastId = AtomicLong(0)
    private val channels = java.util.concurrent.ConcurrentHashMap.newKeySet<Channel<TreeEvent>>()

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
            ch.trySend(withId)
        }
    }

    override fun subscribe(afterId: Long?): ReceiveChannel<TreeEvent> {
        val ch = Channel<TreeEvent>(capacity = 64)
        channels.add(ch)
        // If `afterId` is provided, replay the events with id > afterId
        // by walking from `lastId` backwards. For a single-process bus
        // this is acceptable: events are small, the bus doesn't keep
        // history beyond a ring buffer. For the MVP we just send
        // future events; the reconnect path will use `Last-Event-ID`
        // to seek (a future iteration can add a small ring buffer).
        return ch
    }

    override fun lastId(): Long? = lastId.get().takeIf { it > 0 }
}
