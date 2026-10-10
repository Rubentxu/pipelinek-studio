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
surface is a small, testable unit. Total scope: ~2 weeks of
focused work.

| ID | Surface | What it does | VS Code API |
|----|---------|--------------|-------------|
| EVO-C.1 | **Status bar item** | Persistent "Studio: <state>" indicator. Click opens the most recent run's detail. States: `idle`, `running`, `error`, `disconnected`. | `StatusBarItem` + `setText` |
| EVO-C.2 | **Sidebar tree view** | "Studio" activity bar entry with experiments → runs → snapshots hierarchy. Refresh button. Click on a run opens its log in a new editor. | `TreeView` + `TreeDataProvider` |
| EVO-C.3 | **`studio.openRun` command** | Opens the most recent run's structured log in a new VS Code editor (one JSON-line per run event). | `workspace.openTextDocument` |
| EVO-C.4 | **`studio.cancelRun` command** | Cancels the running experiment (uses SDK `POST /experiments/{id}/cancel`). | Command + `withProgress` |
| EVO-C.5 | **Editor decoration for `*.pipeline.kts`** | Show a CodeLens above the file declaration with the linked experiment's status ("3 steps · last run OK"). Cached + invalidated on file change. | `CodeLensProvider` |
| EVO-C.6 | **Settings hardening** | `pipelinekStudio.baseUrl` (already present) + `pipelinekStudio.allowInsecureHttp` (boolean, default `false`; surfaces a confirmation dialog if the user enables it). C-1 / F-P1-VSCODE-1 / F-P1-VSCODE-2 audit. | `WorkspaceConfiguration` |

**Test contract for the MVP:**
- 4 existing S5 E5.3 tests + 6 new unit tests, one per surface
- 1 integration test: launch a real studio in a temp data dir,
  install the extension in a vscode-test container, exercise each
  surface, assert the state (this is what the B5.4 live
  certification harness extends; the VS Code integration is a
  follow-up because the test env has no Docker daemon today)

## Post-MVP features (EVO-C.1+, not in this scope)

| ID | Surface | Why deferred |
|----|---------|--------------|
| EVO-C.7 | **WebView panel for the run log** | WebView is its own platform — needs a separate design doc, security review (which `postMessage` payloads do we allow), and a CSP. Not in MVP. |
| EVO-C.8 | **Diff view for snapshots** | A snapshot is a tree; the diff is a JSON Patch. The "show diff" UX needs product input (split view? inline? PR-style?). Defer. |
| EVO-C.9 | **MCP client integration** | The MCP server is a separate process. The extension could connect to it directly (without going through the HTTP server) and offer an MCP-aware command palette. Defer — the HTTP path is the contract; MCP is a sibling, not a replacement. |
| EVO-C.10 | **Marketplace publish** | Once the MVP is stable and we have feedback from side-load users. Not before. |
| EVO-C.11 | **Multi-workspace** | Today's MVP is per-workspace. Multi-workspace (e.g. monorepos with multiple studios) is a follow-up because the product decision (one studio per workspace? per folder?) needs operator input. |

## Open product questions (operator input needed)

1. **Status bar states**: is `error` enough, or do we want a
   `warning` state (e.g. "Studio: running, 1 step stuck for 5m")?
2. **Sidebar refresh model**: poll every N seconds, or
   event-driven via the studio's SSE stream? Polling is simpler;
   SSE is real-time. Default to polling with a setting for SSE
   in v1.1.
3. **Run-log editor format**: one JSON-line per line, or a
   pretty-printed tree view? The MVP does one JSON-line per line
   (greppable); the WebView (EVO-C.7) does the tree.
4. **CodeLens cache invalidation**: on file change, or on a
   timer? File change is correct; the cost is one extra HTTP
   request per save.
5. **Multi-studio per workspace**: if the operator runs two
   studios on the same machine, which one does the extension
   talk to? The current default is the one at
   `pipelinekStudio.baseUrl` (single setting). Multi-studio is
   EVO-C.11.

## Deliverables

- [ ] EVO-C.1: status bar item (1 day)
- [ ] EVO-C.2: sidebar tree view (3 days)
- [ ] EVO-C.3: openRun command (1 day)
- [ ] EVO-C.4: cancelRun command (0.5 day)
- [ ] EVO-C.5: CodeLens for `*.pipeline.kts` (2 days)
- [ ] EVO-C.6: settings hardening (0.5 day)
- [ ] Tests: 6 unit + 1 integration (2 days)
- [ ] README + CHANGELOG update (0.5 day)
- [ ] ADR: any new architecture decision (e.g. if EVO-C.5 needs
  a new SDK method) — defer until a code change requires it

**Total: ~10 working days** (2 calendar weeks at the current
pace).

## Risk register (R4)

- **VS Code API churn**: VS Code's API is stable, but tree
  view + CodeLens providers have changed shape in past major
  bumps. We pin `engines.vscode: ^1.85.0` and target the
  1.85 API. Bumping the engine is a follow-up.
- **Generated SDK drift**: the extension imports from
  `./sdk.ts`; if the operator forgets to regenerate after a
  server bump, the extension compiles but breaks at runtime.
  Mitigation: a `studio doctor` check that asserts the SDK
  hash matches the server's `x-studio-api-version` (B5.2).
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
