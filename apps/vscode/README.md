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
| `PipelineK Studio: Show Event Stream Status` | Shows the SSE state, last id, attempt, and run counters |
| `PipelineK Studio: Refresh Tree` | Clears the sidebar tree (rebuilds from the SSE tail) |
| `PipelineK Studio: Open Run` | Opens a run's diagnostics + output as a JSON document |
| `PipelineK Studio: Cancel Run` | POSTs to `/runs/{id}/cancel` (context menu on a run) |
| `PipelineK Studio: Seal + Run` | CodeLens on `*.pipeline.kts` — seal the source, then run |

## Sidebar tree

A live view of the studio's experiments, snapshots, and runs. The
tree is sourced from the SSE event stream:

```
Experiments
  <experiment name>
    Snapshots
      <sha7>
    Runs
      <run8>  (RUNNING|OK|FAILED|CANCELLED)
```

The tree is bootstrapped from the SSE stream only; the studio's
HTTP API has no list-experiments endpoint in the MVP, so after a
server restart the tree is empty until fresh events arrive. A
future iteration (R5.7) will add a bus ring buffer to replay the
last N events on reconnect.

## Configuration

| Setting | Default | Purpose |
|---|---|---|
| `pipelinekStudio.baseUrl` | `https://127.0.0.1:7778` | Studio base URL. HTTP requires the explicit `VSCODE_STUDIO_HTTP_INSECURE=1` opt-in (F-P1-VSCODE-1). |
| `pipelinekStudio.profile` | `default` | Sent as `X-Studio-Profile` on every request — disambiguates multiple local Studio instances. |

## Environment

| Variable | Default | Purpose |
|---|---|---|
| `VSCODE_STUDIO_HTTP` | unset | Override the base URL. HTTP requires `VSCODE_STUDIO_HTTP_INSECURE=1`. |
| `VSCODE_STUDIO_HTTP_INSECURE` | unset | Required (set to `1`) to acknowledge an `http://` override. |
| `VSCODE_STUDIO_HTTP_TIMEOUT_MS` | `5000` | Per-request deadline; requests that exceed it raise `FetchTimeoutError`. |

## Status bar

A status bar item on the left shows the SSE connection state plus
the running and failed run counts. Click to open the event stream
status popup.

## Build

```bash
cd apps/vscode
npm install
npm run build   # produces dist/extension.js
npm test        # 45 unit tests (sseClient + treeView + config + sdk)
```

To package as a `.vsix` for installation:

```bash
npx vsce package
```

The package is `private: true`; it is not published to the
marketplace in this release. Distribution is via source + the
`vsce package` flow above, or by downloading the VSIX from the
matching GitHub Release.

## Design notes

- The extension does NOT bundle the SDK — the operator runs
  `studio generate-sdk-ts --to apps/vscode/src/sdk.ts` from the CLI
  first, so the SDK is always in sync with the server's OpenAPI doc.
- The SSE client is the `apps/vscode/src/sseClient.ts` thin wrapper
  around the WHATWG `EventSource` (Node 20+ in the extension host).
  Backoff is 1s → 30s capped; first successful `: ping` resets the
  attempt counter.
- The tree provider (`apps/vscode/src/treeView.ts`) is a pure
  projection of the SSE event stream onto a `TreeDataProvider`. It
  never issues HTTP reads of its own — the journal is the authority,
  the tree is the visual cache.
- CodeLens on `*.pipeline.kts` uploads the source to the studio and
  executes it via the SDK subprocess. No code from the editor runs
  inside the extension host.
