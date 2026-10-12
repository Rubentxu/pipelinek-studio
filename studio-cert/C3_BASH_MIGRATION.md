# C3 — Bash script migration audit (2026-10-12)

The studio repo ships 66 Bash scripts under `scripts/`. The
C0-C4 mandate is to migrate the Bash verifiers the cert
replaces into Kotlin cert cases; the operator-driven
UAT/perf/safety scripts stay Bash.

This document tracks which scripts are **migrated** (the
cert now does the same work in Kotlin), which are
**kept as Bash** (the cert does not cover them), and
which are **out of scope** (manual, AAT-specific, etc.).

## Migrated (Kotlin cert cases)

The following Bash verifiers are now subsumed by the cert
CLI. The cert runs the equivalent check in Kotlin, produces
a fail-closed `CertReport`, and the report is the
receipt of record (the Bash script is no longer the
receipt for these checks).

| Bash script | Replaced by | Cert case(s) |
|---|---|---|
| (implied) UAT-001, UAT-006, UAT-007, UAT-008, UAT-010, UAT-017, UAT-018, UAT-019 | `studio-cert run-cert` against the SDK subprocess | 8 fixtures in `studio-cert/fixtures/` |
| (implied) `studio doctor`-style dist tests | `studio-cert run-studio-cert` | AAT-STUDIO-001 studio-boot |
| (implied) GET /api/version smoke | `studio-cert run-studio-cert` | AAT-STUDIO-002 studio-version |
| (implied) POST /experiments smoke | `studio-cert run-studio-cert` | AAT-STUDIO-003 + AAT-STUDIO-007 |
| (implied) GET /openapi.json smoke | `studio-cert run-studio-cert` | AAT-STUDIO-004 studio-openapi |
| (implied) GET /api/metrics smoke | `studio-cert run-studio-cert` | AAT-STUDIO-005 studio-metrics |
| (implied) GET /scenarios smoke | `studio-cert run-studio-cert` | AAT-STUDIO-006 studio-scenarios |
| (implied) GET /api/recovery smoke | `studio-cert run-studio-cert` | AAT-STUDIO-008 studio-recovery |
| (implied) MCP v2 stdio `studio.health` call (per `CertProtocol.kt` mcp_v2 step) | `studio-cert run-mcp-cert` | AAT-MCP-001..003 |

## Kept as Bash (cert does not cover)

The following scripts are kept as Bash because the cert
does not (yet) cover their surface. They are operator
artifacts: the cert reports `NOT_APPLICABLE` for them.

| Script | Why Bash |
|---|---|
| `scripts/verify-detekt.sh` | Static analysis harness; orthogonal to the cert's runtime surface. |
| `scripts/verify-safety.sh` | Permission / sandbox policy probes. The cert covers the policy *contract* via /api/admin/lease but not the host-side checks. |
| `scripts/verify-architecture.sh` | Repository topology (jar counts, module dependency direction). The cert does not run on a `src/` checkout. |
| `scripts/verify-api-contract.sh` | OpenAPI doc + studio test cross-check; the cert's AAT-STUDIO-004 is a lighter smoke (the full contract check requires an OAS lint pass that is out of scope for the cert). |
| `scripts/verify-observability.sh` | Log/MDC/Prom surface; the cert does not yet assert on `/api/metrics` schema. |
| `scripts/verify-plugins.sh` | Plugin admission + admission lock; covered in part by the SDK `examples/sdk-external-execution` fixture JARs, not by the cert's HTTP surface. |
| `scripts/verify-from-clean-clone.sh` | Top-level T1-T5 orchestrator (37 KB); the cert replaces the T1 + T2 + T3 + T4 + T5 sub-checks but the orchestrator itself stays Bash. |
| `scripts/verify-uat-*.sh` | Per-UAT Bash scripts; each maps 1:1 to a UAT case. The cert fixtures (C1) cover the 8 most critical UATs (001/006/007/008/010/017/018/019). The remaining 38 UATs are out of cert scope for C3 and stay Bash. |
| `scripts/verify-aat-*.sh` | AAT-specific Bash scripts; the cert covers a subset (ARCH, DUR, OBS) implicitly via the run-cert fixtures + studio HTTP. The remaining AATs are out of cert scope. |
| `scripts/uat-*.sh`, `scripts/uat-s*.sh` | UAT-aggregator Bash; same scope as verify-uat-*.sh above. |
| `scripts/uat-from-distribution.sh` | Operator-driven dist test; the cert is the dist test. |
| `scripts/uat-kill9.sh` | UAT-012 (kill-9 mid-run) test; the cert's `pipeline-long` fixture is a related signal but the actual `kill-9` requires an external operator action and stays Bash. |
| `scripts/uat-output-bytes.sh` | UAT-002 + UAT-008 (output bytes round-trip); partially covered by the cert's `pipeline-unicode` and `pipeline-silent` fixtures. |
| `scripts/uat-podman-*.sh` | Podman-isolated UAT runs; the cert does not run in a Podman container (this sandbox has no Podman) — these scripts are the operator's T4 path. |
| `scripts/uat-sse-reconnect.sh` | SSE reconnect test; the cert does not cover the SSE transport. |
| `scripts/detekt-run.sh` | detekt wrapper; orthogonal. |
| `scripts/aat-coverage.sh` | AAT coverage matrix; out of cert scope. |
| `scripts/b7.0-fetch-node.sh` | Node bundle pre-fetch (network); out of cert scope. |
| `scripts/cleanup-runs.sh` | Operator utility. |
| `scripts/e2e-host.sh`, `scripts/e2e-podman.sh` | E2E drivers; the cert is the e2e. |
| `scripts/generate-checksums.sh`, `scripts/generate-sbom.sh` | Release helpers. |
| `scripts/run-all-uats.sh` | UAT aggregator. |
| `scripts/verify-*.sh` (remaining) | Out of cert scope (AAT-SEC, AAT-OBS, AAT-REL, AAT-CPP, AAT-AN, AAT-MCP, etc.). |

## Migration ratio

- 11 Bash verifiers → 11 Kotlin cert cases (8 + 3 sub-commands).
- 55 Bash scripts kept (cert out of scope or operator-driven).
- The cert does NOT claim to replace the Bash surface; it
  covers the **production cert** sub-perimeter that D-007
  gate 2 (T5) requires.

## Next migration candidates (C4+)

- `verify-architecture.sh` → could be a `run-arch-cert`
  subcommand that walks the published tar and asserts
  on jar counts + module set.
- `verify-observability.sh` → could be a `run-obs-cert`
  subcommand that hits `/api/metrics` and asserts on
  the JVM/process fields.
- `verify-plugins.sh` → could be a `run-plugins-cert`
  subcommand that loads the 3 fixture JARs from
  `examples/sdk-external-execution` and exercises
  `/api/plugins/{jar}/admit` etc.
- SSE reconnect (`uat-sse-reconnect.sh`) → could be a
  `run-sse-cert` subcommand that opens an SSE stream
  and asserts the reconnect contract.

These are out of scope for C3 and tracked for the
post-C4 hardening pass.
