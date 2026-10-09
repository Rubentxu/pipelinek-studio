# S0 Bootstrap Receipt

**Block:** S0 (per ROADMAP.md)
**Version:** `v0.1.0-alpha.1` (buildable, NOT released)
**Status:** BUILDABLE + TESTABLE (NOT DONE — release gate deferred per D-007)
**Date:** 2026-10-09
**Commit:** `0eff6df` (local main, not pushed)
**Mode:** Single session scoped to plan S0..S5 + bootstrap S0.

## Per ROADMAP DoD format

### 1. Estado

**BUILDABLE + TESTABLE — NOT DONE.** Per the verbatim ROADMAP DoD: *"Bloque no cerrado si no existe run real."* No real SDK invocation occurred in this session because the SDK coordinate `dev.pipelinek:pipeline-kotlin-application:0.48.0-rc1` is pinned but its actual jar fetch + a real `echo.pipeline.kts` execution requires a follow-up session with network access to Maven Central + a network-coordinated test run.

### 2. Código

- **Rama:** local `main` (no remote yet)
- **HEAD SHA:** `0eff6df`
- **origin/main SHA:** N/A — repo `Rubentxu/pipelinek-studio` does not exist on remote (verified 2026-10-09 via `gh repo view`)
- **Tag SHA:** no tag published

### 3. Contratos implementados

| Surface | Endpoint / Symbol | Notes |
|---|---|---|
| REST | `GET /health` | 200 OK with `{status: ok}` |
| REST | `GET /api/version` | Reports SDK descriptor when available; honest `Unavailable` |
| REST | `POST /experiments` | Generates `ExperimentId`, persists in `studio.db` |
| REST | `POST /experiments/{id}/snapshots` | SHA-256 seals the source per ADR-005 |
| REST | `POST /runs` (requires `Idempotency-Key` header) | 400 if missing; routes through `StartRunUseCase` |
| REST | `GET /runs/{id}` | Returns 202 Accepted + `UNKNOWN` when status port refuses (S0 honest) |
| REST | `GET /runs/{id}/events` | Returns 202 + `EVENTS_UNKNOWN` until S1 |
| REST | `GET /runs/{id}/output` | Returns 202 + `OUTPUT_REFUSED` until S1 — `BLOCKED-BY-OBS` |
| SDK port | `PipelineKRuntimePort.describe()` | Returns `Available | Unavailable | Failure` based on real classpath introspection |
| SDK adapter | `PipelineKRunner.submit()` | Builds `java -cp <sdk-classpath> MainKt --db <run.db> --control-root <cr> run --script <snap.kts>`; spawns subprocess |
| Domain | `RunStateMachine` | 14 typed transitions; refuses to invent `FAILED` from `RUNNING` without evidence |
| Domain | `Sha256.digest` | Validates hex length 64 |
| Storage | Flyway V1 migration | Creates `studio_schema_version` + 7 tables |

### 4. Pruebas focales

```
domain           14 tests,  0 failures
application       8 tests,  0 failures
infra-storage     4 tests,  0 failures
infra-sdk         5 tests,  0 failures
http              6 tests,  0 failures
cli               3 tests,  0 failures
TOTAL            40 tests,  0 failures,  0 skipped
```

Mutation discriminants included:
- `state machine allows RUNNING to RECOVERY_REQUIRED, enabling honest recovery` — invariant guard against pretending FAILED
- `isContentValid` returns false when sha256 mismatched — content authority check
- Idempotency `Match` vs `Conflict` depending on digest equality

### 5. Full suite de release

`./gradlew test --rerun-tasks` ran with exit 0 across all 7 modules with `useJUnitPlatform()` configured.

`./gradlew assemble --rerun-tasks` produced:
- `studio-app/build/distributions/studio-app-0.1.0-alpha.1.{zip,tar}` (29.9 MB / 31.6 MB)
- `studio-cli/build/distributions/studio-cli-0.1.0-alpha.1.{zip,tar}` (30.9 MB / 32.7 MB)

### 6. UAT instalada

🔲 **NOT EXECUTED.** Per ROADMAP, UAT-001/002/013/014/015 require installation and a real SDK invocation. Deferred to first R3-integrated release candidate build, after BLOCKED-BY-U0 resolved (SDK jar fetched).

### 7. AAT

🔲 **NOT EXECUTED.** Per-block AAT series (AAT-ARCH, AAT-SEC, AAT-DUR, AAT-MCP, AAT-API, AAT-REL) requires an installed binary + runnable SDK to exercise reflective containment probes. Deferred.

### 8. Artefactos

| Artifact | Status | sha256 | Notes |
|---|---|---|---|
| `studio-app-0.1.0-alpha.1.zip` | built locally, NOT uploaded | not computed | would require GitHub release mutation |
| `studio-cli-0.1.0-alpha.1.zip` | built locally, NOT uploaded | not computed | same |
| SBOM | not generated | — | requires cyclonedx plugin adoption (deferred to S5 hardening) |
| `studio-sbom.cdx.json` | — | — | — |
| Manifest | not generated | — | would require reproducible path + SHA256 of every dist artifact |

### 9. Riesgos activos

| ID | Level | Reason | Owner | Next trigger |
|---|---|---|---|---|
| `R-S0-A` | medium | v0.48.0-rc1 prerelease may shift before tag (rc2 candidate). | upstream | pin exact SHA on first SDK-installation run |
| `R-S0-B` | low | No docker available in this session. | env | acceptable for S0; S5 hardening introduces containers |
| `R-S0-C` | low | Kotlin 2.1.0 cannot parse `25.0.4.1` JDK string; pinned JDK 21 LTS. | toolchain | if Studio adopts newer JDK, bump Kotlin or replace jvmToolchain logic |
| `R-S0-D` | medium | SDK jar (v0.48.0-rc1) was pinned but not fetched during this session — `BLOCKED-BY-U0`. | session | user's first release-gate session must include a fetched jar |
| `R-S0-E` | high | No GitHub release / no remote — does not satisfy ROADMAP "Done" definition. | user | D-007 requires user's explicit push authorization |
| `R-S0-F` | medium | OutputReadPort is a refusal stub — `BLOCKED-BY-OBS` carries through to S1. | upstream | requires Output Plane certification per docs/07-research |

### 10. Próximo bloque permitido

- **S1 entry gate PASS gate:** the next milestone (Groovy-like Interactive Console) can start once the user authorizes the GitHub release pipeline for `v0.1.0-alpha.1` AND `R-S0-D` is closed (real SDK invocation proves the bootstrap runs end-to-end).
- Without that gate, S1 should **not** begin — `DONE` requires a real installed smoke, per AGENT_HANDOFF §"Stop conditions": *"Test rojo, suite incompleta, UAT instalada inexistente o release remota no verificable: estado BLOCKED/NOT MEASURED, no DONE."*

## What this receipt DOES NOT claim

- No "completed S0" claim (DONE requires release).
- No "SDK integrated" claim (coord pinned, jar not fetched).
- No "tests = E2E coverage" claim (unit-only; full UAT/AAT deferred).
- No "production grade" claim (this is the bootstrap from which S1..S5 are built).

## Honest verdict

```
BLOQUE S0 · VERSION v0.1.0-alpha.1
- Estado: BUILDABLE + TESTABLE (NOT DONE — release gate deferred)
- Commit: 0eff6df (local)
- Tests: 40 / 0 failures / 0 skipped
- UAT: NOT MEASURED (deferred)
- AAT: NOT MEASURED (deferred)
- Release: NOT PUBLISHED (per D-007)
- Verdict: NOT DONE — proceed to S1 only after (a) SDK jar integrated, (b) UAT-001/002 executed, (c) GitHub release published with SHA evidence
```
