// @vitest-environment node
import { describe, it, expect, vi, beforeEach, afterEach } from "vitest";
import {
  fetchWithTimeout,
  FetchTimeoutError,
  isFetchTimeout,
  studioBaseUrl,
  DEFAULT_HTTP_TIMEOUT_MS,
} from "./api";

describe("studioBaseUrl", () => {
  // We can't easily import.meta in vitest with a per-test env, so
  // we test the helper's structural behavior via the exported
  // constants and the read-from-import.meta contract.
  it("exports a non-empty default", () => {
    const url = studioBaseUrl();
    expect(url).toMatch(/^https?:\/\//);
    expect(url.length).toBeGreaterThan(0);
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
    const r = await fetchWithTimeout("https://example.test/health", {}, 1000);
    expect(r.status).toBe(200);
  });

  it("throws FetchTimeoutError when the deadline elapses", async () => {
    // Use a real hung HTTP server so the AbortController can
    // actually reject the underlying fetch. Stubbing fetch with a
    // never-resolving Promise<Response> doesn't work because the
    // stub ignores the abort signal — only the real fetch
    // implementation wires signal -> rejection.
    const { createServer } = await import("node:net");
    const sockets: import("node:net").Socket[] = [];
    const server = createServer((sock) => {
      sockets.push(sock);
      // Intentionally never end/write.
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

  it("respects an externally aborted signal", async () => {
    // Hung server: the caller's signal aborts before the deadline,
    // so the wrapper must surface a timeout-shaped error within
    // the signal's lifetime, not the 5s deadline.
    const { createServer } = await import("node:net");
    const sockets: import("node:net").Socket[] = [];
    const server = createServer((sock) => {
      sockets.push(sock);
    });
    await new Promise<void>((r) => server.listen(0, "127.0.0.1", r));
    const port = (server.address() as { port: number }).port;
    const ac = new AbortController();
    setTimeout(() => ac.abort(), 30);
    const start = Date.now();
    let caught: unknown = null;
    try {
      await fetchWithTimeout(
        `http://127.0.0.1:${port}/`,
        { signal: ac.signal },
        5000,
      );
    } catch (e) {
      caught = e;
    }
    const elapsed = Date.now() - start;
    for (const s of sockets) s.destroy();
    await new Promise<void>((r) => server.close(() => r()));
    expect(elapsed).toBeLessThan(2000);
    expect(isFetchTimeout(caught)).toBe(true);
  });

  it("DEFAULT_HTTP_TIMEOUT_MS is set to a reasonable value", () => {
    expect(DEFAULT_HTTP_TIMEOUT_MS).toBeGreaterThanOrEqual(1000);
    expect(DEFAULT_HTTP_TIMEOUT_MS).toBeLessThanOrEqual(60_000);
  });
});
