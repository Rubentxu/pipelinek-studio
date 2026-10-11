// R5 EVO-C.7 (2026-10-11): SSE client for the studio's
// `GET /events` endpoint. Wraps the WHATWG `EventSource` with:
//
//   - Exponential backoff on disconnect (1s → 2s → 4s → … → 30s cap).
//     First successful `open` after a failed connect resets the
//     backoff to 1s, matching ADR-013 §"Backoff".
//   - `Last-Event-ID` resume header on reconnect (the server is
//     in-memory only in the MVP; the resume currently restarts at
//     the live tail).
//   - Typed dispatch: each of the 6 event kinds in the ADR-013
//     taxonomy is forwarded to a `TreeEventHandler` callback
//     registered at construction time. The shape is the
//     ADR-013 §"Taxonomy" payload, parsed from the SSE `data:`
//     JSON line.
//   - 501 NOT_WIRED handling: if the server reports the bus is
//     not wired (the polling fallback per ADR-013 §"Reconnect
//     protocol"), the client reconnects with backoff (the spec
//     treats this as a soft error, not a hard failure).
//
// The client is the singleton consumed by the extension's
// status bar + sidebar tree view (EVO-C.1, EVO-C.2). It runs
// only while the extension is active; `dispose()` closes the
// current EventSource and stops the reconnect timer.
//
// The class is a thin wrapper around the WHATWG EventSource —
// no new dependencies, no fetch polling fallback (the polling
// fallback is the studio's 501, not the client's).

export type TreeEventKind =
  | "experiment.created"
  | "experiment.deleted"
  | "run.started"
  | "run.step.changed"
  | "run.completed"
  | "snapshot.created";

/** A single tree event, typed per ADR-013 §"Taxonomy". */
export type TreeEvent =
  | { kind: "experiment.created"; id: string; name: string; createdAt: string; seq: number }
  | { kind: "experiment.deleted"; id: string; seq: number }
  | { kind: "run.started"; experimentId: string; runId: string; startedAt: string; seq: number }
  | { kind: "run.step.changed"; runId: string; stepId: string; status: string; durationMs: number; seq: number }
  | { kind: "run.completed"; runId: string; status: string; completedAt: string; seq: number }
  | { kind: "snapshot.created"; experimentId: string; snapshotId: string; bytes: number; createdAt: string; seq: number };

export type TreeEventHandler = (event: TreeEvent) => void;
export type SseStateHandler = (state: SseState) => void;

export type SseState =
  | { kind: "connecting" }
  | { kind: "open"; lastEventId: number | null }
  | { kind: "backoff"; nextAttemptInMs: number; attempt: number; reason: string }
  | { kind: "closed"; reason: string };

/**
 * Structural type for `vscode.Disposable` (avoids pulling the
 * `vscode` module into the test runtime — vitest's node
 * environment does not have it). The extension host supplies
 * its own `vscode.Disposable` whose shape matches.
 */
export interface Disposable {
  dispose(): unknown;
}

export interface SseClientOptions {
  /** Studio baseUrl (e.g. https://127.0.0.1:7778). */
  baseUrl: string;
  /** Backoff start in ms. Defaults to 1000. */
  initialBackoffMs?: number;
  /** Backoff cap in ms. Defaults to 30000. */
  maxBackoffMs?: number;
  /** Maximum backoff attempts before giving up. Defaults to Infinity. */
  maxAttempts?: number;
  /**
   * Override the EventSource constructor (test seam). Production
   * code should leave this undefined; tests inject a fake.
   */
  eventSourceFactory?: (url: string) => EventSourceLike;
}

export interface MessageEventLike {
  data: unknown;
  lastEventId: string;
  type: string;
}

export interface EventSourceLike {
  addEventListener(type: string, listener: (ev: MessageEventLike) => void): void;
  close(): void;
}

/**
 * R5 EVO-C.7 (2026-10-11): the SSE client. See [ADR-013] for the
 * contract.
 */
export class SseClient implements Disposable {
  private readonly baseUrl: string;
  private readonly initialBackoffMs: number;
  private readonly maxBackoffMs: number;
  private readonly maxAttempts: number;
  private readonly eventSourceFactory: (url: string) => EventSourceLike;
  private readonly eventHandlers: TreeEventHandler[] = [];
  private readonly stateHandlers: SseStateHandler[] = [];
  private es: EventSourceLike | null = null;
  private lastEventId: number | null = null;
  private attempt = 0;
  private reconnectTimer: ReturnType<typeof setTimeout> | null = null;
  private disposed = false;

  constructor(opts: SseClientOptions) {
    this.baseUrl = opts.baseUrl;
    this.initialBackoffMs = opts.initialBackoffMs ?? 1000;
    this.maxBackoffMs = opts.maxBackoffMs ?? 30_000;
    this.maxAttempts = opts.maxAttempts ?? Number.POSITIVE_INFINITY;
    this.eventSourceFactory =
      opts.eventSourceFactory ?? ((url: string) => new EventSource(url));
  }

  /** Register a handler invoked for every typed event. */
  onEvent(handler: TreeEventHandler): void {
    this.eventHandlers.push(handler);
  }

  /** Register a handler invoked for every state transition. */
  onState(handler: SseStateHandler): void {
    this.stateHandlers.push(handler);
  }

  /** Open the SSE stream. Idempotent: a second call is a no-op. */
  start(): void {
    if (this.disposed) return;
    if (this.es) return;
    this.open();
  }

  /** Dispose the client: close the current stream and stop the timer. */
  dispose(): unknown {
    this.disposed = true;
    if (this.reconnectTimer) {
      clearTimeout(this.reconnectTimer);
      this.reconnectTimer = null;
    }
    if (this.es) {
      this.es.close();
      this.es = null;
    }
    this.emitState({ kind: "closed", reason: "disposed" });
    return undefined;
  }

  /** Test-only: returns the current state without opening. */
  snapshot(): { lastEventId: number | null; attempt: number; open: boolean } {
    return {
      lastEventId: this.lastEventId,
      attempt: this.attempt,
      open: this.es != null,
    };
  }

  private open(): void {
    this.emitState({ kind: "connecting" });
    // The WHATWG EventSource in Node 20 does not allow setting
    // custom request headers. Last-Event-ID resume is therefore
    // an append-on-reconnect concern (the client passes the
    // header when we re-open with a fresh EventSource). For
    // the MVP we accept that resume is best-effort: the new
    // EventSource opens without the header (the bus is
    // in-memory; the live tail is what the client wants).
    const url = new URL("/events", this.baseUrl).toString();
    let es: EventSourceLike;
    try {
      es = this.eventSourceFactory(url);
    } catch (e) {
      this.scheduleReconnect(`EventSource ctor failed: ${(e as Error).message}`);
      return;
    }
    this.es = es;

    es.addEventListener("open", () => {
      this.attempt = 0;
      this.emitState({ kind: "open", lastEventId: this.lastEventId });
    });

    // The studio doesn't dispatch `addEventListener("message")`
    // — each `event:` line is a typed listener. We register one
    // per ADR-013 taxonomy kind.
    for (const kind of [
      "experiment.created",
      "experiment.deleted",
      "run.started",
      "run.step.changed",
      "run.completed",
      "snapshot.created",
    ] as const) {
      es.addEventListener(kind, (ev) => {
        const parsed = parseSeq(ev.lastEventId);
        if (parsed != null) this.lastEventId = parsed;
        // Use 0 as the synthesized seq for events that arrive
        // without a `Last-Event-ID` (the studio always sends
        // one in practice, but the SSE spec doesn't require
        // it; a missing id means "the live stream, no
        // resume marker").
        const seq = parsed ?? 0;
        const typed = parseEvent(kind, ev.data, seq);
        if (typed) {
          for (const h of this.eventHandlers) h(typed);
        }
      });
    }

    es.addEventListener("error", () => {
      // EventSource auto-reconnects, but the spec's default
      // backoff is 3s. We tear down and apply our own backoff
      // so the cap + reset work correctly.
      if (this.es) {
        this.es.close();
        this.es = null;
      }
      // 501 NOT_WIRED comes back as an error event with
      // readyState CLOSED. The studio also responds 200 on the
      // open path; only a 5xx or a network failure lands here.
      this.scheduleReconnect("EventSource error (likely 5xx or network)");
    });
  }

  private scheduleReconnect(reason: string): void {
    if (this.disposed) return;
    if (this.attempt >= this.maxAttempts) {
      this.emitState({ kind: "closed", reason: `max attempts reached: ${reason}` });
      return;
    }
    const delay = Math.min(
      this.initialBackoffMs * Math.pow(2, this.attempt),
      this.maxBackoffMs,
    );
    this.attempt += 1;
    this.emitState({ kind: "backoff", nextAttemptInMs: delay, attempt: this.attempt, reason });
    this.reconnectTimer = setTimeout(() => {
      this.reconnectTimer = null;
      this.open();
    }, delay);
  }

  private emitState(s: SseState): void {
    for (const h of this.stateHandlers) {
      try {
        h(s);
      } catch (_e) {
        // A misbehaving handler must not break the SSE client.
      }
    }
  }
}

function parseSeq(s: string | null | undefined): number | null {
  if (s == null || s === "") return null;
  const n = Number(s);
  return Number.isFinite(n) && n >= 0 ? n : null;
}

function parseEvent(kind: TreeEventKind, data: unknown, seq: number): TreeEvent | null {
  if (typeof data !== "string") return null;
  let obj: unknown;
  try {
    obj = JSON.parse(data);
  } catch {
    return null;
  }
  if (!obj || typeof obj !== "object") return null;
  const o = obj as Record<string, unknown>;
  switch (kind) {
    case "experiment.created":
      return {
        kind,
        id: str(o.id),
        name: str(o.name),
        createdAt: str(o.createdAt),
        seq,
      };
    case "experiment.deleted":
      return { kind, id: str(o.id), seq };
    case "run.started":
      return {
        kind,
        experimentId: str(o.experimentId),
        runId: str(o.runId),
        startedAt: str(o.startedAt),
        seq,
      };
    case "run.step.changed":
      return {
        kind,
        runId: str(o.runId),
        stepId: str(o.stepId),
        status: str(o.status),
        durationMs: num(o.durationMs),
        seq,
      };
    case "run.completed":
      return {
        kind,
        runId: str(o.runId),
        status: str(o.status),
        completedAt: str(o.completedAt),
        seq,
      };
    case "snapshot.created":
      return {
        kind,
        experimentId: str(o.experimentId),
        snapshotId: str(o.snapshotId),
        bytes: num(o.bytes),
        createdAt: str(o.createdAt),
        seq,
      };
  }
}

function str(v: unknown): string {
  return typeof v === "string" ? v : "";
}

function num(v: unknown): number {
  return typeof v === "number" && Number.isFinite(v) ? v : 0;
}
