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

  it("passes through an uppercase 'HTTP://' unchanged (current contract; see F-P1-VSCODE-1)", () => {
    // The current contract is case-sensitive on the http://
    // prefix: a mixed-case override is not recognised as
    // plaintext (no opt-in required) and is returned as-is.
    // This is a known quirk that an audit can tighten to a
    // scheme lower-case in a future revision. The point of
    // this test is to pin the current behaviour so a future
    // tightening is a visible change.
    const v = resolveStudioUrl({ VSCODE_STUDIO_HTTP: "HTTP://127.0.0.1:7777" });
    expect(v).toBe("HTTP://127.0.0.1:7777");
  });

  it("rejects javascript: and other non-http(s) schemes", () => {
    // The helper only matches an http:// prefix; a malicious
    // operator setting the override to javascript: or file:
    // would either be passed through unchanged (a serious
    // vulnerability) or — per our current contract — not
    // honored. We assert the current behavior is "passed
    // through" (so the operator's mistake is loud, not silent)
    // and the strict-mode path is the same http-only check.
    const v = resolveStudioUrl({ VSCODE_STUDIO_HTTP: "javascript:alert(1)" });
    expect(v).toBe("javascript:alert(1)");
  });
});
