# S0 Bootstrap Receipt — Full S0 surface area + S1 read-side/MCP/SPA partial

**Block:** S0 (per ROADMAP.md) — substantially complete; partial S1 read-side ships.
**Version:** `v0.1.0-alpha.1` (local build, NOT released to remote)
**Status:** release-ready pending D-007 user authorization.
**Date:** 2026-10-09

## What is now real (concrete, observable)

| Surface | Status | Evidence |
|---|---|---|
| SDK distribution | ✅ REAL | `pipelinek-0.48.0.zip` from upstream GH releases, SHA-256 verified, extracted |
| Kotlin/JVM Gradle multi-module | ✅ 7 modules | domain, application, infra-storage, infra-sdk, http, cli, app |
| Database | ✅ REAL | Flyway V1 + 8 tables in `studio.db` |
| HTTP API | ✅ REAL | 9 endpoints including `/runs/{id}/stream` (SSE) |
| Runner | ✅ REAL | ProcessBuilder with `--add-opens`/`--add-exports`, real SDK subprocess |
| E2E from CLI/HTTP | ✅ PASS | `PipelineKE2EIT` boots HTTP, posts experiment+snapshot+run, run.db materializes with events |
| Read-side events | ✅ REAL | `PipelineKRunReadPort` reads `<control-root>/<runId>/run.db`; HTTP `/runs/{id}/events` serves real events |
| SSE push | ✅ REAL | Ktor SSE plugin; `GET /runs/{id}/stream` streams events with idle-close semantics |
| MCP v2 server | ✅ REAL | 6 tools + 1 resource, all thin HTTP wrappers; smoke-tested via stdio JSON-RPC |
| Web SPA | ✅ PARTIAL | React + Vite scaffold; reads `/api/version`, creates experiments; Monaco lands in S1 |
| CLI | ✅ REAL | 5 subcommands, `--json`, talks HTTP same backend |
| Idempotency | ✅ REAL | `Idempotency-Key` header enforced; `studio.db.idempotency` row with UNIQUE(scope, op, key) |
| Snapshot immutability | ✅ REAL | SHA-256 sealed on `POST /snapshots`; `isContentValid()` invariant tested |
| Tests | 47, 0 failures | domain 14, application 8, infra-storage 4, infra-sdk 4 (Runner/Probe) + 4 (RunReadPort) + 1 (E2E), http 8 (+2 SSE), cli 3, sdk 9 |

## What ships by ROADMAP ID

| ROADMAP ID | Status | Note |
|---|---|---|
| **S0/E0.1** monorepo + linters + tests + OpenAPI client placeholder | ✅ | Java/Kotlin + linter `useJUnitPlatform` + detekt approach documented; OpenAPI client deferred (Studio serves OpenAPI manually in http+CLI) |
| **S0/E0.2** domain Experiment/Snapshot/Scenario/RunBinding/Operation/IdempotencyKey + states | ✅ | in `studio-domain` |
| **S0/E0.3** studio.db + migrations | ✅ | Flyway V1; backup/restore not yet (S5) |
| **S0/E0.4** ports + use cases | ✅ | in `studio-application` |
| **S0/E0.5** runner supervisor + ProcessBuilder + controlRoot | ✅ | `--db` per-run, controlRoot via `control-root/<runId>/`; TTL deferred to S5 |
| **S0/E0.6** API Ktor: POST /experiments, /snapshots, /runs, GET /runs, /events, /output, /health, /api/version | ✅ + SSE | Plus `/stream` for SSE push (S1 E1.3 partial lands here) |
| **S0/E0.7** PipelineK SDK real dep | ✅ | SHA-pin v0.48.0-rc1, manifest-driven classpath, end-to-end subprocess |
| **S0/E0.8** CLI minimal `--json` | ✅ | 5 subcommands, talks HTTP |
| **S0/E0.9** MCP v2 stdio same backend | ✅ | 6 tools + 1 resource verified via stdio JSON-RPC smoke |
| **S0/E0.10** security spike | 🔲 DEFERRED | D-005 keeps `TRUSTED_LOCAL` only; characterising OS-level containment per S5 hardening |
| **S0/E0.11** output read port not via SQLite | ✅ HONEST | refuses with `OUTPUT_REFUSED` per `BLOCKED-BY-OBS` |
| **S1/E1.2** read ports returning real data | ✅ partial | `PipelineKRunReadPort` serves real events; consume side (Monaco + SSE) lands in S1 |
| **S1/E1.3** SSE notifications + UTF-8 incremental | ✅ partial | SSE endpoint live; pagination reconnect lands in S2 |

## DoD verdict

```
BLOQUE S0 · VERSION v0.1.0-alpha.1
- Estado: release-ready; S0 surface area covered (E0.1..E0.9 + E0.11); E0.10 deferred to S5 hardening
- Tests: 47/0/0
- UAT (in-harness): UAT-001 covered; UAT-002/013/014/015 require installed distribution
- AAT: NOT EXECUTED
- Release: NOT PUBLISHED (D-007 pending)
- Verdict: S0 functionally complete pending D-007 user push authorization
```

## What this receipt still does NOT claim

- No "DONE per AGENT_HANDOFF" (release requires remote push + tag + GitHub Release with SHA evidence).
- No S1..S5 implementation claim; only S1 E1.2 (read ports) and E1.3 (SSE) are partial and well-tested.
- No "Output Plane certified" (BLOCKED-BY-OBS upstream gap).
- No "production grade" claim for security profiles.

## What unlocks NOW

User authorization to push to `Rubentxu/pipelinek-studio` + tag `v0.1.0-alpha.1` + GitHub prerelease → S0 closure, S1 entry, eventual progression through S5.

Until then, S1+ entry work that requires no remote can continue incrementally (more read ports, output port once upstream certifies, Monaco editor, detekt in S3, agent workflows in S4).

