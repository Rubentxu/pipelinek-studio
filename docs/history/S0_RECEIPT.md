# S0 Bootstrap Receipt — Read ports read real SDK events

**Block:** S0 (per ROADMAP.md)
**Version:** `v0.1.0-alpha.1` (local build, NOT released to remote)
**Status:** SDK-INTEGRATED + E2E-VERIFIED + READ-PORT-REAL (release gate remains user-authorized per D-007)
**Date:** 2026-10-09

## S0 entry gates verified this session

| ID | Status | Evidence |
|---|---|---|
| **R-S0-D** (SDK only pinned) | ✅ CLOSED | real artifact downloaded, SHA-256 verified, SDK executes |
| **E2E harness** | ✅ GREEN | `PipelineKE2EIT` boots HTTP, real subprocess writes `run.db` |
| **Spawner JVM flags** | ✅ FIXED | `--add-opens`/`--add-exports` mirrored from `bin/pipelinek` |
| **HTTP persistence** | ✅ FIXED | `POST /runs` persists binding to `studio.db` |
| **Real RunReadPort** | ✅ ADDED | `PipelineKRunReadPort` opens `<control-root>/<runId>/run.db` read-only and returns real events; HTTP `/runs/{id}/events` now serves them (replaces the S0 stub `RefusingRunReadPort`) |
| **Tests** | 45, 0 failures | +4 new (`PipelineKRunReadPortIT`) on top of the 41 prior |

## What's wired

```
GET /runs/{id}/events     ← PipelineKRunReadPort reads real run.db events table
GET /runs/{id}            ← initial state lookup uses RunReadPort when no Live status
                           (BLOCKED-BY-OBS still applies for full terminal inference — partial)

PipelineKRunReadPort
  - opens <controlRootBase>/<runId>/run.db in JDBC readonly
  - SELECT sequence, kind, occurred_at, payload FROM events WHERE run_id=? AND sequence>? ORDER BY sequence LIMIT ?
  - decodes payload JSON minimally to a string map
  - never invents events; returns Unknown if run.db is missing
  - state() returns SyntheticStateHint derived from latest event kind
```

## Status

### Per ROADMAP DoD format

1. **Estado:** S0 close-ready pending release authorization (D-007). 45/0/0 tests across 13 classes. Studio HTTP API serves real SDK events through `/runs/{id}/events`. S1 deliverable E1.2 (read ports returning real data from run.db) is functionally complete at the S0 close — only the consume side (Monaco SSE) remains for S1.

2. **Código:** 4 commits on local main (no remote push per D-007).

3. **Contratos:** 8 HTTP endpoints, 6 application ports, 14-stage RunStateMachine. 12 Kotlin modules including TS MCP/Web shells.

4. **Pruebas focales:**
   - domain 14/0/0
   - application 8/0/0
   - infra-storage 4/0/0
   - infra-sdk 5 (Runner+Probe) + 4 (RunReadPort) + 1 (E2E with SDK_PATH) = 10/0/0
   - http 6/0/0
   - cli 3/0/0
   - **TOTAL: 45/0/0**

5. **Full suite de release:** `./gradlew test --rerun-tasks` exits 0 with `SDK_PATH=...`.

6. **UAT instalada:** UAT-001 (HTTP chain to real SDK execution) exercised inside the E2E test harness. From-distribution UAT requires clean-clone install (S5 hardening).

7. **AAT:** Not executed. AAT-ARCH/SEC/DUR/MCP/API/REL require installed binary + observable harness.

8. **Artefactos:** distributions built locally; `pipelinek-0.48.0.zip` SHA-verified upstream; `run.db` observed with `CompilationStarted/Finished`.

### Real SDK events observed in E2E run.db

```
$ sqlite run.db "SELECT sequence, kind FROM events"
1 CompilationStarted
2 CompilationFinished
3 CompilationCacheKeySet
...
```

Stdout from SDK: "hello from PipelineK at <ms>" — proves SDK **evaluates** Kotlin source per R-01, not compile-only.

### Riesgos activos

| ID | Level | Reason | Owner | Next trigger |
|---|---|---|---|---|
| `R-S0-E` | HIGH | No GitHub release / no remote. D-007 pending. | user | explicit push authorization |
| `R-S0-F` | low | OutputReadPort remains a refusal stub — `BLOCKED-BY-OBS`. EventPlane unblocked; OutputPlane upstream certification pending. | upstream | S1 UAT |
| `R-S1-A` | — | Monaco editor + SSE not yet implemented. Read-side now ready (45 tests passing). | session | after `R-S0-E` |

### Próximo paso

User authorizes push to `Rubentxu/pipelinek-studio` + tag `v0.1.0-alpha.1` + GitHub prerelease → S0 = DONE.

S1 entry requires S0 DONE; S1 entry work (Monaco + SSE on top of the now-real read port) can begin immediately after the release gates close.

## What this receipt STILL DOES NOT claim

- No "DONE" claim per AGENT_HANDOFF (release requires remote push).
- No S1..S5 implementation claim (one session cannot reasonably implement 5 more blocks; this session surfaced the S0 block to "release-ready" state).
- No "Output Plane certified" claim (BLOCKED-BY-OBS upstream).

## Honest verdict

```
BLOQUE S0 · VERSION v0.1.0-alpha.1
- Estado: release-ready; D-007 user authorization pending
- Tests: 45/0/0
- SDK integration: PASS end-to-end
- Real read ports: PASS for EventPlane; refusal for OutputPlane
- UAT: in-harness PASS; from-distribution pending
- AAT: NOT EXECUTED (requires installed binary)
- Release: NOT PUBLISHED
- Verdict: S0 surface area covered as far as user-context permits in one session.
  Remaining work = S1..S5 (weeks) + release (D-007).
```

