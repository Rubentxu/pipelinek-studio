// @vitest-environment node
//
// R5 EVO-C.7 (2026-10-11): unit tests for the SSE client.
//
// The tests inject a fake `EventSource` via
// `SseClientOptions.eventSourceFactory` so the assertions are
// deterministic and don't depend on a live studio server. The
// fake captures listener registrations and exposes a
// `dispatch(kind, data, lastEventId)` helper to simulate the
// SSE event source. This mirrors how the extension's host
// EventSource emits typed events.

import { describe, it, expect, vi, beforeEach, afterEach } from "vitest";
import { SseClient, type SseState, type TreeEvent, type EventSourceLike, type MessageEventLike } from "./sseClient";

class FakeEventSource implements EventSourceLike {
  static instances: FakeEventSource[] = [];
  private readonly listeners = new Map<string, ((ev: MessageEventLike) => void)[]>();
  closed = false;
  ctorError: string | null = null;
  constructor(public readonly url: string) {
    if (this.ctorError) throw new Error(this.ctorError);
    FakeEventSource.instances.push(this);
  }
  addEventListener(type: string, listener: (ev: MessageEventLike) => void): void {
    const arr = this.listeners.get(type) ?? [];
    arr.push(listener);
    this.listeners.set(type, arr);
  }
  close(): void {
    this.closed = true;
  }
  emit(type: string, data: unknown, lastEventId = ""): void {
    const arr = this.listeners.get(type) ?? [];
    for (const l of arr) l({ data, lastEventId, type });
  }
}

beforeEach(() => {
  FakeEventSource.instances = [];
  vi.useFakeTimers();
});
afterEach(() => {
  vi.useRealTimers();
});

function newClient(overrides: Partial<{ initialBackoffMs: number; maxBackoffMs: number; maxAttempts: number; eventSourceFactory: (url: string) => EventSourceLike }> = {}): SseClient {
  return new SseClient({
    baseUrl: "https://studio.test:7778",
    initialBackoffMs: 1000,
    maxBackoffMs: 30_000,
    maxAttempts: 5,
    eventSourceFactory: (url) => new FakeEventSource(url),
    ...overrides,
  });
}

describe("SseClient construction", () => {
  it("does not report open before handshake and ignores invalid payload shapes", () => {
    const c = newClient();
    const seen: TreeEvent[] = [];
    c.onEvent(e => seen.push(e));
    c.start();
    expect(c.snapshot().open).toBe(false);
    const es = FakeEventSource.instances[0]!;
    es.emit("open", "");
    es.emit("experiment.created", "{}", "1");
    es.emit("run.step.changed", JSON.stringify({ runId: "r", stepId: "s", status: "OK", durationMs: -1 }), "2");
    expect(seen).toEqual([]);
    c.dispose();
  });

  it("isolates event handlers and ignores stale callbacks and duplicate start during backoff", () => {
    const c = newClient();
    const seen: TreeEvent[] = [];
    c.onEvent(() => { throw new Error("consumer"); });
    c.onEvent(e => seen.push(e));
    c.start();
    const es = FakeEventSource.instances[0]!;
    es.emit("open", "");
    expect(() => es.emit("experiment.deleted", '{"id":"e"}', "1")).not.toThrow();
    expect(seen).toHaveLength(1);
    es.emit("error", "");
    c.start();
    es.emit("open", "");
    expect(c.snapshot().open).toBe(false);
    expect(FakeEventSource.instances).toHaveLength(1);
    c.dispose();
  });

  it("starts closed until start() is called", () => {
    const c = newClient();
    const s = c.snapshot();
    expect(s.open).toBe(false);
    expect(s.attempt).toBe(0);
    expect(s.lastEventId).toBeNull();
  });

  it("start() opens an EventSource against /events", () => {
    const c = newClient();
    c.start();
    expect(FakeEventSource.instances).toHaveLength(1);
    expect(FakeEventSource.instances[0]!.url).toBe("https://studio.test:7778/events");
  });

  it("start() is idempotent: a second call does not open a second source", () => {
    const c = newClient();
    c.start();
    c.start();
    expect(FakeEventSource.instances).toHaveLength(1);
  });
});

describe("SseClient event dispatch", () => {
  it("parses experiment.created payloads and forwards typed events", () => {
    const c = newClient();
    const seen: TreeEvent[] = [];
    c.onEvent((e) => seen.push(e));
    c.start();
    const es = FakeEventSource.instances[0]!;
    es.emit("open", "");
    es.emit(
      "experiment.created",
      JSON.stringify({ id: "e1", name: "demo", createdAt: "2026-10-11T20:30:00Z" }),
      "42",
    );
    expect(seen).toHaveLength(1);
    expect(seen[0]).toEqual({
      kind: "experiment.created",
      id: "e1",
      name: "demo",
      createdAt: "2026-10-11T20:30:00Z",
      seq: 42,
    });
    expect(c.snapshot().lastEventId).toBe(42);
  });

  it("parses the run.step.changed numeric durationMs", () => {
    const c = newClient();
    const seen: TreeEvent[] = [];
    c.onEvent((e) => seen.push(e));
    c.start();
    const es = FakeEventSource.instances[0]!;
    es.emit("open", "");
    es.emit(
      "run.step.changed",
      JSON.stringify({ runId: "r1", stepId: "compile", status: "RUNNING", durationMs: 1234 }),
      "7",
    );
    expect(seen[0]).toMatchObject({ kind: "run.step.changed", status: "RUNNING", durationMs: 1234, seq: 7 });
  });

  it("ignores non-JSON data lines without crashing", () => {
    const c = newClient();
    const seen: TreeEvent[] = [];
    c.onEvent((e) => seen.push(e));
    c.start();
    const es = FakeEventSource.instances[0]!;
    es.emit("open", "");
    es.emit("run.completed", "not-json", "9");
    expect(seen).toHaveLength(0);
  });

  it("tracks the most recent lastEventId across event kinds", () => {
    const c = newClient();
    c.start();
    const es = FakeEventSource.instances[0]!;
    es.emit("open", "");
    es.emit("experiment.created", JSON.stringify({ id: "e1", name: "x", createdAt: "t" }), "1");
    es.emit("run.started", JSON.stringify({ experimentId: "e1", runId: "r1", startedAt: "t" }), "2");
    es.emit("run.completed", JSON.stringify({ runId: "r1", status: "OK", completedAt: "t" }), "3");
    expect(c.snapshot().lastEventId).toBe(3);
  });
});

describe("SseClient state machine", () => {
  it("emits connecting then open on a successful connect", () => {
    const c = newClient();
    const states: SseState[] = [];
    c.onState((s) => states.push(s));
    c.start();
    const es = FakeEventSource.instances[0]!;
    es.emit("open", "");
    expect(states[0]).toEqual({ kind: "connecting" });
    expect(states[1]).toEqual({ kind: "open", lastEventId: null });
  });

  it("on error, schedules a backoff reconnect with growing delay", () => {
    const c = newClient();
    const states: SseState[] = [];
    c.onState((s) => states.push(s));
    c.start();
    const es = FakeEventSource.instances[0]!;
    es.emit("error", "");
    // The error listener closed the source; the first backoff
    // attempt was scheduled.
    expect(es.closed).toBe(true);
    const backoff = states.find((s) => s.kind === "backoff") as Extract<SseState, { kind: "backoff" }>;
    expect(backoff).toBeDefined();
    expect(backoff.attempt).toBe(1);
    expect(backoff.nextAttemptInMs).toBe(1000);
  });

  it("backoff doubles: 1s → 2s → 4s → 8s capped at 30s", () => {
    const c = newClient();
    const states: SseState[] = [];
    c.onState((s) => states.push(s));
    c.start();
    // First error schedules 1s, advance, second opens then errors.
    FakeEventSource.instances[0]!.emit("error", "");
    vi.advanceTimersByTime(1000);
    const es2 = FakeEventSource.instances[1]!;
    es2.emit("error", "");
    vi.advanceTimersByTime(2000);
    const es3 = FakeEventSource.instances[2]!;
    es3.emit("error", "");
    vi.advanceTimersByTime(4000);
    const es4 = FakeEventSource.instances[3]!;
    es4.emit("error", "");
    vi.advanceTimersByTime(8000);
    const es5 = FakeEventSource.instances[4]!;
    es5.emit("error", "");

    const backoffs = states.filter((s) => s.kind === "backoff") as Extract<SseState, { kind: "backoff" }>[];
    const delays = backoffs.map((b) => b.nextAttemptInMs);
    expect(delays).toEqual([1000, 2000, 4000, 8000, 16000]);
  });

  it("backoff caps at 30s", () => {
    const c = newClient({ initialBackoffMs: 1000, maxBackoffMs: 30_000, maxAttempts: 20 });
    const states: SseState[] = [];
    c.onState((s) => states.push(s));
    c.start();
    // Open 1, then trigger 6 errors with each timer tick.
    for (let i = 0; i < 6; i++) {
      const es = FakeEventSource.instances[i]!;
      es.emit("error", "");
      const backoff = states.filter((s) => s.kind === "backoff").pop() as Extract<SseState, { kind: "backoff" }>;
      const lastDelay = backoff?.nextAttemptInMs ?? 0;
      vi.advanceTimersByTime(lastDelay || 1);
    }
    const backoffs = states.filter((s) => s.kind === "backoff") as Extract<SseState, { kind: "backoff" }>[];
    expect(backoffs.length).toBeGreaterThanOrEqual(5);
    // The 6th backoff must be capped at 30s (1s × 2^5 = 32s → 30s).
    expect(backoffs[5]?.nextAttemptInMs).toBe(30_000);
  });

  it("a successful open resets the attempt counter", () => {
    const c = newClient();
    c.start();
    FakeEventSource.instances[0]!.emit("error", "");
    vi.advanceTimersByTime(1000);
    // Second instance opens successfully.
    FakeEventSource.instances[1]!.emit("open", "");
    expect(c.snapshot().attempt).toBe(0);
    // Next error schedules 1s again (not 2s).
    const states: SseState[] = [];
    c.onState((s) => states.push(s));
    FakeEventSource.instances[1]!.emit("error", "");
    const backoff = states.find((s) => s.kind === "backoff") as Extract<SseState, { kind: "backoff" }>;
    expect(backoff.nextAttemptInMs).toBe(1000);
  });

  it("gives up after maxAttempts", () => {
    const c = newClient({ maxAttempts: 3 });
    const states: SseState[] = [];
    c.onState((s) => states.push(s));
    c.start();
    for (let i = 0; i < 5; i++) {
      const es = FakeEventSource.instances[i];
      if (es) es.emit("error", "");
      const lastBackoff = states
        .filter((s) => s.kind === "backoff")
        .pop() as Extract<SseState, { kind: "backoff" }> | undefined;
      vi.advanceTimersByTime(lastBackoff?.nextAttemptInMs ?? 0);
    }
    const closed = states.find((s) => s.kind === "closed") as Extract<SseState, { kind: "closed" }>;
    expect(closed).toBeDefined();
    expect(closed.reason).toMatch(/max attempts reached/);
  });
});

describe("real fetch streaming", () => {
  it.each(["status", "mime", "oversized", "silent"])("backs off on %s and disposes without retry storms", async (mode) => {
    vi.useRealTimers();
    const { createServer } = await import("node:http");
    let requests = 0;
    const server = createServer((_req, res) => {
      requests++;
      if (mode === "silent") return;
      res.writeHead(mode === "status" ? 501 : 200, { "Content-Type": mode === "mime" ? "application/json" : "text/event-stream" });
      res.flushHeaders();
      if (mode === "oversized") res.write("data: " + "x".repeat(300_000));
    });
    await new Promise<void>(r => server.listen(0, "127.0.0.1", r));
    const c = new SseClient({ baseUrl: `http://127.0.0.1:${(server.address() as { port: number }).port}`, connectTimeoutMs: 60, initialBackoffMs: 1000 });
    const states: SseState[] = [];
    c.onState(s => states.push(s));
    try {
      c.start();
      const deadline = Date.now() + 2000;
      while (!states.some(s => s.kind === "backoff") && Date.now() < deadline) await new Promise(r => setTimeout(r, 10));
      expect(states.some(s => s.kind === "backoff")).toBe(true);
      expect(c.snapshot().open).toBe(false);
      c.dispose();
      expect(requests).toBe(1);
    } finally {
      c.dispose();
      server.closeAllConnections();
      await new Promise<void>(r => server.close(() => r()));
    }
  });
  it("frames fragmented CRLF/multiline data, sends profile/resume, observes heartbeat, reconnects and aborts", async () => {
    vi.useRealTimers();
    const { createServer } = await import("node:http");
    const requests: import("node:http").IncomingMessage[] = [];
    const responses: import("node:http").ServerResponse[] = [];
    const timers: ReturnType<typeof setInterval>[] = [];
    const fragments: ReturnType<typeof setTimeout>[] = [];
    const server = createServer((req, res) => {
      requests.push(req);
      responses.push(res);
      res.writeHead(200, { "Content-Type": "text/event-stream" });
      res.flushHeaders();
      if (requests.length === 1) {
        res.write('id: 42\r');
        // Separate event-loop turns exercise chunk-spanning CRLF, not just server writes.
        fragments.push(setTimeout(() => {
          res.write('\nevent: experiment.created\r\ndata: {"id":"e1",\r\n');
        }, 15));
        fragments.push(setTimeout(() => {
          res.write('data: "name":"demo","createdAt":"t"}\r\n\r\n');
          timers.push(setInterval(() => res.write(': ping\r\n\r\n'), 15));
        }, 30));
      }
    });
    await new Promise<void>(r => server.listen(0, "127.0.0.1", r));
    const c = new SseClient({ baseUrl: `http://127.0.0.1:${(server.address() as { port: number }).port}`, headers: { "X-Studio-Profile": "worktree" }, heartbeatTimeoutMs: 120, initialBackoffMs: 20, maxBackoffMs: 40 });
    const states: SseState[] = [];
    const events: TreeEvent[] = [];
    c.onState(s => states.push(s));
    c.onEvent(e => events.push(e));
    const waitFor = async (predicate: () => boolean) => {
      const until = Date.now() + 2500;
      while (!predicate() && Date.now() < until) await new Promise(r => setTimeout(r, 10));
      expect(predicate()).toBe(true);
    };
    try {
      c.start();
      expect(c.snapshot().open).toBe(false);
      await waitFor(() => events.length === 1);
      expect(events[0]).toEqual({ kind: "experiment.created", id: "e1", name: "demo", createdAt: "t", seq: 42 });
      expect(requests[0]!.headers["x-studio-profile"]).toBe("worktree");
      await new Promise(r => setTimeout(r, 250));
      expect(requests).toHaveLength(1);
      expect(c.snapshot().open).toBe(true);
      clearInterval(timers[0]);
      await waitFor(() => requests.length >= 2);
      expect(requests[1]!.headers["last-event-id"]).toBe("42");
      expect(requests[1]!.headers["x-studio-profile"]).toBe("worktree");
      expect(states.some(s => s.kind === "backoff" && s.reason.includes("heartbeat"))).toBe(true);
      await waitFor(() => states.filter(s => s.kind === "open").length === 2);
      c.dispose();
      expect(c.snapshot().open).toBe(false);
      await waitFor(() => responses[1]!.destroyed);
      await new Promise(r => setTimeout(r, 180));
      expect(requests).toHaveLength(2);
    } finally {
      c.dispose();
      timers.forEach(clearInterval);
      fragments.forEach(clearTimeout);
      server.closeAllConnections();
      await new Promise<void>(r => server.close(() => r()));
    }
  });
});

describe("SseClient lifecycle", () => {
  it("dispose() closes the source and stops reconnecting", () => {
    const c = newClient();
    c.start();
    const es = FakeEventSource.instances[0]!;
    c.dispose();
    expect(es.closed).toBe(true);
    // An error after dispose must not schedule a reconnect.
    const states: SseState[] = [];
    c.onState((s) => states.push(s));
    es.emit("error", "");
    vi.advanceTimersByTime(60_000);
    expect(states.filter((s) => s.kind === "backoff" || s.kind === "connecting")).toEqual([]);
  });

  it("ctor failure schedules a backoff instead of throwing", () => {
    const c = new SseClient({
      baseUrl: "https://studio.test:7778",
      eventSourceFactory: (_url) => {
        throw new Error("ECONNREFUSED");
      },
      initialBackoffMs: 100,
      maxBackoffMs: 1000,
      maxAttempts: 1,
    });
    const states: SseState[] = [];
    c.onState((s) => states.push(s));
    c.start();
    const backoff = states.find((s) => s.kind === "backoff") as Extract<SseState, { kind: "backoff" }>;
    expect(backoff).toBeDefined();
    expect(backoff.reason).toMatch(/ctor failed/);
  });
});
