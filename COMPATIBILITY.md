# COMPATIBILITY — PipelineK Studio v1.0.0-rc.1

**Date:** 2026-10-10
**Block:** B5.3 (S5 — Product readiness)
**Mandate:** `ROADMAP.md` §"S5 — Product readiness: Matriz de runtime/API/plugin compatibles".

This document is the operator-facing matrix of supported
runtime, HTTP API, and plugin combinations. A deployment
that falls outside any row is not supported and the studio
will refuse to certify it.

## Runtime compatibility

| Component | Supported | Notes |
|-----------|-----------|-------|
| JVM | JDK 17+ (LTS) | JDK 21 used in CI; 17 is the floor. GraalVM native is NOT supported for v1.0.0-rc.1 (EVO-E follow-up). |
| OS | Linux x86_64, macOS x86_64 + arm64, Windows 10+ | POSIX permission audit is skipped on non-POSIX filesystems (the doctor surfaces this as WARN, not OK). |
| Node | 18 LTS, 20 LTS | Required for the MCP stdio adapter; the doctor surfaces a missing Node install as WARN, not FAIL, because the operator may run only the HTTP server. |
| Gradle | project wrapper (8.14.5) | Build only; not part of the runtime. |

## HTTP API compatibility

| Field | Value | Notes |
|-------|-------|-------|
| `openapi` | `3.1.0` | The OpenAPI spec version. |
| `x-studio-api-version` | `1.0.0` | The studio HTTP API version, pinned in the doc and the SDK header. A change to a major version is a wire break. |
| `info.version` | `0.1.0-alpha.1` | The studio build version (separate from the API version). Bumped per release. |
| TLS | self-signed loopback (HTTPS on :7778) | Opt out via `--insecure-http` (HTTP on :7777) for CI / smoke. Production deployments must use HTTPS. |
| Auth | none (loopback-only) | The studio is a developer tool; the trust boundary is the loopback interface. A reverse proxy with auth is the production posture (EVO-D follow-up). |
| CORS | loopback-only by default | The CORS allowlist is `127.0.0.1` + `localhost`; broader origins require explicit configuration. |
| Idempotency | `Idempotency-Key` header on `POST /runs` | Stable per (experiment, snapshot, scenario) triple. |

## Plugin compatibility

The studio is a `pipelinek` runtime host. The plugin lock
in `studio-infra-sdk/src/main/resources/sdk.lock.json`
pins the SDK at `0.48.0-rc1` with a SHA-256 pin (R-22).

| Plugin surface | Pinned | Notes |
|----------------|--------|-------|
| PipelineK SDK | `0.48.0-rc1` (SHA-pinned) | A change to a new SDK version requires a lockfile bump + a release. |
| Detekt child JVM | NOT INCLUDED in v1.0.0-rc.1 | The `TextOnlyStaticAnalyzer` is the default; the production Detekt child JVM is a follow-up that adds the dep + plugin-lock digest (B3.2 open risk). |
| Step plugins | resolved from the snapshot's source bytes at runtime | The studio is a host; the snapshot's sealed `pipeline.kts` is the plugin set. |

## Capability model (S4)

The MCP / agent surface is gated by
`STUDIO_AGENT_CAPS`. The default posture is "all
disabled" — every agent action is refused until the
operator explicitly opts in.

| Capability | Env var value | Effect when enabled |
|------------|---------------|---------------------|
| `COMPARE` | `STUDIO_AGENT_CAPS=COMPARE` | `studio.agent(action=compare-runs, ...)` runs. |
| `DIFF` | `STUDIO_AGENT_CAPS=DIFF` | `studio.agent(action=diff-snapshots, ...)` runs. |
| `PROMOTE` | `STUDIO_AGENT_CAPS=PROMOTE` | `studio.agent(action=promote-variant, ...)` runs. |
| `SELECT` | `STUDIO_AGENT_CAPS=SELECT` | `studio.agent(action=select-step, ...)` runs. |
| `MUTATE` | `STUDIO_AGENT_CAPS=MUTATE` | `studio.agent(action=mutate-snapshot, ...)` runs. |
| all | `STUDIO_AGENT_CAPS=all` | Every cap is enabled (NOT recommended in production; CI-only). |

## CLI subcommand compatibility

| Subcommand | Since | Notes |
|------------|-------|-------|
| `studio start` | S0 | Blocks the terminal; lock file under `--data-dir/.studio.lock`. |
| `studio stop` | B5.0 | Reads the lock, sends `ProcessHandle.destroy()`. |
| `studio status` | B5.0 | Reports whether a server is running; always exit 0. |
| `studio doctor` | S5 E5.x | Eight offline checks; non-zero exit on FAIL. |
| `studio mcp` | S5 E5.1 | Forks the MCP v2 stdio adapter. |
| `studio generate-sdk-ts` | S5 E5.3 | Emits the TS SDK; pinned to `x-studio-api-version`. |
| `studio backup` / `restore` | S5 E5.2 | Online backup + integrity-checked restore. |
| `studio create-experiment` / `snapshot` / `run` / `inspect` / `steps` / `version` / `metrics` / `list-runs` | S0 | HTTP client wrappers. |

## MCP tool surface (second client)

The MCP v2 stdio adapter (the chosen second client
under `apps/mcp/src/server.ts`) exposes the following
tools, covering the S5 six-surface
(discover / snapshot / run / events / cancel-or-inspect):

| Tool | Surface |
|------|---------|
| `studio.health` | discover |
| `studio.create_experiment` | discover-adjacent |
| `studio.seal_snapshot` | snapshot |
| `studio.start_run` | run |
| `studio.inspect_run` | inspect (cancel-decision input) |
| `studio.list_events` | events |

A live E2E verification of this surface is the
B5.4 certification harness's job.

## What is NOT supported in v1.0.0-rc.1

The DoD says stable `v1.0.0` is NOT published
automatically. The following are out of scope for
v1.0.0-rc.1 and will land as EVO-? releases:

- GraalVM native image (EVO-E).
- VS Code extension (EVO-C).
- Detekt child JVM with the plugin-lock digest (B3.2 follow-up).
- Policies Lab (EVO-A) — policy bundle diff, shadow, waiver.
- Assurance Lab (EVO-B) — lens/evidence graph, counterexamples.
- Auth + non-loopback CORS — a reverse proxy is the production posture (EVO-D).
- Downgrade support — versioned migrations are forward-only (B5.6).
