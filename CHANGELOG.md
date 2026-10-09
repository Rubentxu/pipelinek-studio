# Changelog

All notable changes to PipelineK Studio are documented in this file.
The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and the project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

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

## [0.1.0-alpha.1] - 2026-10-09

- Initial alpha. See Unreleased for the full surface; this is the baseline.
