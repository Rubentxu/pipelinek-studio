package dev.pipelinek.studio.application.ports

import dev.pipelinek.studio.domain.ExperimentId
import dev.pipelinek.studio.domain.RunId
import dev.pipelinek.studio.domain.SnapshotId
import java.time.Instant

/**
 * R5 EVO-C.0 (2026-10-11): the event taxonomy that the SSE
 * endpoint `GET /events` streams to the VS Code extension. Six
 * event types cover the full lifecycle of experiments, runs and
 * snapshots; each event carries a `kind` discriminator + a
 * `data` payload that the extension can render without a second
 * HTTP call.
 *
 * The events are intentionally minimal — they answer "did
 * something happen in the tree?" not "what are the full
 * details?". The extension can fetch the full entity via the
 * HTTP API after receiving the event (lazy loading).
 *
 * Event format (server-sent events, per the SSE spec):
 *   id: <sequence>
 *   event: <kind>
 *   data: <json payload>
 *
 * The `id` is a monotonically-increasing sequence per process; the
 * client can use it as `Last-Event-ID` to reconnect and resume.
 */
sealed interface TreeEvent {
    val id: Long
    val at: Instant

    /** The discriminator used in the `event:` SSE field. */
    val kind: String

    data class ExperimentCreated(
        override val id: Long,
        override val at: Instant,
        val experimentId: ExperimentId,
        val name: String,
    ) : TreeEvent {
        override val kind: String = "experiment.created"
    }

    data class ExperimentDeleted(
        override val id: Long,
        override val at: Instant,
        val experimentId: ExperimentId,
    ) : TreeEvent {
        override val kind: String = "experiment.deleted"
    }

    data class RunStarted(
        override val id: Long,
        override val at: Instant,
        val experimentId: ExperimentId,
        val runId: RunId,
    ) : TreeEvent {
        override val kind: String = "run.started"
    }

    data class RunStepChanged(
        override val id: Long,
        override val at: Instant,
        val runId: RunId,
        val stepId: String,
        val status: String,  // RUNNING | COMPLETED | FAILED
        val durationMs: Long,
    ) : TreeEvent {
        override val kind: String = "run.step.changed"
    }

    data class RunCompleted(
        override val id: Long,
        override val at: Instant,
        val runId: RunId,
        val status: String,  // OK | FAILED | CANCELLED
    ) : TreeEvent {
        override val kind: String = "run.completed"
    }

    data class SnapshotCreated(
        override val id: Long,
        override val at: Instant,
        val experimentId: ExperimentId,
        val snapshotId: SnapshotId,
        val bytes: Long,
    ) : TreeEvent {
        override val kind: String = "snapshot.created"
    }
}

/**
 * R5 EVO-C.0 (2026-10-11): the port that publishes + subscribes
 * to [TreeEvent]s. The HTTP layer subscribes (one
 * `ReceiveChannel<>` per SSE consumer); the use cases publish.
 *
 * `subscribe(afterId: Long?)` returns a coroutine channel that
 * will receive all events with `id > afterId` (or all events if
 * `afterId` is null). The channel is closed when the consumer
 * cancels its subscription.
 *
 * Default: the production boot wires an in-memory implementation.
 * Tests can wire a no-op or a recording impl.
 */
interface TreeEventBusPort {
    /** Publish an event. The bus assigns the id (monotonic per process). */
    suspend fun publish(event: TreeEvent)

    /** Subscribe to events with id > afterId (or all events if null). */
    fun subscribe(afterId: Long? = null): kotlinx.coroutines.channels.ReceiveChannel<TreeEvent>

    /** The most recent published id, or null if no events yet. */
    fun lastId(): Long?
}
