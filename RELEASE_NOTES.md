# Release Notes — PipelineK Studio v0.1.0-alpha.1

**Release date:** 2026-10-09
**Status:** Pre-release. Suitable for early adopters; not yet
production-grade. Operator-driven hardening (iptables, seccomp,
namespace) is documented but not applied by the binary.

---

## What is PipelineK Studio?

PipelineK Studio is a control plane for the [PipelineK](https://github.com/Rubentxu/pipeline-kotlin)
SDK. The Studio is the read/write surface for:

- **Experiments** — long-lived containers for related snapshots and runs.
- **Snapshots** — immutable Kotlin source artifacts (SHA-256 sealed).
- **Runs** — supervised subprocess invocations of the SDK against a
  snapshot + scenario.
- **Events / Output / Diagnostics / IR** — read-side ports over the
  live run.

The Studio is **single-tenant** by design: one operator, one
machine. The SDK runs in a supervised subprocess with the
operator's UID; hardening is the operator's responsibility (see
`docs/05-testing/SANDBOX_*`).

## What's in this release

### HTTP API (14 endpoints, OpenAPI 3.1 contract at `/openapi.json`)

- `POST /experiments` — create an experiment.
- `POST /experiments/{experimentId}/snapshots` — seal a Kotlin source
  snapshot (SHA-256, immutable).
- `POST /runs` — submit a run (idempotent via `Idempotency-Key` header).
- `GET /runs/{runId}` — read run state.
- `GET /runs/{runId}/events` — paginated event list.
- `GET /runs/{runId}/stream` — SSE event stream with `Last-Event-ID`
  reconnect.
- `GET /runs/{runId}/output` — stdout bytes.
- `GET /runs/{runId}/diagnostics` — normalized diagnostics.
- `GET /runs/{runId}/ir` — IR with COMPILED/ADMITTED/OBSERVED phases.
- `GET /steps` + `GET /steps/{stepId}` — admitted Step plugin catalog.
- `GET /health`, `GET /api/version`, `GET /openapi.json`.

### CLI (`studio`, 12 subcommands)

```
studio doctor                                  # /health probe
studio create-experiment --name <name>
studio snapshot --experiment <id> --source <s>
studio run --experiment <id> --snapshot <id> --scenario <id>
studio inspect --run <id>
studio steps
studio version
studio backup --db <path> --dest <file>          # online SQLite backup
studio restore --from <backup> --to <db>         # integrity-check restore
studio start [--host] [--port] [--db]            # minimal Ktor server
studio mcp                                        # exec the Node MCP adapter
studio generate-sdk-ts [--to <file>]              # emit a TypeScript client
```

The CLI bundles the `studio` start subcommand as a smoke server
(`/health`, `/api/version`, `/api/ready`, `/openapi.json`) for
environments without the full `studio-app` distribution. The full
HTTP surface ships in `studio-app` (built from `studio-http` +
`studio-infra-*`).

### MCP v2 stdio adapter (`apps/mcp`)

A Node.js v2 stdio adapter that talks to the local Studio HTTP
server. Six tools (`studio.health`, `studio.create_experiment`,
`studio.seal_snapshot`, `studio.start_run`, `studio.inspect_run`,
`studio.list_events`) and two resources (`studio://version`,
paginated `studio://catalog/steps`). Build with
`cd apps/mcp && npm install && npm run build`.

### Web SPA (`apps/web`)

React 19 + Vite 6 + Monaco editor. The placeholder shell now has:
- status panel (Studio + SDK version, SHA-256 prefix, capabilities)
- "create experiment" button
- Monaco-based Kotlin source editor with debounced onChange
- "Save snapshot" button + experiment picker
- SSE event stream consumer (EventSource, role=log, auto-reconnect)
- Output viewer (200 chunks OR honest 202 OUTPUT_REFUSED rendering)
- ARIA baseline (main/region/alert/live regions, skip link, Cmd-S/Cmd-Enter/Esc shortcuts)

### VS Code extension (`apps/vscode`)

4 commands (health, createExperiment, listSteps, openOpenApi) +
configurable `pipelinekStudio.baseUrl` setting. Consumes the
generated `sdk.ts`. Light + dark themeable icons. 9/9
`vsce package` prerequisites verified by `scripts/vscode-package-check.sh`.

### Tests + CI

- 131 unit / integration tests, 0 failures, 1 E2E auto-skip without
  SDK_PATH.
- 21 end-to-end certification checks in
  `scripts/verify-from-clean-clone.sh` (gradlew wrapper via
  `./gradlew verifyCleanClone`).
- Static analysis via detekt 1.23.8 in-process runner
  (`scripts/detekt-run.sh`, 35 baseline findings, SARIF + txt).
- UAT harness for the installed distribution
  (`scripts/uat-from-distribution.sh`, 10 steps, accepts 200 OR
  documented 202 at every endpoint).

## Known limitations (read before installing)

- **Output Plane**: returns 202 + `OUTPUT_REFUSED` until the SDK's
  output port is certified end-to-end. The Studio's read-side Output
  port is honest about that — it never returns a fake success.
- **Plugins**: Step plugins are discovered but not classloaded. A
  plugin executes only when the operator explicitly runs a scenario
  against it.
- **Sandbox**: The Studio runs the SDK subprocess with the
  operator's UID. Hardening (iptables, seccomp, namespace) is
  operator-driven. See `docs/05-testing/SANDBOX_*`.
- **Single-tenant**: The Studio is single-tenant by design. Don't
  expose it on a public interface without TLS + auth.
- **No push yet (D-007)**: This release is local-only. Push to
  GitHub + tag + GitHub Release require explicit operator
  authorization.

## Upgrade notes

First release — no upgrade path.

## Thanks

To the upstream PipelineK team for the SDK; to the operator who
asked for the full S0..S5 sprint in one session; and to the future
maintainer who will read this and the 12 ADRs in `docs/03-adrs/`
+ the 11 ADR-lights in `odd/tasks/pipelinek-studio-bootstrap.md`.
