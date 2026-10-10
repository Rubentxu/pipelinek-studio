import { describe, it, expect } from "vitest";
import { resolveStudioUrl, resolveTimeoutMs, fetchWithTimeout } from "./config.js";

describe("resolveStudioUrl", () => {
  it("defaults to https://127.0.0.1:7778 when no env is set", () => {
    expect(resolveStudioUrl({})).toBe("https://127.0.0.1:7778");
  });

  it("ignores an empty STUDIO_HTTP override (treats it as absent)", () => {
    expect(resolveStudioUrl({ STUDIO_HTTP: "" })).toBe("https://127.0.0.1:7778");
  });

  it("accepts an https:// override", () => {
    expect(
      resolveStudioUrl({ STUDIO_HTTP: "https://studio.example:9999" }),
    ).toBe("https://studio.example:9999");
  });

  it("refuses an http:// override without STUDIO_HTTP_INSECURE", () => {
    expect(() => resolveStudioUrl({ STUDIO_HTTP: "http://127.0.0.1:7777" })).toThrow(
      /STUDIO_HTTP_INSECURE/,
    );
  });

  it("accepts an http:// override when STUDIO_HTTP_INSECURE=1", () => {
    expect(
      resolveStudioUrl({ STUDIO_HTTP: "http://127.0.0.1:7777", STUDIO_HTTP_INSECURE: "1" }),
    ).toBe("http://127.0.0.1:7777");
  });

  it("still refuses an http:// override when STUDIO_HTTP_INSECURE is set to something other than '1'", () => {
    expect(() =>
      resolveStudioUrl({ STUDIO_HTTP: "http://127.0.0.1:7777", STUDIO_HTTP_INSECURE: "true" }),
    ).toThrow(/STUDIO_HTTP_INSECURE/);
  });
});

describe("resolveTimeoutMs", () => {
  it("defaults to 5000 when no env is set", () => {
    expect(resolveTimeoutMs({})).toBe(5000);
  });

  it("parses a valid integer", () => {
    expect(resolveTimeoutMs({ STUDIO_HTTP_TIMEOUT_MS: "1500" })).toBe(1500);
  });

  it("falls back to default on non-numeric or out-of-range values", () => {
    expect(resolveTimeoutMs({ STUDIO_HTTP_TIMEOUT_MS: "abc" })).toBe(5000);
    expect(resolveTimeoutMs({ STUDIO_HTTP_TIMEOUT_MS: "0" })).toBe(5000);
    expect(resolveTimeoutMs({ STUDIO_HTTP_TIMEOUT_MS: "-1" })).toBe(5000);
    expect(resolveTimeoutMs({ STUDIO_HTTP_TIMEOUT_MS: "999999999" })).toBe(5000);
  });
});

describe("fetchWithTimeout", () => {
  it("aborts within the deadline when the server hangs", async () => {
    // Start a server that accepts the connection but never sends
    // a response. Without the timeout, fetch would hang forever.
    // With the 75ms deadline, the AbortController fires and the
    // fetch rejects with an AbortError.
    const { createServer } = await import("node:net");
    const sockets: import("node:net").Socket[] = [];
    const server = createServer((sock) => {
      sockets.push(sock);
      // Intentionally never call sock.end() / sock.write().
    });
    await new Promise<void>((r) => server.listen(0, "127.0.0.1", r));
    const port = (server.address() as { port: number }).port;

    const startedAt = Date.now();
    let caught: (Error & { cause?: { name?: string } }) | null = null;
    try {
      await fetchWithTimeout(
        `http://127.0.0.1:${port}/`,
        { method: "GET" },
        75,
      );
    } catch (e) {
      caught = e as Error & { cause?: { name?: string } };
    }
    const elapsed = Date.now() - startedAt;

    // Clean up the hung sockets so the test process can exit.
    for (const s of sockets) s.destroy();
    await new Promise<void>((r) => server.close(() => r()));

    expect(caught).not.toBeNull();
    // Node 18+ may throw either an AbortError directly OR a
    // TypeError whose `cause.name` is "AbortError".
    const isAbort =
      caught!.name === "AbortError" || caught!.cause?.name === "AbortError";
    expect(isAbort).toBe(true);
    // The deadline is 75ms; the abort must have fired within a
    // small scheduling slack, not waited 75+ seconds like an
    // unbounded fetch.
    expect(elapsed).toBeLessThan(2000);
  });

  it("resolves normally on a fast 200", async () => {
    // Use a tiny in-process HTTP server so the test is hermetic.
    const { createServer } = await import("node:http");
    const server = createServer((_req, res) => {
      res.writeHead(200, { "content-type": "application/json" });
      res.end("{}");
    });
    await new Promise<void>((r) => server.listen(0, "127.0.0.1", r));
    const port = (server.address() as { port: number }).port;
    try {
      const res = await fetchWithTimeout(
        `http://127.0.0.1:${port}/`,
        { method: "GET" },
        1000,
      );
      expect(res.status).toBe(200);
    } finally {
      await new Promise<void>((r) => server.close(() => r()));
    }
  });
});
