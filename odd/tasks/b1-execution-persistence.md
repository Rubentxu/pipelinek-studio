# B1 — Núcleo de ejecución y persistencia realmente íntegro

**Feature:** `b1-execution-persistence`
**Status:** R0/R1 complete, R2 (implementation) in progress
**Release target:** `v0.2.0-alpha.7`
**B1 entry criterion (per mandate):** "el bloque funcional más
importante de todo el roadmap."

## Problem (R0)

The current `studio.db` schema (`V1__init_studio_schema.sql`)
declares the right tables, but the application code never
populates the `experiments` table. The `CreateExperimentUseCase`
returns the in-memory `Experiment` to the HTTP layer and
discards it; subsequent `run_bindings` inserts reference
`experiment_id` values that have no parent row. After a
Studio restart, the experiment is gone but the runs are still
there with broken FKs.

Two more contract drifts the mandate names:

- `snapshots` and `run_bindings` are written with
  `INSERT OR REPLACE`, which ADR-005 explicitly forbids
  (immutability: once a snapshot is sealed, it is never
  replaced; a re-seal of the same bytes must be a no-op
  identity, not an overwrite).
- The idempotency key path is non-atomic: `idempotency.lookup`
  → `idempotency.record` → `runner.submit` run as three
  separate steps with no transaction. A crash between `record`
  and `submit` leaves a row in `idempotency` with no child
  process; a concurrent retry sees the row, returns the
  recorded responseRef (which never had a process) and the
  caller never knows.

The mandate's discriminator scenarios:

- 50 simultaneous identical idempotency keys → exactly one
  Step launch with effect.
- Same key + different digest → 409 without execution.
- Crash after reservation, before launch → recoverable
  without duplicates.
- Two deliberate runs of the same snapshot with different
  intents → two valid runs.
- Pipeline that ends FAILED → FAILED with authentic terminal
  evidence.

The mandate's ADT:

```
sealed interface RunObservation {
    data class Live(val upstreamId: String) : RunObservation
    data class Terminal(val outcome: VerifiedOutcome) : RunObservation
    data class RecoveryRequired(val reason: String) : RunObservation
    data class Unavailable(val reason: String) : RunObservation
}
```

The current `RunHandleStatus` is `Live / Terminal / Unknown`,
which conflates `RecoveryRequired` (process lost, outcome
possibly reconcilable) with `Unavailable` (no authority to
speak). The DATA_MODEL_AND_PERSISTENCE.md says: "El supervisor
no inventa `FAILED` al perder el proceso si PipelineK puede
reconciliar. No confundir `CANCEL_REQUESTED` con cancelación
consumada." This is the contract for the new ADT.

## Design (R1)

### ADT surfaces

| New ADT | Replaces | Why |
|---------|----------|-----|
| `RunObservation` (sealed, 4 cases) | `RunHandleStatus` (3 cases, conflates recovery vs unavailable) | Mandate requires explicit `RecoveryRequired`; "supervisor no inventa FAILED al perder el proceso" |
| `VerifiedOutcome` (sealed) | `String` `state` field in `RunBindingSnapshot` | The current `String` is the `run_bindings.state` CHECK column projected as-is; can be "UNKNOWN" without evidence. `VerifiedOutcome` carries `evidence: RunEvidence` (run.db path + sequence + terminal event SHA) so `DeclaredPending` and `Executed(terminal, evidence)` are distinguishable. |
| `ReservationOutcome` (sealed) | the current `IdempotencyLookupResult` (Match/Conflict/NotFound) | New: a `Reserved` outcome for "row exists, no child yet" (crash-between-record-and-submit case). Mandate demands the ADT explicitly. |

### Port additions

```kotlin
interface StudioMetadataPort {
    // B1.1: persist an experiment
    fun saveExperiment(experiment: Experiment)
    fun findExperimentById(id: ExperimentId): ExperimentSnapshot?
    // ...existing
}
```

`ExperimentSnapshot` already exists; we add `saveExperiment` +
`findExperimentById` (renamed from `findExperiment` for
consistency with the snapshot/run naming).

### Schema changes (V2 migration)

V1 stays as-is. V2 adds:

- `experiments.workspace_id` (TEXT, nullable in S0–S5,
  populated in B7 per the spec) — not strictly required by
  B1's tests, defer to B7.
- `reservations(idempotency_key PK, request_digest, status,
  studio_run_id, observed_at)` — durable reservation state
  independent of the `idempotency` legacy table. The PK
  includes `request_digest` so two requests with the same
  key but different digests get distinct rows and a 409.
- `idempotency.request_digest` is added to the UNIQUE
  constraint via a new index
  `ix_idempotency_scope_name_key_digest` (replacing the PK
  semantically). The legacy PK
  `scope_id + operation_name + key` stays for back-compat
  (rows from V1 stay readable).

### Concurrency model for B1.4

The mandate: "50 solicitudes idénticas misma clave y digest
=> un launch."

Design:

1. `studio_run_id = UUIDv5(studioNamespace, key + '|' + digest)`
   — deterministic. Same key+digest always produces the same
   id, so a retry of the same payload is idempotent at the
   row level.
2. The `run_bindings` row is `INSERT ... ON CONFLICT DO NOTHING`
   inside a single SQLite transaction with the
   `idempotency` insert. Either both rows commit, or neither.
3. `runner.submit` is called only after the row commit. If
   the process dies between commit and submit, the row exists
   in `run_bindings` with `state=CREATED` and no
   `upstream_run_id` — the recovery path on next Studio
   restart sees the row, marks it
   `RECOVERY_REQUIRED`, and surfaces a clear refusal to the
   caller.

This collapses 50 concurrent identical requests into one row
because:

- All 50 try to insert the same `(key, digest, run_id)`
  tuple.
- SQLite serializes writers; the first wins, the rest get
  `ON CONFLICT DO NOTHING` (zero rows affected), and the
  idempotency lookup on retry returns the same `run_id`.

### Schema test

`AAT-DUR-002` is the property test that proves this: fire
50 parallel POST /runs with the same key, assert exactly one
upstream subprocess was spawned (use the runner's spawn
counter as the assertion target).

## Tasks (R2)

Each task is one atomic commit per the conventional-commits
rule. Tasks that depend on schema changes ship a Flyway V2
migration in the same commit; the V1 back-compat index
ensures old data stays readable.

| Task | Description |
|------|-------------|
| B1.0 | (this document + R0/R1) |
| B1.1 | Add `saveExperiment`/`findExperimentById`; wire `CreateExperimentUseCase`; UAT-001 round-trip test (create → restart → read) |
| B1.2 | Replace `INSERT OR REPLACE` with transactional `INSERT ... ON CONFLICT` for snapshots and run_bindings; add a test that re-sealing the same source bytes returns the same snapshot_id, not overwrites |
| B1.3 | New ADT: `RunObservation` (Live/Terminal/RecoveryRequired/Unavailable) + `VerifiedOutcome` (DeclaredPending/Executed); widen `RunHandleStatus` to the new sealed interface |
| B1.4 | Deterministic `studio_run_id = UUIDv5(ns, key|digest)`; wrap insert + idempotency in one transaction; 50-concurrent test (AAT-DUR-002) |
| B1.5 | GET /runs/{id} projects `RunObservation` from `run_state_history` + `run.db`; 404 when missing (not 200 with empty state) |
| B1.6 | Replace `ClaimStatusDetector` with the new `VerifiedOutcome` ADT; `DeclaredPending` and `Executed` are distinct in the API |
| B1.7 | Type `ScenarioId` distinct from `ExperimentId` everywhere; add scenario CRUD; UAT-013 (HTTP/CLI/MCP all use the same scenario id) |
| B1.8 | JDBC pool owner return; property test that N concurrent reads don't leak FDs |
| B1.9 | Receipt, CHANGELOG, tag v0.2.0-alpha.7 |

## Verification (R3)

- `./gradlew clean test` (full suite, all green)
- `bash scripts/verify-from-clean-clone.sh`
  (`HARNESS_SUMMARY passed=N expected=N HARNESS_OK`)
- 50-concurrent identical idempotency-key test
- Restart-after-create-experiment test (UAT-001)
- Two-runs-different-intents test
- Receipt in `docs/history/B1_RELEASE_RECEIPT.md`

## Scope boundaries

- **No new SHA pin.** The lockfile stays at 0.48.0-rc1
  (R-22); B1 changes are Studio-side and do not touch
  upstream.
- **No migration of `run.db`.** PipelineK's SQLite is its
  authority (ADR-003); B1 never DMLs into it.
- **No new framework.** Hexagonal ports, ADTs, sealed
  interfaces, pure functions, no service-object
  todopoderoso.

## Open risks (R4)

- **Deterministic `studio_run_id` for re-sealed identical
  bytes** — if the digest matches an existing snapshot, the
  runner reads the existing snapshot's source bytes (not the
  new request's), which is the correct behaviour per
  ADR-005 (immutability) but may surprise operators who
  re-submit a "different" snapshot with the same bytes.
- **The V1 → V2 migration back-compat path** — the V1
  `idempotency` rows that pre-date the digest UNIQUE will
  still be readable. A future cleanup migration can drop
  the old PK once all clients are on V2. Out of scope for
  B1; document as residual debt in the receipt.
