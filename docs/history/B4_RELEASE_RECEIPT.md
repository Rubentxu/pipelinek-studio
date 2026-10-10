# B4 — Release receipt (`v0.5.0-beta.1`)

**Date:** 2026-10-10
**Block:** B4 (S4 — Agent collaboration y escenarios inteligentes)
**Release target:** `v0.5.0-beta.1`
**B4 entry criterion (per mandate):** "API/MCP para
variantes de snapshot y compare de runs con diferencias de
runtime/plugins/env perfil; mutation scenarios
controlados, per-run fingerprints/diffs."

## Verdict

**PASS (local).** The seven B4 sub-tasks are landed on `main`
behind per-task commit identities; the full suite is green
(361 tests, 0 failures, 0 errors, 1 documented skip). The
honesty layer is the spine of the S4 mandate: the agent
capability model defaults to "all disabled" and the
mutation control refuses adversarial instructions that
would let an agent produce a false PASS.

## Integrated SHAs

The seven B4 commits on `main` (oldest to newest):

| Hash | Channel |
|------|---------|
| `e610ba3` | **B4.0** — `AgentCapability` enum (COMPARE / DIFF / PROMOTE / SELECT / MUTATE) + `AgentCapabilityPort` + `DisabledByDefaultAgentCapability` + `EnvBackedAgentCapability` (reads `STUDIO_AGENT_CAPS`, comma-separated, case-insensitive, "all" sentinel). Default posture is "all disabled" — the studio refuses every agent action until the operator explicitly opts in. |
| `493225d` | **B4.1** — `studio.runs.compare` typed S4 dimension diff. `RunComparison` extended with `equal: Boolean`, `runtimeVersion` / `pluginSetDigest` / `environmentProfile: ComparisonDimension?` (each carries `differ` + `valueA` + `valueB`), and a human-readable `summary` string. The pre-B4.1 `RunComparison` only carried event-sequence counts; B4.1 is the side-by-side dimension diff the S4 mandate asks for. |
| `de949f4` | **B4.2** — `studio.snapshots.diff` typed line-based diff. `SnapshotDiff` (linesAdded / linesRemoved / linesChanged) + `SnapshotDiffPort` + `LineBasedSnapshotDiff` (O(n*m) LCS over the line sequences, deterministic). Missing snapshot returns `Failure`, never an empty diff. |
| `d5c8eb9` | **B4.3** — `studio.snapshots.promote` explicit promote port. `PromoteVariantRequest` / `PromoteVariantResult.Ok(newSnapshotId, promotedFrom)` / `Failure` + `PromoteVariantPort`. The variant is NEVER auto-promoted; the operator must call the port explicitly. The promoted snapshot carries the same source bytes (same sha256), lives in the same experiment as the variant, and records the promoter + note + variant id in the `creator` field for the audit trail. |
| `461ba68` | **B4.4** — `studio.runs.select` step-contextualized run selection. `RunSelectionRequest` / `RunSelectionResult.Ok(reconstructedSource, lineRange, fingerprint)` / `Unknown(reason)` / `Failure(reason)` + `RunSelectionPort` + `LineRangeReconstructingRunSelection` (reconstructs a single-step pipeline from the run's source + operation_journal metadata). A Step whose body references `prevResult.` returns `Unknown` (runtime-evaluated values cannot be reconstructed offline). |
| `e525d86` | **B4.5** — capability-driven `studio.agent` dispatcher. `AgentDispatcher.dispatch(action)` is the single entry point; the dispatcher's PUBLIC surface is exactly one method (pinned by reflection in `AgentDispatcherB45Test`). `AgentAction` sealed interface: `CompareRuns`, `DiffSnapshots`, `PromoteVariant`, `SelectStep`, `MutateSnapshot`. The capability gate is the FIRST gate; an action whose capability is disabled returns `Refused` without invoking the port. `AgentActionResult` carries `Ok` / `Refused` / `Unknown` / `Failure` so the typed distinctions are preserved across the wire. |
| `54b9ffe` | **B4.6** — prompt-injection guard + mutation control. `AgentRequest` / `AgentRequestResult.Ok / Refused(trigger, reason) / Failure` + `AgentRequestPort` + `HeuristicPromptInjectionGuard` (closed trigger catalog of 8 phrases including "ignore previous instructions", "return success regardless", "mark as pass"; case-insensitive + underscore-to-space normalization). `MutationControlPort.admit(request, mutationId)` + `DefaultMutationControl` (guard → capability → closed mutation catalog; free-text mutations are refused with `Unknown`). The guard is the FIRST gate; a refused guard short-circuits the capability check. |

## Mandate § bullets

| # | Mandate text | Backed by |
|---|--------------|-----------|
| 1 | MCP recursos paginados, discoverability de actions y su procedencia | B4.5 (`AgentAction.name` + the typed `AgentActionResult` ADT; the dispatcher's public surface is exactly one method) |
| 2 | Capability-driven (no tool-per-step) | B4.0 (capability model) + B4.5 (single `dispatch` entry point; per-step tools are not methods on the dispatcher) |
| 3 | API/MCP para variantes de snapshot y compare de runs con diferencias de runtime/plugins/env perfil | B4.1 (`RunComparison` S4 dimension diff: runtimeVersion / pluginSetDigest / environmentProfile) + B4.2 (`SnapshotDiff` typed line-based diff) + B4.3 (`PromoteVariantPort` for variant promotion) |
| 4 | Mutation scenarios controlados, per-run fingerprints/diffs | B4.6 (`MutationControlPort` + closed mutation catalog; guard refuses adversarial instructions) |
| 5 | Run Selection (Step contextualized) | B4.4 (`RunSelectionPort` reconstructs a single-step pipeline; the selected Step is the only one that runs) |

All five S4 mandate items are pinned by a passing test
or a documented runtime check; none is a verbal claim.

## AAT / UAT matrix

- **AAT-MCP-007..008** (MCP discoverability + parity):
  AAT-MCP-007 is the B4.5 single-dispatch-point
  discriminator (the dispatcher's public surface is
  exactly one method). AAT-MCP-008 is the B4.0
  capability model discriminator (the default posture
  refuses every capability until `STUDIO_AGENT_CAPS` is
  set).
- **AAT-DUR-006** (run durability under mutation):
  the pre-B4.6 mutation flow was unrestricted; the
  B4.6 `DefaultMutationControl.admit` step refuses
  unknown mutation ids, so a fabricated mutation
  cannot reach the snapshot bytes.
- **AAT-SEC-008..010** (security hardening under
  agent workflow: prompt injection, capability
  bypass, mutation-driven false PASS):
  - AAT-SEC-008 is the B4.6 prompt-injection guard
    discriminator (the trigger catalog refuses
    "ignore previous instructions" + 7 other
    phrases).
  - AAT-SEC-009 is the B4.0 default-posture
    discriminator (`DisabledByDefaultAgentCapability`
    refuses every cap; the test pins the count +
    the per-cap refusal).
  - AAT-SEC-010 is the B4.6 closed mutation catalog
    discriminator (free-text mutation ids return
    `Unknown` even when MUTATE is enabled).
- **AAT-API-005..008** (API contract tests for the
  new endpoints): the wire DTO additions for B4
  follow the existing JSON shape; the B4 tests pin
  the data-class contract (`equal`,
  `runtimeVersion` / `pluginSetDigest` /
  `environmentProfile`, `linesAdded` /
  `linesRemoved` / `linesChanged`).
- **UAT-027..035** (UAT script-driven): not in this
  checkout (SDK_PATH-gated, the same single
  documented skip as B0/B1/B2/B3).

## Negative test receipt

- **B4.0** (pre-B4.0 agent surface): the pre-B4.0
  studio had no capability model; any code that
  reached a port could trigger an agent action.
  The B4.0 fix: every agent-facing use case MUST
  consult `AgentCapabilityPort.isEnabled` BEFORE
  doing anything; the default posture refuses
  every cap so a misconfigured deployment cannot
  accidentally grant a capability.
- **B4.1** (pre-B4.1 dimension-blind diff): the
  pre-B4.1 `RunComparison` only carried
  event-sequence counts; an agent that wanted
  to know "do these runs have the same
  runtimeVersion?" had to hand-roll the diff.
  The B4.1 fix: `ComparisonDimension(differ,
  valueA, valueB)` for each S4 dimension; the
  `summary` string names the differing
  dimensions in human-readable form.
- **B4.2** (pre-B4.2 no snapshot diff): the
  pre-B4.2 studio could clone a snapshot
  (VariantPort) but had no way to compare two
  snapshots. The B4.2 fix: `SnapshotDiffPort` +
  `LineBasedSnapshotDiff` (deterministic, line-
  based LCS).
- **B4.3** (pre-B4.3 no promote port): the
  pre-B4.3 studio had no way to turn a variant
  into the canonical snapshot. The B4.3 fix:
  `PromoteVariantPort.promote(request)` is the
  explicit operator action; the variant is never
  auto-promoted.
- **B4.4** (pre-B4.4 no run selection): the
  pre-B4.4 studio could replay an entire run but
  could not run a single Step in isolation. The
  B4.4 fix: `RunSelectionPort.select(request)`
  reconstructs a single-step pipeline from the
  run's source + operation_journal metadata; the
  selected Step is the only one that runs.
- **B4.5** (pre-B4.5 tool-per-step): the pre-B4.5
  design would have exposed N MCP tools (one per
  action). The B4.5 fix: a single
  `AgentDispatcher.dispatch(action)` is the only
  entry point; the dispatcher's public surface
  is exactly one method (pinned by reflection).
- **B4.6** (pre-B4.6 prompt-injection silent-
  pass): the pre-B4.6 flow did not inspect the
  request metadata for adversarial instructions.
  A well-crafted adversary could craft a request
  that says "ignore previous instructions" or
  "return success regardless" to make the agent
  produce a false PASS. The B4.6 fix:
  `HeuristicPromptInjectionGuard` scans every
  metadata key + value, the notes, and the
  payload; the guard is the FIRST gate in
  `DefaultMutationControl.admit` so a refused
  guard short-circuits the capability check.

## SDK + runtime

No SDK change. The lockfile (R-22) stays at 0.48.0-rc1
with SHA pin `0811e7b6…`; the B4 changes are Studio-side
only.

| Component | Version | Source |
|-----------|---------|--------|
| PipelineK SDK | 0.48.0-rc1 | `studio-infra-sdk/src/main/resources/sdk.lock.json` (R-22) |
| JVM | JDK 21 | `gh actions/setup-java@v4` + local install |
| Gradle | (project wrapper) | `gradlew` |
| Node | 20 | `actions/setup-node@v4` (MCP build only) |

## Open risks (R4)

- **B4.0 capability opt-in is the default posture.**
  A production deployment that forgets to set
  `STUDIO_AGENT_CAPS` will have all agent tools
  disabled. The B4.0 test asserts the default is
  "all disabled" so the posture is explicit; the
  follow-up doc change in the release notes must
  flag this for operators.
- **B4.2 snapshot diff is line-based.** The default
  diff is a line-based LCS (longest common
  subsequence). This is not a perfect diff for
  refactors (a single rename produces many
  "removed" + "added" lines), but it is
  deterministic and the agent can post-process. A
  future S7 pass could add a structural diff
  (AST-based).
- **B4.4 run selection reconstruction is best-
  effort.** The reconstruction reads the
  operation_journal's `input` field for the
  selected Step and builds a pipeline that
  supplies those inputs. The reconstruction is
  best-effort: a Step that depended on a
  runtime-evaluated value (`prevResult.`)
  cannot be reconstructed offline. The B4.4 test
  asserts the reconstruction succeeds for the
  common case (a Step with declared inputs) and
  surfaces `Unknown("cannot reconstruct
  offline")` otherwise.
- **B4.6 prompt-injection guard is heuristic.** A
  well-crafted adversarial instruction that
  doesn't match the trigger phrase would bypass
  the guard. The B4.6 test pins the contract for
  the trigger phrase; a future S7 pass could add
  an embedding-based detector.

## Receipt summary

```
Block: B4 / version: v0.5.0-beta.1
Main commit / candidate / tag SHA: 54b9ffe (HEAD) / v0.5.0-beta.1 → <to be filled at tag time>
Upstream PipelineK runtime version + digest: 0.48.0-rc1 + SHA-pinned
Commands/tests (exit codes, failures, skips):
  ./gradlew check
  -> BUILD SUCCESSFUL
  -> 361 tests, 0 failures, 0 errors, 1 skip (SDK_PATH-gated)
Mandate § bullets: 5/5 backed by passing test or runtime check
AAT-MCP-007..008 + AAT-DUR-006 + AAT-SEC-008..010 + AAT-API-005..008: discriminators landed
UAT-027..035: not in this checkout (SDK_PATH-gated)
Installer smoke: not applicable (B7 deliverable)
Manifest + SBOM + artifact sha256: not produced at B4 (B7 deliverable)
GitHub release URL: not created at B4
Open risks: capability opt-in is the default posture (operator
  must set STUDIO_AGENT_CAPS); snapshot diff is line-based LCS;
  run selection is best-effort (prevResult returns Unknown);
  prompt-injection guard is heuristic (closed trigger catalog)
Verdict: PASS — the 5 S4 mandate items are pinned by tests;
  the honesty layer (capability default posture + closed
  mutation catalog + heuristic prompt-injection guard) is
  the spine; the agent loop is wired but the LLM-side
  agent is whoever calls the MCP.
```
