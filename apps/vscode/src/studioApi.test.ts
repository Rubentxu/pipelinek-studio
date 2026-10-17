// @vitest-environment node
//
// R5 EVO-C.T3 (2026-10-16) — typed HTTP client used by every extension
// command and by the authoritative tree bootstrap.
//
// These tests pin the observable HTTP contract: which URL/method is sent,
// the profile header on every request, honest failures (no invented
// collections), the paged journal cursor, cancellation outcomes, and the
// canonical-id association between experiments and their runs/snapshots.

import { describe, it, expect, beforeEach, afterEach, vi } from "vitest";
import { validateStudioConfig } from "./config";
import { StudioApi, StudioApiError, runIdempotencyKey } from "./studioApi";

const config = validateStudioConfig({
  baseUrl: "https://127.0.0.1:7778",
  profile: "worktree",
});

interface Call {
  url: string;
  method: string;
  headers: Record<string, string>;
  body?: string;
}

let realFetch: typeof fetch;

function installFetch(handler: (call: Call) => Response | Promise<Response>): Call[] {
  const calls: Call[] = [];
  globalThis.fetch = vi.fn(async (input: unknown, init?: RequestInit) => {
    const headers = Object.fromEntries(
      [...new Headers((init?.headers ?? {}) as HeadersInit).entries()].map(([k, v]) => [k.toLowerCase(), v]),
    );
    const call: Call = {
      url: String(input),
      method: init?.method ?? "GET",
      headers,
      body: typeof init?.body === "string" ? init.body : undefined,
    };
    calls.push(call);
    return handler(call);
  }) as unknown as typeof fetch;
  return calls;
}

function json(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "content-type": "application/json" },
  });
}

function pathOf(call: Call): string {
  return new URL(call.url).pathname + new URL(call.url).search;
}

beforeEach(() => {
  realFetch = globalThis.fetch;
});

afterEach(() => {
  globalThis.fetch = realFetch;
});

describe("StudioApi request shape", () => {
  it("sends the validated profile header on every SDK, direct-read and cancel request", async () => {
    const calls = installFetch((call) => {
      const path = new URL(call.url).pathname;
      if (path.endsWith("/cancel")) return json({ acknowledged: true });
      if (path === "/health") return json({ status: "ok" });
      if (path === "/runs") return json({ items: [] });
      if (path === "/experiments") {
        return call.method === "POST"
          ? json({ experiment_id: "e1", name: "demo", created_at: "2026-03-02T00:00:00Z" })
          : json({ items: [] });
      }
      if (path.startsWith("/experiments/")) return json({ items: [] });
      if (path.endsWith("/events")) return json({ events: [], nextSequence: null });
      return json({ items: [] });
    });
    const api = new StudioApi(config, { timeoutMs: 2000 });

    await api.health();
    await api.listExperiments();
    await api.listRuns();
    await api.createExperiment("demo");
    await api.cancelRun("11111111-1111-4111-8111-111111111111");
    await api.journalPage("11111111-1111-4111-8111-111111111111", null, 50);

    expect(calls.length).toBeGreaterThanOrEqual(6);
    for (const call of calls) {
      expect(call.headers["x-studio-profile"], `${call.method} ${pathOf(call)}`).toBe("worktree");
    }
    const sent = calls.map((call) => `${call.method} ${pathOf(call)}`);
    expect(sent).toContain("GET /health");
    expect(sent.some((entry) => entry.startsWith("GET /experiments?"))).toBe(true);
    expect(sent.some((entry) => entry.startsWith("GET /runs?"))).toBe(true);
    expect(sent).toContain("POST /runs/11111111-1111-4111-8111-111111111111/cancel");
  });

  it("rejects an invalid base URL at construction time instead of requesting it", async () => {
    const calls = installFetch(() => json({}));
    expect(() => new StudioApi({ ...config, baseUrl: "file:///tmp/studio" })).toThrow(/https/);
    expect(calls).toHaveLength(0);
  });
});

describe("collection reads", () => {
  it("returns the server order and forwards the page request", async () => {
    const calls = installFetch(() =>
      json({
        items: [
          { experiment_id: "e2", name: "newer", created_at: "2026-03-02T00:00:00Z" },
          { experiment_id: "e1", name: "older", created_at: "2026-03-01T00:00:00Z" },
        ],
      }),
    );
    const api = new StudioApi(config);
    const page = await api.listExperiments({ limit: 2, offset: 1 });

    expect(pathOf(calls[0]!)).toBe("/experiments?limit=2&offset=1");
    expect(page.items.map((e) => e.id)).toEqual(["e2", "e1"]);
    expect(page.items[0]).toEqual({ id: "e2", name: "newer", createdAt: "2026-03-02T00:00:00Z" });
    expect(page.full).toBe(true);
  });

  it("fails honestly on a malformed collection instead of inventing items", async () => {
    installFetch(() => json({ items: [{ name: "no id" }] }));
    const api = new StudioApi(config);
    await expect(api.listExperiments()).rejects.toBeInstanceOf(StudioApiError);
    await expect(api.listExperiments()).rejects.toThrow(/unexpected experiment collection/);
  });

  it("surfaces the typed server error code for a refused collection read", async () => {
    installFetch(() =>
      json({ code: "COLLECTION_UNAVAILABLE", message: "metadata port does not support experiment collection reads" }, 503),
    );
    const api = new StudioApi(config);
    const failure = await api.listExperiments().catch((e: unknown) => e);
    expect(failure).toBeInstanceOf(StudioApiError);
    expect((failure as StudioApiError).status).toBe(503);
    expect((failure as StudioApiError).code).toBe("COLLECTION_UNAVAILABLE");
    expect((failure as StudioApiError).message).toContain("metadata port does not support");
  });
});

describe("authoritative tree snapshot", () => {
  it("pages the collections and associates runs/snapshots by canonical id only", async () => {
    const calls = installFetch((call) => {
      const url = new URL(call.url);
      if (url.pathname === "/experiments") {
        return json({
          items: [
            { experiment_id: "e1", name: "one", created_at: "2026-03-02T00:00:00Z" },
            { experiment_id: "e2", name: "two", created_at: "2026-03-01T00:00:00Z" },
          ],
        });
      }
      if (url.pathname === "/experiments/e1/snapshots") {
        return json({
          items: [
            {
              snapshot_id: "s1",
              experiment_id: "e1",
              sha256: "a".repeat(64),
              created_at: "2026-03-02T01:00:00Z",
            },
          ],
        });
      }
      if (url.pathname === "/experiments/e2/snapshots") return json({ items: [] });
      if (url.pathname === "/runs") {
        return json({
          items: [
            {
              run_id: "r1",
              experiment_id: "e1",
              snapshot_id: "s1",
              scenario_id: "sc1",
              state: "RUNNING",
              created_at: "2026-03-02T02:00:00Z",
            },
            {
              run_id: "r2",
              experiment_id: "e2",
              snapshot_id: "s2",
              scenario_id: "sc1",
              state: "SUCCEEDED",
              created_at: "2026-03-02T03:00:00Z",
            },
          ],
        });
      }
      return json({ code: "BAD_REQUEST", message: `unexpected ${url.pathname}` }, 400);
    });

    const snapshot = await new StudioApi(config).collectTree();

    expect(snapshot.experiments.map((e) => e.id)).toEqual(["e1", "e2"]);
    expect(snapshot.snapshots.map((s) => `${s.experimentId}/${s.snapshotId}`)).toEqual(["e1/s1"]);
    expect(snapshot.runs.map((r) => `${r.experimentId}/${r.runId}/${r.state}`)).toEqual([
      "e1/r1/RUNNING",
      "e2/r2/SUCCEEDED",
    ]);
    expect(snapshot.truncated).toBe(false);
    expect(calls.some((c) => pathOf(c) === "/experiments/e2/snapshots?limit=200&offset=0")).toBe(true);
  });

  it("marks a partial view as truncated rather than claiming authority", async () => {
    installFetch((call) => {
      const url = new URL(call.url);
      if (url.pathname === "/experiments") return json({ items: [] });
      if (url.pathname === "/runs") {
        const page = Number(url.searchParams.get("offset") ?? "0");
        return json({
          items: [
            {
              run_id: `r${page}`,
              experiment_id: "e1",
              snapshot_id: "s1",
              scenario_id: "sc1",
              state: "RUNNING",
              created_at: "2026-03-02T02:00:00Z",
            },
          ],
        });
      }
      return json({ items: [] });
    });

    const snapshot = await new StudioApi(config).collectTree({ pageSize: 1, maxPages: 3 });
    expect(snapshot.truncated).toBe(true);
    expect(snapshot.runs).toHaveLength(3);
  });
});

describe("cancellation", () => {
  it("reports an acknowledged cancel without inventing a terminal state", async () => {
    const calls = installFetch(() => json({ acknowledged: true }));
    const outcome = await new StudioApi(config).cancelRun("r1");
    expect(outcome).toEqual({ kind: "acknowledged", detail: "acknowledged", status: 200 });
    expect(pathOf(calls[0]!)).toBe("/runs/r1/cancel");
    expect(calls[0]!.method).toBe("POST");
  });

  it("reports CANCEL_UNKNOWN and CANCEL_REFUSED as failures with the server detail", async () => {
    installFetch(() => json({ code: "CANCEL_UNKNOWN", message: "no run binding for r1" }, 202));
    const unknown = await new StudioApi(config).cancelRun("r1");
    expect(unknown.kind).toBe("unknown");
    expect(unknown.detail).toBe("no run binding for r1");

    installFetch(() => json({ code: "CANCEL_REFUSED", message: "transport failure" }, 503));
    const refused = await new StudioApi(config).cancelRun("r1");
    expect(refused.kind).toBe("refused");
    expect(refused.detail).toBe("transport failure");
    expect(refused.status).toBe(503);
  });

  it("throws a typed error for an unexpected status", async () => {
    installFetch(() => json({ code: "BAD_REQUEST", message: "route parameter 'runId' is not a valid UUID: 'r1'" }, 400));
    await expect(new StudioApi(config).cancelRun("r1")).rejects.toThrow(/not a valid UUID/);
  });
});

describe("journal paging", () => {
  it("walks the run journal with the after cursor and stops at the last page", async () => {
    const calls = installFetch((call) => {
      const after = new URL(call.url).searchParams.get("after");
      if (after === null) {
        return json({
          runId: "r1",
          events: [
            { sequence: 1, kind: "RunStarted", occurredAt: "2026-03-02T00:00:00Z", payload: {} },
            { sequence: 2, kind: "RunFinished", occurredAt: "2026-03-02T00:00:01Z", payload: { status: "OK" } },
          ],
          nextSequence: 2,
        });
      }
      return json({
        runId: "r1",
        events: [{ sequence: 3, kind: "RunFinished", occurredAt: "2026-03-02T00:00:02Z", payload: {} }],
        nextSequence: null,
      });
    });

    const events = await new StudioApi(config).journal("r1", { pageSize: 2, maxPages: 5 });

    expect(events.map((e) => e.sequence)).toEqual([1, 2, 3]);
    expect(events[1]!.payload).toEqual({ status: "OK" });
    expect(calls.map((c) => pathOf(c))).toEqual(["/runs/r1/events?limit=2", "/runs/r1/events?limit=2&after=2"]);
  });

  it("stops at maxPages and returns exactly what it read", async () => {
    const calls = installFetch(() =>
      json({
        runId: "r1",
        events: [{ sequence: 1, kind: "RunStarted", occurredAt: "2026-03-02T00:00:00Z", payload: {} }],
        nextSequence: 1,
      }),
    );
    const events = await new StudioApi(config).journal("r1", { pageSize: 1, maxPages: 2 });
    expect(events).toHaveLength(2);
    expect(calls).toHaveLength(2);
  });
});

describe("existing command requests", () => {
  it("keeps the health document body and the OpenAPI document verbatim", async () => {
    installFetch((call) => {
      if (new URL(call.url).pathname === "/openapi.json") {
        return new Response('{"openapi":"3.1.0"}', { status: 200, headers: { "content-type": "application/json" } });
      }
      return json({ status: "ok", service: "pipelinek-studio" });
    });
    const api = new StudioApi(config);
    expect(JSON.parse(await api.health())).toEqual({ status: "ok", service: "pipelinek-studio" });
    expect(await api.openapiDocument()).toBe('{"openapi":"3.1.0"}');
  });

  it("reads a single run's authoritative state", async () => {
    const calls = installFetch(() => json({ run_id: "r1", state: "RUNNING" }));
    const state = await new StudioApi(config).runState("r1");
    expect(state).toEqual({ runId: "r1", state: "RUNNING" });
    expect(pathOf(calls[0]!)).toBe("/runs/r1");
  });
});

describe("existing-experiment catalog", () => {
  it("walks every experiment page instead of trusting a single page", async () => {
    const pages: Record<number, unknown[]> = {
      0: [{ experiment_id: "e1", name: "one", created_at: "2026-03-02T00:00:00Z" }],
      1: [{ experiment_id: "e2", name: "two", created_at: "2026-03-01T00:00:00Z" }],
      2: [],
    };
    const calls = installFetch((call) => {
      const url = new URL(call.url);
      const offset = Number(url.searchParams.get("offset") ?? "0");
      if (url.pathname === "/experiments") return json({ items: pages[offset] ?? [] });
      return json({ code: "BAD_REQUEST", message: `unexpected ${url.pathname}` }, 400);
    });

    const result = await new StudioApi(config).experiments({ pageSize: 1, maxPages: 3 });

    expect(result.items.map((e) => e.id)).toEqual(["e1", "e2"]);
    expect(result.truncated).toBe(false);
    expect(calls.map(pathOf)).toEqual([
      "/experiments?limit=1&offset=0",
      "/experiments?limit=1&offset=1",
      "/experiments?limit=1&offset=2",
    ]);
  });

  it("reports truncation when the page budget runs out, so a missing id is never claimed absent", async () => {
    installFetch(() =>
      json({ items: [{ experiment_id: "e1", name: "one", created_at: "2026-03-02T00:00:00Z" }] }),
    );
    const result = await new StudioApi(config).experiments({ pageSize: 1, maxPages: 1 });
    expect(result.items.map((e) => e.id)).toEqual(["e1"]);
    expect(result.truncated).toBe(true);
  });
});

describe("scenario catalog (GET /scenarios)", () => {
  it("reads the registered scenario records the studio actually serves", async () => {
    const calls = installFetch(() =>
      json({
        items: [
          {
            scenario_id: "sc1",
            version: 2,
            plugin_lock_digest: null,
            environment_profile: "trusted_local",
            created_at: "",
          },
        ],
      }),
    );

    const page = await new StudioApi(config).listScenarios({ limit: 50, offset: 0 });

    expect(page.items).toEqual([
      {
        scenarioId: "sc1",
        version: 2,
        pluginLockDigest: undefined,
        environmentProfile: "trusted_local",
        createdAt: undefined,
      },
    ]);
    expect(pathOf(calls[0]!)).toBe("/scenarios?limit=50&offset=0");
  });

  it("fails honestly on a scenario record without a real id or version", async () => {
    installFetch(() => json({ items: [{ version: 1, environment_profile: "trusted_local", created_at: "" }] }));
    await expect(new StudioApi(config).listScenarios()).rejects.toBeInstanceOf(StudioApiError);
    await expect(new StudioApi(config).listScenarios()).rejects.toThrow(/scenario catalog/);
  });

  it("walks the scenario pages so a real scenario beyond the first page is still offered", async () => {
    installFetch((call) => {
      const offset = Number(new URL(call.url).searchParams.get("offset") ?? "0");
      return json({
        items: [
          {
            scenario_id: offset === 0 ? "sc1" : "sc2",
            version: 1,
            plugin_lock_digest: null,
            environment_profile: "trusted_local",
            created_at: "",
          },
        ],
      });
    });
    const result = await new StudioApi(config).scenarios({ pageSize: 1, maxPages: 2 });
    expect(result.items.map((s) => s.scenarioId)).toEqual(["sc1", "sc2"]);
  });
});

describe("run idempotency key", () => {
  it("is deterministic, charset-safe for the studio and bounded to 128 chars", () => {
    const key = runIdempotencyKey("9f8f8f8f-1111-4111-8111-111111111111", "sc/with spaces:v1");
    expect(key).toBe("vscode-9f8f8f8f-1111-4111-8111-111111111111-sc-with-spaces-v1");
    expect(key.length).toBeLessThanOrEqual(128);
    expect(key).toMatch(/^[A-Za-z0-9_-]+$/);
    expect(runIdempotencyKey("s1", "sc1")).toBe(runIdempotencyKey("s1", "sc1"));
    expect(runIdempotencyKey("s1", "sc1")).not.toBe(runIdempotencyKey("s1", "sc2"));
    expect(runIdempotencyKey("x".repeat(200), "sc1").length).toBe(128);
  });
});
