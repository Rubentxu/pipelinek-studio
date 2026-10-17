// @vitest-environment node
import { describe, it, expect } from "vitest";
import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { dirname, resolve } from "node:path";

/**
 * MCP tools/list ↔ tools/call parity (2026-10-17).
 *
 * The MCP server in `server.ts` declares its surface in two places
 * that MUST stay in lockstep:
 *
 *   - `ListToolsRequestSchema` advertises the tool catalogue: every
 *     name a client can see in `tools/list`.
 *   - `CallToolRequestSchema` dispatches on `switch (name)`: every
 *     name a client can call. The `default` arm returns
 *     `tool '<name>' not implemented`.
 *
 * If a contributor adds a name to one list but forgets the other,
 * clients see a tool in `tools/list` and then get `not implemented`
 * when they call it. This test pins the two sets in lockstep at the
 * source level — analogous to `OpenApiDocPathParityTest` for the
 * Kotlin HTTP surface.
 *
 * The scanner is brace-aware: we find each `setRequestHandler(...)`
 * opener and walk to the matching `}));` so the extraction is
 * insensitive to indentation or in-line comments.
 */
describe("MCP tools/list ↔ tools/call parity", () => {
  it("every name in tools/list has a matching case in tools/call", () => {
    const src = readSource();
    const advertised = extractListToolsNames(src);
    const dispatched = extractCallToolCases(src);
    const onlyListed = advertised.filter((n) => !dispatched.includes(n));
    const onlyDispatched = dispatched.filter((n) => !advertised.includes(n));
    expect(onlyListed, "names in tools/list without a tools/call case").toEqual([]);
    expect(onlyDispatched, "names in tools/call without a tools/list entry").toEqual([]);
    expect(advertised.length).toBeGreaterThan(0);
  });

  it("every tool name follows the studio[.X[.Y]] convention", () => {
    // Two-segment names (e.g. `studio.health`) and three-segment names
    // (e.g. `studio.create_experiment`, `studio.list_tasks`) are both
    // legitimate; the rule is "starts with `studio.` and uses
    // snake_case segments afterwards". A future contributor who adds
    // `studioHealth` (camelCase) or `studio-create-experiment`
    // (kebab-case) or `listtasks` (no studio prefix) gets caught here.
    const advertised = extractListToolsNames(readSource());
    const offenders = advertised.filter((n) => !/^studio(?:\.[a-z_]+){1,2}$/.test(n));
    expect(offenders, "tool names that violate the studio[.X[.Y]] convention").toEqual([]);
  });

  it("no tool name is duplicated in tools/list", () => {
    const advertised = extractListToolsNames(readSource());
    const seen = new Set<string>();
    const dups: string[] = [];
    for (const n of advertised) {
      if (seen.has(n)) dups.push(n);
      else seen.add(n);
    }
    expect(dups, "duplicated tool names in tools/list").toEqual([]);
  });
});

function readSource(): string {
  const here = dirname(fileURLToPath(import.meta.url));
  return readFileSync(resolve(here, "server.ts"), "utf8");
}

/**
 * Extract the `name: "..."` entries that live inside the body of
 * `setRequestHandler(ListToolsRequestSchema, async () => ({...}))`.
 * Brace-aware: a depth counter skips past inner objects and only
 * counts entries that belong to the top-level tools list.
 */
function extractListToolsNames(src: string): string[] {
  const opener = "setRequestHandler(ListToolsRequestSchema";
  const start = src.indexOf(opener);
  if (start < 0) throw new Error("ListToolsRequestSchema handler not found in server.ts");
  // Walk forward to find the outer `({` opener; the handler body
  // starts at the first `(` after the `=>` arrow's `{` block.
  // Simplest: locate the first `=>` after the opener and start
  // counting braces from the first `{` we encounter.
  const arrowAt = src.indexOf("=>", start);
  if (arrowAt < 0) throw new Error("ListToolsRequestSchema handler missing `=>`");
  const out: string[] = [];
  let depth = 0;
  let sawOpen = false;
  for (let i = arrowAt; i < src.length; i++) {
    const ch = src[i];
    if (ch === "{") {
      depth++;
      sawOpen = true;
    } else if (ch === "}") {
      depth--;
      if (sawOpen && depth === 0) return out;
    } else if (sawOpen && depth >= 1) {
      // We're inside the top-level body. Try to match `name: "..."` at
      // this position. The `tools` body is `({ tools: [ {name:...}, ...
      // ] })` — the array and the tool entries sit at depth 2+; the
      // `>= 1` check covers the whole handler body. We anchor on `n`
      // (the start of `name:`) so the regex works regardless of which
      // char happens to be at position i.
      const slice = src.slice(i);
      const m = /^name:\s*"([^"]+)"/.exec(slice);
      if (m) {
        out.push(m[1]);
        i += m[0].length - 1; // skip past the closing quote
      }
    }
  }
  throw new Error("ListToolsRequestSchema handler did not close cleanly");
}

/**
 * Extract the `case "...":` labels that live inside the body of
 * `setRequestHandler(CallToolRequestSchema, async (req) => {...})`.
 * Same brace-walking strategy as `extractListToolsNames`.
 */
function extractCallToolCases(src: string): string[] {
  const opener = "setRequestHandler(CallToolRequestSchema";
  const start = src.indexOf(opener);
  if (start < 0) throw new Error("CallToolRequestSchema handler not found in server.ts");
  const arrowAt = src.indexOf("=>", start);
  if (arrowAt < 0) throw new Error("CallToolRequestSchema handler missing `=>`");
  const out: string[] = [];
  let depth = 0;
  let sawOpen = false;
  for (let i = arrowAt; i < src.length; i++) {
    const ch = src[i];
    if (ch === "{") {
      depth++;
      sawOpen = true;
    } else if (ch === "}") {
      depth--;
      if (sawOpen && depth === 0) return out;
    } else if (sawOpen && depth >= 1) {
      const slice = src.slice(i);
      // `case "...":` lives inside the `switch (name) { ... }` body,
      // which is itself inside the arrow's `{ ... }`. We anchor on
      // `c` (the start of `case`) so the regex works regardless of
      // which char happens to be at position i.
      const m = /^case\s+"([^"]+)":/.exec(slice);
      if (m) {
        out.push(m[1]);
        i += m[0].length - 1;
      }
    }
  }
  throw new Error("CallToolRequestSchema handler did not close cleanly");
}
