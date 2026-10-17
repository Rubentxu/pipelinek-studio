import { fetchWithTimeout, resolveTimeoutMs, validateStudioUrl } from "./config";

export type TreeEventKind =
  | "experiment.created" | "experiment.deleted" | "run.started"
  | "run.step.changed" | "run.completed" | "snapshot.created";

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
export interface Disposable { dispose(): unknown }
export interface MessageEventLike { data: unknown; lastEventId: string; type: string }
export interface EventSourceLike {
  addEventListener(type: string, listener: (ev: MessageEventLike) => void): void;
  close(): void;
}
export interface SseClientOptions {
  baseUrl: string;
  headers?: Record<string, string>;
  initialBackoffMs?: number;
  maxBackoffMs?: number;
  maxAttempts?: number;
  /** Header deadline, using the existing HTTP timeout policy (default 5s). */
  connectTimeoutMs?: number;
  /** No bytes received within this interval means disconnected. Default 45s. */
  heartbeatTimeoutMs?: number;
  /** Legacy test seam; production uses fetch so headers and comments are observable. */
  eventSourceFactory?: (url: string) => EventSourceLike;
}

const KINDS: TreeEventKind[] = ["experiment.created", "experiment.deleted", "run.started", "run.step.changed", "run.completed", "snapshot.created"];
const MAX_FRAME_CHARS = 256 * 1024;

/** Node 20+ streaming SSE. Reopen is a signal to refresh the authoritative snapshot. */
export class SseClient implements Disposable {
  private readonly opts: SseClientOptions;
  private readonly initialBackoffMs: number;
  private readonly maxBackoffMs: number;
  private readonly eventHandlers: TreeEventHandler[] = [];
  private readonly stateHandlers: SseStateHandler[] = [];
  private es: EventSourceLike | null = null;
  private controller: AbortController | null = null;
  private reconnectTimer: ReturnType<typeof setTimeout> | null = null;
  private heartbeatTimer: ReturnType<typeof setTimeout> | null = null;
  private connected = false;
  private lastEventId: number | null = null;
  private attempt = 0;
  private disposed = false;

  constructor(opts: SseClientOptions) {
    validateStudioUrl(opts.baseUrl);
    this.opts = opts;
    this.initialBackoffMs = positive(opts.initialBackoffMs, 1000);
    this.maxBackoffMs = positive(opts.maxBackoffMs, 30_000);
  }
  onEvent(handler: TreeEventHandler): void { this.eventHandlers.push(handler); }
  onState(handler: SseStateHandler): void { this.stateHandlers.push(handler); }
  start(): void {
    if (!this.disposed && !this.controller && !this.reconnectTimer) this.open();
  }
  dispose(): unknown {
    if (this.disposed) return;
    this.disposed = true;
    if (this.reconnectTimer) clearTimeout(this.reconnectTimer);
    this.reconnectTimer = null;
    this.closeTransport();
    this.emitState({ kind: "closed", reason: "disposed" });
    return undefined;
  }
  snapshot(): { lastEventId: number | null; attempt: number; open: boolean } {
    return { lastEventId: this.lastEventId, attempt: this.attempt, open: this.connected };
  }
  private closeTransport(): void {
    this.connected = false;
    if (this.heartbeatTimer) clearTimeout(this.heartbeatTimer);
    this.heartbeatTimer = null;
    const controller = this.controller;
    this.controller = null;
    controller?.abort();
    this.es?.close();
    this.es = null;
  }
  private open(): void {
    const controller = new AbortController();
    this.controller = controller;
    this.emitState({ kind: "connecting" });
    if (!this.active(controller)) return;
    const url = new URL("/events", this.opts.baseUrl).toString();
    if (this.opts.eventSourceFactory) {
      try {
        const es = this.opts.eventSourceFactory(url);
        this.es = es;
        es.addEventListener("open", () => {
          if (this.active(controller)) this.didOpen();
        });
        for (const kind of KINDS) es.addEventListener(kind, ev => {
          if (this.active(controller)) this.dispatch(kind, ev.data, ev.lastEventId);
        });
        es.addEventListener("error", () => this.fail(controller, "EventSource error (likely 5xx or network)"));
      } catch {
        this.fail(controller, "EventSource ctor failed");
      }
    } else {
      void this.stream(url, controller);
    }
  }
  private active(controller: AbortController): boolean {
    return !this.disposed && this.controller === controller;
  }
  private didOpen(): void {
    this.connected = true;
    this.attempt = 0;
    this.emitState({ kind: "open", lastEventId: this.lastEventId });
  }
  private watchHeartbeat(controller: AbortController): void {
    if (this.heartbeatTimer) clearTimeout(this.heartbeatTimer);
    this.heartbeatTimer = setTimeout(() => {
      this.fail(controller, "heartbeat timeout");
    }, positive(this.opts.heartbeatTimeoutMs, 45_000));
  }
  private async stream(url: string, controller: AbortController): Promise<void> {
    let reader: ReadableStreamDefaultReader<Uint8Array> | undefined;
    const cancel = () => { void reader?.cancel().catch(() => undefined); };
    try {
      const headers = new Headers(this.opts.headers);
      headers.set("Accept", "text/event-stream");
      if (this.lastEventId !== null) headers.set("Last-Event-ID", String(this.lastEventId));
      const response = await fetchWithTimeout(url, {
        headers, signal: controller.signal, redirect: "error",
      }, positive(this.opts.connectTimeoutMs, resolveTimeoutMs()));
      // fetchWithTimeout covers headers only; cancel the reader explicitly after that.
      reader = response.body?.getReader();
      controller.signal.addEventListener("abort", cancel, { once: true });
      if (!this.active(controller)) { cancel(); return; }
      if (!response.ok || response.headers.get("content-type")?.split(";")[0].trim().toLowerCase() !== "text/event-stream" || !reader) {
        throw new Error("invalid SSE handshake");
      }
      this.didOpen();
      if (!this.active(controller)) return;
      this.watchHeartbeat(controller);
      const parser = new SseParser((kind, data, id) => {
        if (this.active(controller)) this.dispatch(kind, data, id);
      });
      const decoder = new TextDecoder();
      while (this.active(controller)) {
        const { value, done } = await reader.read();
        if (done) throw new Error("SSE stream ended");
        if (!this.active(controller)) break;
        if (value.length) this.watchHeartbeat(controller);
        parser.push(decoder.decode(value, { stream: true }));
      }
    } catch {
      // Do not expose URLs/headers or arbitrary server bodies in status messages.
      this.fail(controller, "SSE connection failed or stream ended");
    } finally {
      controller.signal.removeEventListener("abort", cancel);
      await reader?.cancel().catch(() => undefined);
      reader?.releaseLock();
    }
  }
  private dispatch(kind: string, data: unknown, id: string): void {
    if (id === "") this.lastEventId = null;
    const seq = parseSeq(id);
    if (seq !== null) this.lastEventId = seq;
    const event = parseEvent(kind, data, seq ?? 0);
    if (event) for (const handler of this.eventHandlers) {
      try { handler(event); } catch { /* Consumers cannot kill the stream. */ }
    }
  }
  private fail(controller: AbortController, reason: string): void {
    if (!this.active(controller)) return;
    this.closeTransport();
    if (this.attempt >= (this.opts.maxAttempts ?? Infinity)) {
      this.emitState({ kind: "closed", reason: `max attempts reached: ${reason}` });
      return;
    }
    const delay = Math.min(this.initialBackoffMs * 2 ** Math.min(this.attempt, 30), this.maxBackoffMs);
    this.attempt++;
    // Install before notifying: reentrant start/dispose must not create another retry.
    this.reconnectTimer = setTimeout(() => {
      this.reconnectTimer = null;
      if (!this.disposed) this.open();
    }, delay);
    this.emitState({ kind: "backoff", nextAttemptInMs: delay, attempt: this.attempt, reason });
  }
  private emitState(state: SseState): void {
    for (const handler of this.stateHandlers) {
      try { handler(state); } catch { /* Isolate UI handlers. */ }
    }
  }
}

/** Incremental CR/LF/CRLF framing, including CRLF split across chunks. */
class SseParser {
  private line = "";
  private afterCR = false;
  private kind = "message";
  private data: string[] = [];
  private id = "";
  private size = 0;
  constructor(private readonly emit: (kind: string, data: string, id: string) => void) {}
  push(chunk: string): void {
    for (const char of chunk) {
      if (this.afterCR) {
        this.afterCR = false;
        if (char === "\n") continue;
      }
      if (char === "\r" || char === "\n") {
        this.consumeLine();
        this.afterCR = char === "\r";
      } else {
        this.line += char;
        if (this.line.length + this.size > MAX_FRAME_CHARS) throw new Error("SSE frame too large");
      }
    }
  }
  private consumeLine(): void {
    const line = this.line;
    this.line = "";
    if (line === "") {
      if (this.data.length) this.emit(this.kind, this.data.join("\n"), this.id);
      this.kind = "message";
      this.data = [];
      this.size = 0;
      return;
    }
    if (line.startsWith(":")) return;
    const colon = line.indexOf(":");
    const field = colon < 0 ? line : line.slice(0, colon);
    let value = colon < 0 ? "" : line.slice(colon + 1);
    if (value.startsWith(" ")) value = value.slice(1);
    if (field === "data") {
      this.data.push(value);
      this.size += value.length + 1;
      if (this.size > MAX_FRAME_CHARS) throw new Error("SSE frame too large");
    } else if (field === "event") this.kind = value;
    else if (field === "id" && !value.includes("\0")) this.id = value;
    // retry is deliberately ignored: client backoff remains bounded by local policy.
  }
}

function positive(value: number | undefined, fallback: number): number {
  return value !== undefined && Number.isFinite(value) && value >= 1 && value <= 600_000 ? value : fallback;
}
function parseSeq(value: string): number | null {
  if (!/^\d+$/.test(value)) return null;
  const n = Number(value);
  return Number.isSafeInteger(n) && n >= 0 ? n : null;
}
function parseEvent(kind: string, data: unknown, seq: number): TreeEvent | null {
  if (typeof data !== "string" || !KINDS.includes(kind as TreeEventKind)) return null;
  let obj: unknown;
  try { obj = JSON.parse(data); } catch { return null; }
  if (!obj || typeof obj !== "object" || Array.isArray(obj)) return null;
  const o = obj as Record<string, unknown>;
  const strings: Record<TreeEventKind, string[]> = {
    "experiment.created": ["id", "name", "createdAt"],
    "experiment.deleted": ["id"],
    "run.started": ["experimentId", "runId", "startedAt"],
    "run.step.changed": ["runId", "stepId", "status"],
    "run.completed": ["runId", "status", "completedAt"],
    "snapshot.created": ["experimentId", "snapshotId", "createdAt"],
  };
  if (!strings[kind as TreeEventKind].every(key => typeof o[key] === "string" && o[key] !== "")) return null;
  const numeric = kind === "run.step.changed" ? "durationMs" : kind === "snapshot.created" ? "bytes" : null;
  if (numeric && (typeof o[numeric] !== "number" || !Number.isFinite(o[numeric]) || (o[numeric] as number) < 0)) return null;
  const payload: Record<string, unknown> = {};
  for (const key of strings[kind as TreeEventKind]) payload[key] = o[key];
  if (numeric) payload[numeric] = o[numeric];
  return { ...payload, kind, seq } as TreeEvent;
}
