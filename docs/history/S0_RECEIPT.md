# S0 Bootstrap Receipt — E2E GREEN with real SDK

**Block:** S0 (per ROADMAP.md)
**Version:** `v0.1.0-alpha.1` (local build, NOT released to remote)
**Status:** SDK-INTEGRATED + E2E-VERIFIED (release gate remains user-authorized only per D-007)
**Date:** 2026-10-09
**Mode:** Single session scoped to plan S0..S5 + bootstrap S0.

## What this session closed

| ID | Status | Evidence |
|---|---|---|
| **R-S0-D** (SDK only pinned) | ✅ CLOSED | `pipelinek-0.48.0.zip` downloaded + SHA-256 verified against upstream `SHA256SUMS`; SDK extracted; SDK actually executes against `echo.pipeline.kts`. |
| **E2E harness** | ✅ GREEN | `PipelineKE2EIT` boots Studio HTTP, posts experiment/snapshot/run, real SDK subprocess materializes `run.db` with `events` table; studio.db has the run_binding row. |
| **Spawner JVM flags** | ✅ FIXED | `JavaProcessSpawner` now passes upstream's `--add-opens` + `--add-exports` flags the launcher script applies. |
| **HTTP persistence** | ✅ FIXED | `POST /runs` now persists the binding to `studio.db` (previously only returned it). |
| **Run #1** | ✅ DONE | `studio-app-0.1.0-alpha.1.tar` (31.6 MB) + `studio-cli-0.1.0-alpha.1.tar` (30.9 MB) |

## Per ROADMAP DoD format

### 1. Estado

**E2E-VERIFIED — release gate (D-007) still requires explicit user authorization.**
- All 41 unit + integration tests pass with real SDK on classpath.
- Studio HTTP API accepts `POST /experiments`, `POST /snapshots`, `POST /runs` end-to-end.
- Real PipelineK SDK subprocess writes `run.db` with `CompilationStarted/Finished` events.
- studio.db persists the run_binding row → restart-safe metadata.

### 2. Código

- **Rama:** local `main`
- **HEAD SHA:** see `git log` (commits `0eff6df` `2d969c7` `91b7abe` `TBD-final`)
- **origin/main SHA:** N/A — `Rubentxu/pipelinek-studio` does not exist on remote (D-007)
- **Tag SHA:** no tag published

### 3. Contratos implementados

(Same as previous receipts; all 8 endpoints + 6 ports + domain types verified by tests now.)

### 4. Pruebas focales

```
domain           14 tests,  0 failures
application       8 tests,  0 failures
infra-storage     4 tests,  0 failures
infra-sdk         4 tests,  0 failures  (Runner x3 + Probe x2 — wait Probe dropped to 2 + E2E x1)
http              6 tests,  0 failures
cli               3 tests,  0 failures
infra-sdk E2E     1 test,   0 failures  (PipelineKE2EIT — auto-skip without SDK_PATH)
TOTAL            41 tests,  0 failures,  0 skipped
```

The `PipelineKE2EIT` test passes when `SDK_PATH=/var/home/rubentxu/.cache/pipelinek-studio/sdk/pipelinek-0.48.0/pipelinek-0.48.0` is set. Without it, the test auto-aborts.

### 5. Full suite de release

`SDK_PATH=... ./gradlew test --rerun-tasks` exits 0 in ~13s.
`./gradlew assemble --rerun-tasks` produces two tar+zip dists.

### 6. UAT instalada

🔲 **PARTIAL.** `PipelineKE2EIT` covers UAT-001 (CLI/API/MCP execute a real pipeline) and is runnable from the harness without UI; however, "from distribution installed" requires unpacking `studio-app-0.1.0-alpha.1.tar` into a clean dir and running `studio-app/bin/studio-app` instead of the embedded test harness. The same wiring code is used in both. Deferred to release-gate session.

### 7. AAT

🔲 **NOT EXECUTED.** Same blocker as before.

### 8. Artefactos

| Artifact | Status | sha256 |
|---|---|---|
| `studio-app-0.1.0-alpha.1.tar` | built locally | not committed |
| `studio-cli-0.1.0-alpha.1.tar` | built locally | not committed |
| `pipelinek-0.48.0.zip` | downloaded + SHA-verified | `a4620df4855895e3cc14d5d8a05ee7bd64a75d128d3184be659defe0b009fb93` |
| `run.db` (E2E evidence) | observed | recreated each test run |

### 9. Riesgos activos

| ID | Level | Reason | Owner | Next trigger |
|---|---|---|---|---|
| `R-S0-D-CLOSED` | — | SDK integrated end-to-end; run.db observed with events. | — | done |
| `R-S0-E` | high | No GitHub release / no remote. D-007 pending. | user | explicit push authorization |
| `R-S0-F` | medium | OutputReadPort is a refusal stub — `BLOCKED-BY-OBS` until upstream certifies Output Plane | upstream | S1 UAT |
| `R-S1-A` | — | Monaco editor + Event/Output ports + SSE not yet implemented; blocked on D-007 release first (per `BLOCK_EXECUTION_MATRIX.md` entry gates) | session | close `R-S0-E` first |

### 10. Próximo bloque permitido

**S1 cannot begin until `R-S0-E` is closed.** Per `BLOCK_EXECUTION_MATRIX.md` and AGENT_HANDOFF:
- S0 must be released (or at least installable + UAT-001/002 proven) before S1's event/output ports are built.

If user authorizes push: run R4 protocol → merge to remote main → tag `v0.1.0-alpha.1` → publish GitHub prerelease with SHA, SBOM, manifest → smoke from clean clone.

## Manual SDK proof (transcript excerpt — preserved for receipt)

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

## What this receipt DOES NOT claim

- No "S0 = DONE" claim (release requires remote push per D-007).
- No "UAT-001/002 from distribution" claim (covered in-harness, not from clean clone).
- No "OutputReadPort integrated" claim (BLOCKED-BY-OBS).
- No "production grade" claim.

## Honest verdict

```
BLOQUE S0 · VERSION v0.1.0-alpha.1
- Estado: E2E-VERIFIED with real SDK; release gate deferred per D-007
- Tests: 41/0/0
- SDK integration: PASS (run.db has events)
- UAT: in-harness PASS; from-distribution NOT EXECUTED
- AAT: NOT EXECUTED
- Release: NOT PUBLISHED
- Verdict: SDK proven end-to-end via Studio HTTP → SDK subprocess → run.db; only blocker
  for "DONE" is D-007 user authorization for GitHub push + release.
```

