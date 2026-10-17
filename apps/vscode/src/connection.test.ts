// @vitest-environment node
//
// R5 EVO-C.T3 (2026-10-16) — the connection orchestrator.
//
// Every request path must go through `validateStudioConfig` first, plaintext
// HTTP needs an explicit confirmation scoped to the exact URL inside this
// extension session, the stream state must be truthful (never `open` before a
// handshake), and every `open` — including the first — must trigger the
// authoritative tree snapshot.

import { describe, it, expect, vi, beforeEach } from "vitest";
import type { SseClient, SseState, TreeEvent } from "./sseClient";
import type { ValidatedStudioConfig } from "./config";
import type { StudioApi } from "./studioApi";
import type { TreeSnapshot } from "./treeView";
import { StudioConnection, type ConnectionStatus } from "./connection";

const EMPTY_SNAPSHOT: TreeSnapshot = { experiments: [], snapshots: [], runs: [], truncated: false };

class FakeSse {
  readonly stateHandlers: ((s: SseState) => void)[] = [];
  readonly eventHandlers: ((e: TreeEvent) => void)[] = [];
  readonly order: string[] = [];
  started = 0;
  disposed = 0;
  onState(handler: (s: SseState) => void): void {
    this.order.push("onState");
    this.stateHandlers.push(handler);
  }
  onEvent(handler: (e: TreeEvent) => void): void {
    this.order.push("onEvent");
    this.eventHandlers.push(handler);
  }
  start(): void {
    this.order.push("start");
    this.started += 1;
  }
  dispose(): void {
    this.disposed += 1;
  }
  emitState(state: SseState): void {
    for (const handler of this.stateHandlers) handler(state);
  }
  emitEvent(event: TreeEvent): void {
    for (const handler of this.eventHandlers) handler(event);
  }
}

function harness(options: {
  settings?: Record<string, unknown>;
  approve?: boolean | ((baseUrl: string) => boolean | Promise<boolean>);
  collect?: () => Promise<TreeSnapshot>;
} = {}) {
  let settings: Record<string, unknown> = options.settings ?? {
    baseUrl: "https://127.0.0.1:7778",
    profile: "default",
    allowInsecureHttp: false,
  };
  const confirmCalls: string[] = [];
  const apis: { config: ValidatedStudioConfig }[] = [];
  const api = {
    collectTree: vi.fn(async () => (options.collect ? options.collect() : EMPTY_SNAPSHOT)),
  };
  const sses: FakeSse[] = [];
  const statuses: ConnectionStatus[] = [];
  const snapshots: TreeSnapshot[] = [];
  const events: TreeEvent[] = [];

  const connection = new StudioConnection({
    readSettings: () => settings as never,
    confirmInsecureHttp: async (baseUrl: string) => {
      confirmCalls.push(baseUrl);
      if (typeof options.approve === "function") return options.approve(baseUrl);
      return options.approve ?? true;
    },
    createApi: (config) => {
      apis.push({ config });
      return api as unknown as StudioApi;
    },
    createSse: (config) => {
      void config;
      const sse = new FakeSse();
      sses.push(sse);
      return sse as unknown as SseClient;
    },
    onStatus: (status) => statuses.push(status),
    onEvent: (event) => events.push(event),
    syncTree: async (load) => {
      snapshots.push(await load());
    },
  });

  return {
    connection,
    api,
    apis,
    sses,
    statuses,
    snapshots,
    events,
    confirmCalls,
    setSettings: (next: Record<string, unknown>) => {
      settings = next;
    },
    lastStatus: () => statuses[statuses.length - 1],
  };
}

async function flush(): Promise<void> {
  for (let i = 0; i < 4; i += 1) await Promise.resolve();
  await new Promise((resolve) => setTimeout(resolve, 0));
}

beforeEach(() => {
  vi.clearAllMocks();
});

describe("configuration validation and HTTP consent", () => {
  it("rejects an invalid configuration before creating any client", async () => {
    const h = harness({ settings: { baseUrl: "file:///tmp/studio", profile: "default", allowInsecureHttp: false } });
    await h.connection.ensureApi().catch(() => undefined);
    expect(h.apis).toHaveLength(0);
    expect(h.sses).toHaveLength(0);
    expect(h.lastStatus()?.kind).toBe("error");
    expect(h.lastStatus()?.detail).toMatch(/must use https/);
  });

  it("sends nothing when the plaintext HTTP confirmation is declined", async () => {
    const h = harness({ settings: { baseUrl: "http://127.0.0.1:7777", allowInsecureHttp: true }, approve: false });
    const failure = await h.connection.ensureApi().catch((e: unknown) => e as Error);
    expect((failure as Error).message).toMatch(/http:\/\/127\.0\.0\.1:7777/);
    expect(h.confirmCalls).toEqual(["http://127.0.0.1:7777"]);
    expect(h.apis).toHaveLength(0);
    expect(h.sses).toHaveLength(0);
    expect(h.lastStatus()?.kind).toBe("error");
  });

  it("does not ask for consent when allowInsecureHttp is false, and sends nothing", async () => {
    const h = harness({ settings: { baseUrl: "http://127.0.0.1:7777", allowInsecureHttp: false }, approve: true });
    await h.connection.ensureApi().catch(() => undefined);
    expect(h.confirmCalls).toEqual([]);
    expect(h.apis).toHaveLength(0);
    expect(h.lastStatus()?.detail).toMatch(/allowInsecureHttp/);
  });

  it("scopes the confirmation to the exact URL inside the session", async () => {
    const h = harness({ settings: { baseUrl: "http://127.0.0.1:7777", allowInsecureHttp: true }, approve: true });
    await h.connection.ensureApi();
    await h.connection.reload();
    await h.connection.ensureApi();
    expect(h.confirmCalls).toEqual(["http://127.0.0.1:7777"]);

    // A different exact URL is a new decision, even with consent already given.
    h.setSettings({ baseUrl: "http://127.0.0.1:7999", allowInsecureHttp: true });
    await h.connection.reload();
    expect(h.confirmCalls).toEqual(["http://127.0.0.1:7777", "http://127.0.0.1:7999"]);
  });

  it("asks once and creates one client for concurrent ensureApi calls", async () => {
    const h = harness({
      settings: { baseUrl: "http://127.0.0.1:7777", allowInsecureHttp: true },
      approve: true,
    });

    const [a, b] = await Promise.all([h.connection.ensureApi(), h.connection.ensureApi()]);

    expect(a).toBe(b);
    expect(h.confirmCalls).toEqual(["http://127.0.0.1:7777"]);
    expect(h.apis).toHaveLength(1);
    expect(h.sses).toHaveLength(1);
    expect(h.sses[0]!.started).toBe(1);
  });

  // R5 correction (P-A/P-B): a settings change invalidates the in-flight
  // generation. A consent that resolves afterwards must never assign a
  // client, send a request or emit `open` for the replaced URL.
  it("discards a consent that resolves after the settings changed to a declined URL", async () => {
    let resolveA: (approved: boolean) => void = () => undefined;
    const pendingConsent = new Promise<boolean>((resolve) => {
      resolveA = resolve;
    });
    const h = harness({
      settings: { baseUrl: "http://127.0.0.1:7777", allowInsecureHttp: true },
      approve: (baseUrl) => (baseUrl === "http://127.0.0.1:7777" ? pendingConsent : false),
    });

    // The dialog for the first URL is still open ...
    const first = h.connection.ensureApi().then(
      (api) => api as unknown,
      (error: unknown) => error as Error,
    );
    // ... when the operator switches to a second plaintext URL and declines it.
    h.setSettings({ baseUrl: "http://127.0.0.1:7999", allowInsecureHttp: true });
    await h.connection.reload();

    expect(h.confirmCalls).toEqual(["http://127.0.0.1:7777", "http://127.0.0.1:7999"]);
    expect(h.apis).toHaveLength(0);
    expect(h.sses).toHaveLength(0);
    expect(h.lastStatus()?.kind).toBe("error");

    // The old dialog now resolves with approval: the previous URL must not
    // be resurrected, and the command must not receive a stale client.
    resolveA(true);
    const outcome = await first;

    expect(outcome).toBeInstanceOf(Error);
    expect((outcome as Error).message).toMatch(/7999/);
    expect(h.apis).toHaveLength(0);
    expect(h.sses).toHaveLength(0);
    expect(h.lastStatus()?.kind).toBe("error");
  });

  it("revalidates settings on reload and never reuses the stale client", async () => {
    const h = harness();
    await h.connection.ensureApi();
    expect(h.sses).toHaveLength(1);
    expect(h.apis[0]!.config.profile).toBe("default");

    h.setSettings({ baseUrl: "https://127.0.0.1:7778", profile: "worktree", allowInsecureHttp: false });
    await h.connection.reload();

    expect(h.sses[0]!.disposed).toBe(1);
    expect(h.sses).toHaveLength(2);
    expect(h.apis[1]!.config.profile).toBe("worktree");
    expect(h.apis[1]!.config.headers).toEqual({ "X-Studio-Profile": "worktree" });
  });

  it("goes to error and keeps no client when the reloaded configuration is invalid", async () => {
    const h = harness();
    await h.connection.ensureApi();
    h.setSettings({ baseUrl: "https://127.0.0.1:7778", profile: "bad\r\nInjected: yes", allowInsecureHttp: false });
    await h.connection.reload();
    expect(h.sses[0]!.disposed).toBe(1);
    expect(h.sses).toHaveLength(1);
    expect(h.lastStatus()?.kind).toBe("error");
    await expect(h.connection.ensureApi()).rejects.toThrow(/profile/);
  });
});

describe("stream lifecycle", () => {
  it("subscribes before start so the first open can refresh the tree", async () => {
    const h = harness();
    await h.connection.ensureApi();
    expect(h.sses[0]!.order).toEqual(["onState", "onEvent", "start"]);
  });

  it("stays truthful before the handshake and refreshes on every open", async () => {
    const h = harness();
    await h.connection.ensureApi();
    const sse = h.sses[0]!;
    expect(h.connection.status.kind).toBe("connecting");
    expect(h.snapshots).toHaveLength(0);

    sse.emitState({ kind: "open", lastEventId: null });
    await flush();
    expect(h.connection.status.kind).toBe("live");
    expect(h.snapshots).toHaveLength(1);

    // A reconnect is another authoritative refresh, not a resumed tail.
    sse.emitState({ kind: "backoff", nextAttemptInMs: 2000, attempt: 1, reason: "heartbeat timeout" });
    expect(h.connection.status.kind).toBe("disconnected");
    sse.emitState({ kind: "open", lastEventId: 12 });
    await flush();
    expect(h.snapshots).toHaveLength(2);
  });

  it("reports a failed authoritative refresh as an error without dropping the stream", async () => {
    const h = harness({
      collect: async () => {
        throw new Error("HTTP 503: COLLECTION_UNAVAILABLE");
      },
    });
    await h.connection.ensureApi();
    h.sses[0]!.emitState({ kind: "open", lastEventId: null });
    await flush();
    expect(h.lastStatus()?.kind).toBe("error");
    expect(h.lastStatus()?.detail).toMatch(/COLLECTION_UNAVAILABLE/);
    expect(h.sses[0]!.disposed).toBe(0);
  });

  it("forwards live events and disposes exactly once", async () => {
    const h = harness();
    await h.connection.ensureApi();
    h.sses[0]!.emitEvent({ kind: "experiment.deleted", id: "e1", seq: 3 });
    expect(h.events).toHaveLength(1);

    h.connection.dispose();
    h.connection.dispose();
    expect(h.sses[0]!.disposed).toBe(1);
  });
});
