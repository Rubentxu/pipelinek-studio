// Audit F-P1-VSCODE-1 + F-P1-VSCODE-2 (2026-10-10): the VS Code
// extension used to default to http://127.0.0.1:7777 (the legacy
// HTTP port) and called bare fetch() with no timeout. Two real
// defects, mirroring the MCP slice:
//
// 1. The studio is HTTPS-default on 127.0.0.1:7778 (audit C-1).
//    A vscode extension that defaults to plaintext connects to
//    a service that isn't there, or — worse — connects to a
//    service the operator left running with --insecure-http,
//    defeating the C-1 hardening silently.
//
// 2. Bare fetch() with no timeout makes the extension hang
//    forever when the backend is unreachable. The status bar
//    shows a spinner, the user has no way to know the studio
//    is the problem, and there's no timeout to surface it.
//
// The fix mirrors the MCP slice:
//   - resolveStudioUrl(): defaults to https://127.0.0.1:7778;
//     http:// requires VSCODE_STUDIO_HTTP_INSECURE=1
//   - resolveTimeoutMs(): configurable via VSCODE_STUDIO_HTTP_TIMEOUT_MS
//   - fetchWithTimeout(): AbortController-wrapped fetch

export const DEFAULT_HTTPS_URL = "https://127.0.0.1:7778";
export const DEFAULT_TIMEOUT_MS = 5000;

export function resolveStudioUrl(env: Record<string, string | undefined> = process.env): string {
  const override = env.VSCODE_STUDIO_HTTP;
  if (typeof override === "string" && override.length > 0) {
    if (override.startsWith("http://") && env.VSCODE_STUDIO_HTTP_INSECURE !== "1") {
      throw new Error(
        "VSCODE_STUDIO_HTTP is http:// but VSCODE_STUDIO_HTTP_INSECURE is not set. " +
          "Either set VSCODE_STUDIO_HTTP to an https:// URL or set " +
          "VSCODE_STUDIO_HTTP_INSECURE=1 to acknowledge the studio is running with --insecure-http.",
      );
    }
    return override;
  }
  return DEFAULT_HTTPS_URL;
}

export function resolveTimeoutMs(env: Record<string, string | undefined> = process.env): number {
  const raw = env.VSCODE_STUDIO_HTTP_TIMEOUT_MS;
  if (typeof raw !== "string" || raw.length === 0) return DEFAULT_TIMEOUT_MS;
  const n = Number(raw);
  if (!Number.isFinite(n) || n < 1 || n > 600_000) return DEFAULT_TIMEOUT_MS;
  return n;
}

export class FetchTimeoutError extends Error {
  override readonly name = "FetchTimeoutError";
  readonly deadlineMs: number;
  readonly url: string;
  constructor(url: string, deadlineMs: number) {
    super(`fetch timed out after ${deadlineMs}ms: ${url}`);
    this.url = url;
    this.deadlineMs = deadlineMs;
  }
}

export function isFetchTimeout(err: unknown): boolean {
  if (err instanceof FetchTimeoutError) return true;
  const e = err as { name?: string; cause?: { name?: string } } | null;
  if (!e) return false;
  return e.name === "AbortError" || e.cause?.name === "AbortError";
}

export async function fetchWithTimeout(
  input: string,
  init: RequestInit = {},
  timeoutMs: number = DEFAULT_TIMEOUT_MS,
): Promise<Response> {
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), timeoutMs);
  const externalSignal = init.signal;
  const onExternalAbort = () => controller.abort();
  if (externalSignal) {
    if (externalSignal.aborted) controller.abort();
    else externalSignal.addEventListener("abort", onExternalAbort, { once: true });
  }
  try {
    const { signal: _ignored, ...rest } = init;
    void _ignored;
    return await fetch(input, { ...rest, signal: controller.signal });
  } catch (e) {
    if (isFetchTimeout(e)) {
      throw new FetchTimeoutError(input, timeoutMs);
    }
    throw e;
  } finally {
    clearTimeout(timer);
    if (externalSignal) externalSignal.removeEventListener("abort", onExternalAbort);
  }
}
