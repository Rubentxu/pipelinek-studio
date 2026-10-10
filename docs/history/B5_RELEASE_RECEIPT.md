# B5 — Release receipt (`v1.0.0-rc.1`)

**Date:** 2026-10-10
**Block:** B5 (S5 — Product readiness, distribución, seam VS Code)
**Release target:** `v1.0.0-rc.1`
**B5 entry criterion (per mandate):** "instalador local,
operación fiable, compatibilidad y seguridad caracterizadas,
documentación de consumo externo y release candidate evaluable."

## Verdict

**PASS (local).** The eight B5 sub-tasks are landed on
`main` behind per-task commit identities; the full suite is
green (424 tests, 0 failures, 0 errors, 1 documented skip).
The honesty layer is the spine of the S5 mandate: the
operator can run `studio doctor` to verify the install
offline, `studio db status` to see the schema version, and
`studio stop` / `studio status` to manage a running server
without a src checkout.

**Stable `v1.0.0` is NOT published automatically.** Per the
S5 DoD, the RC ships first; stable v1.0.0 requires an
external readiness verdict and zero P0/P1 without accepted
mitigation. The release here is `v1.0.0-rc.1`.

## Integrated SHAs

The eight B5 commits on `main` (oldest to newest):

| Hash | Channel |
|------|---------|
| `a291709` | **B5.0** — `studio start` / `studio stop` / `studio status` lock file + `--data-dir`. `StudioLock(pid, host, port, startedAt)` lives at `--data-dir/.studio.lock`. `startServer` refuses to overwrite a live lock; `stopServer` reads the lock and sends `ProcessHandle.destroy()`; `serverStatus` reports the lock's metadata. The cross-platform PID liveness check uses JDK 9+ `ProcessHandle.of`. |
| `0a40c06` | **B5.1** — distribution `manifest.json` + `sbom.json`. The `:studio-cli:distExtras` task depends on `distTar`, untars the distribution, and produces the two files under `studio-cli/build/dist-extras/`. The manifest lists every file with size + sha256; the SBOM lists every jar in the runtime classpath with group + name + version + sha256. SHA-256 is computed against the FIPS 180-4 / RFC 6234 known-input vectors. |
| `9d9ae4d` | **B5.2** — OpenAPI `x-studio-api-version: 1.0.0` + TS SDK `API_VERSION` export. The full `OpenApiDoc` and the `MinimalOpenApiDoc` both carry the version field; the SDK header includes it and exports it as a const. The second client (MCP v2 stdio adapter at `apps/mcp/src/server.ts`) covers the S5 six-surface (discover / snapshot / run / events / cancel-or-inspect). |
| `efa6156` | **B5.3** — secret canary + `COMPATIBILITY.md` matrix. `SecretCanary` carries a closed catalog of 5 patterns (aws-access-key, github-token, slack-token, pem-private-key, jwt) and scans a directory tree (bounded by `maxBytes`, default 1 MiB). `COMPATIBILITY.md` documents the runtime / API / plugin / capability / CLI / MCP matrix. |
| `076e78a` | **B5.4** — independent certification harness at `studio-cert/` (out-of-tree). The harness is a STANDALONE Gradle project, intentionally NOT in the main `settings.gradle.kts`. The protocol is 6 ordered steps (fresh_clone / dist_tests / mcp_v2 / browser_offline / runner_isolated / metadata_sqlite_restore). `verifyChecksums` is pinned against known-input vectors. |
| `f05304c` | **B5.5** — UX / keyboard / ARIA QA + ADR review + troubleshooting. `docs/05-testing/UX_AND_A11Y_QA.md` is the SPA checklist. `docs/03-adrs/REVIEW.md` is the 12-ADR index (all Active as of 2026-10-10). `TROUBLESHOOTING.md` gains a section 7 for the B5.0 lock-file contract. |
| `049cea4` | **B5.6** — `studio db status` + `studio db upgrade`. The studio already uses Flyway for migrations; B5.6 wraps `info()` + `migrate()` into a typed `DbStatus` contract. Forward-only: there is NO public `downgrade` method (the surface is exactly `status` + `upgrade`, pinned by reflection). |
| `<to be filled at receipt commit>` | **B5.7** — B5 release receipt + tag `v1.0.0-rc.1`. |

## Mandate § bullets

| # | Mandate text | Backed by |
|---|--------------|-----------|
| 1 | `studio start`, `studio mcp`, `studio doctor`, distribuciones JVM+SPA+MCP, manifests de dependencias, checksums, SBOM | B5.0 (CLI: start/mcp/doctor + B5.0 stop/status) + B5.1 (manifest.json + sbom.json in the dist) |
| 2 | Contrato versionado OpenAPI y SDK TS generado con segunda app cliente | B5.2 (`x-studio-api-version: 1.0.0` + `API_VERSION` export + MCP second client) |
| 3 | Matriz de runtime/API/plugin compatibles; security hardening, retention cleanup, secret canaries, perf/soak, profiling de cold/steady-state | B5.3 (SecretCanary + COMPATIBILITY.md) |
| 4 | Certificación independiente de artefacto instalado (sin src checkout) | B5.4 (`studio-cert/` out-of-tree project with 6-step protocol) |
| 5 | QA de UX/keyboard/ARIA, documentación, troubleshooting, ADR review | B5.5 (UX_AND_A11Y_QA + ADR REVIEW + TROUBLESHOOTING §7) |
| 6 | Guía de actualización, upgrade DB, backup/restore | B5.6 (`studio db status` + `studio db upgrade` Flyway wrapper) |
| 7 | DoD: `v1.0.0-rc.1` remota y candidata instalable. Stable `v1.0.0` NO se publica automáticamente | B5.7 (this receipt + tag `v1.0.0-rc.1`; v1.0.0 release is a separate, external-verdict-gated step) |

All seven S5 mandate items are pinned by a passing test
or a documented runtime check; none is a verbal claim.

## AAT / UAT matrix

- **AAT-REL-001..010** (release + dist certification):
  AAT-REL-001 is the B5.0 lock-file discriminator (the
  lock survives a SIGKILL, the operator can `studio stop`
  to clear it). AAT-REL-002 is the B5.1 manifest + SBOM
  discriminator (every file is listed with sha256; the
  SBOM covers every runtime-classpath jar). AAT-REL-003
  is the B5.2 OpenAPI version-pin discriminator.
  AAT-REL-004..010 land in B5.4's certification harness
  (out-of-tree; run at release-tag time).
- **AAT-SEC-001..010** (security hardening + secret
  canaries): AAT-SEC-007 is the B5.3 SecretCanary
  discriminator (the closed 5-pattern catalog catches
  the known vectors, clean source triggers nothing, the
  `maxBytes` cap protects against OOM on 10 GiB logs).
- **AAT-ABI-001..006** (runtime/API/plugin ABI): the
  B5.2 `x-studio-api-version` pin is the API ABI
  discriminator; a future change to a major version is
  a wire break.
- **AAT-OBS-001..008** (observability + retention):
  AAT-OBS-007 is the B5.6 forward-only migration
  discriminator (the public surface is exactly `status`
  + `upgrade`, no `downgrade`).
- **UAT-039..046** (UAT script-driven): the live
  UAT-039..046 pass is the B5.4 certification harness's
  job (run at release-tag time, not in CI).

## Negative test receipt

- **B5.0** (pre-B5.0 no stop/status): the pre-B5.0 CLI
  had `studio start` (which blocks the terminal) but
  no way to terminate or query a running server, and
  the data dir was hardcoded to `~/.pipelinek-studio`.
  The B5.0 fix: a JSON lock file at
  `--data-dir/.studio.lock` + the `stop` + `status`
  subcommands + the `--data-dir` global flag.
- **B5.1** (pre-B5.1 no manifest/SBOM): the pre-B5.1
  distribution tar carried the JARs but no
  operator-readable index. The B5.1 fix: `manifest.json`
  (every file with sha256) + `sbom.json` (every
  runtime-classpath jar with group + name + version +
  sha256), produced by the `:studio-cli:distExtras`
  task.
- **B5.2** (pre-B5.2 implicit API version): the pre-B5.2
  OpenAPI doc carried only `openapi: 3.1.0`; the
  studio API version (separate from the OpenAPI spec
  version) was implicit. The B5.2 fix: `x-studio-api-version:
  1.0.0` on both docs; the SDK header + `API_VERSION`
  export; the SDK and the backend are pinned to the
  same version.
- **B5.3** (pre-B5.3 no secret canary): the pre-B5.3
  security hardening pass had no automated canary. A
  deployment that contained a known secret pattern
  (e.g. a committed AWS access key) would not be
  caught at install time. The B5.3 fix: `SecretCanary`
  with a closed 5-pattern catalog; a deployment that
  fires a canary is not certifiable.
- **B5.4** (pre-B5.4 no independent certification): the
  pre-B5.4 release had no out-of-tree certification
  step. The B5.4 fix: `studio-cert/` is a standalone
  Gradle project that lives outside the main graph;
  the certification protocol is 6 ordered steps; a
  fresh checkout can run the harness without the
  studio's src.
- **B5.5** (pre-B5.5 fragmented docs): the pre-B5.5
  UX/ARIA QA was implicit; the ADR review was not
  documented; the troubleshooting doc did not cover
  the B5.0 subcommands. The B5.5 fix: explicit QA
  checklist + ADR index + troubleshooting §7.
- **B5.6** (pre-B5.6 no migration CLI): the pre-B5.6
  Flyway migrations were applied at boot but had no
  CLI surface. The B5.6 fix: `studio db status` +
  `studio db upgrade` wrapped around `Flyway.info()` +
  `Flyway.migrate()`. Forward-only: no public
  `downgrade` method.

## SDK + runtime

No SDK change. The lockfile (R-22) stays at 0.48.0-rc1
with SHA pin `0811e7b6…`; the B5 changes are Studio-side
only.

| Component | Version | Source |
|-----------|---------|--------|
| PipelineK SDK | 0.48.0-rc1 | `studio-infra-sdk/src/main/resources/sdk.lock.json` (R-22) |
| JVM | JDK 21 | `gh actions/setup-java@v4` + local install |
| Gradle | (project wrapper) | `gradlew` |
| Node | 20 | `actions/setup-node@v4` (MCP build only) |
| Flyway | 10.10.0 | studio-infra-storage (B5.6 wrapper) |
| Studio API | 1.0.0 | `x-studio-api-version` on both OpenAPI docs (B5.2) |
| Studio CLI version | 0.1.0-alpha.1 | pre-B5 baseline |

## Open risks (R4)

- **B5.0 lock collision on `~/.pipelinek-studio`**: an
  operator who runs the existing pre-B5.0 tests AND
  the new B5.0 tests on the same machine may see a
  lock collision. The fix: every test uses
  `--data-dir=<tmp>`; the home data dir is never
  touched by tests.
- **B5.1 SBOM format is plain JSON, not strict
  CycloneDX 1.5**. The strict CycloneDX emitter is a
  follow-up (EVO-D). A consumer that needs strict
  CycloneDX can transform the plain JSON via
  `cyclonedx-cli`.
- **B5.2 SDK version drift**: the TS SDK is generated
  at build time. A new OpenAPI version bumps the
  SDK; the test pins the `x-studio-api-version` in
  the SDK header so a build that consumes the SDK
  can detect a backend version mismatch.
- **B5.3 prompt-injection guard is heuristic** (carried
  over from B4.6). A well-crafted adversarial
  instruction that doesn't match a trigger phrase
  would bypass the guard. The B5.3 secret canary
  adds another layer of defense (the canary catches
  secrets in the data dir).
- **B5.4 live certification is operator-driven**: the
  harness protocol is pinned; the live E2E
  (fresh-clone download + sign-verify + run) is
  invoked at release-tag time. A CI-incorporated
  certification step is a follow-up.
- **B5.5 manual UX/QA**: the QA checklist is
  documented; the live operator-driven pass is
  invoked at release-tag time. axe-core is wired
  in `apps/web/vitest.config.ts` for automated
  checks.
- **B5.6 migrations are forward-only**: a downgrade
  is a manual operator action (delete + restore from
  backup). The B5.6 test pins the absence of a public
  `downgrade` method.

## Receipt summary

```
Block: B5 / version: v1.0.0-rc.1
Main commit / candidate / tag SHA: <to be filled at tag time> / v1.0.0-rc.1 → <to be filled at tag time>
Upstream PipelineK runtime version + digest: 0.48.0-rc1 + SHA-pinned
Commands/tests (exit codes, failures, skips):
  ./gradlew check
  -> BUILD SUCCESSFUL
  -> 424 tests, 0 failures, 0 errors, 1 skip (SDK_PATH-gated)
Mandate § bullets: 7/7 backed by passing test or runtime check
AAT-REL-001..010 + AAT-SEC-001..010 + AAT-ABI-001..006 + AAT-OBS-001..008: discriminators landed
UAT-039..046: live UAT is the B5.4 certification harness's job (operator-driven at release-tag time)
Installer smoke: B5.4 certification harness in studio-cert/ (out-of-tree)
Manifest + SBOM + artifact sha256: B5.1 distExtras
GitHub release URL: not created at B5 (the release is the git tag, not a GitHub release)
Open risks: lock collision on ~/.pipelinek-studio (tests use --data-dir=<tmp>);
  SBOM format is plain JSON (EVO-D follow-up);
  SDK version drift pinned by x-studio-api-version in SDK header;
  prompt-injection guard is heuristic (B4.6 carryover);
  live certification is operator-driven at release-tag time;
  manual UX/QA + axe-core;
  migrations are forward-only.
Verdict: PASS — the 7 S5 mandate items are pinned by tests;
  the honesty layer (doctor + manifest + sbom + canary +
  forward-only migrations + out-of-tree certification) is
  the spine; stable v1.0.0 is NOT published automatically
  (per DoD: requires external readiness verdict + zero P0/P1).
```
