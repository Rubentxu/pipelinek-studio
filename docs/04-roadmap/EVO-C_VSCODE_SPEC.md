# EVO-C — VS Code extension scope (2026-10-10)

**Status:** PROPOSAL · **Owner:** Studio Bootstrap Agent
**Source code today:** `apps/vscode/` (S5 E5.3 — 4 commands)
**ADR-012:** VS Code consumes services, never React components as
the contract. The extension is a client of the studio's HTTP
control plane (OpenAPI + MCP), not a UI mirror of the SPA.

## Why

The S5 E5.3 extension shipped a 4-command MVP (`health`,
`createExperiment`, `listSteps`, `openOpenApi`) and stopped. The
operator can launch the studio from a terminal, but cannot
interact with experiments / runs / snapshots from the editor.
The EVO-C work brings the extension to feature parity with the
Web SPA's primary surfaces, scoped to what the editor is good at
(commands, status bar, sidebar, editor decorations) — NOT a
WebView mirror of the SPA.

## Tech stack (locked)

- **Language:** TypeScript 5.6+ (already in `apps/vscode/package.json`)
- **Engine:** VS Code 1.85+ (already pinned)
- **SDK:** generated `sdk.ts` from `studio generate-sdk-ts
  --to apps/vscode/src/sdk.ts` (NOT bundled — the operator
  regenerates on every server bump; ADR-012)
- **Runtime:** Node 20.18.0 (matches the bundled Node in the
  standalone tar; the extension itself doesn't run Node, but the
  `npm install` + `tsc` build chain does)
- **Tests:** Vitest (already wired)
- **No new dependencies** in the MVP. WebView (iframe-less native
  VS Code UI) is a follow-up.

## Activation model (locked)

- **Commands** (current + new) — operator-driven actions
- **Status bar** (new) — shows "Studio: idle / running / error"
  with click-through to the most recent run
- **Tree view in sidebar** (new) — "Studio" view with
  experiments → runs → snapshots hierarchy
- **Editor decorations** (new) — inline icons next to
  `*.pipeline.kts` files that have a linked experiment
- **No WebView in MVP** — VS Code-native UI only

## Distribution model (locked)

- **Private** (`"private": true` in `package.json`) — not
  published to the VS Code marketplace in MVP
- **Side-load** via `npx vsce package` → `.vsix` → drag-and-drop
  in VS Code's Extensions pane
- **Marketplace listing** is a v1.0.x follow-up; the marketplace
  requires a publisher ID + an icon + a README, all of which we
  have, but the operational cost of publishing + responding to
  issues is not worth it for a 1.x product

## MVP feature set (EVO-C.0)

The MVP adds 6 surfaces to the 4-command S5 E5.3 base. Each
surface is a small, testable unit. Total scope: ~2-3 weeks of
focused work (the SSE refresh model is +3 days vs polling).

| ID | Surface | What it does | VS Code API |
|----|---------|--------------|-------------|
| EVO-C.1 | **Status bar item** | Persistent "Studio: <state>" indicator. Click opens the most recent run's detail. States: `idle`, `running`, `error`, `disconnected`. | `StatusBarItem` + `setText` |
| EVO-C.2 | **Sidebar tree view (SSE-driven)** | "Studio" activity bar entry with experiments → runs → snapshots hierarchy. **Updated in real-time via an SSE stream from the studio** (operator decision: SSE over polling). The tree view is event-sourced; each event mutates the in-memory tree. | `TreeView` + `TreeDataProvider` + `EventEmitter` |
| EVO-C.3 | **`studio.openRun` command** | Opens a run's structured log in a new VS Code editor (one JSON-line per run event). | `workspace.openTextDocument` |
| EVO-C.4 | **`studio.cancelRun` command** | Cancels the running experiment (uses SDK `POST /experiments/{id}/cancel`). | Command + `withProgress` |
| EVO-C.5 | **Editor decoration for `*.pipeline.kts`** | Show a CodeLens above the file declaration with the linked experiment's status ("3 steps · last run OK"). Cached + invalidated on file change. | `CodeLensProvider` |
| EVO-C.6 | **Settings hardening** | `pipelinekStudio.baseUrl` (already present) + `pipelinekStudio.allowInsecureHttp` (boolean, default `false`; surfaces a confirmation dialog if the user enables it). C-1 / F-P1-VSCODE-1 / F-P1-VSCODE-2 audit. | `WorkspaceConfiguration` |
| EVO-C.7 | **SSE client** | Lifecycle-managed SSE connection to `GET /events` (or similar). Reconnect with exponential backoff (1s → 30s cap). Heartbeat ping every 30s to detect silent disconnects. Surface connection state in the status bar (EVO-C.1's `disconnected` state). | `EventSource` (browser) OR `eventsource` npm + Node http |

**SSE contract** (new SDK method, added in the same MVP):

```
GET /events
Accept: text/event-stream
200 OK
Content-Type: text/event-stream

event: experiment.created
data: {"id": "...", "name": "...", "createdAt": "..."}

event: experiment.deleted
data: {"id": "..."}

event: run.started
data: {"experimentId": "...", "runId": "...", "startedAt": "..."}

event: run.step.changed
data: {"runId": "...", "stepId": "...", "status": "RUNNING|COMPLETED|FAILED", "durationMs": 1234}

event: run.completed
data: {"runId": "...", "status": "OK|FAILED|CANCELLED"}

event: snapshot.created
data: {"experimentId": "...", "snapshotId": "...", "bytes": 12345}

: ping (every 30s, server-sent comment line for keep-alive)
```

The event taxonomy is the contract surface; the studio already
emits structured events for runs (per the existing run-log
format), so the SSE endpoint is a thin projection of the same
event bus onto a stream. The extension is the first consumer
beyond the SPA's polling — if it works here, it can be reused
by any future client (CLI, scripts, etc.).

**Test contract for the MVP:**
- 4 existing S5 E5.3 tests + 7 new unit tests, one per surface
- 1 SSE integration test: launch a real studio in a temp data
  dir, connect an SSE consumer, fire `experiment.create` via the
  SDK, assert the SSE event arrives within 2s
- 1 reconnect test: kill the studio mid-stream, assert the
  client reconnects with backoff when the studio comes back

## Post-MVP features (EVO-C.1+, not in this scope)

| ID | Surface | Why deferred |
|----|---------|--------------|
| EVO-C.8 | **WebView panel for the run log** | WebView is its own platform — needs a separate design doc, security review (which `postMessage` payloads do we allow), and a CSP. Not in MVP. |
| EVO-C.9 | **Diff view for snapshots** | A snapshot is a tree; the diff is a JSON Patch. The "show diff" UX needs product input (split view? inline? PR-style?). Defer. |
| EVO-C.10 | **MCP client integration** | The MCP server is a separate process. The extension could connect to it directly (without going through the HTTP server) and offer an MCP-aware command palette. Defer — the HTTP path is the contract; MCP is a sibling, not a replacement. |
| EVO-C.11 | **Marketplace publish** | Once the MVP is stable and we have feedback from side-load users. Not before. |
| EVO-C.12 | **Multi-workspace** | Today's MVP is per-workspace. Multi-workspace (e.g. monorepos with multiple studios) is a follow-up because the product decision (one studio per workspace? per folder?) needs operator input. |

## Open product questions (operator input needed)

1. **Status bar states**: is `error` enough, or do we want a
   `warning` state (e.g. "Studio: running, 1 step stuck for 5m")?
2. **~~Sidebar refresh model~~**: ~~poll vs SSE~~. **DECIDED
   (2026-10-10): SSE event-driven.** A new SDK method
   `GET /events` (server-sent events) streams tree mutations
   to the extension; the tree view is event-sourced.
3. **Run-log editor format**: one JSON-line per line, or a
   pretty-printed tree view? The MVP does one JSON-line per line
   (greppable); the WebView (EVO-C.8) does the tree.
4. **CodeLens cache invalidation**: on file change, or on a
   timer? File change is correct; the cost is one extra HTTP
   request per save.
5. **Multi-studio per workspace**: if the operator runs two
   studios on the same machine, which one does the extension
   talk to? The current default is the one at
   `pipelinekStudio.baseUrl` (single setting). Multi-studio is
   EVO-C.12.
6. **NEW (driven by SSE decision)**: SSE reconnection backoff
   params — start at 1s, cap at 30s? Or use a different curve?
   Default to 1s → 30s exponential; configurable in v1.1.

## Deliverables

- [ ] **Server: SSE endpoint `GET /events`** (2 days)
  - 1 SDK method + 1 server endpoint
  - 1 OpenAPI doc update
  - 1 integration test (SSE consumer + event delivery)
- [ ] EVO-C.1: status bar item (1 day)
- [ ] EVO-C.2: sidebar tree view (SSE-sourced) (3 days)
- [ ] EVO-C.3: openRun command (1 day)
- [ ] EVO-C.4: cancelRun command (0.5 day)
- [ ] EVO-C.5: CodeLens for `*.pipeline.kts` (2 days)
- [ ] EVO-C.6: settings hardening (0.5 day)
- [ ] EVO-C.7: SSE client + lifecycle + backoff (2 days)
- [ ] Tests: 7 unit + 2 integration (3 days)
- [ ] README + CHANGELOG update (0.5 day)
- [ ] ADR: SSE endpoint contract (ADR-013 or extension of ADR-012)

**Total: ~15 working days** (3 calendar weeks at the current
pace; +5 days vs the polling baseline for the SSE work).

## Risk register (R4)

- **SSE in VS Code's extension host**: VS Code's extension host
  is Node 20 with `fetch` and the WHATWG `EventSource`
  available. No new dependency needed; we can use the built-in
  `EventSource` (Node 18+). If that turns out to be buggy
  (event ordering, large payloads), fallback is the
  `eventsource` npm package.
- **VS Code API churn**: VS Code's API is stable, but tree
  view + CodeLens providers have changed shape in past major
  bumps. We pin `engines.vscode: ^1.85.0` and target the
  1.85 API. Bumping the engine is a follow-up.
- **Generated SDK drift**: the extension imports from
  `./sdk.ts`; if the operator forgets to regenerate after a
  server bump, the extension compiles but breaks at runtime.
  Mitigation: a `studio doctor` check that asserts the SDK
  hash matches the server's `x-studio-api-version` (B5.2).
- **SSE behind a reverse proxy**: if the operator runs the
  studio behind nginx, the proxy must support streaming
  (proxy_buffering off, proxy_read_timeout ≥ 30s). Documented
  in the install channels' READMEs.
- **Sidebar perf**: tree view with 1000+ experiments is slow
  to render. Mitigation: lazy-load the children (only fetch
  runs for an experiment when the user expands it).

## What this scope does NOT include

- A WebView panel for the SPA. The SPA is web; the extension
  is VS Code-native. They are siblings, not duplicates.
- A marketplace listing. The MVP is side-load only.
- A "studio in a Docker container" workflow. The extension
  expects a local studio reachable on `baseUrl`.
- A multi-studio UX. One studio per workspace is the MVP.
- A self-update mechanism. The extension version is pinned
  to the studio version in the side-load README.
