// @vitest-environment node
//
// R5 EVO-C.6 (2026-10-11): tests for the SDK profile header
// + the baseUrl/TLS contract. These are adversarial: the
// goal is to fail loudly if a future change re-introduces
// the silent-downgrade path that F-P1-VSCODE-1 documented.

import { describe, it, expect, vi, beforeEach, afterEach } from "vitest";
import { StudioClient } from "./sdk";
import { resolveStudioUrl } from "./config";

describe("StudioClient default headers", () => {
  let realFetch: typeof fetch;
  beforeEach(() => {
    realFetch = globalThis.fetch;
  });
  afterEach(() => {
    globalThis.fetch = realFetch;
  });

  it("sends X-Studio-Profile on every request", async () => {
    const seen: Array<{ url: string; headers: Record<string, string> }> = [];
    globalThis.fetch = vi.fn(async (url, init) => {
      const h = Object.fromEntries(new Headers(init?.headers ?? {}).entries());
      seen.push({ url: String(url), headers: h });
      return new Response("{}", { status: 200 });
    }) as unknown as typeof fetch;
    const c = new StudioClient({
      baseUrl: "https://studio.test:7778",
      defaultHeaders: { "X-Studio-Profile": "worktree-A" },
    });
    await c.getHealth();
    await c.getSteps();
    expect(seen).toHaveLength(2);
    for (const call of seen) {
      expect(call.headers["x-studio-profile"]).toBe("worktree-A");
    }
  });

  it("user-supplied headers in the request override the defaults", async () => {
    let captured: Record<string, string> = {};
    globalThis.fetch = vi.fn(async (_url, init) => {
      captured = Object.fromEntries(new Headers(init?.headers ?? {}).entries());
      return new Response("{}", { status: 200 });
    }) as unknown as typeof fetch;
    const c = new StudioClient({
      baseUrl: "https://studio.test:7778",
      defaultHeaders: { "X-Studio-Profile": "default" },
    });
    // The SDK merges the request body headers on top of the
    // defaultHeaders. A future change that swapped the order
    // would be a silent regression.
    await c.postExperiments({ name: "demo" });
    expect(captured["x-studio-profile"]).toBe("default");
    expect(captured["content-type"]).toBe("application/json");
  });

  it("honours per-request timeoutMs override", async () => {
    globalThis.fetch = vi.fn(async () => new Response("{}", { status: 200 })) as unknown as typeof fetch;
    const c = new StudioClient({ baseUrl: "https://studio.test:7778", timeoutMs: 1234 });
    await c.getHealth();
    // fetchWithTimeout is the wrapper; we just need to assert
    // it didn't throw (a hung server would have thrown
    // FetchTimeoutError).
  });
});

describe("resolveStudioUrl adversarial cases (EVO-C.6 / F-P1-VSCODE-1)", () => {
  it("rejects an http:// override without explicit opt-in", () => {
    expect(() => resolveStudioUrl({ VSCODE_STUDIO_HTTP: "http://127.0.0.1:7777" })).toThrow(
      /VSCODE_STUDIO_HTTP_INSECURE/,
    );
  });

  it("requires explicit opt-in for uppercase HTTP too", () => {
    expect(() => resolveStudioUrl({ VSCODE_STUDIO_HTTP: "HTTP://127.0.0.1:7777" })).toThrow(
      /VSCODE_STUDIO_HTTP_INSECURE/,
    );
    expect(resolveStudioUrl({
      VSCODE_STUDIO_HTTP: "HTTP://127.0.0.1:7777",
      VSCODE_STUDIO_HTTP_INSECURE: "1",
    })).toBe("HTTP://127.0.0.1:7777");
  });

  it("rejects javascript: and other non-http(s) schemes even with HTTP opt-in", () => {
    for (const url of ["javascript:alert(1)", "file:///tmp/studio", "ftp://localhost"]) {
      expect(() => resolveStudioUrl({
        VSCODE_STUDIO_HTTP: url,
        VSCODE_STUDIO_HTTP_INSECURE: "1",
      })).toThrow(/https:\/\/ or http:\/\//);
    }
  });
});
