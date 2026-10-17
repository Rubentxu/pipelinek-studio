# ADR-013 — SSE Tree-Events contract for the VS Code extension

**Status:** Accepted (R5 EVO-C.0 implementation, 2026-10-11)
**Context:** R5 EVO-C — VS Code extension consumes the studio
HTTP control plane (per ADR-012). The extension needs a live
event stream of experiment / run / snapshot mutations to render
the sidebar tree without polling.

## Decision

The studio exposes **one** SSE endpoint, `GET /events`, that
streams a fixed taxonomy of six event types. The event envelope
is the SSE spec (RFC: `id:`, `event:`, `data:` lines) with:

- `id:` is a monotonic sequence per process (the bus assigns it
  on publish; the publisher-supplied id is replaced).
- `event:` is the discriminator that the client's
  `addEventListener` dispatches on.
- `data:` is a JSON payload, one line, no embedded newlines.
- A `: ping` comment line every 30s for keep-alive (the VS Code
  extension uses this to distinguish "server is alive, no events"
  from "connection is dead").

The endpoint is `text/event-stream`. `Last-Event-ID` does **not**
resume history in the MVP. Every connection requires an authoritative
refresh. `GET /runs`, `GET /runs/{runId}` and per-run event reads exist;
experiment and snapshot collection reads are not currently exposed by the
HTTP module. Full hierarchy recovery therefore remains an integration gap,
not a capability supplied by this stream. The stream-open comment is
diagnostic, not a snapshot barrier.

## Taxonomy (locked)

| `event:` value | `data:` payload | Trigger |
|---|---|---|
| `experiment.created` | `{"id":"…","name":"…","createdAt":"…"}` | `CreateExperimentUseCase` success |
| `experiment.deleted` | `{"id":"…"}` | (future) experiment archive |
| `run.started` | `{"experimentId":"…","runId":"…","startedAt":"…"}` | `StartRunUseCase` runner admission after binding persistence; never refusal, reservation replay or dedup hit |
| `run.step.changed` | `{"runId":"…","stepId":"…","status":"RUNNING\|COMPLETED\|FAILED","durationMs":1234}` | reserved; no verified step payload projection wired yet |
| `run.completed` | `{"runId":"…","status":"OK\|FAILED\|CANCELLED","completedAt":"…"}` | read-only run.db `RunFinished` with a recognized outcome |
| `snapshot.created` | `{"experimentId":"…","snapshotId":"…","bytes":12345,"createdAt":"…"}` | `SealSnapshotUseCase` success |

The taxonomy is closed. Future event types are an ADR follow-up.

## Wire format

```
HTTP/1.1 200 OK
Content-Type: text/event-stream
Cache-Control: no-cache
Connection: keep-alive

: stream-open lastId=42

id: 43
event: experiment.created
data: {"id":"e1","name":"demo","createdAt":"2026-10-11T20:30:00Z"}

id: 44
event: run.started
data: {"experimentId":"e1","runId":"r1","startedAt":"2026-10-11T20:30:01Z"}

: ping

id: 45
event: run.completed
data: {"runId":"r1","status":"OK","completedAt":"2026-10-11T20:30:05Z"}
```

`data:` lines are JSON, one object per line. The studio never
embeds newlines in a `data:` line (they would terminate the
event per the SSE spec). All control characters are escaped
(`\n`, `\r`, `\t`, `\\`, `\"`).

## Reconnect protocol

The client reconnects with the most recent `id` it observed:

```
GET /events
Last-Event-ID: 45
```

The bus does not replay history, including when Last-Event-ID is sent.
Refresh the tree on every connection, then reconcile live notifications.
A slow subscriber's 64-event buffer overflowing closes its stream;
the client reconnects and refreshes rather than silently losing changes.
Cancelled subscriptions are removed from the bus.

## Backoff

The VS Code extension reconnects with exponential backoff:
1s → 2s → 4s → 8s → 16s → 30s cap. The first attempt after a
successful `: ping` resets the backoff to 1s. The 30s cap is
in line with the polling fallback spec (R5 §6).

## Why SSE (not WebSocket)?

- SSE is one-way (server → client) which matches the mutation
  notification pattern. The extension doesn't push events to
  the studio; it uses the existing HTTP API for actions.
- SSE works through the same HTTP stack (Host allow-list, TLS,
  rate limit) without a separate upgrade. The extension's
  `fetch` + `EventSource` is enough; no new dependency.
- SSE is supported in the VS Code extension host Node 20 with
  the WHATWG `EventSource` built-in.

## Why not polling?

The R5 spec chose SSE because polling 1000 experiments every
5s = 200 req/s of pure noise; SSE pushes 1 event per mutation
with a 30s heartbeat of zero events. The cost model is
`O(mutations) + O(1 heartbeat)` instead of `O(clients × polls)`.

## Production wiring

Both CLI and app boot share one `InMemoryTreeEventBus` per server.
Tests can pass `null` to retain the 501 NOT_WIRED fallback.
Experiment and snapshot use cases publish only after persistence.
Run admission publishes only after runner acceptance, not mere reservation.
An application-scoped IO coroutine polls existing run journal reads every
second, advancing per-run cursors and publishing recognized `RunFinished`
outcomes with their persisted timestamps (`success` → `OK`, `failure`/
`failed` → `FAILED`, `cancelled`/`canceled` → `CANCELLED`). Missing journals,
unknown outcomes, process exits and cancellation requests are not terminal
facts. The coroutine is cancelled with the hosting Ktor application.
Step events remain reserved until a verified step identity/duration payload
contract is available; experiment deletion remains a future capability.
The six-type taxonomy and 30-second ping are unchanged.

## Rejected alternatives

- **WebSocket**: bidirectional, more capable, but adds an
  upgrade + a separate transport for actions (which the
  extension already has via HTTP).
- **Long-poll on `GET /events?since=…`**: works but adds
  request overhead per poll; SSE keeps the connection open.
- **Polling on the existing `GET /experiments`**: simple but
  the cost model is `O(clients × polls)`; we lose the
  mutation latency guarantee.

## Consequences

- The studio's HTTP server grows one endpoint (`GET /events`).
- The VS Code extension grows a `SseClient` that wraps
  `EventSource` with the backoff state machine + Last-Event-ID.
- The OpenAPI doc gains a `text/event-stream` schema for
  `GET /events` (the existing `/runs/{runId}/stream` already
  has this pattern).
- The bus is in-memory; a restart loses the sequence and
  forces the client to do a full refresh. A future iteration
  can persist a small ring buffer in `studio.db` (R5.7
  follow-up).

---

**Recibo generado por:** Agente (R5 EVO-C.A, sesión de octubre 2026).
**Spec:** `docs/04-roadmap/EVO-C_VSCODE_SPEC.md` §"SSE event types".
**Related:** [ADR-012 — VS Code seam](ADR-012-vscode-seam.md),
[EVO-C_VSCODE_SPEC](../04-roadmap/EVO-C_VSCODE_SPEC.md).
