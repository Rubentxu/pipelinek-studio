# Changelog

All notable changes to PipelineK Studio are documented in this file.
The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and the project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [0.2.0-alpha.6] - 2026-10-10

### Fixed (B0 — recover trust in the certification gate)

The release-harness surface was audited for false-PASS channels
(scenarios in which the gate would declare `READY` after a test
had failed, been skipped, or been misclassified). Six channels
were identified; all are now closed.

- **B0.1 — start smoke fail-on-timeout** (`verify-from-clean-clone.sh`):
  the for-loop that polls `/health` had no fail path when the
  backend never became healthy. A backend that silently died at
  startup would fall through the loop and proceed to step 4 with
  zero successful HTTP probes. The harness now fails the smoke
  with a clear "never returned 200" message when the loop
  exhausts.
- **B0.2 — workflow classifier** (`.github/workflows/release-harness.yml`):
  a non-zero harness exit was previously split into `❌ NOT_READY`
  (when the log contained a `❌` marker) and `⚠️ READY_WITH_RISKS`
  (when it did not). The soft verdict was exactly the false-
  positive channel B0 exists to close. A non-zero exit is now
  always classified as `NOT_READY`; the `❌` marker is no longer
  load-bearing.
- **B0.3 — detekt default = fail on findings** (`scripts/detekt-run.sh`):
  every detekt finding was previously swallowed as "non-fatal for
  the S0 bootstrap" and the script exited 0. The harness could
  declare READY while detekt was reporting an unbounded number of
  issues. The new default `DETEKT_FAIL_ON_FINDINGS=1` counts
  findings from the SARIF report (machine-readable, `jq` with a
  `grep` fallback) and exits non-zero on any findings. The legacy
  baseline posture is preserved as an explicit opt-out.
- **B0.4 — explicit N-of-M coverage + MCP honesty**
  (`verify-from-clean-clone.sh`): the harness previously printed a
  textual "ALL FIVE CHECKS PASSED" with no machine-readable
  summary. Every checkpoint is now a `step` call (increments
  `HARNESS_EXPECTED`); every successful assertion is an `ok` call
  (marks the current step as passed). The `on_exit` trap compares
  the two and emits a stable `HARNESS_SUMMARY passed=N expected=M`
  line that the workflow greps for. The MCP step no longer falls
  back to a guidance-only `ok` when `node` is missing: it hard-
  fails (with `SKIP_MCP=1` as an explicit, logged opt-out for
  node-less environments). The MCP round-trip is re-anchored to
  a freshly-spawned minimal backend on a new port.
- **B0.5 — wire UAT from distribution** (`verify-from-clean-clone.sh`):
  the harness previously only smoke-tested the minimal CLI
  (`studio-cli-minimal`); a passing `studio start` against it
  proved nothing about the real product. The new step 3b builds
  `:studio-app:installDist`, starts the full server with
  `JAVA_OPTS` overriding the launcher's defaults
  (`-Dpipelinek.studio.runtime.port` and
  `-Dpipelinek.studio.runtime.dataDir`), runs `uat-from-
  distribution.sh` against it, and tears it down. The
  `JAVA_OPTS` path keeps the user's real `~/.pipelinek-studio`
  runtime directory untouched.
- **B0.6 — failure-injection self-test** (new
  `scripts/verify-cert-gate.sh`): a certification gate that
  cannot prove it can fail is itself a false-PASS surface. The
  new script copies the harness, strips out one `ok` call,
  runs the tampered copy, and asserts the trap fires (non-zero
  exit + `❌ HARNESS_SUMMARY passed=K expected=M` line + the
  passed < expected gap). The script does not touch the
  production harness — only the tmp copy. Wired into the
  harness as step 6; opt-out with `STUDIO_SKIP_CERT_GATE=1`.

### Test fixes caught by the new gate

The first harness run after the B0 changes surfaced a real
contract drift that the previous (silent) harness had been
hiding:

- **`StudioCliUtilsTest.requireOption throws when missing`**
  was written for the legacy `requireOption` which threw
  `IllegalStateException`. The F-P1-CLI-3 fix replaced the
  implementation with `requireOptionValue` which throws
  `CliOptionError` (an `IllegalArgumentException` subclass).
  The test was never updated. Per the mandate ("no alterar el
  test únicamente para hacerlo pasar"), the test now asserts
  the real contract: `assertFailsWith<CliOptionError>` (exact
  type, not superclass) + message names the option + covers
  the new empty/flag-as-value cases. The `requireOption` back-
  compat alias is also tested for delegation.

### Security

The harness no longer reads or writes anything under
`~/.pipelinek-studio`. Step 3b routes `studio-app` through
`JAVA_OPTS` to a fresh `/tmp/studio-app-uat-data-<pid>` data
dir; the user's real `studio.db` and `run.db` are never
touched by the harness.

## [0.2.0-alpha.5] - 2026-10-10

### Fixed (HIGH deferred + F-P1 catalog, audit 2026-10-10 follow-up)

All 10 HIGH findings the original audit marked as "deferred" and
all 17 F-P1 items in the apps catalog are now closed. See
`docs/06-audits/2026-10-10-senior-audit.md` for the per-item commit
map; this entry summarises the theme.

- **HIGH deferred (10/10)**: H-15 (Host header), H-16 (SSE JSON injection),
  H-17 (Idempotency-Key DoS), H-19..H-21 (RateLimit TOCTOU/XFF/OOM),
  H-22 (connection pool), H-23 (UUID validator + atomic INSERT),
  H-25 (RunReconciler UUID gate + symlink defense), H-26 (backup size
  cap with partial-file cleanup).
- **R-16 (release harness)**: GitHub Actions workflow runs the
  from-clean-clone harness on every push to main and on every PR;
  classifies the verdict as READY / READY_WITH_RISKS / NOT_READY.
- **R-22 (SHA pin lockfile)**: `studio-infra-sdk/src/main/resources/sdk.lock.json`
  is the executable form of the pin matrix. The runtime probe
  refuses to admit runs when the installed SDK SHA does not match.
  `STUDIO_SDK_PIN=enforce|warn|off` selects the policy.
- **S5 E5.2 (`studio doctor`)**: the previous `/health` stub is now
  a real offline diagnostic (lockfile present, db path writable,
  JVM >= 17, node on PATH, HTTPS default) with a non-zero exit
  code on any FAIL.
- **F-P1 apps (17/17)**: silent-downgrade paths in MCP and VS Code
  extension (HTTPS default + `*_INSECURE` opt-in), shared
  `fetchWithTimeout` in apps/{web,vscode,mcp} (no more bare
  `fetch()` with unbounded timeouts), MCP tool input validation
  (`requireString` / `optionalString` / `optionalNumber` with
  `McpInputError`), `StudioHttpClient` trust-all X509 scoped to
  loopback only, stable `Idempotency-Key` per (experiment,
  snapshot, scenario) in the SPA and CLI (no more double-submit
  races), `OutputViewer` cross-chunk UTF-8 (no more replacement
  chars at chunk boundaries), `cancelRun` accepts 202 as a
  success path, EventStream distinguishes "reconnecting" from
  "closed", polling stops on terminal runs, localStorage cleared
  on terminal runs, Cmd-S no longer fires while typing in an
  input field, `requireOptionValue` rejects flag-as-value and
  empty CLI args, MCP `list_events` bounded to `[1, 200]`,
  structured `callHttpJson` replaces the regex-based response
  parsing in `list_tasks` and the `/steps` resource.

### Added

- **Apps test infrastructure**: `apps/mcp` and `apps/vscode` now have
  vitest configured (mirroring `apps/web`) with a `test` script in
  their respective `package.json`. The MCP package gained 27 unit
  tests covering URL resolution, timeout, and tool input validation;
  the VS Code package gained 11 tests covering the same.

### Security

- **`StudioHttpClient` trust-all is loopback-only**: an operator
  pointing the CLI at a non-loopback host now gets the JVM default
  truststore (real system roots) instead of a MITM-vulnerable
  trust-all. The `isLoopbackHost` test seam is unit-tested for the
  five relevant cases (localhost, 127.0.0.0/8, ::1, private
  RFC1918, malformed URL).

## [0.2.0-alpha.2] - 2026-10-10

### Fixed (CRITICAL audit 2026-10-10)

All 7 CRITICAL findings from the 2026-10-10 senior technical audit are
closed. See `docs/06-audits/2026-10-10-senior-audit.md` for the full
report; this entry covers the code changes.

- **C-1 / F-P0-1 (HTTPS transport)**: studio now defaults to HTTPS on
  `127.0.0.1:7778` with a self-signed cert generated on first boot
  (`<dataDir>/keystore.jks`). The flag `--insecure-http` opts back to
  plain HTTP on the legacy port `7777` for local development. CLI
  client installs a trust-all `X509TrustManager` for the loopback
  cert. See `docs/04-security/transport.md`.
- **C-2 (NIL UUID in StartRunUseCase)**: `StartRunUseCase` now accepts
  `StudioMetadataPort` and resolves `experimentId` via
  `metadata.findSnapshot(snapshotId)?.experimentId`. If the snapshot
  row is missing the use case throws `IllegalStateException` with
  reason `SNAPSHOT_NOT_FOUND` instead of minting a NIL UUID
  placeholder.
- **C-3 (descriptor discarded)**: `StartRunUseCase` captures the
  `RuntimeDescriptor` from `runtime.describe()` and propagates
  `version`, `artifactDigest`, `pluginSdkVersion` into every
  `RunBinding` branch (was previously empty strings).
- **C-4 (AgentWorkflows fake PASS)**: `ScenarioExecutionRequest` gains
  a `runId: RunId` field; `AgentWorkflows.mutateAndCompare` threads
  the explicit `newRunId` through the scenario. `AgentStepResult`
  surfaces the same `newRunId` so the caller can correlate the
  variant execution with its run.
- **C-5 (RateLimit non-atomic)**: `Bucket.remaining` is now
  `AtomicLong`; check-then-decrement is one atomic step. Rollback if
  the bucket was already empty.
- **C-6 (spawnedHandles leak)**: `JavaProcessSpawner` now registers
  `proc.onExit().whenComplete { spawnedHandles.remove(wrapper) }` so
  the backing map is auto-pruned when the subprocess terminates
  (was growing unbounded across the JVM lifetime).
- **C-7 (capabilities fake)**: `PipelineKRuntimeProbe` returns
  `Failure` when the manifest is unreadable and an empty
  `capabilities` set when the jar has no capability markers (was
  fabricating hard-coded defaults).
- **F-P0-2 (variant no persiste)**: `VariantAndCompareAdapter.variant`
  now persists the new `SourceSnapshot` via `metadata.saveSnapshot`.
  On persistence failure, returns `Failure` (no orphan id).
- **F-P0-3 (status stub)**: `PipelineKRunner.status` is now a hybrid
  supervisor: returns `Live(upstreamRunId, pid)` when the live
  process is registered and alive, `Unknown` with a clear reason
  pointing to `run.db` otherwise (was a hard-coded
  `Unknown("status supervisor not implemented in S0 bootstrap")`).

### Added

- New dependency: `io.ktor:ktor-network-tls-certificates:3.0.1`
  (for the self-signed cert generator).
- `studio-app/src/main/kotlin/dev/pipelinek/studio/app/TlsConfig.kt`:
  cert generation + persistence utility.
- `studio-app/src/test/kotlin/dev/pipelinek/studio/app/TlsConfigTest.kt`:
  3 tests for first-boot generation, reuse across boots, and port
  constants.
- `docs/04-security/transport.md`: transport security documentation
  (defaults, cert generation, opt-out path, known limits).
- `docs/06-audits/2026-10-10-senior-audit.md`: the full audit
  report (informe ejecutivo, scorecard, hallazgos, riesgos,
  roadmap).
- `odd/tasks/fix-criticals-v0.2.0-alpha.2.md`: ODD feature document
  tracking the 10 commits in this release.

### Changed

- `studio-app/build.gradle.kts`: added
  `useJUnitPlatform()` so the new tests are picked up.
- `Wiring` aggregate (`studio-application/UseCases.kt`): now carries
  the `scenarioRunner`, `variant`, and `comparison` ports (default
  implementations return `Failure` honestly).
- `studio-cli/StartArgs`: gains `insecureHttp: Boolean = false` and
  the default port moves from 7777 to 7778.

## [Unreleased]

### Added (S0..S5 bootstrap, single release)

#### Backend
- **Domain (`studio-domain`)**: typed IDs (ExperimentId, SnapshotId, ScenarioId,
  RunId, OperationId, IdempotencyKey, UpstreamRunId), Sha256 wrapper, RunState
  state machine including REFUSED / RECOVERY_REQUIRED / LOST / UNKNOWN.
- **Application (`studio-application`)**: hexagonal ports (StudioMetadataPort,
  PipelineRunnerPort, RunReadPort, OutputReadPort, IdempotencyPort,
  PipelineKRuntimePort, VariantPort, RunComparisonPort, StepCatalogReadPort,
  DiagnosticsReadPort, IrExplorerReadPort, ScenarioRunnerPort). Use cases:
  CreateExperiment, SealSnapshot, StartRun, InspectRun, RunScenario, AgentWorkflows.
  Renderers (Markdown + HTML) for ResultDescriptor.
- **Storage (`studio-infra-storage`)**: Flyway V1 schema (8 tables), StudioDatabase
  migrator, StudioBackup (xerial NativeDB Online Backup API), StudioMetadataRepository,
  IdempotencyRepository.
- **SDK adapter (`studio-infra-sdk`)**: PipelineKRunner (real ProcessBuilder with
  `--add-opens`/`--add-exports` JVM flags), PipelineKRuntimeProbe, PipelineKRunReadPort
  (reads run.db), PipelineKOutputReadPort (reads stdout.log captured to disk),
  StaticManifestInspector (JAR metadata without classloading), PipelineKStepCatalogReadPort
  (META-INF/services + Core*Step.class central-directory scan),
  PipelineKDiagnosticsAndIrPorts (normalized diagnostics + IR with COMPILED/ADMITTED/OBSERVED
  phases), VariantAndCompareAdapter.
- **HTTP (`studio-http`)**: 14 routes including POST /experiments, POST
  /experiments/{id}/snapshots, POST /runs, GET /runs/{id}, GET /runs/{id}/events,
  GET /runs/{id}/stream (SSE with Last-Event-ID + ?after=<seq> resume), GET
  /runs/{id}/output, GET /runs/{id}/diagnostics, GET /runs/{id}/ir, GET /steps,
  GET /steps/{id}, GET /health, GET /api/version, GET /openapi.json.
- **HTTP hardening**: `installBodySizeLimit` (1 MiB, 413 + JSON error),
  `installRateLimit` (per-IP token bucket, 60 req/60s, 429 + Retry-After + RATE_LIMITED,
  opt-out via STUDIO_RATE_LIMIT_DISABLED=1 for tests), `RouteParams.requireUuid`
  (strict UUID validation, 400 BAD_REQUEST on malformed).
- **HTTP contract**: OpenAPI 3.1 served at /openapi.json (curated; refusal codes
  OUTPUT_REFUSED, IR_UNAVAILABLE, DIAGNOSTICS_UNAVAILABLE, STEP_NOT_FOUND are part
  of the contract).
- **CLI (`studio-cli`)**: subcommands `doctor`, `create-experiment`, `snapshot`,
  `run`, `inspect`, `steps`, `version`, `backup`, `restore`, `start`, `mcp`,
  `generate-sdk-ts`. The minimal server is intentionally Ktor-Netty for distribution
  packaging; the full server is `studio-app`.
- **MCP v2 (`apps/mcp`)**: stdio JSON-RPC server with 6 tools + 2 resources
  (paginated `studio://catalog/steps`).

#### SPA (`apps/web`)
- React 19 + Vite 6 + Monaco editor.
- Sections: status, experiment creation, Kotlin source editor, "Save snapshot"
  (POSTs to /experiments/{id}/snapshots), event stream (EventSource), output
  viewer, ARIA baseline (main/region/alert/live regions), skip link (W3C G1),
  keyboard shortcuts (Cmd-S seal, Cmd-Enter create, Escape clear focus).

#### VS Code client (`apps/vscode`)
- 4 commands (health, createExperiment, listSteps, openOpenApi), `pipelinekStudio.baseUrl`
  setting, themeable light + dark icons, galleryBanner. Consumes the
  generated `sdk.ts` from `studio generate-sdk-ts`.

#### Tests + CI
- 131 unit / integration tests, 0 failures, 1 E2E auto-skip without SDK_PATH.
- `scripts/verify-from-clean-clone.sh`: 20 end-to-end checks (tests, distTar,
  studio start, OpenAPI doc, backup, SDK TS gen, detekt, VS Code scaffold, Monaco,
  SPA seal + SSE + output + keyboard shortcuts, UAT scaffold, VS Code package
  check, net policy, seccomp, namespace, MCP).
- `./gradlew verifyCleanClone` task wraps the shell harness.
- `scripts/uat-from-distribution.sh`: 10-step end-to-end harness for the installed
  distribution (accepts 200 OR documented 202 at every step).
- `scripts/detekt-run.sh`: in-process detekt 1.23.8 (35 baseline findings; SARIF + txt).
- `scripts/vscode-package-check.sh`: 9 assertions for `vsce package` prerequisites.

#### Docs
- `docs/history/S0_RECEIPT.md` — running receipt.
- `docs/05-testing/SECURITY_POSTURE.md` — current posture + residual risks.
- `docs/05-testing/SANDBOX_NETPOLICY.md` — iptables + nftables samples.
- `docs/05-testing/SANDBOX_SECCOMP.md` — seccomp profile.
- `docs/05-testing/SANDBOX_NAMESPACE.md` — overlay + unshare recipe.
- 12 ADRs in `docs/03-adrs/`, 4 ADR-lights (D-001..D-011) in `odd/tasks/pipelinek-studio-bootstrap.md`.

#### Fixtures (`studio-application/.../fixtures`)
- `Scenarios` (hello-world / json / table / failing).
- `Mutations` (variant + comparison + base/mutated run event fixtures).
- `ScenarioBuilder` + `ScenarioSpec` (hand-rolled JSON serializer for the test source set).

#### Conventions
- Conventional Commits; no AI attribution footer.
- 14 ADR-light decisions in `odd/tasks/pipelinek-studio-bootstrap.md` (D-001..D-011).
- D-007: no push to GitHub without explicit operator authorization.

### Known limitations

- D-007 release: pending explicit push authorization.
- E1.6 UAT end-to-end: requires the installed `studio-app` to actually run.
- E5.3 VS Code real build: requires `npm install && vsce package` in a node environment.
- E5.4 OS-level hardening apply: requires root on the host.
- E5.6 deep keyboard nav: requires running SPA + screen reader manual verification.
- SDK is pinned to v0.48.0-rc1 prerelease per D-002.

### Added (S5 polish, post-bootstrap)

#### Backend
- **Secret canary defence (`studio-application`)**: `SecretRedactor` port
  (no-op default + `PatternSecretRedactor` impl). Wired into
  `ResultRenderers` so every Text/Json/Table/ArtifactRefs/NotAvailable
  pass is scanned before render. Catches AWS keys, GitHub PATs, Slack
  tokens, `STUDIO_*` env assignments, and high-entropy blobs. 6 new
  tests; renderer wiring is opt-in (D-005).
- **Profiling endpoint (`studio-http`)**: `GET /api/metrics` returns JVM
  uptime_ms, threads_live, threads_peak, heap_used_mb, heap_max_mb,
  available_processors. Same shape on the minimal server. Exposed via
  the curated OpenAPI doc.

#### CLI (`studio-cli`)
- `studio --version` / `-v` — prints the bundled CLI version offline
  (no server needed). Backed by the new `StudioCliVersion` constant.
- `studio metrics` — new subcommand hitting `GET /api/metrics`.

#### SPA (`apps/web`)
- Vitest harness: `apps/web/vitest.config.ts` (jsdom env), `src/test/setup.ts`,
  `npm test` / `npm run test:watch` scripts, devDependencies
  `vitest@2.1.0` + `@testing-library/react@16.1.0` + `jsdom@25.0.0`.
- 7 smoke tests: 6 unit tests for the keyboard shortcut hook
  (Cmd-S / Ctrl-S seal, Cmd-Enter create, Esc clear focus, negative
  cases), 1 component smoke for `KotlinEditor`.

#### Scripts (operator-driven, never auto-run)
- `scripts/generate-sbom.sh` — CycloneDX 1.5 JSON from the
  `:studio-app` runtimeClasspath. 97 components on a fresh scan.
  Zero external dependencies; falls back to a structured JSON error
  if no gradle is on PATH.
- `scripts/generate-checksums.sh` — SHA-256 manifest of the
  `studio-cli/build/install/studio-cli` tree, both
  `sha256sum`-compatible `.txt` and JSON. 65 files on a fresh install.
- `scripts/cleanup-runs.sh` — per-run DB retention cleanup. Only
  terminal runs (COMPLETED / FAILED / CANCELLED, R-07) older than
  `--ttl-days` (default 30) are eligible. Default mode is DRY-RUN;
  the operator must pass `--apply` to actually remove files
  (D-005). studio.db metadata is never deleted.

#### Docs
- `TROUBLESHOOTING.md` (repo root) — 10 symptom-fix entries:
  port-in-use, DB lock, 429 rate limit, 413 body too large, MCP not
  found, SPA Monaco hang, run stuck RUNNING after kill -9, Flyway
  schema drift, suspiciously-fast empty runs, OOM.
- `UPGRADE.md` (repo root) — operator-driven upgrade procedure.
  Pre-flight checklist, upgrade steps, Flyway policy (additive vs
  destructive), rollback via `studio restore`, hot-upgrade caveat
  (no zero-downtime, single-writer R-08).
- `docs/06-operations/COMPATIBILITY_MATRIX.md` — runtime stack,
  Studio release matrix (0.1.0-alpha.1 ↔ SDK 0.48.0-rc1), plugin
  ABI table (same major ADMIT, cross major REJECT), API versioning,
  SPA / VS Code, anti-support policy.
- `CONTRIBUTING.md` (repo root) — maintainer guide: project shape,
  conventions, pre-PR checklist, 6-step port recipe, 3-step MCP
  recipe, commit format.
- `MAINTAINERS.md` (repo root) — short contact list: Steward +
  Backup Steward + Security (`rubentxu`); Ops vacant. Add/remove
  procedures + security disclosure pointer.

#### Tests
- 137 unit / integration tests, 0 failures, 1 E2E auto-skip
  without SDK_PATH. (+6 vs the S0..S5 bootstrap row, from
  `SecretRedactorTest`.)
- `scripts/verify-from-clean-clone.sh`: **34** end-to-end checks
  (+11 vs the S0..S5 bootstrap row). New steps 4t..4ad assert
  CONTRIBUTING, MAINTAINERS, Vitest harness, MAINTAINERS,
  SecretRedactor, SBOM, TROUBLESHOOTING + --version, checksums,
  UPGRADE, compatibility matrix, /api/metrics, retention cleanup.

## [0.1.0-alpha.1] - 2026-10-09

- Initial alpha. See Unreleased for the full surface; this is the baseline.
