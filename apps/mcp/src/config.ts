// Pure configuration helpers for the MCP stdio adapter.
//
// Extracted from server.ts so the URL resolution and timeout policy
// are unit-testable without spawning a child process or reaching for
// the network. Audit F-P1-MCP-1 + F-P1-MCP-2 (2026-10-10) introduced
// these as new defenses; this file is the canonical place to keep
// them honest.

export const DEFAULT_HTTPS_URL = "https://127.0.0.1:7778";
export const DEFAULT_TIMEOUT_MS = 5000;
const MIN_TIMEOUT_MS = 1;
const MAX_TIMEOUT_MS = 600_000;

export function resolveStudioUrl(env: NodeJS.ProcessEnv = process.env): string {
  const override = env.STUDIO_HTTP;
  if (typeof override === "string" && override.length > 0) {
    if (override.startsWith("http://") && env.STUDIO_HTTP_INSECURE !== "1") {
      throw new Error(
        "STUDIO_HTTP is http:// but STUDIO_HTTP_INSECURE is not set. " +
          "Either set STUDIO_HTTP to an https:// URL or set STUDIO_HTTP_INSECURE=1 " +
          "to acknowledge that you are running the studio with --insecure-http.",
      );
    }
    return override;
  }
  return DEFAULT_HTTPS_URL;
}

export function resolveTimeoutMs(env: NodeJS.ProcessEnv = process.env): number {
  const raw = env.STUDIO_HTTP_TIMEOUT_MS;
  if (typeof raw !== "string" || raw.length === 0) return DEFAULT_TIMEOUT_MS;
  const n = Number(raw);
  if (!Number.isFinite(n) || n < MIN_TIMEOUT_MS || n > MAX_TIMEOUT_MS) {
    return DEFAULT_TIMEOUT_MS;
  }
  return n;
}

/**
 * Wrap a fetch with a hard deadline. The returned promise resolves
 * with the same Response on success or with a TimeoutError on
 * expiry. The error name is "AbortError" (from AbortController) so
 * callers can detect it with a structural check.
 */
export async function fetchWithTimeout(
  input: string,
  init: RequestInit,
  timeoutMs: number,
): Promise<Response> {
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), timeoutMs);
  try {
    return await fetch(input, { ...init, signal: controller.signal });
  } finally {
    clearTimeout(timer);
  }
}
