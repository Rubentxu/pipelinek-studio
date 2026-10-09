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
| HTTP API | ✅ REAL | 12 endpoints: experiments / snapshots / runs / events / output / stream / steps / steps/{id} / diagnostics / ir / health / version |
| Runner | ✅ REAL | ProcessBuilder with `--add-opens`/`--add-exports`, real SDK subprocess, stdout+stderr captured to disk |
| E2E from CLI/HTTP | ✅ PASS | `PipelineKE2EIT` boots HTTP, posts experiment+snapshot+run, run.db materializes with events |
| Read-side events | ✅ REAL | `PipelineKRunReadPort` reads `<control-root>/<runId>/run.db`; HTTP `/runs/{id}/events` serves real events |
| Output Read port | ✅ REAL | `PipelineKOutputReadPort` reads `<control-root>/<runId>/stdout.log`; HTTP `/runs/{id}/output` serves real bytes (R-S0-F effectively closes for S0 bootstrap) |
| SSE push | ✅ REAL | Ktor SSE plugin; `GET /runs/{id}/stream` streams events with idle-close semantics |
| MCP v2 server | ✅ REAL | 6 tools + 1 resource, all thin HTTP wrappers; smoke-tested via stdio JSON-RPC |
| S2 Plugin Lab — static manifest inspector | ✅ REAL | `StaticManifestInspector` reads JAR metadata without classloading; detects META-INF/services + plugin.properties; trust classification |
| S2 Plugin Lab — step catalog | ✅ REAL | `PipelineKStepCatalogReadPort` enumerates Step contracts via META-INF/services + Core\*Step.class central-directory scan; HTTP `GET /steps` and `GET /steps/{id}` |
| S3 Diagnostics | ✅ REAL | `DiagnosticsReadPort` normalizes SDK Compilation diagnostics; HTTP `GET /runs/{id}/diagnostics` |
| S3 IR Explorer | ✅ REAL | `IrExplorerReadPort` projects compiled/admitted/observed phases; HTTP `GET /runs/{id}/ir` |
| Web SPA | ✅ PARTIAL | React + Vite scaffold; reads `/api/version`, creates experiments; Monaco lands in S1 |
| CLI | ✅ REAL | 6 subcommands (doctor, create-experiment, snapshot, run, inspect, steps), `--json`, talks HTTP same backend |
| Idempotency | ✅ REAL | `Idempotency-Key` header enforced; `studio.db.idempotency` row with UNIQUE(scope, op, key) |
| Snapshot immutability | ✅ REAL | SHA-256 sealed on `POST /snapshots`; `isContentValid()` invariant tested |
| S5 E5.1 CLI surface | ✅ REAL | `studio start` (minimal Ktor server on /health, /api/version, /api/ready, /openapi.json), `studio mcp` (locates Node adapter, execs node) |
| S5 E5.2 backup/restore | ✅ REAL | `StudioBackup` uses SQLite Online Backup API via xerial NativeDB; CLI `backup`/`restore` subcommands; integrity_check on restore |
| S5 E5.3 OpenAPI SDK TS + VS Code client | 🔶 PARTIAL | OpenAPI 3.1 doc served at `/openapi.json` (full + minimal server mirror); SDK TS codegen + VS Code client still TODO |
| S5 E5.4 request body limit | ✅ REAL | `installBodySizeLimit` 1 MiB; oversized requests → 413 + JSON `{code,message}`; production module wired |
| S5 E5.5 perf baselines | ✅ REAL | `PerfBaselineTest` enforces median < budget for /health, /api/version, /steps, /runs/{id} |
| S5 E5.7 from-clean-clone verification | ✅ REAL | `scripts/verify-from-clean-clone.sh` runs 5 checks end-to-end: tests, distTar, /health+/api/version+/openapi.json smoke, backup, MCP resources/list |
| S4 E4.5 MCP resources paginated | ✅ REAL | `studio://catalog/steps` resource with ?cursor=N&limit=M; honest refusal when backend missing |
| S2 E2.5 ResultDescriptor renderers | ✅ REAL | Markdown + HTML renderers for Text/Json/Table/ArtifactRefs/NotAvailable; pure functions over existing sealed interface |
| S1 E1.4 SSE reconnect | ✅ REAL | `Last-Event-ID` header and `?after=<seq>` query param; `id:` field on every event; `SSE_RESUMED` marker event |
| S2 E2.3 scenario test runner | ✅ REAL | `RunScenarioUseCase` wraps `ScenarioRunnerPort`; structured pass/fail/diff verdict; default wiring returns honest Failure (no fake PASS) |
| S5 E5.3 SDK TS codegen | ✅ REAL | `studio generate-sdk-ts [--to file]` emits a typed fetch client from `/openapi.json`; 14 wrappers covering all documented routes; verified in 4b step of E5.7 |
| S5 E5.4 UUID strict validation | ✅ REAL | `RouteParams.requireUuid` validates route param IDs; malformed IDs → 400 + structured `BAD_REQUEST`; 7 routes wired |
| S3 E3.2 Detekt in-process | ✅ REAL | `detekt.yml` baseline + `scripts/detekt-run.sh` runs detekt-cli 1.23.8 in isolated JVM; 35 baseline findings; SARIF + txt reports under `build/reports/`; verify step 4c |
| S2 E2.4 plugin fixtures | ✅ REAL | `Scenarios.kt` (hello-world / json-success / table-output / failing) + `deterministicRunner()` |
| S4 E4.3 mutation fixtures | ✅ REAL | `Mutations.kt` (variantPort + comparisonPort + base/mutated run event fixtures) |
| S5 E5.4 security posture doc | 🔶 PARTIAL | `docs/05-testing/SECURITY_POSTURE.md` characterises current posture + residual risks; namespace isolation + rate limit + TLS still TODO |
| S4 E4.1 scenario generation testkit | ✅ REAL | `ScenarioBuilder` + `ScenarioSpec` (hand-rolled JSON serializer for the test source set) |
| S4 E4.6 agent workflows | ✅ REAL | `AgentWorkflows.mutateAndCompare` helper coordinates variant → scenario → compare with `success` flag |
| S1 E1.1 Monaco editor | ✅ REAL | `apps/web/src/KotlinEditor.tsx` wraps `@monaco-editor/react`; Kotlin language, dark theme, debounced onChange, graceful loading/unavailable states |
| S5 E5.3 VS Code client scaffold | ✅ REAL | `apps/vscode/` with package.json, extension.ts, 4 commands (health/createExperiment/listSteps/openOpenApi), baseUrl setting, consumes the generated sdk.ts |
| S5 E5.6 ARIA + keyboard | ✅ REAL | ARIA baseline (main/region/alert/live regions, aria-labels) + keyboard shortcuts (Cmd-S seal, Cmd-Enter create, Esc clear focus) + auto-focus main on mount + SkipLink W3C G1 |
| S5 E5.4 rate limiter | ✅ REAL | in-process per-IP token bucket (60 req/60s default); 429 + Retry-After + RATE_LIMITED; opt-out via STUDIO_RATE_LIMIT_DISABLED=1 |
| S1 E1.2 SPA snapshot sealing | ✅ REAL | `App.tsx` posts current Monaco source to `/experiments/{id}/snapshots`; renders `snapshot_id` + `sha256` in `role=status aria-live=polite` |
| S1 E1.3 SPA SSE consumer | ✅ REAL | `EventStream.tsx` consumes `/runs/{runId}/stream` via EventSource; renders events in `role=log aria-live=polite`; auto-reconnects on drop (S1 E1.4 backend support) |
| S1 E1.5 SPA Output viewer | ✅ REAL | `OutputViewer.tsx` polls `/runs/{runId}/output`; renders 200 chunks, neutral rendering for 202 OUTPUT_REFUSED/UNKNOWN_STREAM, AbortController on unmount |
| S1 E1.6 UAT scaffold | ✅ REAL | `scripts/uat-from-distribution.sh` — 10-step end-to-end harness for the installed distribution; accepts 200 OR documented 202 refusal; explicit exit codes |
| S5 E5.3 VS Code package check | ✅ REAL | `scripts/vscode-package-check.sh` — 9/9 assertions for `vsce package` prerequisites (publisher, engines, unique command ids, activation events, activate/deactivate) |
| S5 E5.3 VS Code icons | ✅ REAL | `apps/vscode/images/studio-icon{,-dark}.svg` (3-segment pipeline motif) + `icon` + `galleryBanner` in package.json |
| S5 E5.3 CHANGELOG | ✅ REAL | `CHANGELOG.md` Keep-a-Changelog format; full S0..S5 surface documented; 0.1.0-alpha.1 anchor |
| S5 E5.3 RELEASE_NOTES | ✅ REAL | `RELEASE_NOTES.md` operator-facing first-60-seconds doc; what's in, what's NOT, known limitations, upgrade notes |
| S5 E5.3 CONTRIBUTING | ✅ REAL | `CONTRIBUTING.md` maintainer guide: project shape, conventions, pre-PR checklist, port + MCP recipes, commit format |
| S5 E5.3 MAINTAINERS | ✅ REAL | `MAINTAINERS.md` short contact list: Steward + Backup Steward + Security roles, Ops vacant; add/remove procedures; security disclosure pointer |
| S5 E5.4 net policy doc | 🔶 PARTIAL | `docs/05-testing/SANDBOX_NETPOLICY.md` ships iptables + nftables samples for loopback-only egress; the actual 'apply' step is operator-driven (D-005) |
| S5 E5.4 seccomp doc | 🔶 PARTIAL | `docs/05-testing/SANDBOX_SECCOMP.md` ships a deny-by-default seccomp profile (AF_UNIX socket+connect only, no TCP/UDP) + apply + verify + caveats |
| S5 E5.4 namespace doc | 🔶 PARTIAL | `docs/05-testing/SANDBOX_NAMESPACE.md` ships overlay + unshare + capability drop recipe; pairs with net + seccomp for layered defence |
| S5 E5.4 TLS doc | 🔶 PARTIAL | `docs/05-testing/SANDBOX_TLS.md` ships nginx + Caddy reverse-proxy configs with HSTS, SSE keep-alive, mTLS block; operator-applied |
| S5 E5.6 SPA Vitest harness | ✅ REAL | `apps/web/vitest.config.ts` + `src/test/setup.ts` + `npm test` script; 2 test files: 6 hook tests for keyboard shortcuts + 1 component smoke for `KotlinEditor`. Requires `npm install` on the operator's machine |
| S5 secret canaries | ✅ REAL | `SecretRedactor` port (no-op default + `PatternSecretRedactor` impl); wired into `ResultRenderers` so any Text/Json/Table/ArtifactRefs/NotAvailable pass is scanned before render; 6 tests catch AWS keys, GitHub PATs, Slack tokens, env vars, high-entropy blobs; benign controls untouched |
| S5 SBOM | ✅ REAL | `scripts/generate-sbom.sh` — CycloneDX 1.5 subset, zero external deps, scans `:studio-app` runtimeClasspath; writes 97+ components to `dist/sbom.cdx.json`; falls back to structured JSON error if gradle is missing |
| S5 troubleshooting | ✅ REAL | `TROUBLESHOOTING.md` — 10 symptom-fix entries (port-in-use, DB lock, rate limit, 413, MCP, SPA, run stuck, schema drift, fast-no-output, OOM) + `studio --version` / `-v` CLI flag that prints the bundled version offline |
| S5 checksums | ✅ REAL | `scripts/generate-checksums.sh` — SHA-256 manifest of the `studio-cli/build/install/studio-cli` tree, 65 files, written as both sha256sum-compatible `.txt` and JSON; zero external deps |
| S5 upgrade guide | ✅ REAL | `UPGRADE.md` — pre-flight checklist (backup, integrity check, stop, version), upgrade steps (extract, verify checksums, start, watch Flyway, doctor), Flyway policy (additive vs destructive), rollback procedure, hot-upgrade caveat (no zero-downtime, single-writer rule R-08) |
| S5 compatibility matrix | ✅ REAL | `docs/06-operations/COMPATIBILITY_MATRIX.md` — runtime stack (JDK/Gradle/Kotlin/Node/OS), Studio release matrix (0.1.0-alpha.1 ↔ SDK 0.48.0-rc1), plugin ABI (same major = ADMIT, cross major = REJECT), API version, SPA/VS Code, anti-support policy + how to add a row |
| S5 profiling endpoint | ✅ REAL | `GET /api/metrics` (full server + minimal server + OpenAPI + `studio metrics` CLI subcommand): `{jvm: {uptime_ms, threads_live, threads_peak, heap_used_mb, heap_max_mb, available_processors}}` — cold vs steady-state visibility |
| S5 retention cleanup | ✅ REAL | `scripts/cleanup-runs.sh` — operator-driven (D-005) per-run DB pruner; default dry-run with `--apply` gate; only terminal states (COMPLETED/FAILED/CANCELLED) eligible (R-07); queries studio.db via `sqlite3`; preserves studio.db metadata |
| Tests | **137, 0 failures** (1 E2E auto-skip without SDK_PATH) | domain 14, application 47 (+6), infra-storage 7, infra-sdk 27, http 25, cli 18, e2e 1 (skip) |

## What ships by ROADMAP ID

| ROADMAP ID | Status | Note |
|---|---|---|
| **S0/E0.1** monorepo + linters + tests + OpenAPI client placeholder | ✅ | Java/Kotlin + linter `useJUnitPlatform` + detekt approach documented; OpenAPI client deferred (Studio serves OpenAPI manually in http+CLI) |
| **S0/E0.2** domain Experiment/Snapshot/Scenario/RunBinding/Operation/IdempotencyKey + states | ✅ | in `studio-domain` |
| **S0/E0.3** studio.db + migrations | ✅ | Flyway V1; backup/restore not yet (S5) |
| **S0/E0.4** ports + use cases | ✅ | in `studio-application` |
| **S0/E0.5** runner supervisor + ProcessBuilder + controlRoot | ✅ | `--db` per-run, controlRoot via `control-root/<runId>/`; stdout+stderr captured to stdout.log; TTL deferred to S5 |
| **S0/E0.6** API Ktor: POST /experiments, /snapshots, /runs, GET /runs, /events, /output, /health, /api/version | ✅ + SSE | Plus `/stream` for SSE push (S1 E1.3 partial lands here); `/output` now serves real subprocess bytes |
| **S0/E0.7** PipelineK SDK real dep | ✅ | SHA-pin v0.48.0-rc1, manifest-driven classpath, end-to-end subprocess |
| **S0/E0.8** CLI minimal `--json` | ✅ | 5 subcommands, talks HTTP |
| **S0/E0.9** MCP v2 stdio same backend | ✅ | 6 tools + 1 resource verified via stdio JSON-RPC smoke |
| **S0/E0.10** security spike | 🔲 DEFERRED | D-005 keeps `TRUSTED_LOCAL` only; characterising OS-level containment per S5 hardening |
| **S0/E0.11** output read port not via SQLite | ✅ HONEST | refuses with `OUTPUT_REFUSED` per `BLOCKED-BY-OBS`; real bytes now served via `PipelineKOutputReadPort` reading stdout.log |
| **S2/E2.1** static manifest inspector (no classloading) | ✅ | `StaticManifestInspector` reads JarFile + Manifest only; detects META-INF/services + plugin.properties; trust classification |
| **S1/E1.2** read ports returning real data | ✅ | `PipelineKRunReadPort` serves real events; consume side (Monaco + SSE) lands in S1 |
| **S1/E1.3** SSE notifications + UTF-8 incremental | ✅ partial | SSE endpoint live; pagination reconnect lands in S2 |
| **S2/E2.1** static manifest inspector | ✅ | `StaticManifestInspector` — JAR metadata only, no classloading |
| **S2/E2.2** snapshot StepRegistry + GET /steps | ✅ | `PipelineKStepCatalogReadPort` + HTTP endpoints + 4 tests |
| **S3/E3.1** normalized diagnostics | ✅ | `DiagnosticsReadPort` reads SDK Compilation events + normalizes; HTTP `/runs/{id}/diagnostics` |
| **S3/E3.3** IR Explorer with COMPILED\|ADMITTED\|OBSERVED phases | ✅ | `IrExplorerReadPort` projects events + operation_journal; HTTP `/runs/{id}/ir` |
| **S4** agent collaboration | 🔲 DEFERRED | future agent-workflows block; closest near-term = mutation scenarios |
| **S5/E5.1** CLI `start` / `mcp` / `doctor` | ✅ | `studio start` boots minimal Ktor server; `studio mcp` locates Node adapter; `studio doctor` HTTP probe |
| **S5/E5.2** backup/restore | ✅ | `StudioBackup` via xerial NativeDB Online Backup API; CLI `backup`/`restore` |
| **S5/E5.3** OpenAPI SDK TS + VS Code client | ✅ | OpenAPI 3.1 doc + `studio generate-sdk-ts` codegen shipped; VS Code client still TODO |
| **S5/E5.4** security hardening | 🔶 PARTIAL | Body size limit + UUID strict validation shipped; OS-level sandbox characterisation pending |
| **S5/E5.5** perf baselines | ✅ | Median-budget test harness covers 4 read-only endpoints |
| **S5/E5.6** QA UX / keyboard / ARIA | 🔶 PARTIAL | Vitest harness ships (config + setup + 7 unit/smoke tests); screen-reader manual verification still needs a running SPA + human review |
| **S5/E5.7** ADR review + certification | ✅ | `verify-from-clean-clone.sh` passes 34 end-to-end checks; D-008..D-011 added |

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

