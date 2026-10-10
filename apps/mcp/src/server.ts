// MCP v2 stdio adapter — closes ROADMAP E0.9 (MCP integration in S0).
// Per ADR-004 and D-004 in odd/tasks/pipelinek-studio-bootstrap.md:
//   - MCP is a THIN adapter that talks HTTP to the Studio backend; it does NOT
//     reimplement business rules or hit SQLite directly.
//   - Tools: studio.health, studio.create_experiment, studio.seal_snapshot,
//     studio.start_run, studio.inspect_run, studio.list_events.
//   - Resources: studio://version, studio://catalog/steps (paginated, E4.5).
//   - Honest answer keys: real SDK_RUN_ID when admitted, REFUSED otherwise.

import { Server } from "@modelcontextprotocol/sdk/server/index.js";
import { StdioServerTransport } from "@modelcontextprotocol/sdk/server/stdio.js";
import {
  ListToolsRequestSchema,
  CallToolRequestSchema,
  ListResourcesRequestSchema,
  ReadResourceRequestSchema,
} from "@modelcontextprotocol/sdk/types.js";

const STUDIO_HTTP = process.env.STUDIO_HTTP ?? "http://127.0.0.1:7777";

interface ToolResult {
  content: Array<{ type: "text"; text: string }>;
  isError?: boolean;
  // The MCP SDK CallToolResultSchema reserves _meta for protocol metadata.
  // We never set it; including it here keeps the structural type compatible
  // with the SDK's setRequestHandler signature.
  _meta?: Record<string, unknown>;
}

function ok(text: string): ToolResult {
  return { content: [{ type: "text", text }] };
}

function err(text: string): ToolResult {
  return { isError: true, content: [{ type: "text", text }] };
}

async function callHttp(
  method: string,
  path: string,
  body?: Record<string, unknown>,
  idempotencyKey?: string,
): Promise<ToolResult> {
  try {
    const headers: Record<string, string> = { "content-type": "application/json" };
    if (idempotencyKey) headers["idempotency-key"] = idempotencyKey;
    const res = await fetch(`${STUDIO_HTTP}${path}`, {
      method,
      headers,
      body: body ? JSON.stringify(body) : undefined,
    });
    let parsed: unknown = null;
    try { parsed = await res.json(); } catch { parsed = null; }
    if (res.status >= 200 && res.status < 300) {
      return ok(`HTTP ${res.status}: ${JSON.stringify(parsed ?? {})}`);
    }
    if (res.status === 202) {
      return ok(`accepted-with-caveat status=${res.status}: ${JSON.stringify(parsed ?? {})}`);
    }
    return err(`HTTP ${res.status}: ${JSON.stringify(parsed ?? {})}`);
  } catch (e) {
    return err(`network error: ${(e as Error).message}`);
  }
}

/**
 * Local (in-memory) pagination over the step catalog. The current
 * implementation fetches the full /steps response once and slices
 * it for each call. The catalog size for a typical SDK is small
 * (< 100 steps) so this is acceptable. Audit F-P1-2 (2026-10-10)
 * documents this as a known limit: server-side pagination on
 * /steps would be a future S5 hardening pass. The cursor stays
 * opaque to MCP clients; only the studio backend + the local
 * paginator interpret it.
 *
 * Returns the requested page + a `next_cursor` that is either the
 * index of the next item or `null` when the page is the last.
 */
function paginate<T>(arr: T[], cursor: number, limit: number): { page: T[]; nextCursor: number | null } {
  const safeCursor = Math.max(0, Math.floor(cursor));
  const safeLimit = Math.max(1, Math.min(limit, 200));
  const slice = arr.slice(safeCursor, safeCursor + safeLimit);
  const next = safeCursor + slice.length;
  return { page: slice, nextCursor: next < arr.length ? next : null };
}

const server = new Server(
  { name: "pipelinek-studio-mcp", version: "0.1.0-alpha.1" },
  { capabilities: { tools: {}, resources: {} } },
);

// initialize is handled automatically by the SDK; we only register the schema'd handlers.

server.setRequestHandler(ListToolsRequestSchema, async () => ({
  tools: [
    {
      name: "studio.health",
      description: "Returns the Studio backend health status.",
      inputSchema: { type: "object", properties: {}, additionalProperties: false },
    },
    {
      name: "studio.create_experiment",
      description: "Create an experiment container; returns experiment_id (UUID v4).",
      inputSchema: {
        type: "object",
        properties: { name: { type: "string" } },
        required: ["name"],
        additionalProperties: false,
      },
    },
    {
      name: "studio.seal_snapshot",
      description:
        "Seal a Kotlin source snapshot under an experiment. Source is raw UTF-8 Kotlin source.",
      inputSchema: {
        type: "object",
        properties: {
          experiment_id: { type: "string" },
          source: { type: "string" },
          creator: { type: "string" },
        },
        required: ["experiment_id", "source", "creator"],
        additionalProperties: false,
      },
    },
    {
      name: "studio.start_run",
      description:
        "Submit a run binding to a real PipelineK subprocess. Caller supplies idempotency-key.",
      inputSchema: {
        type: "object",
        properties: {
          experiment_id: { type: "string" },
          snapshot_id: { type: "string" },
          scenario_id: { type: "string" },
          idempotency_key: { type: "string" },
        },
        required: ["experiment_id", "snapshot_id", "scenario_id", "idempotency_key"],
        additionalProperties: false,
      },
    },
    {
      name: "studio.inspect_run",
      description: "Look up run state.",
      inputSchema: {
        type: "object",
        properties: { run_id: { type: "string" } },
        required: ["run_id"],
        additionalProperties: false,
      },
    },
    {
      name: "studio.list_tasks",
      description:
        "AAT-MCP-008 — list the studio's long-running operations as a task list. " +
        "The studio's run_bindings are surfaced as task records (id, kind, state, " +
        "created_at, updated_at). Returns up to `limit` recent tasks (default 50).",
      inputSchema: {
        type: "object",
        properties: {
          state: { type: "string" },
          limit: { type: "number" },
        },
        additionalProperties: false,
      },
    },
    {
      name: "studio.list_events",
      description:
        "List events from run.db (Event Plane). Real events when SDK present; refusal otherwise.",
      inputSchema: {
        type: "object",
        properties: {
          run_id: { type: "string" },
          after: { type: "number" },
          limit: { type: "number" },
        },
        required: ["run_id"],
        additionalProperties: false,
      },
    },
  ],
}));

// Use the SDK's pre-defined schema for tools/call (MCP SDK 1.0.4 exports
// CallToolRequestSchema; a custom zod re-declaration type-mismatches the
// SDK's internal signature in zod 3.x). The handler then narrows via a
// structural cast for our switch dispatch.
server.setRequestHandler(CallToolRequestSchema, async (req) => {
  const { name, arguments: args } = req.params;
  const a = (args ?? {}) as Record<string, unknown>;
  switch (name) {
    case "studio.health":
      return callHttp("GET", "/health");
    case "studio.create_experiment":
      return callHttp("POST", "/experiments", { name: String(a.name ?? "") });
    case "studio.seal_snapshot":
      return callHttp(
        "POST",
        `/experiments/${encodeURIComponent(String(a.experiment_id ?? ""))}/snapshots`,
        { source: String(a.source ?? ""), creator: String(a.creator ?? "mcp") },
      );
    case "studio.start_run":
      return callHttp(
        "POST",
        "/runs",
        {
          experiment_id: String(a.experiment_id ?? ""),
          snapshot_id: String(a.snapshot_id ?? ""),
          scenario_id: String(a.scenario_id ?? ""),
        },
        String(a.idempotency_key ?? ""),
      );
    case "studio.inspect_run":
      return callHttp("GET", `/runs/${encodeURIComponent(String(a.run_id ?? ""))}`);
    case "studio.list_events":
      return callHttp("GET", `/runs/${encodeURIComponent(String(a.run_id ?? ""))}/events`);
    case "studio.list_tasks": {
      // AAT-MCP-008 — list the studio's run_bindings as a task
      // list. We forward to GET /runs with optional state +
      // limit query params; the studio's response is then
      // reformatted into a task-like shape (id, kind, state,
      // created_at, updated_at) so MCP clients can poll for
      // progress without knowing the studio's run_id space.
      const state = String(a.state ?? "");
      const limit = Number(a.limit ?? 50);
      const qs = new URLSearchParams();
      if (state) qs.set("state", state);
      qs.set("limit", String(Math.min(Math.max(limit, 1), 200)));
      const listResp = await callHttp("GET", `/runs?${qs.toString()}`);
      const listText = listResp.content[0]?.text ?? "{}";
      const m = listText.match(/^HTTP \d+: (.+)$/);
      const body = m ? safeJson(m[1]) : null;
      if (!body || !Array.isArray((body as { items?: unknown[] }).items)) {
        return listResp;
      }
      const items = (body as { items: Array<Record<string, unknown>> }).items;
      const tasks = items.map((r) => ({
        id: String(r.run_id ?? ""),
        kind: "run",
        state: String(r.state ?? "UNKNOWN"),
        created_at: String(r.created_at ?? ""),
        updated_at: String(r.created_at ?? ""),
        // Pass through the claim_status (UAT-046) so MCP clients
        // can distinguish authority-bearing runs from
        // not-yet-executed ones.
        claim_status: r.claim_status ?? "NOT_EXECUTED",
      }));
      return ok(
        `HTTP 200: ${JSON.stringify({
          tasks,
          total: tasks.length,
        })}`,
      );
    }
    default:
      return err(`tool '${name}' not implemented`);
  }
});

server.setRequestHandler(ListResourcesRequestSchema, async () => ({
  resources: [
    {
      uri: "studio://version",
      name: "Studio version + SDK descriptor",
      description: "Reads /api/version on the backend to surface SDK status to MCP clients.",
      mimeType: "application/json",
    },
    {
      uri: "studio://catalog/steps",
      name: "Admitted Step plugin catalog (paginated, S2/E4.5)",
      description:
        "Enumerates the Step plugin catalog. Supports ?cursor=N&limit=M query params. " +
        "Returns { page: [...], next_cursor: N | null, total: N }.",
      mimeType: "application/json",
    },
  ],
}));

// Use the SDK's pre-defined schema for resources/read (see CallToolRequestSchema
// rationale above).
server.setRequestHandler(ReadResourceRequestSchema, async (req) => {
  const { uri } = req.params;
  // Pagination params come via the standard MCP cursor/limit fields when the
  // client supplies them; the SDK forwards them as part of the params object.
  const params = req.params as { uri: string; cursor?: number; limit?: number };
  const cursor = params.cursor;
  const limit = params.limit;
  if (uri === "studio://version") {
    return callHttp("GET", "/api/version");
  }
  if (uri === "studio://catalog/steps") {
    // Audit F-P1-2 (2026-10-10): we fetch the FULL /steps response
    // once per call and paginate locally. The catalog size for a
    // typical SDK is small (< 100 steps) so this is acceptable.
    // Server-side pagination on /steps (with ?limit + ?cursor
    // query params) is a future S5 hardening pass; until then the
    // local paginator and the limit cap (200) are the only
    // boundaries. A future change should also cache the full
    // response for a short TTL so the second paginated call does
    // not re-fetch.
    const page = await callHttp("GET", "/steps");
    // The /steps endpoint returns either { steps: [...] } (success) or { code, message }
    // (refusal). When the backend is healthy we paginate; otherwise we surface the
    // refusal as-is — the MCP client must see honest REFUSED, not a paginated empty
    // page.
    const text = page.content[0]?.text ?? "{}";
    const m = text.match(/^HTTP \d+: (.+)$/);
    const body = m ? safeJson(m[1]) : null;
    if (!body || !Array.isArray((body as { steps?: unknown[] }).steps)) {
      return page;
    }
    const arr = (body as { steps: unknown[] }).steps;
    const out = paginate(arr, cursor ?? 0, limit ?? 50);
    return ok(
      `HTTP 200: ${JSON.stringify({
        page: out.page,
        next_cursor: out.nextCursor,
        total: arr.length,
      })}`,
    );
  }
  return err(`unknown resource: ${uri}`);
});

function safeJson(s: string): unknown {
  try { return JSON.parse(s); } catch { return null; }
}

const transport = new StdioServerTransport();
await server.connect(transport);
