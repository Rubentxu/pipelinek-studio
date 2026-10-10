# B0 — Recover trust in the certification gate

**Feature:** `b0-cert-gate`
**Status:** implementation complete; harness running for the first time
under the new gate; this document is the B0 receipt.
**Release target:** `v0.2.0-alpha.6`
**B0 entry criterion (per mandate):** "No avanzar a B1 mientras el sistema
de certificación pueda declarar READY después de un test fallido."

## Problem (R0)

A pre-B0 audit of the release-harness surface found six concrete
false-PASS channels. Each one was a way for the certification
gate to declare `READY` while at least one mandatory test had
either been skipped, swallowed, or misclassified. The user's
mandate is explicit: B0 closes every one of these before any
new feature work begins.

| # | Channel | Where it lived |
|---|---------|----------------|
| 1 | Start smoke loop fell through on backend never becoming healthy | `scripts/verify-from-clean-clone.sh:51-65` |
| 2 | `READY_WITH_RISKS` was awarded to any non-zero exit without a `❌` marker | `.github/workflows/release-harness.yml:69-82` |
| 3 | Detekt findings were swallowed as "non-fatal for the S0 bootstrap" and the script exited 0 | `scripts/detekt-run.sh:50-55` |
| 4 | Harness printed "ALL FIVE CHECKS PASSED" with no N-of-M coverage assertion; MCP fell back to a guidance-only "ok" when `node` was missing | `scripts/verify-from-clean-clone.sh:507-534` |
| 5 | The harness never wired `uat-from-distribution.sh` (UAT was a presence check, not an execution) | `scripts/verify-from-clean-clone.sh` (no step at all) |
| 6 | No failure-injection test proved the gate could actually fail | (no script) |

## Why these are real (and the audit didn't catch them)

The previous senior audit (2026-10-10, score 9.5/10) was an
inspection of code correctness, not of the certification
process. The audit verified the production code paths — the
detekt script, the workflow file, the harness script — but it
did not run them with a deliberately broken backend or a
missing `node`. The certification gate was code-reviewed, not
property-tested. A code-reviewed gate can still silently
declare READY; only a property-tested gate can prove it
cannot.

## Design (R1)

The architectural shape of the fix is a four-piece property test:

1. **Step counters.** Every checkpoint in the harness is a
   `step` call that increments `HARNESS_EXPECTED`. Every
   successful assertion is an `ok` call that increments
   `HARNESS_PASSED`. The `on_exit` trap compares the two; if
   the script exits 0 with `passed < expected`, the trap
   re-raises exit 1 and prints a `❌ HARNESS_SUMMARY
   passed=K expected=M` line.
2. **Stable summary line.** The harness always emits
   `HARNESS_SUMMARY passed=N expected=M HARNESS_OK` (on
   success) or `❌ HARNESS_SUMMARY passed=K expected=M
   HARNESS_FAIL: <reason>` (on failure). The GitHub workflow
   greps for this exact shape and downgrades the verdict to
   `NOT_READY` if the line is missing or shows a gap.
3. **Detekt gating.** `DETEKT_FAIL_ON_FINDINGS=1` is the
   default. Findings are counted from the SARIF report
   (machine-readable), with a `jq` path and a grep fallback
   so the count is independent of `jq` availability. The
   operator can opt into the legacy baseline posture with
   `DETEKT_FAIL_ON_FINDINGS=0`, but the default is honest.
4. **Self-test (`verify-cert-gate.sh`).** A second script
   copies the harness, deletes one `ok` call, runs the
   tampered copy, and asserts the trap fires. This is a
   property test of the certification gate itself: the
   deliverable of B0 is not a feature but a guarantee that
   the gate cannot lie.

## Tasks (R2)

| Task | Description | Commit |
|------|-------------|--------|
| B0.1 | Close the silent-skip channel in the start smoke loop | (this iteration) |
| B0.2 | Tighten the workflow classifier (no more `READY_WITH_RISKS`) | (this iteration) |
| B0.3 | Detekt default = fail on findings; opt-in to baseline | (this iteration) |
| B0.4 | N-of-M coverage + MCP honest (no guidance-only ok) | (this iteration) |
| B0.5 | Wire UAT from distribution into the harness against `studio-app` | (this iteration) |
| B0.6 | Add `verify-cert-gate.sh` failure-injection self-test | (this iteration) |

## Verification (R3)

- All 6 tasks ship with this iteration
- The harness runs end-to-end on `origin/main` and either
  passes (printing `HARNESS_SUMMARY passed=N expected=N HARNESS_OK`)
  or fails honestly (printing the `❌ HARNESS_SUMMARY` shape).
  The latter is the B0 receipt: the gate is now capable of
  saying NOT_READY.
- The cert-gate self-test is a separate script that runs
  inside step 6 of the harness. It cannot be skipped without
  an explicit `STUDIO_SKIP_CERT_GATE=1` opt-out, which prints
  a visible marker in the log.

## Scope boundaries

- The 46-finding detekt baseline is **preserved** in this
  iteration. Closing each finding (LongMethod, NestedBlockDepth,
  TooGenericExceptionCaught, MagicNumber) is its own work item
  in B1 or later; shrinking the baseline is the team's job,
  not the gate's. The gate's job is to refuse to certify a
  dirty baseline; the team's job is to keep the baseline
  clean.
- The harness still depends on `node` + `npm` for the MCP
  step. The B0.4 fix replaces the silent skip with a hard
  fail; running the harness in a node-less CI now requires
  the explicit opt-out.
- The "negative test" the user mandate requires (introduce
  a temporary failing test, prove the harness refuses) is
  precisely what `verify-cert-gate.sh` automates. It is the
  meta-test that closes the loop.

## Open risks (R4)

- **Gradle test failures unrelated to the gate**: the harness
  runs `./gradlew clean test` and any flaky test will now be
  visible (as it should be). A green B0 is only as honest as
  the tests it gates; if a test was silently skipped before,
  it is now an outright failure.
- **Detekt finding churn**: a PR that adds a single finding
  will now be blocked from merging by the release-harness
  job. The team should set a max-issue count and treat the
  rule set as load-bearing from now on.
- **Cert-gate runtime**: `verify-cert-gate.sh` runs the
  harness a second time. On a clean tree that's another
  ~3-5 minutes of gradle work. Operators can opt out with
  `STUDIO_SKIP_CERT_GATE=1` for the GH workflow's primary
  job; the local `bash scripts/verify-from-clean-clone.sh`
  invocation should run it.
