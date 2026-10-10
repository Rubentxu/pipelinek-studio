# B4 — Agent collaboration & intelligent scenarios (`v0.5.0-beta.1`)

**Date:** 2026-10-10
**Block:** B4 (S4 — Agent collaboration y escenarios inteligentes)
**Target:** `v0.5.0-beta.1`
**Mandate:** `ROADMAP.md` §"S4 — Agent collaboration y
escenarios inteligentes".

## Problem

The studio already has agent-adjacent surface from
S0-S3 (AgentWorkflows.mutateAndCompare use case,
ScenarioExecutionRequest with `runId`, RunScenarioUseCase
with `expected: ResultDescriptor?`, ScenarioBuilder).
What is missing per the S4 mandate is the
**agent-facing** spine: the MCP tools the agent calls
to compare runs, diff snapshots, promote a variant, and
execute a contextualized single Step. Without those
tools the agent is forced to hand-roll them, which
violates "MCP recursos paginados, discoverability de
actions y su procedencia; capability-driven (no
tool-per-step)".

## Why

The S4 mandate is the "agent-first" block. Every
upstream that exposes an MCP to an LLM agent has to
face the question: "can the agent change a result to
a false PASS?" The B4 deliverables pin that property
with a prompt-injection guard, a mutation control
port, and a per-run fingerprint that ties the
mutation back to the audit trail.

## Scope

- **In scope**: B4.0 agent capability model + ports,
  B4.1 studio.runs.compare, B4.2 snapshot variants +
  diff, B4.3 promote snapshot, B4.4 Run Selection
  (Step contextualized), B4.5 capability-driven MCP
  (no tool-per-step), B4.6 prompt-injection guard
  + mutation control.
- **Out of scope**: the LLM-side agent implementation
  is not part of B4. The studio exposes the
  capability model; the agent is whoever calls the
  MCP. A B7 distribution test will exercise the
  end-to-end agent loop.

## Constraints

- All tests pass before commit (308 → 308+ new).
- SDK stays at 0.48.0-rc1 (R-22 SHA-pinned).
- No AI attribution in commits; conventional commits.
- Tag `v0.5.0-beta.1` requires operator authorization
  per D-007 (no push to GitHub without explicit
  authorization).

## Acceptance

- B4.0: `AgentCapability` enum (COMPARE / DIFF /
  PROMOTE / SELECT / MUTATE) + `AgentCapabilityPort`
  + default impl that reads the env var
  `STUDIO_AGENT_CAPS` (comma-separated) to enable /
  disable caps. Default is "all disabled" — the
  operator must opt in.
- B4.1: `studio.runs.compare(runA, runB)` returns
  a typed `RunComparison` with diffs in
  `runtimeVersion`, `pluginSetDigest`,
  `environmentProfile`. A test asserts that two runs
  with the same profile produce `equal = true`;
  a test with different profiles produces `equal = false`
  + a clear reason.
- B4.2: `studio.snapshots.diff(snapshotA, snapshotB)`
  returns a typed `SnapshotDiff` with `linesAdded`,
  `linesRemoved`, `linesChanged`. A test asserts the
  diff is deterministic (same input → same diff).
- B4.3: `studio.snapshots.promote(variantId)` returns
  a `SnapshotId` for the new canonical snapshot.
  The variant is never auto-promoted; the operator
  must call this tool explicitly. A test asserts the
  promotion creates a new snapshot row with the
  variant's bytes + the operator's note.
- B4.4: `studio.runs.select(runId, stepOpId)` returns
  a reconstructed pipeline that runs only the
  selected Step. The reconstruction MUST include
  the dependencies the Step needs (per the operation_
  journal's `input` field). A test asserts the
  selected Step is the only one that ran.
- B4.5: a single MCP tool `studio.agent(action, ...)`
  replaces any per-step tool. The action is gated by
  the `AgentCapability` model. A test asserts the
  per-step tool is NOT registered when the capability-
  driven MCP is wired.
- B4.6: the `AgentRequestPort` inspects the request
  metadata + any log fields for adversarial instructions
  (e.g. "ignore previous instructions",
  "override safety", "return success regardless").
  A test asserts the port refuses a request whose
  metadata contains the trigger phrase and returns
  `Refused("prompt injection suspected")`.

## Applicable checks

- `./gradlew check` returns BUILD SUCCESSFUL.
- AAT-MCP-007..008 (MCP discoverability + parity).
- AAT-DUR-006 (run durability under mutation).
- AAT-SEC-008..010 (security hardening under agent
  workflow: prompt injection, capability bypass,
  mutation-driven false PASS).
- AAT-API-005..008 (API contract tests for the new
  endpoints).

## Tasks

- [ ] **B4.0** — agent capability model + ports.
  `AgentCapability` enum (COMPARE / DIFF / PROMOTE /
  SELECT / MUTATE) + `AgentCapabilityPort` + default
  impl driven by `STUDIO_AGENT_CAPS` env var
  (default: all disabled). The opt-in posture is the
  honesty layer for the rest of B4.
- [ ] **B4.1** — `studio.runs.compare` MCP tool.
  Typed `RunComparison` with diffs in
  `runtimeVersion`, `pluginSetDigest`,
  `environmentProfile`. Two runs with the same
  profile produce `equal = true`; different profiles
  produce `equal = false` + a clear reason.
- [ ] **B4.2** — snapshot variants + diff.
  `studio.snapshots.diff(snapshotA, snapshotB)`
  returns a typed `SnapshotDiff` with `linesAdded`,
  `linesRemoved`, `linesChanged`. The diff is
  deterministic.
- [ ] **B4.3** — promote snapshot. Explicit user
  action `studio.snapshots.promote(variantId)` that
  creates a new canonical snapshot from a variant.
  The variant is never auto-promoted; the operator
  must call this tool. The promotion creates a new
  snapshot row with the variant's bytes + an
  operator note.
- [ ] **B4.4** — Run Selection (Step contextualized).
  `studio.runs.select(runId, stepOpId)` reconstructs
  a legal pipeline that runs only the selected Step
  with its declared inputs. The selected Step is
  the only one that ran; the test asserts no other
  Step fired.
- [ ] **B4.5** — capability-driven MCP. A single MCP
  tool `studio.agent(action, ...)` replaces any
  per-step tool. The action is gated by the
  `AgentCapability` model. The per-step tool is NOT
  registered when the capability-driven MCP is wired.
- [ ] **B4.6** — prompt-injection guard + mutation
  control. The `AgentRequestPort` inspects the
  request metadata + log fields for adversarial
  instructions and refuses the request. A test
  asserts the port refuses a request whose metadata
  contains the trigger phrase "ignore previous
  instructions" and returns `Refused("prompt
  injection suspected")`.
- [ ] **B4.7** — B4 release receipt + tag
  v0.5.0-beta.1 (operator authorization required for
  the push + tag per D-007).

## Open risks

- **Capability opt-in is the default posture.** A
  production deployment that forgets to set
  `STUDIO_AGENT_CAPS` will have all agent tools
  disabled. The B4.0 test asserts the default is
  "all disabled" so the posture is explicit; a
  follow-up doc change in the release notes must
  flag this for operators.
- **Snapshot diff is line-based.** The default diff
  is a line-based LCS (longest common subsequence).
  This is not a perfect diff for refactors (a single
  rename produces many "removed" + "added" lines), but
  it is deterministic and the agent can post-process.
  A future S7 pass could add a structural diff
  (AST-based).
- **Run Selection reconstruction.** The
  reconstruction reads the operation_journal's
  `input` field for the selected Step and builds a
  pipeline that supplies those inputs. The
  reconstruction is best-effort: a Step that depended
  on a runtime-evaluated value cannot be reconstructed
  offline. The B4.4 test asserts the reconstruction
  succeeds for the common case (a Step with declared
  inputs) and surfaces `Unknown("cannot reconstruct
  offline")` otherwise.
- **Prompt-injection guard is heuristic.** A
  well-crafted adversarial instruction that doesn't
  match the trigger phrase would bypass the guard.
  The B4.6 test pins the contract for the trigger
  phrase; a future S7 pass could add an embedding-
  based detector.

## Next step

B4.0. The capability model is the spine: every other
B4 sub-task reads from the port to decide whether the
agent's request is allowed. The default posture is
"all disabled" — the operator must opt in via
`STUDIO_AGENT_CAPS`.
