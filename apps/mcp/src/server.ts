// MCP v2 stdio adapter — closes ROADMAP E0.9 (MCP integration in S0).
// Per ADR-004 and D-004 in odd/tasks/pipelinek-studio-bootstrap.md:
//   - MCP is a THIN adapter that talks HTTP to the Studio backend; it does NOT
//     reimplement business rules or hit SQLite directly.
//   - Tools: studio.health, studio.create_experiment, studio.seal_snapshot,
//     studio.start_run, studio.inspect_run, studio.list_events.
//   - Resources: studio://version, studio://catalog/steps (paginated, E4.5).
//   - Honest answer keys: real SDK_RUN_ID when admitted, REFUSED otherwise.
//
// Studio URL resolution (audit F-P1-MCP-1, 2026-10-10):
//   - Default: https://127.0.0.1:7778 — matches the studio's post-C-1
//     HTTPS default. The previous default (http://127.0.0.1:7777) was a
//     silent-downgrade path: if an operator enabled --insecure-http on
//     the backend, the MCP would happily talk plaintext, defeating
//     the C-1 hardening.
//   - Override: STUDIO_HTTP=https://host:port for a different host/port.
//   - Opt-out: STUDIO_HTTP_INSECURE=1 to talk plaintext to a backend
//     running with --insecure-http. Without this flag, the MCP will
//     refuse to start against an http:// URL — that's the point: the
//     default must not silently downgrade.
//
// Timeout (audit F-P1-MCP-2, 2026-10-10):
//   - STUDIO_HTTP_TIMEOUT_MS caps every outgoing request. A hung
//     backend previously froze the MCP stdio forever, blocking the
//     agent. Default 5000ms.

import { Server } from "@modelcontextprotocol/sdk/server/index.js";
import { StdioServerTransport } from "@modelcontextprotocol/sdk/server/stdio.js";
import {
  ListToolsRequestSchema,
  CallToolRequestSchema,
  ListResourcesRequestSchema,
  ReadResourceRequestSchema,
} from "@modelcontextprotocol/sdk/types.js";

import {
  resolveStudioUrl,
  resolveTimeoutMs,
  fetchWithTimeout,
  requireString,
  optionalString,
  optionalNumber,
  McpInputError,
} from "./config.js";

const STUDIO_HTTP = resolveStudioUrl();
const STUDIO_TIMEOUT_MS = resolveTimeoutMs();

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
  // Audit F-P1-MCP-2: every outgoing call gets a hard deadline via
  // AbortController. A hung backend used to freeze the MCP stdio
  // forever, which is a denial of service against the agent. The
  // timeout is configurable via STUDIO_HTTP_TIMEOUT_MS; the default
  // is intentionally tight (5s) — a healthy local backend answers
  // in well under 1s, and a slow run_start is already async on the
  // backend side, so the MCP only needs to wait for the admission
  // decision, not the run itself.
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), STUDIO_TIMEOUT_MS);
  try {
    const headers: Record<string, string> = {
      "content-type": "application/json",
      "host": new URL(STUDIO_HTTP).host,
    };
    if (idempotencyKey) headers["idempotency-key"] = idempotencyKey;
    const res = await fetchWithTimeout(
      `${STUDIO_HTTP}${path}`,
      {
        method,
        headers,
        body: body ? JSON.stringify(body) : undefined,
      },
      STUDIO_TIMEOUT_MS,
    );
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
    const err_ = e as Error & { cause?: { name?: string } };
    // Node 18+ wraps an AbortController-triggered abort in a
    // TypeError whose `cause` is the underlying AbortError. The
    // older direct-AbortError shape is kept for completeness.
    const isAbort =
      err_.name === "AbortError" || err_.cause?.name === "AbortError";
    if (isAbort) {
      return err(`timeout after ${STUDIO_TIMEOUT_MS}ms: ${method} ${path}`);
    }
    return err(`network error: ${err_.message}`);
  }
}

/**
 * Audit F-P1-MCP-4 (2026-10-10): the previous list_tasks handler
 * parsed the callHttp response text with a regex (`/^HTTP \d+:
 * (.+)$/`) to recover the JSON, then re-parsed with
 * `safeJson`. The round-trip was fragile: a single newline in
 * the response body (legitimate in any pretty-printed JSON)
 * would break the regex and silently fall through to the
 * `return listResp` branch, surfacing the raw text to the
 * agent instead of the structured task list.
 *
 * `callHttpJson` is the structured counterpart: same fetch,
 * same timeout, same auth headers, but returns `{ status, body }`
 * with body already parsed (or null on parse error). The
 * list_tasks handler uses it to read `body.items` directly,
 * eliminating the regex entirely. The text-based `callHttp`
 * stays as-is for the other 6 tool handlers that don't need
 * to re-process the body.
 */
async function callHttpJson(
  method: string,
  path: string,
  body?: Record<string, unknown>,
  idempotencyKey?: string,
): Promise<{ status: number; body: unknown }> {
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), STUDIO_TIMEOUT_MS);
  try {
    const headers: Record<string, string> = {
      "content-type": "application/json",
      "host": new URL(STUDIO_HTTP).host,
    };
    if (idempotencyKey) headers["idempotency-key"] = idempotencyKey;
    const res = await fetchWithTimeout(
      `${STUDIO_HTTP}${path}`,
      {
        method,
        headers,
        body: body ? JSON.stringify(body) : undefined,
      },
      STUDIO_TIMEOUT_MS,
    );
    let parsed: unknown = null;
    try { parsed = await res.json(); } catch { parsed = null; }
    return { status: res.status, body: parsed };
  } finally {
    clearTimeout(timer);
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

/**
 * R4.1 mandate item 7 (2026-10-11): 'No anunciar soporte de MCP Tasks
 * si el SDK o cliente no lo demuestra'. The MCP protocol exposes a
 * dedicated Tasks capability (MCP 2026-07-28) for long-running
 * operations the server can hand back as task records. Studio does
 * NOT implement that protocol feature (Studio surfaces its own
 * run_bindings via the regular `studio.list_tasks` tool, which is
 * a custom tool — not the MCP Tasks capability). The capabilities
 * object MUST stay exactly { tools, resources }; adding `tasks: {}`
 * would advertise support we do not implement, and clients would
 * then call the Tasks API and get an empty/error response.
 *
 * Exported so the contract is testable in isolation (without
 * importing this side-effecting file, which would start stdio).
 */
export const MCP_CAPABILITIES = { tools: {}, resources: {} } as const;

const server = new Server(
  { name: "pipelinek-studio-mcp", version: "0.1.0-alpha.1" },
  { capabilities: MCP_CAPABILITIES },
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
  // Audit F-P1-MCP-3 (2026-10-10): the tool handlers previously
  // did `String(a.name ?? "")` for every required arg, which
  // silently coerced null/missing to "" and pushed the validation
  // burden onto the backend. We now gate the inputs at the tool
  // boundary with the helpers in config.ts; a missing or
  // malformed arg surfaces as a structured INVALID_ARGUMENT
  // error instead of a confusing 400.
  try {
    switch (name) {
      case "studio.health":
        return callHttp("GET", "/health");
      case "studio.create_experiment":
        return callHttp("POST", "/experiments", { name: requireString(a, "name") });
      case "studio.seal_snapshot":
        return callHttp(
          "POST",
          `/experiments/${encodeURIComponent(requireString(a, "experiment_id"))}/snapshots`,
          {
            source: requireString(a, "source"),
            creator: optionalString(a, "creator", "mcp"),
          },
        );
      case "studio.start_run": {
        const idemKey = requireString(a, "idempotency_key");
        return callHttp(
          "POST",
          "/runs",
          {
            experiment_id: requireString(a, "experiment_id"),
            snapshot_id: requireString(a, "snapshot_id"),
            scenario_id: requireString(a, "scenario_id"),
          },
          idemKey,
        );
      }
      case "studio.inspect_run":
        return callHttp("GET", `/runs/${encodeURIComponent(requireString(a, "run_id"))}`);
      case "studio.list_events": {
        // Audit F-P1-MCP-5 (2026-10-10): the previous handler
        // forwarded `after` and `limit` verbatim. The schema
        // declared both as `number` with no bounds, so a
        // user could ask for `limit: 1000000` and either
        // get a giant page or trigger downstream DoS. The
        // new handler bounds `after` to non-negative and
        // `limit` to [1, 200], matching the list_tasks
        // contract and the backend's documented Event Plane
        // pagination window.
        const runId = requireString(a, "run_id");
        const after = optionalNumber(a, "after", 0, 0, Number.MAX_SAFE_INTEGER);
        const limit = optionalNumber(a, "limit", 50, 1, 200);
        const qs = new URLSearchParams();
        if (after > 0) qs.set("after", String(after));
        qs.set("limit", String(limit));
        const path = `/runs/${encodeURIComponent(runId)}/events${qs.toString() ? `?${qs.toString()}` : ""}`;
        return callHttp("GET", path);
      }
      case "studio.list_tasks": {
        // AAT-MCP-008 — list the studio's run_bindings as a task
        // list. We forward to GET /runs with optional state +
        // limit query params; the studio's response is then
        // reformatted into a task-like shape (id, kind, state,
        // created_at, updated_at) so MCP clients can poll for
        // progress without knowing the studio's run_id space.
        //
        // Audit F-P1-MCP-4 (2026-10-10): now uses callHttpJson
        // for the structured body; the previous regex-based
        // round-trip was fragile when the JSON contained a
        // newline.
        const state = optionalString(a, "state", "");
        const limit = optionalNumber(a, "limit", 50, 1, 200);
        const qs = new URLSearchParams();
        if (state) qs.set("state", state);
        qs.set("limit", String(limit));
        const { status, body } = await callHttpJson("GET", `/runs?${qs.toString()}`);
        if (status < 200 || status >= 300) {
          return err(`HTTP ${status}: ${JSON.stringify(body ?? {})}`);
        }
        if (!body || !Array.isArray((body as { items?: unknown[] }).items)) {
          return err(`unexpected response shape: ${JSON.stringify(body)}`);
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
  } catch (e) {
    if (e instanceof McpInputError) {
      // The validation gate already produced a structured
      // message; surface it as a tool error so the agent gets
      // isError=true without a network round trip.
      return err(`${e.code}: ${e.message}`);
    }
    throw e;
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
    const { status, body } = await callHttpJson("GET", "/steps");
    // The /steps endpoint returns either { steps: [...] } (success) or { code, message }
    // (refusal). When the backend is healthy we paginate; otherwise we surface the
    // refusal as-is — the MCP client must see honest REFUSED, not a paginated empty
    // page.
    //
    // Audit F-P1-MCP-4 (2026-10-10): the previous implementation
    // parsed the response text with /^HTTP \d+: (.+)$/ and
    // safeJson; a newline in the JSON broke the regex. Now we
    // read the structured body directly.
    if (status < 200 || status >= 300) {
      // Non-2xx (202, 4xx, 5xx) — surface the backend's refusal
      // envelope as-is so the MCP client sees the honest code
      // (e.g. OUTPUT_REFUSED, SDK_PIN_MISMATCH), not a paginated
      // empty page.
      return err(`HTTP ${status}: ${JSON.stringify(body ?? {})}`);
    }
    if (!body || !Array.isArray((body as { steps?: unknown[] }).steps)) {
      return err(`unexpected /steps response shape: ${JSON.stringify(body)}`);
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

const transport = new StdioServerTransport();
await server.connect(transport);
