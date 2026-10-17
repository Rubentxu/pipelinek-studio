// @vitest-environment node
import { describe, it, expect, vi, beforeEach, afterEach } from "vitest";
import {
  resolveStudioUrl,
  validateStudioConfig,
  resolveTimeoutMs,
  fetchWithTimeout,
  FetchTimeoutError,
  isFetchTimeout,
} from "./config";

describe("resolveStudioUrl", () => {
  it("defaults to https://127.0.0.1:7778 when no env is set", () => {
    expect(resolveStudioUrl({})).toBe("https://127.0.0.1:7778");
  });

  it("ignores an empty VSCODE_STUDIO_HTTP override", () => {
    expect(resolveStudioUrl({ VSCODE_STUDIO_HTTP: "" })).toBe("https://127.0.0.1:7778");
  });

  it("accepts an https:// override", () => {
    expect(
      resolveStudioUrl({ VSCODE_STUDIO_HTTP: "https://studio.example:9999" }),
    ).toBe("https://studio.example:9999");
  });

  it("refuses an http:// override without VSCODE_STUDIO_HTTP_INSECURE", () => {
    expect(() => resolveStudioUrl({ VSCODE_STUDIO_HTTP: "http://127.0.0.1:7777" })).toThrow(
      /VSCODE_STUDIO_HTTP_INSECURE/,
    );
  });

  it("accepts an http:// override when VSCODE_STUDIO_HTTP_INSECURE=1", () => {
    expect(
      resolveStudioUrl({
        VSCODE_STUDIO_HTTP: "http://127.0.0.1:7777",
        VSCODE_STUDIO_HTTP_INSECURE: "1",
      }),
    ).toBe("http://127.0.0.1:7777");
  });
});

describe("validated configuration", () => {
  it("checks schemes case-insensitively and rejects unsupported URLs and embedded credentials", () => {
    expect(() => resolveStudioUrl({ VSCODE_STUDIO_HTTP: "HTTP://localhost:7777" })).toThrow(/VSCODE_STUDIO_HTTP_INSECURE/);
    for (const baseUrl of ["file:///tmp/studio", "not a url", "https://user:password@localhost"]) {
      expect(() => resolveStudioUrl({ VSCODE_STUDIO_HTTP: baseUrl })).toThrow();
    }
    expect(resolveStudioUrl({ VSCODE_STUDIO_HTTP: "HTTPS://localhost:7778" })).toBe("HTTPS://localhost:7778");
  });

  it("defaults to HTTPS and requires explicit boolean HTTP opt-in, without granting consent", () => {
    expect(validateStudioConfig({})).toEqual({ baseUrl: "https://127.0.0.1:7778", profile: "default", allowInsecureHttp: false, headers: { "X-Studio-Profile": "default" } });
    expect(() => validateStudioConfig({ baseUrl: "HTTP://localhost" })).toThrow(/allowInsecureHttp/);
    expect(validateStudioConfig({ baseUrl: "http://localhost", allowInsecureHttp: true, profile: "worktree" }).headers).toEqual({ "X-Studio-Profile": "worktree" });
    for (const profile of ["", "bad\r\nInjected: yes", "☃"]) {
      expect(() => validateStudioConfig({ profile })).toThrow(/profile/);
    }
    expect(() => validateStudioConfig({ allowInsecureHttp: "true" as unknown as boolean })).toThrow();
  });
});

describe("resolveTimeoutMs", () => {
  it("defaults to 5000", () => {
    expect(resolveTimeoutMs({})).toBe(5000);
  });

  it("parses a valid integer", () => {
    expect(resolveTimeoutMs({ VSCODE_STUDIO_HTTP_TIMEOUT_MS: "1500" })).toBe(1500);
  });

  it("falls back on bad values", () => {
    expect(resolveTimeoutMs({ VSCODE_STUDIO_HTTP_TIMEOUT_MS: "abc" })).toBe(5000);
    expect(resolveTimeoutMs({ VSCODE_STUDIO_HTTP_TIMEOUT_MS: "0" })).toBe(5000);
    expect(resolveTimeoutMs({ VSCODE_STUDIO_HTTP_TIMEOUT_MS: "-1" })).toBe(5000);
  });
});

describe("fetchWithTimeout", () => {
  let realFetch: typeof fetch;
  beforeEach(() => {
    realFetch = globalThis.fetch;
  });
  afterEach(() => {
    globalThis.fetch = realFetch;
  });

  it("resolves normally when fetch returns before the deadline", async () => {
    globalThis.fetch = vi.fn(async () => new Response("{}", { status: 200 })) as unknown as typeof fetch;
    const r = await fetchWithTimeout("https://example.test/", {}, 1000);
    expect(r.status).toBe(200);
  });

  it("throws FetchTimeoutError when the deadline elapses (real hung server)", async () => {
    // Real hung HTTP server so the AbortController actually
    // rejects the underlying fetch. Stubbing fetch with a
    // never-resolving Promise<Response> would mask the bug
    // because the stub ignores the abort signal.
    const { createServer } = await import("node:net");
    const sockets: import("node:net").Socket[] = [];
    const server = createServer((sock) => {
      sockets.push(sock);
    });
    await new Promise<void>((r) => server.listen(0, "127.0.0.1", r));
    const port = (server.address() as { port: number }).port;
    const start = Date.now();
    let caught: unknown = null;
    try {
      await fetchWithTimeout(`http://127.0.0.1:${port}/`, {}, 75);
    } catch (e) {
      caught = e;
    }
    const elapsed = Date.now() - start;
    for (const s of sockets) s.destroy();
    await new Promise<void>((r) => server.close(() => r()));
    expect(caught).toBeInstanceOf(FetchTimeoutError);
    expect(isFetchTimeout(caught)).toBe(true);
    expect(elapsed).toBeLessThan(2000);
  });

  it("propagates non-timeout errors without wrapping", async () => {
    globalThis.fetch = vi.fn(async () => {
      throw new TypeError("DNS resolution failed");
    }) as unknown as typeof fetch;
    await expect(
      fetchWithTimeout("https://no-such-host.invalid/", {}, 1000),
    ).rejects.toThrow(TypeError);
  });
});
