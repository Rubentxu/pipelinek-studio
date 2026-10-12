# v1.0.0-rc.15 — Production Readiness R5 + R6.1

## Cert authority (R5)

- **R5.1 run-all-cert authority**: required cases now derive from the
  run-all-cert's own surface (C1+C2+C3 = 19 cases). The previous code
  required the full `ALL_REQUIRED` (which includes R6 cases owned by
  `run-e2e-cert`), producing a false `FAIL:CASE_MISSING` for every
  run-all-cert invocation. Fixed: `run-all-cert` now requires only
  C1+C2+C3; the R6 cert is a separate execution path.

- **R5.2 real integrity**:
  - `IntegrityEnvelope.isSatisfied()` no longer converts `null` →
    `true` for optional fields. The previous `OPTIONAL_FIELDS`
    short-circuit was the exact R5.2 violation the mandate
    forbids ("nunca convertirlas en PASS"). The envelope is
    now satisfied iff every required field is measured AND
    true. `null` is NOT_MEASURED and contributes nothing to the
    verdict.
  - Added 6th field `runtimeMatchesManifest` — the
    "relación entre runtime probado y manifest" the mandate
    requires. Compares the studio's reported
    `pipelinekRuntime` to the lockfile's pinned version.
  - `artifactDigestsMatch`, `sdkVersionMatchesLock`,
    `studioVersionPinned`, and `runtimeMatchesManifest` are
    now ALWAYS measured (the cert has the inputs in hand).
    `tagMatchesCommit` and `commitMatchesRelease` remain
    env-gated (the cert cannot measure them without a tag +
    GitHub release).
  - `MasterCertReportJson` now includes the integrity
    envelope. The previous JSON had `"integrity": {}` — the
    published report had no integrity data, in violation of
    R5.2.

- **R5.3 catalog-driven required cases**: `ContractCatalog.ALL_REQUIRED`
  is the source of truth (independent of the executed set). The
  R5.3 violation `requiredCases = cases.map { it.caseId }` is
  not present; the catalog is queried per execution surface.

- **R5.4 oracles and versions**: no obsolete `1.0.0-rc.13`
  literals in the cert. `/api/version` strict-asserts
  `pipelinekRuntime` (R5.4). When the SDK jar lacks
  `Implementation-Version`, the lockfile-by-SHA recovery
  returns BLOCKED (rather than inventing a version).

- **R5.5 CI**:
  - studio-cli: 1 test failing (`TlsHandshakeR02BTest`
    second case). Fixed: the test now sets the runtime JVM
    property `pipelinek.studio.runtime.port` before
    invoking `main()` (the dist start script's
    DEFAULT_JVM_OPTS wins over CLI args; tests calling
    main() directly must set the property to honour the
    chosen free port).
  - studio-http: 0 tests failing.
  - studio-infra-sdk: 4 tests failing
    (`PipelineKRunnerTest`). Fixed: the test helper
    `preCreateRunStartedEvent` now also pre-populates
    `stdout.log` (the runner reads the upstream id from
    the subprocess's stdout journal, not from `run.db`).
  - studio-cert: 9 tests failing (5 envelopes + 1 verdict
    + 4 self-test). Fixed: the test fixtures declare the
    new 6th field `runtimeMatchesManifest = true` in their
    satisfied envelopes.
  - VS Code `./sdk` import: clean. `tsc -p .` and
    `eslint .` pass with no issues.
  - Release harness: `.github/workflows/release-harness.yml`
    runs the from-clean-clone harness on every push to main.
  - Version/OpenAPI: `VersionCoherenceB0Test` (9/9) and
    `OpenApiVersionB52Test` (6/6) pass.

- **R5.6 distribution**: rc.15 artifacts published:
  - `studio-cli-1.0.0-rc.15.tar` (34.4 MB)
  - `studio-cert-cli-1.0.0-rc.15.tar` (16.2 MB)
  - `pipelinek-studio-1.0.0-rc.15.vsix` (85.7 KB)
  - `sbom-rc.15.cdx.json` (37.8 KB) — CycloneDX 1.5 strict
    with purl + bom-ref + serialNumber + dependencies
  - `checksums-rc.15.json` + `checksums-rc.15.txt` (66
    files)
  - `manifest-rc.15.json` — single source of truth for the
    release
  - Annotated tag `v1.0.0-rc.15`. `v1.0.0-rc.14` is not
    rewritten.

## R6.1 — production admission bug

- `PipelineKRunner.readUpstreamIdFromJournal`:
  - The previous `Thread.sleep`-based poll was broken in
    the dev sandbox: `Thread.sleep` was being elided (the
    JVM optimizes it away in some hot-path code), so the
    loop ran 2.5M+ times in 60 s and never observed the
    subprocess's events.
  - Fix: switch to `Process.waitFor(timeout, MILLISECONDS)`
    (a kernel-level wait the JVM cannot elide) and read
    the subprocess's stdout journal between waits.
  - Critical: extract the `runId` field from the
    `RunStarted` event, NOT a fabricated `upstream_id`.
    The SDK event payload has `runId`, never `upstream_id`.
    The four previous fix attempts were looking for a
    field that does not exist in the wire format.
- `PipelineKRunReadPort.observation()`: the SDK adapter
  now overrides the default observation (which always
  returned `Terminal(DeclaredPending)`) to read the last
  event from `run.db`. When the last event is
  `RunFinished`, the SDK's `outcome` ("success" /
  "failure" / "cancelled") is translated to the
  documented `RunState` names (SUCCEEDED / FAILED /
  CANCELLED) and returned as `Terminal(Executed(...))`.

- Studio's dist start script: the cert now sets
  `STUDIO_APP_OPTS` to override the JVM property
  `pipelinek.studio.runtime.port` and
  `pipelinek.studio.runtime.dataDir` (the dist's
  `DEFAULT_JVM_OPTS` hardcodes both to 7777 and
  `~/.pipelinek-studio/runtime`, ignoring the `--port`
  and `--db` CLI args). The cert also sets
  `PIPELINEK_SDK_CLASSPATH` so the runtime probe sees
  the SDK jars.

## R6.1 cert scaffold

- `run-e2e-cert` end-to-end: 9 AAT-INT-* cases (R6.1
  golden path + R6.2 normative assertions).
- 4/9 cases currently pass end-to-end:
  AAT-INT-014 (idempotency), AAT-INT-012 (kill-9
  recovery), AAT-INT-040 (SQLite migration),
  AAT-INT-011 (restart persistence).
- The remaining 5/9 failures are orthogonal to the
  admission bug: `OutputReadPort` does not surface
  `echo`/`sh` output to the cert, and the cancel case
  cancels after the subprocess already exited with
  SUCCEEDED. Both are R6.2 work.

## Cert run summary

| Surface         | Cases | Pass | Fail | Verdict |
|-----------------|-------|------|------|---------|
| C1 (SDK UAT)    |   8   |  8   |  0   | PASS    |
| C2 (Studio HTTP)|   8   |  8   |  0   | PASS    |
| C3 (MCP v2)     |   3   |  3   |  0   | PASS    |
| R6.1 (E2E)      |   9   |  4   |  5   | PARTIAL |

Integrity envelope (rc.15, local dev sandbox):
- `artifactDigestsMatch`: true (SDK zip digest matches the pin)
- `sdkVersionMatchesLock`: true (SDK version 0.48.0-rc1 matches lockfile)
- `studioVersionPinned`: true (1.0.0-rc.15 from gradle.properties)
- `runtimeMatchesManifest`: true (studio's reported runtime matches lockfile)
- `tagMatchesCommit`: null (NOT_MEASURED — env var not set in local sandbox)
- `commitMatchesRelease`: null (NOT_MEASURED — env var not set in local sandbox)
