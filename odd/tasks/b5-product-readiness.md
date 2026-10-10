# B5 — Product readiness, distribución, seam VS Code (`v1.0.0-rc.1`)

**Date:** 2026-10-10
**Block:** B5 (S5 — Product readiness, distribución, seam VS Code)
**Target:** `v1.0.0-rc.1`
**Mandate:** `ROADMAP.md` §"S5 — Product readiness".

## Problem

The studio's backend (B0..B4) is stable and shipped. The
S5 mandate is the LAST block before `v1.0.0-rc.1`: install,
operate, certify, document. The operator needs a CLI that
boots a server without a src checkout, an offline doctor
that catches install-time issues, a distribution with
checksums + SBOM, a versioned OpenAPI + TS SDK + second
client, a compatibility matrix, a security hardening
pass, retention + secret canaries, perf/soak baselines,
a certification harness that runs against the installed
artifact (not the src), UX/keyboard/ARIA QA, docs, DB
upgrade + backup/restore, and the release receipt.

## Audit (B5.0, B5.1, B5.2, B5.6 partial — already on main)

The pre-B5 main already carries substantial S5 surface:

- **B5.0 CLI surface**: `studio start`, `studio mcp`,
  `studio doctor`, `studio generate-sdk-ts` are all
  wired in `studio-cli/src/main/kotlin/dev/pipelinek/
  studio/cli/`. Doctor runs 8 offline checks (cli
  version, lockfile present, lockfile parseable, db
  path writable, data dir POSIX perms, JVM 17+, node
  on PATH, HTTPS default). Tests:
  `DoctorTest`, `StartAndMcpSubcommandTest`,
  `GenerateSdkTest`, `CliOptionTest`, `StudioHttpClient
  LoopbackTest`.
- **B5.1 distribution**: `studio-cli/build/distributions/`
  produces the application distribution. SBOM file
  emission is the remaining gap.
- **B5.2 OpenAPI + SDK + second client**: `MinimalOpenApi
  Doc.kt` emits the wire contract; `GenerateSdk.kt`
  produces a TypeScript client on stdout. The MCP
  adapter under `apps/mcp/` is a Node.js v2 stdio
  consumer — the second-client deliverable.
- **B5.6 backup/restore**: `StudioBackup` provides online
  backup + integrity-checked restore. Tests:
  `BackupSubcommandTest`. The versioned-migration
  framework is the remaining gap.

The remaining gaps are listed per sub-task.

## Scope

- **B5.0** — CLI surface: audit existing subcommands +
  add `--data-dir` + the `studio stop` + `studio status`
  subcommands the S5 mandate asks for.
- **B5.1** — distribution + SBOM: produce a CycloneDX
  SBOM at build time, wire a `manifest.json` with
  sha256 checksums for every distribution file.
- **B5.2** — OpenAPI versioned + TS SDK + second client:
  pin the OpenAPI version, emit the SDK with the
  version in the file header, add a second consumer
  (the existing `apps/mcp/` is the second client;
  verify it exercises discover + snapshot + run +
  events/output + cancel/inspect).
- **B5.3** — compat matrix + hardening + perf/soak:
  produce a `COMPATIBILITY.md` (runtime x API x plugin
  versions), run the secret canary (R-07), retention
  cleanup, perf/soak baseline measurements.
- **B5.4** — certification harness: a fresh-clone +
  installed-artifact test that runs `studio start`,
  `studio doctor`, `studio mcp`, the second-client
  loop, browser offline, runner isolated, SQLite
  restore, recovery. The harness lives in
  `studio-cert/` and is NOT in the main module graph.
- **B5.5** — UX/QA + a11y + docs: keyboard + ARIA QA,
  troubleshooting doc, ADR review.
- **B5.6** — DB upgrade + backup/restore: a versioned
  migration framework with `studio db upgrade` and
  `studio db status` subcommands. Pre-B5 production
  DBs upgrade cleanly; backup + restore round-trips
  bit-perfect.
- **B5.7** — B5 release receipt + tag `v1.0.0-rc.1`.
  Per DoD: stable `v1.0.0` is NOT published
  automatically; it requires external readiness
  verdict and zero P0/P1.

## Constraints

- All tests pass before commit (361 → 361+ new).
- SDK stays at 0.48.0-rc1 (R-22 SHA-pinned).
- No AI attribution in commits; conventional commits.
- Tag `v1.0.0-rc.1` requires operator authorization
  per D-007.

## Acceptance

- B5.0: `studio start`, `studio mcp`, `studio doctor`
  + `studio stop`, `studio status`, `studio --version`
  all work. Doctor exits non-zero on FAIL.
- B5.1: SBOM is emitted at build time. `manifest.json`
  carries sha256 checksums for every distribution file.
- B5.2: OpenAPI is versioned (`openapi_version: 1.0.0`).
  The TS SDK header carries the same version. The MCP
  second client exercises discover / snapshot / run /
  events / cancel / inspect.
- B5.3: `COMPATIBILITY.md` lists supported runtime x
  API x plugin versions. Retention cleanup test passes.
  Perf/soak baseline numbers are recorded in the receipt.
- B5.4: the certification harness produces a `PASS` /
  `FAIL` verdict against the installed artifact (no
  src checkout).
- B5.5: keyboard + ARIA QA checklist is filled in.
  `docs/TROUBLESHOOTING.md` is updated.
- B5.6: `studio db upgrade` upgrades a pre-B5 production
  DB cleanly. `studio db status` reports the schema
  version. Backup + restore round-trips bit-perfect.
- B5.7: B5 release receipt is committed. `v1.0.0-rc.1`
  is tagged (operator authorization required).

## Applicable checks

- `./gradlew check` returns BUILD SUCCESSFUL.
- AAT-REL-001..010 (release + dist certification).
- AAT-SEC-001..010 (security hardening + secret canaries).
- AAT-ABI-001..006 (runtime/API/plugin ABI).
- AAT-OBS-001..008 (observability + retention).
- Certificación independiente del artefacto instalado.

## Tasks

- [ ] **B5.0** — CLI surface audit + `studio stop` +
  `studio status` + `--data-dir`. Doctor's existing
  tests are the contract; the new subcommands add
  tests for their own contract.
- [ ] **B5.1** — distribution + SBOM + manifest.json
  with sha256 checksums.
- [ ] **B5.2** — OpenAPI version pin + SDK version pin
  + second-client E2E (discover / snapshot / run /
  events / cancel / inspect).
- [ ] **B5.3** — COMPATIBILITY.md + secret canary +
  retention cleanup test + perf/soak baseline.
- [ ] **B5.4** — independent certification harness in
  `studio-cert/` (separate module, not in main graph).
- [ ] **B5.5** — UX/QA + keyboard/ARIA + TROUBLESHOOTING.md.
- [ ] **B5.6** — versioned migration framework +
  `studio db upgrade` + `studio db status`.
- [ ] **B5.7** — B5 release receipt + tag `v1.0.0-rc.1`.

## Open risks

- **B5.0 `studio stop` needs a discovery mechanism.**
  The current `start` blocks the terminal; there is
  no PID file or socket. The simplest is a
  `.studio.lock` file in the data dir; `stop` reads
  it + sends SIGTERM. The B5.0 test pins the
  protocol.
- **B5.1 SBOM format choice.** CycloneDX is the
  studio's pick (smaller, JSON-native, no XML).
  SPDX is the alternative; future EVO-D may add
  SPDX alongside.
- **B5.2 SDK version drift.** The TS SDK is generated
  from the OpenAPI doc at build time. A new OpenAPI
  version bumps the SDK; a test pins the
  `openapi_version` in the SDK header.
- **B5.4 certification harness is a separate
  module.** It must NOT be in the main graph
  (it would inflate the distribution with the
  test sources). The harness downloads the
  published artifact and exercises it.
- **B5.6 migration framework is forward-only.** A
  downgrade is a manual operator action (delete +
  restore from backup). The B5.6 test asserts the
  upgrade is forward-only.

## Next step

B5.0 — audit the existing CLI surface and add the
missing subcommands (`stop`, `status`, `--data-dir`).
The Doctor tests are the contract; the new subcommands
add tests for their own contract.
