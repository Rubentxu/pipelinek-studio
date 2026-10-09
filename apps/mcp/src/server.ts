// MCP v2 stdio adapter — skeleton.
// Per ADR-004 and D-004 in odd/tasks/pipelinek-studio-bootstrap.md:
//   - MCP is a THIN adapter that talks HTTP to the Studio backend; it does NOT
//     reimplement business rules or hit SQLite directly.
//
// S0 bootstrap: only lists capabilities. Real tools/resources land in S1 because the
// HTTP backend needs to expose enough state for MCP to be useful (policies/lab/diff
// arrive in S2..S4). Until then the adapter returns honest "not implemented" — no fake
// PASS.
import { Server } from "@modelcontextprotocol/sdk/server/index.js";
import { StdioServerTransport } from "@modelcontextprotocol/sdk/server/stdio.js";

const STUDIO_HTTP = process.env.STUDIO_HTTP ?? "http://127.0.0.1:7777";

const server = new Server(
  { name: "pipelinek-studio-mcp", version: "0.1.0-alpha.1" },
  { capabilities: { tools: {}, resources: {} } },
);

server.setRequestHandler("initialize", async () => ({
  protocolVersion: "2026-07-28",
  serverInfo: { name: "pipelinek-studio-mcp", version: "0.1.0-alpha.1" },
  capabilities: { tools: {}, resources: {} },
}));

server.setRequestHandler("tools/list", async () => ({
  tools: [
    {
      name: "studio.health",
      description: "Returns the Studio backend health status.",
      inputSchema: { type: "object", properties: {}, additionalProperties: false },
    },
  ],
}));

server.setRequestHandler("tools/call", async (req: any) => {
  const { name } = req.params ?? {};
  if (name === "studio.health") {
    const r = await fetch(`${STUDIO_HTTP}/health`);
    return { content: [{ type: "text", text: `status=${r.status}` }] };
  }
  return { isError: true, content: [{ type: "text", text: `tool '${name}' not implemented in S0 bootstrap` }] };
});

server.setRequestHandler("resources/list", async () => ({ resources: [] }));

const transport = new StdioServerTransport();
await server.connect(transport);
