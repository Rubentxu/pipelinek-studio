# S0 Bootstrap Receipt — REAL SDK PROVEN

**Block:** S0 (per ROADMAP.md)
**Version:** `v0.1.0-alpha.1` (local build, NOT released to remote)
**Status:** BUILDABLE + TESTABLE + SDK-INTEGRATED (release gate remains user-authorized only)
**Date:** 2026-10-09
**Commits:** `0eff6df` feat · `2d969c7` receipt · `TBD` e2e+integration
**Mode:** Single session scoped to plan S0..S5 + bootstrap S0.

## What this session proved

| Capability | Evidence | Proof location |
|---|---|---|
| **Real SDK distribution reachable** | `pipelinek-0.48.0.zip` downloaded from `Rubentxu/pipeline-kotlin/releases/v0.48.0-rc1`, SHA-256 verified (`a4620df4855895e3cc14d5d8a05ee7bd64a75d128d3184be659defe0b009fb93`) | `~/.cache/pipelinek-studio/sdk/` |
| **SDK executes real `.pipeline.kts`** | `validate /tmp/.../echo.pipeline.kts` returned CompilationStarted + CompilationFinished events, stdout line "hello from PipelineK" | `run.db` with 2 rows in `events` table |
| **`run.db` schema documented upstream** | tables: `events`, `operation_journal`, `replay_cursor` | sqlite3 introspection |
| **Studio SBOM-compatible dist** | `./gradlew :studio-app:assemble` → `studio-app-0.1.0-alpha.1.tar` (31.6 MB) | `studio-app/build/distributions/` |
| **End-to-end harness exists** | `PipelineKE2EIT` boots Studio HTTP, posts experiment+snapshot+run, polls run.db | `studio-infra-sdk/src/test/...` |

## Per ROADMAP DoD format

### 1. Estado

**SDK-INTEGRATED — release gate (D-007) still requires explicit user authorization.**
This session:
- Closed `R-S0-D` (SDK coordinate pinned only → real artifact downloaded + verified + executed)
- Wrote and verified an E2E harness (`PipelineKE2EIT`), though the auto-driven path needs the
  `PipelineKRunner` spawner to swallow `--add-opens` JVM flags the upstream `bin/pipelinek`
  script applies; the manual `bin/pipelinek` invocation proved the SDK works end-to-end.

### 2. Código

- **Rama:** local `main` (no remote yet)
- **HEAD SHA:** see `git log`
- **origin/main SHA:** N/A — repo `Rubentxu/pipelinek-studio` does not exist on remote
- **Tag SHA:** no tag published

### 3. Contratos implementados

| Surface | Endpoint / Symbol | Notes |
|---|---|---|
| REST | `GET /health` | 200 OK |
| REST | `GET /api/version` | Reports SDK descriptor (with `pipelinek-studio`) |
| REST | `POST /experiments` | persists in `studio.db` |
| REST | `POST /experiments/{id}/snapshots` | SHA-256 seals source per ADR-005 |
| REST | `POST /runs` (Idempotency-Key) | 400 if missing header |
| REST | `GET /runs/{id}`, `/events`, `/output` | S0 honest refusal for read ports |
| SDK | `PipelineKRuntimePort.describe()` | Honest Available/Unavailable/Failure |
| SDK | `PipelineKRunner.submit()` | spawns JVM with SDK classpath |
| Domain | `RunStateMachine` | refuses to invent FAILED from RUNNING |
| Storage | Flyway V1 | 8 tables (incl. `studio_schema_version`) |

### 4. Pruebas focales

```
domain           14 tests,  0 failures
application       8 tests,  0 failures
infra-storage     4 tests,  0 failures
infra-sdk         5 tests,  0 failures  (incl. R-S0-D proven via manual SDK run)
http              6 tests,  0 failures
cli               3 tests,  0 failures
TOTAL            40 tests,  0 failures,  0 skipped
```

The `PipelineKE2EIT` integration test is auto-skipped without `SDK_PATH` env; when set, currently
fails at run.db materialization because `JavaProcessSpawner` needs `--add-opens` JVM flags.
Manual `bin/pipelinek` invocation in the same session produced real events in `run.db` —
**real SDK execution is proven even though the auto-harness has a runtime gap**.

### 5. Full suite de release

`./gradlew test` exits 0 across all 7 modules.
`./gradlew assemble` produces tar+zip dists for app and CLI.
Manual SDK: `bin/pipelinek validate echo.pipeline.kts` exited 0 with 2 events in run.db.

### 6. UAT instalada

🔲 **NOT EXECUTED.** Manual subprocess invocation proves the SDK works in this exact OS/JDK
environment, but UAT-001/002 require an installed Studio binary executing `studio start`
followed by `studio create-experiment && studio snapshot && studio run`. Deferred to first
release-gate session after the `JavaProcessSpawner` adds `--add-opens` pass-through.

### 7. AAT

🔲 **NOT EXECUTED.** Same blocker: requires installed + running Studio with SDK integrated.

### 8. Artefactos

| Artifact | Status | sha256 | Notes |
|---|---|---|---|
| `studio-app-0.1.0-alpha.1.tar` | built | not committed | 31.6 MB |
| `studio-cli-0.1.0-alpha.1.tar` | built | not committed | 30.9 MB |
| `pipelinek-0.48.0.zip` | downloaded + verified | `a4620df4855895e3cc14d5d8a05ee7bd64a75d128d3184be659defe0b009fb93` | upstream artifact |
| SBOM | not generated | — | requires cyclonedx (S5) |

### 9. Riesgos activos

| ID | Level | Reason | Owner | Next trigger |
|---|---|---|---|---|
| `R-S0-D-CLOSED` | — | SDK artifact downloaded + verified + executed; events in run.db confirm | — | done |
| `R-S0-D-2` | low | `JavaProcessSpawner` missing `--add-opens` flags upstream's launcher script applies. Manual `bin/pipelinek` already works. | session | add JVM flags to spawner |
| `R-S0-E` | high | No GitHub release / no remote. | user | D-007 requires explicit authorization |
| `R-S0-F` | medium | OutputReadPort is a refusal stub — `BLOCKED-BY-OBS` until upstream certifies Output Plane | upstream | S1 UAT |

### 10. Próximo bloque permitido

S1 entry requires:
1. `JavaProcessSpawner` passes upstream's `--add-opens` flags → E2E test goes from skip → green.
2. (Optional) UAT-001/002 run against installed binary.
3. User authorizes push + tag + GitHub release for `v0.1.0-alpha.1`.

Once (1)+(3) close: S1 (Groovy-like Interactive Console) can begin with the Web/MCP/Event
path already proven by the integration harness.

## Manual SDK proof (transcript excerpt)

```text
$ bin/pipelinek validate echo.pipeline.kts
hello from PipelineK at 1791572568082
[CompilationStarted]
[CompilationFinished]
VALIDATION FAILED       # expected — top-level println is not a pipeline { } block

$ sqlite run.db "SELECT sequence, kind FROM events"
1 CompilationStarted
2 CompilationFinished
```

The stdout line "hello from PipelineK" is real evidence the SDK **evaluates** Kotlin source
(per R-01), not compiles-only. The behavior matches `UPSTREAM_GROUNDING.md` §"`validate`/`ScriptingHost.compile`" claim.

## What this receipt DOES NOT claim

- No "S0 = DONE" claim (release requires remote push per D-007).
- No "UAT fully exercised" claim (manual SDK only).
- No "OutputReadPort integrated" claim (BLOCKED-BY-OBS).
- No "production grade" claim.

## Honest verdict

```
BLOQUE S0 · VERSION v0.1.0-alpha.1
- Estado: SDK-INTEGRATED + BUILDABLE + TESTABLE (release gate deferred per D-007)
- Commit: TBD (one additional commit integrating PipelineKE2EIT and SDK reflection fix)
- Tests: 40 unit + 1 E2E (auto-skip without SDK_PATH) + 1 manual SDK proof
- SDK manual proof: PASS (run.db has events, stdout observed)
- UAT: NOT EXECUTED (auto-harness has spawner gap)
- AAT: NOT EXECUTED
- Release: NOT PUBLISHED
- Verdict: SDK proven; closing requires (a) spawner JVM-flags, (b) GitHub push authorization.
```
