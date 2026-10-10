# B1 — Release receipt (`v0.2.0-alpha.7`)

**Date:** 2026-10-10
**Block:** B1 — Núcleo de ejecución y persistencia realmente íntegro
**Release target:** `v0.2.0-alpha.7`
**B1 entry criterion (per mandate):** "el bloque funcional más
importante de todo el roadmap."

## Verdict

**PASS (local).** The 50-concurrent identical-key discriminator
test passes; the studio_run_id is deterministic; the four-case
`ReservationOutcome` ADT replaces the pre-B1.3 conflated
`RunState` enum; the GET /runs/{id} handler returns 404 honestly
when the row is missing; the `ClaimStatusDetector` regex
inspection is gone.

## Integrated SHA

The five B1 commits on `main`:

| Hash | Channel |
|------|---------|
| `86e1d53` | B1.1 — `CreateExperimentUseCase` persists the experiment; new `saveExperiment` port + UAT-001 round-trip test |
| `638ec6e` | B1.2 — `INSERT OR REPLACE` removed; both `snapshots` and `run_bindings` use `INSERT ... ON CONFLICT(<pk>) DO NOTHING` |
| `eee42c0` | B1.3 + B1.4 — `ReservationOutcome` ADT (4 cases) + `RunObservation` ADT (4 cases) + `VerifiedOutcome` ADT (2 cases) + `DeterministicId.studioRunId(key, digest)` (UUIDv5) + `RunReadPort.observation` |
| `8278b60` | B1.5 + B1.6 — `GET /runs/{id}` projects the new ADT (returns 404 for missing rows, 503 for RecoveryRequired/Unavailable); `ClaimStatusDetector` removed; HTTP handlers reworked to project the 4-case outcome |
| `2096610` | AAT-DUR-002 discriminator — 50 concurrent identical `(key, digest)` record calls collapse to 1 row; every post-burst lookup returns the same `response_ref` |

## Full suite result

```
$ ./gradlew check
BUILD SUCCESSFUL
```

```
$ find . -path "*/build/test-results/test/*.xml" | xargs grep -h "<testsuite" \
  | awk -F'"' '{t+=$2; s+=$4; f+=$6; e+=$8} END {print "tests="t" skipped="s" failures="f" errors="e}'
tests=246 skipped=1 failures=0 errors=0
```

The single skip is the SDK_PATH-gated UAT E2E (no SDK installed
in this checkout), which is the documented behaviour per
`docs/05-testing/UAT_CATALOG.md` (UAT-001 calls out the
dependency).

## Discriminator tests (mandate § 5)

- `50 concurrent identical idempotency-key records collapse to
  one row (AAT-DUR-002)` — the storage-layer property test.
  50 threads call `IdempotencyRepository.record` with the
  same key+digest, gated by a `CyclicBarrier(50)`. Exactly
  one thread sees `Recorded`; the other 49 see
  `AlreadyExists`. Every subsequent `lookup` returns the
  same `response_ref`.
- `studio_run_id is deterministic for same key and digest
  (B1_4)` — the application-layer property test. Two
  sequential calls with the same key+digest return the same
  `ReservationOutcome.Reserved.studioRunId`; the second
  call does not invoke the runner.
- `same key with different digest yields IDEMPOTENCY_CONFLICT
  (B1_4)` — the conflict discriminator. Second call with a
  different digest throws `IDEMPOTENCY_CONFLICT`.
- `experiment survives a DB reopen (UAT-001 round-trip)` —
  the UAT-001 discriminator. Open a path-based
  `StudioDatabase`, save an experiment, close, reopen, find
  the experiment back. Pre-B1.1 this returned `null`.

## Negative test receipt

The pre-B1.6 `ClaimStatusDetector.detect` was the only test
fixture that inspected source bytes for `assert/shouldEqual/
expect` regex patterns. B1.6 removed both the detector and
the test. The pre-B1.6 fixture would have falsely reported
`CLAIM_VERIFIED` for any source that mentioned `assert` in a
comment — the kind of false PASS the B1 mandate explicitly
forbids.

The B1.5 fix returns 404 instead of 200-with-fake-state. The
pre-B1.5 fixture in `StudioHttpModuleTest` that asserted
200/202 for a missing row was updated to assert 404
`RUN_NOT_FOUND` — the test was wrong, not the production
code.

## SDK + runtime

No SDK change. The lockfile (R-22) stays at 0.48.0-rc1 with
SHA pin `0811e7b6…`; the B1 changes are Studio-side only.

| Component | Version | Source |
|-----------|---------|--------|
| PipelineK SDK | 0.48.0-rc1 | `studio-infra-sdk/src/main/resources/sdk.lock.json` (R-22) |
| JVM | JDK 21 | `gh actions/setup-java@v4` + local install |
| Gradle | (project wrapper) | `gradlew` |
| Node | 20 | `actions/setup-node@v4` (MCP build only) |

## Open risks (R4)

- **The 50-concurrent test runs against an in-memory SQLite.**
  Production uses a file-backed SQLite under
  `~/.pipelinek-studio/runtime/studio.db` with WAL. The
  in-memory test exercises the SQL contract; WAL behaviour
  is verified by the audit H-22 pool tests already in
  `StudioDatabaseTest`. The B0.6 cert-gate self-test
  exercises the harness end-to-end with a real file-backed
  DB; the next release (B2) will exercise the B1
  discriminator under WAL.
- **The `VerifiedOutcome.Executed` projection requires the
  journal terminal event.** The default `RunReadPort.
  observation` returns `DeclaredPending` until the SDK
  adapter overrides with the journal-aware implementation.
  B2 (kill -9 reconciliation) is the natural block to
  ship that override.
- **The `ReservationOutcome.RecoveryRequired` path is
  reachable but untested at the integration level.** The
  use case returns it on a pathological race (idempotency
  row deleted between `record` and re-`lookup`); a real
  production trigger is rare. B7 distribution testing will
  exercise it.

## Receipt summary

```
Block: B1 / version: v0.2.0-alpha.7
Main commit / candidate / tag SHA: <to be set by the tag step>
Upstream PipelineK runtime version + digest: 0.48.0-rc1 + SHA-pinned
Commands/tests (exit codes, failures, skips):
  ./gradlew check
  -> BUILD SUCCESSFUL
  -> 246 tests, 0 failures, 0 errors, 1 skip (SDK_PATH-gated)
UAT / AAT matrix: UAT-001 round-trip + AAT-DUR-002 50-concurrent
Installer smoke: not applicable (B7 deliverable)
Manifest + SBOM + artifact sha256: not produced at B1 (B7 deliverable)
GitHub release URL: not created at B1
Open risks: in-memory SQLite for the 50-concurrent test; SDK
  adapter override for Executed projection pending B2
Verdict: PASS — the four-case ReservationOutcome ADT is the
  contract; the deterministic studio_run_id is the property;
  the 50-concurrent test is the proof.
```
