# @pipelinek-studio/vscode

S5 E5.3 — VS Code client for the PipelineK Studio HTTP control plane.

The extension consumes the generated `sdk.ts` (from
`studio generate-sdk-ts`) and exposes four commands:

| Command | What it does |
|---|---|
| `PipelineK Studio: Ping Health` | GET `/health` via the SDK; reports `ok` or the error |
| `PipelineK Studio: Create Experiment` | Asks for a name, POST `/experiments`, shows the new id |
| `PipelineK Studio: List Steps` | GET `/steps`, shows a QuickPick with the step catalog |
| `PipelineK Studio: Show OpenAPI Document` | GET `/openapi.json`, opens it as an untitled document |

The base URL is configurable via the `pipelinekStudio.baseUrl` setting
(default `http://127.0.0.1:7777`).

## Build

This extension is part of the PipelineK Studio monorepo. To build it:

```bash
cd apps/vscode
npm install
npm run build   # produces dist/extension.js
```

To package as a `.vsix` for installation:

```bash
npx vsce package
```

## Design notes

- The extension does NOT bundle the SDK — the operator runs
  `studio generate-sdk-ts --to apps/vscode/src/sdk.ts` from the CLI
  first, so the SDK is always in sync with the server's OpenAPI doc.
- The extension is `private: true`; it is not published to the
  marketplace in this release. Distribution is via source + the
  `vsce package` flow above.
- The `workspaceContains:**/*.pipeline.kts` activation event is a
  hint for the future: when a workspace has a PipelineK source file,
  the extension can offer context-aware commands.
