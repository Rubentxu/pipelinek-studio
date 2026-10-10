/**
 * Single source of truth for the studio backend URL.
 *
 * Audit F-P1-1 (2026-10-10): the previous implementation had the
 * loopback URL duplicated in 5 files (App.tsx + 4 viewers). Changing
 * the URL required editing 5 sites and there was no compile-time
 * guarantee they stayed in sync. This module centralises the default
 * and exports a single helper that reads the Vite env override or
 * falls back to the canonical loopback URL.
 *
 * Per the 2026-10-10 audit C-1, the default is now HTTPS on
 * 127.0.0.1:7778. The legacy HTTP URL (7777) is kept as a
 * documented escape hatch for operators who run with
 * --insecure-http on the backend.
 */

const LEGACY_HTTP_PORT = 7777;
const DEFAULT_HTTPS_PORT = 7778;

/**
 * Read the studio backend URL from the build-time env, falling
 * back to the canonical HTTPS loopback URL. The Vite env
 * variable is `VITE_STUDIO_HTTP`; setting it overrides the
 * default without code changes.
 */
export function studioBaseUrl(): string {
  // Vite injects `import.meta.env` at build time. The `as any`
  // cast keeps the type narrow; the env is the only mutable
  // configuration point.
  if (typeof import.meta !== "undefined") {
    const env = (import.meta as any).env;
    const override = env?.VITE_STUDIO_HTTP;
    if (typeof override === "string" && override.length > 0) {
      return override.replace(/\/+$/, ""); // strip trailing slash
    }
  }
  return `https://127.0.0.1:${DEFAULT_HTTPS_PORT}`;
}

/**
 * Legacy HTTP URL exposed for operators who run the backend
 * with --insecure-http. Use only when the backend is explicitly
 * started in HTTP mode.
 */
export function legacyHttpBaseUrl(): string {
  return `http://127.0.0.1:${LEGACY_HTTP_PORT}`;
}

/**
 * Audit F-P1-WEB-3 (2026-10-10): the SPA used to call bare `fetch`
 * with no timeout in App.tsx + OutputViewer.tsx + DiagnosticsViewer
 * + IrExplorer. A hung backend (TLR-dead, half-closed TCP, slow
 * SQLite write under load) made the SPA sit in "loading…" forever,
 * which the user perceived as "the studio is broken". The backend
 * already answers in < 1s for a healthy local install, so a hard
 * deadline is the right shape: surfaces the problem instead of
 * hiding it.
 *
 * `fetchWithTimeout` wraps fetch with an AbortController. The
 * default deadline is intentionally tight (5s) — a healthy loopback
 * backend answers in well under 1s. The deadline is overridable
 * per-call for long-poll style operations (e.g. the SSE handler
 * already runs without a timeout on purpose).
 *
 * The catch block recognises BOTH the direct AbortError shape and
 * Node 18+'s wrapped `TypeError` whose `cause` is the AbortError,
 * so the caller can detect a timeout reliably across runtimes.
 */
export const DEFAULT_HTTP_TIMEOUT_MS = 5000;

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

export function isFetchTimeout(err: unknown): err is FetchTimeoutError {
  if (err instanceof FetchTimeoutError) return true;
  // Match the wrapped shape too, for callers that prefer not to
  // import the class.
  const e = err as { name?: string; cause?: { name?: string } } | null;
  if (!e) return false;
  return e.name === "AbortError" || e.cause?.name === "AbortError";
}

export async function fetchWithTimeout(
  input: string,
  init: RequestInit = {},
  timeoutMs: number = DEFAULT_HTTP_TIMEOUT_MS,
): Promise<Response> {
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), timeoutMs);
  // If the caller passed their own signal (e.g. a React useEffect
  // cleanup signal), wire it to our controller so unmount also
  // aborts the request without losing the timeout.
  const externalSignal = init.signal;
  const onExternalAbort = () => controller.abort();
  if (externalSignal) {
    if (externalSignal.aborted) controller.abort();
    else externalSignal.addEventListener("abort", onExternalAbort, { once: true });
  }
  try {
    // Strip the caller's signal so we don't pass a conflicting
    // signal to fetch — our controller is the single source of
    // truth for aborts.
    const { signal: _ignored, ...rest } = init;
    void _ignored;
    return await fetch(input, { ...rest, signal: controller.signal });
  } catch (e) {
    const err_ = e as Error & { cause?: { name?: string } };
    if (isFetchTimeout(err_)) {
      throw new FetchTimeoutError(input, timeoutMs);
    }
    throw err_;
  } finally {
    clearTimeout(timer);
    if (externalSignal) externalSignal.removeEventListener("abort", onExternalAbort);
  }
}
