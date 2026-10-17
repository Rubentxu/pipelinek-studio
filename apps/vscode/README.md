# @pipelinek-studio/vscode

R5 EVO-C — VS Code client for the PipelineK Studio HTTP control plane.

The extension consumes the generated `sdk.ts` (from
`studio generate-sdk-ts`) and exposes both commands and a live
sidebar tree sourced from the studio's `GET /events` SSE endpoint
(per [ADR-013](../../docs/03-adrs/ADR-013-sse-tree-events.md)).

## Commands

| Command | What it does |
|---|---|
| `PipelineK Studio: Ping Health` | GET `/health` via the SDK; reports `ok` or the error |
| `PipelineK Studio: Create Experiment` | Asks for a name, POST `/experiments`, shows the new id |
| `PipelineK Studio: List Steps` | GET `/steps`, shows a QuickPick with the step catalog |
| `PipelineK Studio: Show OpenAPI Document` | GET `/openapi.json`, opens it as an untitled document |
| `PipelineK Studio: Show Event Stream Status` | Status bar click: opens the most recent run's journal, or shows the connection/run summary when no run is known |
| `PipelineK Studio: Refresh Tree` | Re-reads the authoritative collections (`GET /experiments`, `GET /experiments/{id}/snapshots`, `GET /runs`) and reconciles the tree |
| `PipelineK Studio: Open Run` | Opens the run's real event journal as JSONL, walking every page of `GET /runs/{id}/events` |
| `PipelineK Studio: Open Run Diagnostics` | Opens `GET /runs/{id}/diagnostics` + `GET /runs/{id}/output` as a JSON document |
| `PipelineK Studio: Cancel Run` | `POST /runs/{id}/cancel` inside a `withProgress` notification; reports the studio's answer, never a fake terminal state |
| `PipelineK Studio: Link Experiment` | Links (or re-links) the addressed `*.pipeline.kts` document to an **existing** experiment chosen from `GET /experiments`; the association is persisted per document URI in workspace state |
| `PipelineK Studio: Seal + Run` | CodeLens + command: seals the current document source into the **linked** experiment and submits a scenario chosen from `GET /scenarios` |

## Sidebar tree

A live view of the studio's experiments, snapshots, and runs. The
tree is a live tail **plus** an authoritative snapshot:

```
Experiments
  <experiment name>
    Snapshots
      <sha7>
    Runs
      <run8>  (RUNNING|OK|FAILED|CANCELLED)
```

The SSE stream is a live tail, not a journal, so the extension reads the
collections on the initial connection and on **every** reconnect. Rows are
reconciled by stable id (experiment id, run id, snapshot id); a mutation
that arrives while a snapshot is in flight is queued and replayed after it,
so a reconnect cannot lose it. When a collection read stops at the client's
page budget, the tree is merged rather than replaced — a partial page never
deletes a row. There is no history promise: an `Last-Event-ID` resume is a
best-effort hint and the authoritative snapshot is what recovers missed
events.

## File association and Seal + Run (T4)

A `*.pipeline.kts` document is linked to an **existing** experiment; the
operator chose "link to an existing experiment" over "create one per click".

```
Studio: Seal + Run            |  Studio: demo - RUNNING  |  Studio: Change linked experiment
Studio: Seal + Run            |  Studio: No linked experiment
```

The CodeLens reports exactly what is known: the linked experiment's name and
the latest run state the tree has reconciled (for example `RUNNING`,
`SUCCEEDED`, `FAILED`, or `no runs`). It refreshes on source saves and on
every authoritative tree update; it never claims a successful state it did
not observe.

- The association is stored in `workspaceState` under a single key
  (`pipelinekStudio.fileExperimentAssociations`) keyed by document URI. It
  survives a window reload and a new extension session, and is scoped to the
  workspace. A corrupted stored value is ignored, and no association is ever
  written without an explicit choice from the experiment QuickPick.
- `Studio: Link Experiment` offers only experiments the studio really serves
  (`GET /experiments`, every page). An unlinked file offers the link action;
  linking never creates an experiment.
- `Studio: Seal + Run` refuses an unlinked document, and re-checks against the
  studio before writing anything: an experiment id the studio does not have is
  reported, not used. Scenarios come from `GET /scenarios`; when none is
  registered the command stops instead of inventing an id.
- The sealed payload is the current document source (unsaved edits included),
  submitted as `{ experiment_id, snapshot_id, scenario_id }` with a
  deterministic, charset-safe `Idempotency-Key`. No code is executed in the
  extension host: the studio performs the run.
- Cancelling either QuickPick sends no seal and no submit request, and writes
  nothing. The run is only reported and opened through its true `run_id`.

This intentionally removes the earlier placeholder behaviour that created an
`vscode-<file>` experiment per click and submitted the fabricated scenario
`vscode-default`.

## Configuration

| Setting | Default | Purpose |
|---|---|---|
| `pipelinekStudio.baseUrl` | `https://127.0.0.1:7778` | HTTP(S) Studio URL without embedded credentials. Scheme validation is case-insensitive. |
| `pipelinekStudio.allowInsecureHttp` | `false` | Machine-scoped plaintext HTTP opt-in. Requires confirmation before connecting; it never disables TLS verification. |
| `pipelinekStudio.profile` | `default` | `X-Studio-Profile` request header; 1–256 printable ASCII characters, without surrounding spaces. |

Every request path (generated SDK, typed direct reads, cancellation and the
SSE handshake) first runs `validateStudioConfig`. For `http://`, the extension
shows a modal confirmation naming that exact URL; the approval is held only
for the current VS Code session and never applies to a different URL. Declining
— or leaving `allowInsecureHttp` unset — sends no request at all. Enabling the
setting is not consent, and no code path disables TLS certificate verification
or adds an `Authorization` header the operator did not configure.

## Environment

| Variable | Default | Purpose |
|---|---|---|
| `VSCODE_STUDIO_HTTP` | unset | Legacy `resolveStudioUrl` helper override, not an automatic override of VS Code settings. HTTP requires `VSCODE_STUDIO_HTTP_INSECURE=1`, including uppercase schemes. Unsupported schemes and embedded credentials are rejected. |
| `VSCODE_STUDIO_HTTP_INSECURE` | unset | Required (set to `1`) to acknowledge an `http://` override. |
| `VSCODE_STUDIO_HTTP_TIMEOUT_MS` | `5000` | Per-request deadline; requests that exceed it raise `FetchTimeoutError`. |

## Status bar

A status bar item on the left shows one of four states:

| State | Text | Meaning |
|---|---|---|
| `idle` | `$(check) Studio: idle` | connected, no run in flight (also shown while connecting) |
| `running` | `$(sync~spin) Studio: running <n>` | connected with `n` runs in flight |
| `error` | `$(error) Studio: error` | configuration rejected, or the last authoritative read failed |
| `disconnected` | `$(debug-disconnect) Studio: disconnected` | event stream down; the client retries with bounded backoff |

The tooltip carries the detail (base URL, profile, counters, most recent run).
Clicking the item opens the most recent run's journal when one is known, and
otherwise shows the status summary.

## Build

```bash
cd apps/vscode
npm install
npm run build   # produces dist/extension.js
npm test        # transport, tree, config and SDK unit/integration tests
```

To package as a `.vsix` for installation:

```bash
cd apps/vscode
npm run package:vsix      # vsce package --out dist (uses the installed vsce; no download)
```

The package is `private: true`; it is not published to the
marketplace in this release. Distribution is via source + the
`vsce package` flow above, or by downloading the VSIX from the
matching GitHub Release.

## Acceptance checks (R5)

Three runners, three different claims. None of them is a substitute for
another, and each states what it does **not** prove.

| Command | Proves | Does not prove |
|---|---|---|
| `node scripts/extension-host-smoke.mjs dist/pipelinek-studio-1.0.0.vsix`<br>`npm run smoke:archive` | VSIX archive tree, `main`, the exact 11-command inventory (incl. `linkExperiment`, `runDiagnostics`), no duplicate ids, `onCommand:` activation events all registered, sidebar container + view | activation, providers, transport — this is a zip/manifest check |
| `node scripts/run-extension-host.mjs`<br>`npm run smoke:host` | real VS Code Extension Host (desktop build, `--extensionTestsPath`, isolated user-data/extensions/shared-data/workspace): activation, all 11 commands registered, CodeLens provider output, sidebar view, configuration defaults, settings-reload disposal path, no unhandled errors | any HTTP request: plaintext HTTP needs the operator's modal confirmation, which a headless run must not bypass. Needs a display session; without one it exits 3 (`UNAVAILABLE`). |
| `node scripts/real-studio-smoke.mjs`<br>`npm run smoke:studio` | real `studio-cli start` in an isolated `--data-dir`/`--db`, SSE `experiment.created` from a real `POST /experiments` within 2s, kill → reconnect with backoff → new event delivered, `GET /experiments` shows the persisted rows | the VS Code UI: it drives the compiled `dist/` transport (`SseClient`, `StudioClient`) directly |
| `node scripts/run-extension-host.mjs --install-vsix dist/pipelinek-studio-1.0.0.vsix` | the packaged artifact side-loads: `--install-extension` succeeds into an isolated extensions dir and `--list-extensions` reports the id/version | activation of the installed copy — never claimed, for any artifact |

All runners keep their state inside the git-ignored `build/r5-acceptance/`
tree, never touch the production `studio.db`, never install dependencies and
never write global VS Code state (`--shared-data-dir` is isolated too).
Evidence for the current revision lives in
[`docs/history/R5_VSCODE_COMPLETION_ACCEPTANCE.md`](../../docs/history/R5_VSCODE_COMPLETION_ACCEPTANCE.md).

**Evidence is artifact-bound.** A host or Studio observation describes the
`dist/`/VSIX bytes it was produced from, nothing more. After any source change:
`npm run build` → `npm test` → `npm run package:vsix` → `npm run smoke:archive --
dist/pipelinek-studio-1.0.0.vsix`, and treat the previous host/Studio results as
history until they are re-produced for the new artifact hash. The receipt records
the artifact hash beside every observation for exactly this reason.

In the current receipt the recorded host `9/9` and Studio `295 ms` runs belong to
the **pre-correction** build (`extension.js` `305e857d…`, VSIX `149374e5…`). The
delivered corrected VSIX (`extension.js` `94969ccf…`; its archive `sha256` is
recorded in the receipt, since repackaging rewrites the archive checksum) has
automated test + archive/manifest evidence only; installed-VSIX activation, full
in-editor consent and real-engine UAT remain **not proven**.

## Delivered R5 client contract (T3)

`config.ts` exports `validateStudioConfig(StudioConfigInput)` returning
`ValidatedStudioConfig`: `{ baseUrl, profile, allowInsecureHttp, headers }`.
Missing values default to HTTPS, `default`, and `false`. Validation is pure:
it neither displays a dialog nor stores approval. `validateStudioUrl` shares
the scheme/credential checks with legacy `resolveStudioUrl`.
`resolveTimeoutMs` and `fetchWithTimeout` retain their existing behavior.

What the client guarantees:

1. Reads the three VS Code settings and calls `validateStudioConfig` before
   any SDK, direct HTTP or SSE request. For HTTP it asks for an explicit modal
   confirmation naming the exact URL. A false setting or a declined dialog
   prevents every request. Approvals are session-scoped and keyed by exact URL
   (never persisted, never inherited by another URL); a settings change
   disposes the old client and revalidates.
2. Passes `headers` to SDK `defaultHeaders`, direct request headers, and
   `new SseClient({ baseUrl, headers, connectTimeoutMs: resolveTimeoutMs() })`.
   The SSE constructor checks URL syntax/scheme/credentials but deliberately
   does not own HTTP consent; callers must use the validated config path.
3. Registers `onState` and `onEvent` before `start()`. On **every** `open`,
   including the first, fetch and reconcile the authoritative tree snapshot.
   Buffer/reconcile concurrent events rather than clearing the tree and
   assuming the live bus replays history. `Last-Event-ID` is best effort.
4. Disposes the old client before replacing configuration on a settings
   change and keeps the new one in the extension subscriptions.
   `dispose()` aborts the handshake, cancels the stream reader and clears
   heartbeat/reconnect timers.

`SseClient`, `SseState`, `TreeEvent`, `start`, `dispose`, `onState`, `onEvent`
and `snapshot` remain exported/available. `snapshot().open` is true only
after a handshake, never merely after construction or while reconnecting.
Additive `SseClientOptions` fields are `headers`, `connectTimeoutMs` and
`heartbeatTimeoutMs`; existing backoff/attempt options remain supported.
The old `eventSourceFactory(url)` seam remains for existing tests, but it
cannot observe comment heartbeats or carry the new headers. Production
must leave it unset. No TLS verification bypass or new dependency is used.

## Design notes

- The extension does NOT bundle the SDK — the operator runs
  `studio generate-sdk-ts --to apps/vscode/src/sdk.ts` from the CLI
  first, so the SDK is always in sync with the server's OpenAPI doc.
- The SSE client in `apps/vscode/src/sseClient.ts` uses dependency-free
  Node 20+ `fetch` streaming, not a global `EventSource`. It handles
  fragmented UTF-8, CR/LF/CRLF, multiline data and comment heartbeats.
  A validated HTTP SSE handshake emits `open` and resets backoff;
  retries default to 1s → 30s capped. A 45s no-byte watchdog detects
  silent connections; comment heartbeats keep idle connections alive.
  Frames are capped at 256 Ki characters. Invalid JSON/payload shapes
  are ignored. HTTP errors, stream termination and oversized frames
  reconnect; redirects are rejected so headers cannot follow them.
- The tree provider (`apps/vscode/src/treeView.ts`) projects the SSE
  stream onto a `TreeDataProvider` and reconciles it against the
  authoritative collection reads. It never issues HTTP requests itself:
  `connection.ts` owns consent + lifecycle, `studioApi.ts` owns the typed
  requests.
- `studioApi.ts` uses the generated `sdk.ts` for the routes it covers and a
  typed direct read for `GET /experiments`, `GET /experiments/{id}/snapshots`,
  `GET /scenarios` and `POST /runs/{id}/cancel`, which the generated file does
  not wrap yet. Regenerating `sdk.ts` (`studio generate-sdk-ts`) is a separate
  change to a generated artifact; until then the direct helper is the honest
  path.
- `fileAssociation.ts` owns the persisted document→experiment link (workspace
  state, one key, validated on read and write). It never contacts the studio
  and never creates an experiment.
- CodeLens on `*.pipeline.kts` links the source to an existing experiment and
  submits it to the studio as a sealed snapshot. No code from the editor runs
  inside the extension host.
