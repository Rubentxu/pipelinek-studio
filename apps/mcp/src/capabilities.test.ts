import { describe, it, expect } from "vitest";
import { MCP_CAPABILITIES } from "./server.js";

/**
 * R4.1 mandate item 7 (2026-10-11): 'No anunciar soporte de MCP Tasks
 * si el SDK o cliente no lo demuestra'.
 *
 * MCP 2026-07-28 defines a dedicated `tasks` capability on the server's
 * initialize response. When advertised, clients can call the Tasks
 * API (tasks/list, tasks/get, tasks/cancel) and expect task records.
 * Studio does NOT implement those endpoints — it surfaces its own
 * long-running runs via the regular `studio.list_tasks` tool, which
 * is a custom tool NOT the protocol Tasks feature.
 *
 * These tests pin the contract: the capabilities object MUST be
 * exactly { tools, resources } and MUST NOT include `tasks`,
 * `logging`, `prompts`, `roots`, `sampling`, or any other key. A
 * future contributor who adds `tasks: {}` (perhaps thinking the
 * custom tool qualifies) would advertise support we don't implement,
 * and clients would then hit unsupported endpoints. The tests pin
 * the safe shape.
 */

describe("R4.1 MCP capabilities contract", () => {
  it("advertises only tools and resources", () => {
    const keys = Object.keys(MCP_CAPABILITIES).sort();
    expect(keys).toEqual(["resources", "tools"]);
  });

  it("does NOT advertise the MCP Tasks capability", () => {
    // The MCP protocol `tasks` capability is for the server's own
    // long-running operations. We do NOT implement it.
    expect(MCP_CAPABILITIES).not.toHaveProperty("tasks");
  });

  it("does NOT advertise other unsupported protocol capabilities", () => {
    // Each of these is a separate MCP protocol feature that Studio
    // does not implement. Pinning their absence guards against
    // accidental additions during a half-finished migration.
    expect(MCP_CAPABILITIES).not.toHaveProperty("logging");
    expect(MCP_CAPABILITIES).not.toHaveProperty("prompts");
    expect(MCP_CAPABILITIES).not.toHaveProperty("roots");
    expect(MCP_CAPABILITIES).not.toHaveProperty("sampling");
  });

  it("tools and resources capabilities are non-empty objects", () => {
    // Per MCP spec, a capability key MUST be present with at least
    // an empty object when the server supports that area; an empty
    // object is the canonical 'enabled but with no sub-features'
    // signal.
    expect(typeof MCP_CAPABILITIES.tools).toBe("object");
    expect(MCP_CAPABILITIES.tools).not.toBeNull();
    expect(typeof MCP_CAPABILITIES.resources).toBe("object");
    expect(MCP_CAPABILITIES.resources).not.toBeNull();
  });
});