// MCP v2 stdio adapter — closes ROADMAP E0.9 (MCP integration in S0).
// Per ADR-004 and D-004 in odd/tasks/pipelinek-studio-bootstrap.md:
//   - MCP is a THIN adapter that talks HTTP to the Studio backend; it does NOT
//     reimplement business rules or hit SQLite directly.
//   - Tools: studio.health, studio.create_experiment, studio.seal_snapshot,
//     studio.start_run, studio.inspect_run, studio.list_events.
//   - Resources: studio://version (introspectable SDK descriptor + capabilities).
//   - Honest answer keys: real SDK_RUN_ID when admitted, REFUSED otherwise.

import { Server } from "@modelcontextprotocol/sdk/server/index.js";
import { StdioServerTransport } from "@modelcontextprotocol/sdk/server/stdio.js";
import { z } from "zod";
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

// Use zod schema for the call handler — newer SDK requires schema'd method literal.
const ToolCall = z.object({
  method: z.literal("tools/call"),
  params: z.object({
    name: z.string(),
    arguments: z.record(z.unknown()).optional(),
  }),
});

server.setRequestHandler(ToolCall, async (req) => {
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
  ],
}));

const ResourceRead = z.object({
  method: z.literal("resources/read"),
  params: z.object({ uri: z.string() }),
});

server.setRequestHandler(ResourceRead, async (req) => {
  const uri = req.params.uri;
  if (uri !== "studio://version") return err(`unknown resource: ${uri}`);
  return callHttp("GET", "/api/version");
});

const transport = new StdioServerTransport();
await server.connect(transport);
