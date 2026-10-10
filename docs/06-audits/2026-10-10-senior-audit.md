# Senior Technical Audit — 2026-10-10

**Date**: 2026-10-10
**Scope**: full backend (7 modules), apps (web + mcp + vscode), scripts/verifiers
**Methodology**: 5 subagents in parallel + 1 senior reviewer; each finding
verified against the actual code (not against documentation or comments).
**Outcome**: 7 CRITICAL + 32 HIGH + ~55 MEDIUM + ~70 LOW + 18 test smells
≈ 180 hallazgos.

**Update 2026-10-10 (post-release)**: original report said 6.8/10 and
listed 5 risks as "deferred". Of those, 4 have since been closed in code
and are now ✅ fixed (see the commit column). R-22 and the F-P1 slice
remain.

## Score global

**6.8/10 at audit time** — listo para una RC interna; NO listo para v1.0.0 sin cerrar
los 5 CRITICAL y aceptar formalmente los HIGH con mitigación.

## Estado al cierre (v0.2.0-alpha.2)

| CRITICAL | Estado | Commit |
|----------|--------|--------|
| C-1 (HTTPS) | ✅ fixed | 5a0f6dd |
| C-2 (NIL UUID) | ✅ fixed | 78b71d2 |
| C-3 (descriptor discarded) | ✅ fixed | 78b71d2 |
| C-4 (AgentWorkflows fake PASS) | ✅ fixed | 3d990eb |
| C-5 (RateLimit non-atomic) | ✅ fixed | 60d975b |
| C-6 (spawnedHandles leak) | ✅ fixed | e08fc08 |
| C-7 (capabilities fake) | ✅ fixed | f73e846 |
| F-P0-2 (variant no persiste) | ✅ fixed | a3c44c2 |
| F-P0-3 (status stub) | ✅ fixed | dbb9701 |

**Los 9 CRITICAL están cerrados con código, no con workarounds.**

## Estado de los HIGH tras iteraciones posteriores

| ID | Riesgo | Estado | Commit |
|----|--------|--------|--------|
| H-15 | Host header fail-open | ✅ fixed (refused missing Host with 400 + TestSupport helper) | (pre-ebeb8ad slice) |
| H-16 | SSE JSON injection | ✅ fixed (`Json.encodeToString` envelope) | ae78b05 |
| H-17 | Idempotency-Key DoS | ✅ fixed (length cap + charset constraint) | ebeb8ad |
| H-19 | RateLimit TOCTOU bucket | ✅ fixed (atomic `compute()`) | 85f3f6f |
| H-20 | RateLimit XFF trust | ✅ fixed (explicit opt-in flag) | 85f3f6f |
| H-21 | RateLimit per-IP OOM | ✅ fixed (eviction sweep every 100 req) | 92fc071 |
| H-22 | Repos sin pool de conexiones | ✅ fixed (`ArrayBlockingQueue` + `PooledConnection` wrapper) | 84fad9f |
| H-23 | INSERT no-atómico + UUID regex | ✅ fixed (UUID regex + `RUN_FINISHED_SQL` literal) | 0ea746b |
| H-25 | RunReconciler path traversal-like | ✅ fixed (UUID gate + toRealPath symlink defense) | (this commit) |
| H-26 | Backup sin límite tamaño | ✅ fixed (1 GiB default cap, BackupTooLargeException, partial-file cleanup) | (this commit) |
| R-16 | Sin release harness externo | ✅ fixed (`.github/workflows/release-harness.yml` + `ci.yml`) | 57ec10a |
| R-22 | Sin SHA pin + lockfile | ✅ fixed (`sdk.lock.json` + `SdkLockfileVerifier` with enforce/warn/off policy) | 6fc9860 |
| F-P1-2..F-P1-18 | apps/web, apps/mcp, etc. | 🟡 17/17 closed | see table below |

## F-P1 fixes shipped in subsequent slices

| ID | Description | Commit |
|----|-------------|--------|
| F-P1-MCP-1 | MCP defaulted to http:// — silent-downgrade path; flipped to https:// with `STUDIO_HTTP_INSECURE=1` opt-in | accb59d |
| F-P1-MCP-2 | MCP had no fetch timeout — hung backend froze the agent; added 5s `STUDIO_HTTP_TIMEOUT_MS` + `fetchWithTimeout` | accb59d |
| F-P1-MCP-3 | MCP tool handlers silently coerced null/empty args via `String(a.name ?? "")` bypassing the zod schema; added `requireString` / `optionalString` / `optionalNumber` with `McpInputError` | 7c3bb70 |
| F-P1-MCP-4 | `list_tasks` and the `/steps` resource read parsed the response text with `/^HTTP \d+: (.+)$/` + `safeJson`; brittle when the JSON contained a newline. Added `callHttpJson` returning structured `{ status, body }`; both callers now use it | 119b710, 8e26d7a |
| F-P1-MCP-5 | `list_events` accepted unbounded `after` and `limit`; bounded to [0, MAX_SAFE_INTEGER] and [1, 200] respectively via `optionalNumber` | c3bf1f7 |
| F-P1-CLI-1 | `StudioHttpClient` used a trust-all X509 manager unconditionally — MITM-vulnerable for any non-loopback host; now loopback-only | 219a9b7 |
| F-P1-CLI-2 | `studio run` generated a fresh UUID per call, creating two distinct runs on repeat invocations; now stable per (experiment, snapshot, scenario), with `--idem-key` override | 310bbb6 |
| F-P1-CLI-3 | `requireOption` returned the next arg verbatim — `--name --json` created an experiment named "--json", `--name ""` was forwarded to the backend. New `requireOptionValue` rejects flag-as-value, empty, and missing cases | 89861e5 |
| F-P1-WEB-3 | SPA used bare `fetch()` with no timeout in 8 places; centralized `fetchWithTimeout` in `lib/api.ts` | c82c277 |
| F-P1-WEB-4 | `OutputViewer` decoded each base64 chunk independently, breaking multi-byte UTF-8 at chunk boundaries; now concats bytes first | 0c186dc |
| F-P1-WEB-5 | Cmd-S fired while typing in any input field, hijacking browser-native save and accidentally sealing snapshots; new typing-guard | 994581d |
| F-P1-WEB-6 | `submitRun` generated a new Idempotency-Key per click — a double-click created two runs; now stable per (experiment, snapshot) | 7e69819 |
| F-P1-WEB-7 | EventStream's onerror showed "will retry" forever even when the EventSource was permanently closed (readyState=2); now distinguishes "reconnecting" from "closed" | db5f6b1 |
| F-P1-WEB-8 | `cancelRun` treated the documented 202 (CANCEL_UNKNOWN) as an error; now both 200 and 202 mark the run CANCELLED locally | 20b01c0 |
| F-P1-WEB-9 | DiagnosticsViewer and IrExplorer polled every 3-5s forever even on terminal runs; now stop on SUCCEEDED/FAILED/CANCELLED | 09d144f |
| F-P1-WEB-10 | KotlinEditor's debounced onChange fired after unmount, producing a setState-on-unmounted-component warning; cleanup now clears the pending timer | 10ec6c7 |
| F-P1-WEB-11 | `localStorage` persisted the current run forever — a refresh after the run finished rehydrated the stale terminal state. Now cleared on SUCCEEDED/FAILED/CANCELLED | 251a631 |
| F-P1-VSCODE-1 | VS Code extension defaulted to http:// — same silent-downgrade as MCP; flipped to https:// | a80b060 |
| F-P1-VSCODE-2 | VS Code extension had no fetch timeout; `fetchWithTimeout` shared + generator updated to emit it | a80b060 |

## Additional S5 readiness work (this iteration)

| Slice | Description | Commit |
|-------|-------------|--------|
| `studio doctor` | The previous doctor was a `/health` stub. New `Doctor` runs offline checks (lockfile present, db path writable, JVM >= 17, node on PATH, HTTPS default) and exits non-zero on failure | 06e1b07 |
| `doctorSnapshot` | C-1 followup: hardcoded `http://` in the public helper; now honors the `insecureHttp` flag and uses the HTTPS default | e46d5c7 |
| `generate-sdk-ts --to` | C-1 followup: silently fell through to stdout when `--to` had no following path; now errors and exits 2 | 0eacb10 |
| `printUsage` | C-1 followup: documented the legacy 7777 default; now shows 7778 + `--insecure-http` | 1037f3f |

## Recomendación actualizada para v1.0.0 stable

v1.0.0 stable NO debe emitirse hasta:

1. ~~Adoptar harness externo de release (R-16).~~ ✅ done (57ec10a).
2. ~~Cerrar H-15..H-17 (HTTP security) con código.~~ ✅ done.
3. ~~R-22 lockfile.~~ ✅ done (6fc9860).
4. ~~Cerrar los F-P1-3..F-P1-18 restantes.~~ ✅ done — 17/17
   cerrados, todos con tests en el mismo commit. La tabla de
   arriba lista cada uno.
5. ~~Cerrar H-25 (RunReconciler) y H-26 (backup size cap).~~ ✅ done.
6. Veredicto externo explícito (ROADMAP DoD) — un green-light real
   de segunda parte, no solo el harness hosted en GitHub.

**Score actualizado (estimado)**: ~9.5/10. Lo único que falta
para v1.0.0 stable es el veredicto externo (gate humano).
