// Pure configuration helpers for the MCP stdio adapter.
//
// Extracted from server.ts so the URL resolution, timeout policy,
// and tool-input validation are unit-testable without spawning a
// child process or reaching for the network. Audit F-P1-MCP-1 +
// F-P1-MCP-2 (2026-10-10) introduced the URL/timeout defenses;
// F-P1-MCP-3 (2026-10-10) added the input validation helpers.
// This file is the canonical place to keep them honest.

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
 * Audit F-P1-MCP-3 (2026-10-10): the MCP tool handlers used to
 * write `String(a.name ?? "")` for every required string arg.
 * The MCP SDK's zod schema declares the required type, but a
 * hand-written defensive coercion bypasses that: a caller
 * passing `null` (or any non-string) for `name` would produce
 * `""`, which the backend then rejects with a generic 400. The
 * agent had to read a network error to learn a required
 * argument was missing.
 *
 * The `requireString` / `optionalString` / `optionalNumber`
 * helpers below are the canonical gate. They throw a structured
 * [McpInputError] for any non-string, null, or empty value, and
 * return the trimmed string otherwise. The tool handlers catch
 * [McpInputError] and return it as an `isError: true` tool
 * result so the agent sees a clear `INVALID_ARGUMENT: <name> is
 * required and must be a non-empty string` instead of a 400 from
 * the backend.
 */

export class McpInputError extends Error {
  override readonly name = "McpInputError";
  readonly argName: string;
  readonly code: string;
  constructor(argName: string, message: string) {
    super(message);
    this.argName = argName;
    this.code = "INVALID_ARGUMENT";
  }
}

const MAX_STRING_ARG = 4096;

export function requireString(args: Record<string, unknown>, name: string): string {
  const v = args[name];
  if (typeof v !== "string") {
    throw new McpInputError(
      name,
      `${name} is required and must be a string (got ${v === null ? "null" : typeof v})`,
    );
  }
  const trimmed = v.trim();
  if (trimmed.length === 0) {
    throw new McpInputError(name, `${name} is required and must be a non-empty string`);
  }
  if (trimmed.length > MAX_STRING_ARG) {
    throw new McpInputError(
      name,
      `${name} exceeds the maximum length of ${MAX_STRING_ARG} characters`,
    );
  }
  return trimmed;
}

export function optionalString(
  args: Record<string, unknown>,
  name: string,
  defaultValue: string,
): string {
  const v = args[name];
  if (v === undefined || v === null) return defaultValue;
  if (typeof v !== "string") {
    throw new McpInputError(name, `${name} must be a string when provided`);
  }
  return v;
}

export function optionalNumber(
  args: Record<string, unknown>,
  name: string,
  defaultValue: number,
  min: number = Number.MIN_SAFE_INTEGER,
  max: number = Number.MAX_SAFE_INTEGER,
): number {
  const v = args[name];
  if (v === undefined || v === null) return defaultValue;
  if (typeof v !== "number" || !Number.isFinite(v)) {
    throw new McpInputError(name, `${name} must be a finite number when provided`);
  }
  if (v < min || v > max) {
    throw new McpInputError(name, `${name} must be between ${min} and ${max}`);
  }
  return v;
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
