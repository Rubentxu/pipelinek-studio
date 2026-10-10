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
