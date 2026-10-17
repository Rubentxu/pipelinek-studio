// @vitest-environment node
//
// R5 EVO-C.T3 (2026-10-16) — end-to-end wiring of the real `activate()`.
//
// The extension host is faked by `./__mocks__/vscode` (aliased as `vscode`
// in vitest.config.ts) and the studio by a fetch router, so every assertion
// is an observable result: an HTTP request, a message, a progress
// notification, or the text of an opened document. Nothing here pokes at
// extension internals.

import { describe, it, expect, beforeEach, afterEach, vi } from "vitest";
import { __createContext, __fireSave, __reset, __state, __fireConfigurationChange, commands, Uri, workspace } from "./__mocks__/vscode";
import { activate, deactivate } from "./extension";
import { ASSOCIATION_STATE_KEY } from "./fileAssociation";

interface Call {
  url: string;
  path: string;
  method: string;
  headers: Record<string, string>;
  body?: string;
}

let realFetch: typeof fetch;

function sseStream(): Response {
  const stream = new ReadableStream<Uint8Array>({
    start(controller) {
      controller.enqueue(new TextEncoder().encode(": connected\n\n"));
      // Stays open: the extension keeps one live stream until deactivate().
    },
  });
  return new Response(stream, { status: 200, headers: { "content-type": "text/event-stream" } });
}

const EXPERIMENTS = {
  items: [{ experiment_id: "e1", name: "demo", created_at: "2026-03-02T00:00:00Z" }],
};

const SNAPSHOTS = {
  items: [
    { snapshot_id: "s1", experiment_id: "e1", sha256: "a".repeat(64), created_at: "2026-03-02T01:00:00Z" },
  ],
};

const RUNS = {
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
      experiment_id: "e1",
      snapshot_id: "s1",
      scenario_id: "sc1",
      state: "FAILED",
      created_at: "2026-03-02T03:00:00Z",
    },
  ],
};

// The studio's real `GET /scenarios` shape (R0.3.1): a sealed scenario has
// an id and a version; `created_at` is served empty by the list route.
const SCENARIOS = {
  items: [
    {
      scenario_id: "sc1",
      version: 1,
      plugin_lock_digest: null,
      environment_profile: "trusted_local",
      created_at: "",
    },
  ],
};

function json(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "content-type": "application/json" },
  });
}

function installStudio(overrides: Record<string, () => Response> = {}): Call[] {
  const calls: Call[] = [];
  globalThis.fetch = vi.fn(async (input: unknown, init?: RequestInit) => {
    const url = new URL(String(input));
    const call: Call = {
      url: String(input),
      path: url.pathname + url.search,
      method: init?.method ?? "GET",
      // Normalise: the SSE client passes a `Headers` instance while the
      // typed API passes a plain object.
      headers: Object.fromEntries(
        [...new Headers((init?.headers ?? {}) as HeadersInit).entries()].map(([k, v]) => [k.toLowerCase(), v]),
      ),
      body: typeof init?.body === "string" ? init.body : undefined,
    };
    calls.push(call);
    const overrideKey = `${call.method} ${url.pathname}`;
    if (overrides[overrideKey]) return overrides[overrideKey]!();
    if (url.pathname === "/events") return sseStream();
    if (url.pathname === "/health") return json({ status: "ok" });
    if (url.pathname === "/openapi.json") return json({ openapi: "3.1.0" });
    if (url.pathname === "/experiments" && call.method === "GET") return json(EXPERIMENTS);
    if (url.pathname === "/experiments" && call.method === "POST") {
      return json({ experiment_id: "e9", name: "created", created_at: "2026-03-02T04:00:00Z" });
    }
    if (url.pathname === "/experiments/e1/snapshots" && call.method === "GET") return json(SNAPSHOTS);
    if (/^\/experiments\/[^/]+\/snapshots$/.test(url.pathname) && call.method === "POST") {
      return json({ snapshot_id: "s9", experiment_id: "e9", sha256: "b".repeat(64), created_at: "t" });
    }
    if (url.pathname === "/runs" && call.method === "POST") return json({ run_id: "r9", state: "CREATED" });
    if (url.pathname === "/runs" && call.method === "GET") return json(RUNS);
    if (url.pathname === "/steps") return json({ steps: [{ id: "compile", name: "Compile" }] });
    if (url.pathname === "/scenarios" && call.method === "GET") return json(SCENARIOS);
    if (url.pathname.endsWith("/cancel")) return json({ acknowledged: true });
    if (/^\/runs\/[^/]+\/events$/.test(url.pathname)) {
      const after = url.searchParams.get("after");
      if (after === null) {
        return json({
          runId: url.pathname.split("/")[2],
          events: [
            { sequence: 1, kind: "RunStarted", occurredAt: "2026-03-02T00:00:00Z", payload: { step: "compile" } },
            { sequence: 2, kind: "StepStarted", occurredAt: "2026-03-02T00:00:01Z", payload: { step: "compile" } },
          ],
          nextSequence: 2,
        });
      }
      return json({
        runId: url.pathname.split("/")[2],
        events: [{ sequence: 3, kind: "RunFinished", occurredAt: "2026-03-02T00:00:02Z", payload: { status: "OK" } }],
        nextSequence: null,
      });
    }
    if (url.pathname.endsWith("/diagnostics")) return json({ diagnostics: [{ id: "d1" }] });
    if (url.pathname.endsWith("/output")) return json({ run_id: "r1", chunks: [] });
    return json({ code: "BAD_REQUEST", message: `unexpected ${url.pathname}` }, 400);
  }) as unknown as typeof fetch;
  return calls;
}

async function flush(): Promise<void> {
  for (let i = 0; i < 8; i += 1) await Promise.resolve();
  await new Promise((resolve) => setTimeout(resolve, 0));
}

async function activateExtension(): Promise<void> {
  activate(__createContext() as never);
  await flush();
}

const health = () => commands.executeCommand("pipelinek-studio.health");
const statusBarText = () => __state.statusBarItems[0]?.text ?? "";

beforeEach(() => {
  realFetch = globalThis.fetch;
  __reset();
});

afterEach(() => {
  deactivate();
  globalThis.fetch = realFetch;
  vi.restoreAllMocks();
});

describe("preserved commands under an allowed configuration", () => {
  it("keeps Ping Health, Create Experiment, List Steps and Open OpenAPI behaving as before", async () => {
    const calls = installStudio();
    await activateExtension();

    await health();
    expect(__state.information.at(-1)?.message).toBe('PipelineK Studio /health -> {"status":"ok"}');

    __state.inputResponses.push("nightly-cache-bench");
    await commands.executeCommand("pipelinek-studio.createExperiment");
    const create = calls.find((c) => c.method === "POST" && c.path === "/experiments");
    expect(create?.body).toBe('{"name":"nightly-cache-bench"}');
    expect(__state.information.at(-1)?.message).toBe("Created experiment e9: created");

    __state.quickPickResponses.push({ label: "compile", description: "Compile" });
    await commands.executeCommand("pipelinek-studio.listSteps");
    expect(__state.information.at(-1)?.message).toBe("Selected step: compile");

    await commands.executeCommand("pipelinek-studio.openOpenApi");
    expect(__state.openedDocuments.at(-1)?.content).toBe('{"openapi":"3.1.0"}');
  });

  // T4 intentionally removes the old fake path (it created an experiment per
  // click and submitted the hard-coded scenario `vscode-default`). The lens
  // and the command stay registered; an unlinked document is now refused
  // instead of silently creating an experiment.
  it("keeps the Seal + Run CodeLens action registered and refuses an unlinked document", async () => {
    const calls = installStudio();
    await activateExtension();
    expect(__state.codeLensProviders.map((p) => p.pattern)).toContain("**/*.pipeline.kts");

    __state.fileTexts[FILE_URI] = "fun main() {}";
    await commands.executeCommand("pipelinek-studio.sealAndRun", FILE_URI);

    expect(calls.filter((c) => c.method === "POST")).toHaveLength(0);
    expect(__state.errors.at(-1)?.message).toMatch(/not linked/i);
  });

  it("refreshes the tree from the server instead of clearing it", async () => {
    installStudio();
    await activateExtension();
    const before = countRequests("/experiments");
    await commands.executeCommand("pipelinek-studio.tree.refresh");
    expect(countRequests("/experiments")).toBeGreaterThan(before);
    expect(__state.information.at(-1)?.message).toMatch(/refreshed/i);
  });
});

function countRequests(path: string): number {
  const calls = (globalThis.fetch as unknown as { mock: { calls: unknown[][] } }).mock.calls;
  return calls.filter((c) => String(c[0]).includes(path)).length;
}

describe("HTTP opt-in", () => {
  it("sends no request at all when the plaintext confirmation is declined", async () => {
    __state.config = { baseUrl: "http://127.0.0.1:7777", allowInsecureHttp: true, profile: "default" };
    const calls = installStudio();
    await activateExtension();

    expect(calls).toHaveLength(0);
    expect(__state.warnings).toHaveLength(1);
    expect(__state.warnings[0]!.message).toContain("http://127.0.0.1:7777");
    expect(statusBarText()).toBe("$(error) Studio: error");

    await health();
    expect(__state.errors.at(-1)?.message).toMatch(/http:\/\/127\.0\.0\.1:7777/);
    expect(calls).toHaveLength(0);
  });

  it("confirms once per exact URL and sends the profile header on every request", async () => {
    __state.config = { baseUrl: "http://127.0.0.1:7777", allowInsecureHttp: true, profile: "worktree" };
    __state.warningResponses.push("Allow HTTP");
    const calls = installStudio();
    await activateExtension();

    await health();
    await commands.executeCommand("pipelinek-studio.openRun", {
      kind: "run",
      experimentId: "e1",
      runId: "r1",
      status: "RUNNING",
    });

    expect(__state.warnings).toHaveLength(1);
    expect(calls.length).toBeGreaterThan(0);
    for (const call of calls) {
      expect(call.headers["x-studio-profile"], `${call.method} ${call.path}`).toBe("worktree");
    }
  });
});

describe("authoritative tree and run commands", () => {
  it("opens the real journal as JSONL, walking every page", async () => {
    installStudio();
    await activateExtension();

    await commands.executeCommand("pipelinek-studio.openRun", {
      kind: "run",
      experimentId: "e1",
      runId: "r1",
      status: "RUNNING",
    });

    const doc = __state.openedDocuments.at(-1);
    expect(doc?.languageId).toBe("json");
    expect(doc?.content.split("\n")).toEqual([
      '{"sequence":1,"kind":"RunStarted","occurredAt":"2026-03-02T00:00:00Z","payload":{"step":"compile"}}',
      '{"sequence":2,"kind":"StepStarted","occurredAt":"2026-03-02T00:00:01Z","payload":{"step":"compile"}}',
      '{"sequence":3,"kind":"RunFinished","occurredAt":"2026-03-02T00:00:02Z","payload":{"status":"OK"}}',
    ]);
    expect(__state.shownDocuments.at(-1)).toBe(doc);
  });

  it("keeps diagnostics on its own command", async () => {
    installStudio();
    await activateExtension();
    await commands.executeCommand("pipelinek-studio.runDiagnostics", {
      kind: "run",
      experimentId: "e1",
      runId: "r1",
      status: "RUNNING",
    });
    const doc = __state.openedDocuments.at(-1);
    expect(JSON.parse(doc!.content)).toEqual({
      runId: "r1",
      diagnostics: { diagnostics: [{ id: "d1" }] },
      output: { run_id: "r1", chunks: [] },
    });
  });

  it("cancels through withProgress and the real /runs/{id}/cancel route", async () => {
    const calls = installStudio();
    await activateExtension();
    await commands.executeCommand("pipelinek-studio.cancelRun", {
      kind: "run",
      experimentId: "e1",
      runId: "r1",
      status: "RUNNING",
    });

    expect(__state.progress).toHaveLength(1);
    expect(__state.progress[0]!.title).toContain("r1");
    expect(calls.some((c) => c.method === "POST" && c.path === "/runs/r1/cancel")).toBe(true);
    expect(__state.information.at(-1)?.message).toBe("Cancel r1: acknowledged by the studio");
    expect(__state.errors).toHaveLength(0);
  });

  it("surfaces the actual cancellation error", async () => {
    installStudio({
      "POST /runs/r1/cancel": () => json({ code: "CANCEL_REFUSED", message: "transport failure" }, 503),
    });
    await activateExtension();
    await commands.executeCommand("pipelinek-studio.cancelRun", {
      kind: "run",
      experimentId: "e1",
      runId: "r1",
      status: "RUNNING",
    });
    expect(__state.errors.at(-1)?.message).toBe("Cancel r1 failed: transport failure");
  });

  it("opens the most recent run when the status indicator is clicked", async () => {
    installStudio();
    await activateExtension();
    // The authoritative snapshot seeds two runs; r2 is the most recent.
    expect(statusBarText()).toBe("$(sync~spin) Studio: running 1");

    await commands.executeCommand("pipelinek-studio.status");
    const doc = __state.openedDocuments.at(-1);
    expect(doc?.content).toContain('"RunStarted"');
    expect(__state.shownDocuments).toHaveLength(1);
  });

  it("shows the status summary when no run is known", async () => {
    installStudio({ "GET /runs": () => json({ items: [] }) });
    await activateExtension();
    await commands.executeCommand("pipelinek-studio.status");
    expect(__state.information.at(-1)?.message).toMatch(/Studio: live/);
    expect(__state.openedDocuments).toHaveLength(0);
  });
});

describe("settings changes", () => {
  it("revalidates and reconnects with the new profile on a settings change", async () => {
    const calls = installStudio();
    await activateExtension();
    const before = calls.filter((c) => c.path.startsWith("/health")).length;

    __state.config = { baseUrl: "https://127.0.0.1:7778", allowInsecureHttp: false, profile: "worktree" };
    __fireConfigurationChange();
    await flush();

    await health();
    const healthCalls = calls.filter((c) => c.path.startsWith("/health"));
    expect(healthCalls.length).toBe(before + 1);
    expect(healthCalls.at(-1)?.headers["x-studio-profile"]).toBe("worktree");
  });

  it("goes to the error state without requesting when the new profile is invalid", async () => {
    installStudio();
    await activateExtension();
    __state.config = { baseUrl: "https://127.0.0.1:7778", allowInsecureHttp: false, profile: "☃" };
    __fireConfigurationChange();
    await flush();

    expect(statusBarText()).toBe("$(error) Studio: error");
    const before = countRequests("/health");
    await health();
    expect(__state.errors.at(-1)?.message).not.toMatch(/^\s*$/);
    expect(countRequests("/health")).toBe(before);
  });
});

// ---------------------------------------------------------------------------
// R5 EVO-C.T4 — persistent per-file experiment association and the real run.
//
// The operator chose "link this file to an existing experiment, show its
// state, and pick an existing scenario when a run is explicitly requested".
// Every assertion below is a public-interface observation: the requests that
// were (or were not) sent, the persisted workspace state, the CodeLens text,
// and the message/document the operator sees.
// ---------------------------------------------------------------------------

const FILE_URI = "file:///work/demo.pipeline.kts";
const OTHER_URI = "file:///work/other.pipeline.kts";

interface LensCommand {
  title: string;
  command: string;
  arguments?: unknown[];
}

interface MockLens {
  command?: LensCommand;
}

interface MockLensProvider {
  provideCodeLenses(doc: unknown): MockLens[];
  onDidChangeCodeLenses(listener: () => void): { dispose(): void };
}

function lensProvider(): MockLensProvider {
  const entry = __state.codeLensProviders.at(-1);
  if (!entry) throw new Error("no CodeLens provider was registered");
  return entry.provider as MockLensProvider;
}

function lensTitles(uri: string): string[] {
  const doc = { uri: Uri.parse(uri), languageId: "pipelinek", getText: () => __state.fileTexts[uri] ?? "" };
  return lensProvider()
    .provideCodeLenses(doc)
    .map((lens) => lens?.command?.title ?? "");
}

function codeLensChanges(): () => number {
  let count = 0;
  lensProvider().onDidChangeCodeLenses(() => {
    count += 1;
  });
  return () => count;
}

function associations(): unknown {
  return __state.workspaceState[ASSOCIATION_STATE_KEY];
}

function writes(calls: Call[]): Call[] {
  return calls.filter((call) => call.method === "POST");
}

async function linkFile(uri: string, experimentId: string, label = experimentId): Promise<void> {
  __state.quickPickResponses.push({ label, description: experimentId, experimentId });
  await commands.executeCommand("pipelinek-studio.linkExperiment", uri);
}

async function chooseScenario(scenarioId: string, version = 1, profile = "trusted_local"): Promise<void> {
  __state.quickPickResponses.push({ label: scenarioId, description: `v${version} - ${profile}`, scenarioId });
}

describe("file → experiment association", () => {
  it("links a file to an existing experiment and shows the name and state in the CodeLens", async () => {
    const calls = installStudio();
    await activateExtension();
    __state.fileTexts[FILE_URI] = "fun main() {}";

    expect(lensTitles(FILE_URI)).toEqual(["Studio: Seal + Run", "Studio: No linked experiment"]);

    await linkFile(FILE_URI, "e1", "demo");

    expect(__state.quickPicks.at(-1)).toEqual([{ label: "demo", description: "e1", experimentId: "e1" }]);
    expect(associations()).toEqual({ [FILE_URI]: "e1" });
    expect(__state.information.at(-1)?.message).toBe("Linked demo.pipeline.kts to experiment demo (e1).");
    expect(lensTitles(FILE_URI)).toEqual([
      "Studio: Seal + Run",
      // r2 is the most recently created run of e1 in the authoritative read.
      "Studio: demo - FAILED",
      "Studio: Change linked experiment",
    ]);
    // Linking never creates an experiment and never runs anything.
    expect(writes(calls)).toHaveLength(0);
  });

  it("writes nothing when the experiment pick is cancelled", async () => {
    const calls = installStudio();
    await activateExtension();

    await commands.executeCommand("pipelinek-studio.linkExperiment", FILE_URI);

    expect(associations()).toBeUndefined();
    expect(writes(calls)).toHaveLength(0);
    expect(lensTitles(FILE_URI)).toContain("Studio: No linked experiment");
    expect(__state.errors).toHaveLength(0);
  });

  it("reports the studio's own experiment choices and never invents one", async () => {
    installStudio({ "GET /experiments": () => json({ items: [] }) });
    await activateExtension();

    await commands.executeCommand("pipelinek-studio.linkExperiment", FILE_URI);

    expect(__state.quickPicks).toHaveLength(0);
    expect(__state.information.at(-1)?.message).toMatch(/no experiments/i);
    expect(associations()).toBeUndefined();
  });

  it("survives a new controller/session instance over the same workspace state", async () => {
    installStudio();
    await activateExtension();
    await linkFile(FILE_URI, "e1", "demo");
    expect(associations()).toEqual({ [FILE_URI]: "e1" });

    deactivate();
    await activateExtension();

    expect(associations()).toEqual({ [FILE_URI]: "e1" });
    expect(lensTitles(FILE_URI)).toEqual([
      "Studio: Seal + Run",
      "Studio: demo - FAILED",
      "Studio: Change linked experiment",
    ]);
  });

  it("keeps one association per document and changes only the addressed file", async () => {
    installStudio();
    await activateExtension();
    await linkFile(FILE_URI, "e1", "demo");
    await linkFile(OTHER_URI, "e1", "demo");

    expect(associations()).toEqual({ [FILE_URI]: "e1", [OTHER_URI]: "e1" });
  });

  it("keeps the HTTP consent gate on the link and run commands", async () => {
    __state.config = { baseUrl: "http://127.0.0.1:7777", allowInsecureHttp: true, profile: "default" };
    const calls = installStudio();
    await activateExtension();
    expect(calls).toHaveLength(0);

    await commands.executeCommand("pipelinek-studio.linkExperiment", FILE_URI);
    await commands.executeCommand("pipelinek-studio.sealAndRun", FILE_URI);

    expect(calls).toHaveLength(0);
    expect(__state.errors.at(-1)?.message).toMatch(/not linked/i);
    expect(associations()).toBeUndefined();
  });

  it("refreshes the CodeLens on source saves and on authoritative tree updates", async () => {
    const overrides: Record<string, () => Response> = {};
    installStudio(overrides);
    await activateExtension();
    await linkFile(FILE_URI, "e1", "demo");
    const changes = codeLensChanges();

    __fireSave(FILE_URI);
    expect(changes()).toBe(1);

    // The studio is authoritative: a run finishing is reflected in the lens
    // only after the tree is reconciled against the collections.
    overrides["GET /runs"] = () =>
      json({
        items: [
          {
            run_id: "r1",
            experiment_id: "e1",
            snapshot_id: "s1",
            scenario_id: "sc1",
            state: "SUCCEEDED",
            created_at: "2026-03-02T02:00:00Z",
          },
        ],
      });
    await commands.executeCommand("pipelinek-studio.tree.refresh");

    expect(lensTitles(FILE_URI)[1]).toBe("Studio: demo - SUCCEEDED");
    expect(changes()).toBeGreaterThan(1);
  });
});

describe("Seal + Run for a linked file", () => {
  it("seals the current source for the linked experiment and submits an existing scenario", async () => {
    const calls = installStudio();
    await activateExtension();
    __state.fileTexts[FILE_URI] = "fun main() { println(1) }\n";
    await linkFile(FILE_URI, "e1", "demo");
    const before = countRequests("/experiments");

    await chooseScenario("sc1");
    await commands.executeCommand("pipelinek-studio.sealAndRun", FILE_URI);

    expect(__state.quickPicks.at(-1)).toEqual([
      { label: "sc1", description: "v1 - trusted_local", scenarioId: "sc1" },
    ]);
    const seal = calls.find((c) => c.method === "POST" && c.path === "/experiments/e1/snapshots");
    expect(seal?.body).toBe(JSON.stringify({ source: "fun main() { println(1) }\n", creator: "vscode" }));
    const run = calls.find((c) => c.method === "POST" && c.path === "/runs");
    expect(run?.body).toBe(JSON.stringify({ experiment_id: "e1", snapshot_id: "s9", scenario_id: "sc1" }));
    expect(run?.headers["idempotency-key"]).toBe("vscode-s9-sc1");
    // No experiment is created by a run.
    expect(calls.filter((c) => c.method === "POST" && c.path === "/experiments")).toHaveLength(0);
    expect(__state.information.at(-1)?.message).toBe("Seal+Run: run id r9 (CREATED)");
    expect(__state.information.at(-1)?.items).toContain("Open Run");
    // The tree is reconciled against the studio, not patched locally.
    expect(countRequests("/experiments")).toBeGreaterThan(before);
    expect(associations()).toEqual({ [FILE_URI]: "e1" });
  });

  it("makes no seal and no submit request when the scenario pick is cancelled", async () => {
    const calls = installStudio();
    await activateExtension();
    await linkFile(FILE_URI, "e1", "demo");

    await commands.executeCommand("pipelinek-studio.sealAndRun", FILE_URI);

    expect(writes(calls)).toHaveLength(0);
    expect(associations()).toEqual({ [FILE_URI]: "e1" });
    expect(__state.errors).toHaveLength(0);
  });

  it("makes no request at all for an unlinked document", async () => {
    const calls = installStudio();
    await activateExtension();
    const before = calls.length;

    await commands.executeCommand("pipelinek-studio.sealAndRun", FILE_URI);

    expect(calls).toHaveLength(before);
    expect(__state.errors.at(-1)?.message).toMatch(/not linked/i);
    expect(associations()).toBeUndefined();
  });

  it("refuses a linked experiment the studio no longer has, without sealing or submitting", async () => {
    __state.workspaceState[ASSOCIATION_STATE_KEY] = { [FILE_URI]: "e404" };
    const calls = installStudio();
    await activateExtension();

    await chooseScenario("sc1");
    await commands.executeCommand("pipelinek-studio.sealAndRun", FILE_URI);

    expect(writes(calls)).toHaveLength(0);
    expect(__state.errors.at(-1)?.message).toMatch(/e404/);
    expect(associations()).toEqual({ [FILE_URI]: "e404" });
  });

  it("refuses to run without a registered scenario instead of inventing an id", async () => {
    const calls = installStudio({ "GET /scenarios": () => json({ items: [] }) });
    await activateExtension();
    await linkFile(FILE_URI, "e1", "demo");

    await commands.executeCommand("pipelinek-studio.sealAndRun", FILE_URI);

    expect(writes(calls)).toHaveLength(0);
    expect(__state.errors.at(-1)?.message).toMatch(/scenario/i);
  });

  it("opens the run through openRun with the true run id", async () => {
    const calls = installStudio();
    await activateExtension();
    await linkFile(FILE_URI, "e1", "demo");
    await chooseScenario("sc1");
    __state.informationResponses.push("Open Run");

    await commands.executeCommand("pipelinek-studio.sealAndRun", FILE_URI);

    expect(calls.some((c) => c.method === "GET" && c.path.startsWith("/runs/r9/events"))).toBe(true);
    expect(__state.openedDocuments.at(-1)?.content).toContain('"RunStarted"');
  });
});

// ---------------------------------------------------------------------------
// R5 correction (2026-10-16) — concurrent invocations and settings races.
//
// Three candidate-caused defects are pinned here through the public command
// surface: one consent dialog + one stream for concurrent connect attempts, no
// stale client when the URL changes while consent is pending, and one
// seal/submit for two concurrent invocations of the same document (while a
// deliberate second invocation still runs again).
// ---------------------------------------------------------------------------

describe("concurrent command invocations", () => {
  it("uses one plaintext consent dialog and one event stream for concurrent commands", async () => {
    __state.config = { baseUrl: "http://127.0.0.1:7777", allowInsecureHttp: true, profile: "default" };
    let allow: (choice: string | undefined) => void = () => undefined;
    __state.warningResponses.push(
      new Promise<string | undefined>((resolve) => {
        allow = resolve;
      }),
    );
    const calls = installStudio();

    await activateExtension();
    expect(__state.warnings).toHaveLength(1);
    expect(calls).toHaveLength(0);

    // Two commands race the still-open consent dialog.
    const both = Promise.all([health(), health()]);
    await flush();
    expect(__state.warnings).toHaveLength(1);

    allow("Allow HTTP");
    await both;
    await flush();

    expect(__state.warnings).toHaveLength(1);
    expect(countRequests("/events")).toBe(1);
    expect(calls.filter((call) => call.path === "/health")).toHaveLength(2);
    expect(__state.information.filter((m) => m.message.includes("/health -> "))).toHaveLength(2);
  });

  it("never hands out a stale client when the settings change to a declined URL while consent is pending", async () => {
    __state.config = { baseUrl: "http://127.0.0.1:7777", allowInsecureHttp: true, profile: "default" };
    let allowA: (choice: string | undefined) => void = () => undefined;
    __state.warningResponses.push(
      new Promise<string | undefined>((resolve) => {
        allowA = resolve;
      }),
    );
    const calls = installStudio();

    await activateExtension();
    expect(__state.warnings).toHaveLength(1);
    expect(__state.warnings[0]!.message).toContain("http://127.0.0.1:7777");

    // The operator changes the URL and declines the new plaintext host.
    __state.config = { baseUrl: "http://127.0.0.1:7999", allowInsecureHttp: true, profile: "default" };
    __fireConfigurationChange();
    await flush();

    expect(__state.warnings).toHaveLength(2);
    expect(__state.warnings[1]!.message).toContain("http://127.0.0.1:7999");
    expect(statusBarText()).toBe("$(error) Studio: error");

    // The replaced dialog now resolves with approval: the old URL must not
    // be resurrected and nothing may be sent to either host.
    allowA("Allow HTTP");
    await flush();
    expect(statusBarText()).toBe("$(error) Studio: error");
    expect(calls).toHaveLength(0);

    await health();
    expect(__state.errors.at(-1)?.message).toMatch(/7999/);
    expect(countRequests("/health")).toBe(0);
    expect(calls.filter((call) => call.url.includes("7777"))).toHaveLength(0);
  });

  function installUniqueSnapshots(): { seals: () => Call[]; submits: () => Call[] } {
    let issued = 0;
    const calls = installStudio({
      "POST /experiments/e1/snapshots": () => {
        issued += 1;
        return json({ snapshot_id: `s${issued}`, experiment_id: "e1", sha256: "b".repeat(64), created_at: "t" });
      },
    });
    return {
      seals: () => calls.filter((call) => call.method === "POST" && call.path === "/experiments/e1/snapshots"),
      submits: () => calls.filter((call) => call.method === "POST" && call.path === "/runs"),
    };
  }

  it("seals and submits once for two concurrent invocations of the same document", async () => {
    const { seals, submits } = installUniqueSnapshots();
    await activateExtension();
    __state.fileTexts[FILE_URI] = "fun main() {}\n";
    await linkFile(FILE_URI, "e1", "demo");
    const picksBefore = __state.quickPicks.length;
    await chooseScenario("sc1");

    await Promise.all([
      commands.executeCommand("pipelinek-studio.sealAndRun", FILE_URI),
      commands.executeCommand("pipelinek-studio.sealAndRun", FILE_URI),
    ]);

    expect(seals()).toHaveLength(1);
    expect(submits()).toHaveLength(1);
    // One scenario selection: the second invocation never reached the picker.
    expect(__state.quickPicks.slice(picksBefore)).toHaveLength(1);
    expect(JSON.parse(submits()[0]!.body!)).toMatchObject({ snapshot_id: "s1", scenario_id: "sc1" });
    expect(submits()[0]!.headers["idempotency-key"]).toBe("vscode-s1-sc1");
  });

  it("still seals and submits again for a deliberate second invocation", async () => {
    const { seals, submits } = installUniqueSnapshots();
    await activateExtension();
    __state.fileTexts[FILE_URI] = "fun main() {}\n";
    await linkFile(FILE_URI, "e1", "demo");
    await chooseScenario("sc1");
    await chooseScenario("sc1");

    await commands.executeCommand("pipelinek-studio.sealAndRun", FILE_URI);
    await commands.executeCommand("pipelinek-studio.sealAndRun", FILE_URI);

    expect(seals()).toHaveLength(2);
    expect(submits()).toHaveLength(2);
    // Distinct snapshots mean distinct idempotency keys: two real runs.
    expect(submits()[0]!.headers["idempotency-key"]).toBe("vscode-s1-sc1");
    expect(submits()[1]!.headers["idempotency-key"]).toBe("vscode-s2-sc1");
  });
});
