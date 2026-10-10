# B3 — Diagnostics, Detekt & IR Explorer (`v0.4.0-beta.1`)

**Date:** 2026-10-10
**Block:** B3 (S3 — Diagnósticos + IR Explorer)
**Target:** `v0.4.0-beta.1`
**Mandate:** `ROADMAP.md` §"S3 — Diagnostics, Detekt e IR Explorer".

## Problem

The studio already exposes diagnostic + IR surfaces (ports,
HTTP routes, adapter) from earlier blocks. What is missing
per the S3 mandate is the **honesty layer** that prevents
the studio from claiming static analysis it cannot do:

1. The `Diagnostic` ADT has no toolchain provenance — the
   version and digest of the analyzer that produced the
   diagnostic is not carried in the payload, so the SPA
   cannot tell a Detekt-from-upstream-0.48.0-rc1 diagnostic
   from a Detekt-from-upstream-0.47.0 diagnostic.
2. There is no `STATIC_ANALYZE` capability port — when the
   upstream SDK can't safely do static analysis, the studio
   either refuses silently or returns an empty diagnostic
   list. Both paths look like "no problems" to the SPA.
3. There is no "0 effects" canary — a malicious script that
   runs `System.exit(42)` in its top level is treated the
   same as a benign script. The Detekt process must be
   proven to NOT execute the script.
4. There is no custom DSL Detekt rule + mutation test — the
   "mutation tests" deliverable is empty.
5. There is no source-map exactness test — the
   `line`/`column` in the diagnostic is not asserted to
   point at the actual offending line in the source.
6. The IR Explorer can fabricate blocks when the source is
   missing. The mandate says "sin duplicar compiler" —
   the IR must surface honest `Unknown` when the journal
   has a step name without a corresponding source span.

## Why

The B3 mandate is the "honest diagnostics" block. Every
upstream that bolts static analysis on top of an eval
engine has to face the question: "did the analyzer
actually run the script?" If yes, the analyzer is an
eval surface; if no, the analyzer must prove it. The
B3 deliverables pin that property with a test
(`canary 0 efectos`), the capability port, and the
custom rule + mutation test.

## Scope

- **In scope**: B3.0 capability model, B3.1 source-map
  exactness, B3.2 Detekt in-process isolated with
  0-effects canary, B3.3 `STATIC_ANALYZE` capability port,
  B3.4 custom DSL Detekt rule + mutation test, B3.5
  IR Explorer no-synthetic-block, B3.6 release receipt.
- **Out of scope**: the Monaco + Kotlin LSP spike is a
  frontend deliverable. The backend exposes a
  `STATIC_ANALYZE_CAPABILITY` flag the frontend can read
  to decide whether to show an LSP-style marker overlay
  or fall back to the existing diagnostic table. The
  spike itself is not part of B3 — it's a follow-up
  that the B3.3 capability port enables.

## Constraints

- All tests pass before commit (290 → 290+ new).
- SDK stays at 0.48.0-rc1 (R-22 SHA-pinned).
- No AI attribution in commits; conventional commits.
- Tag `v0.4.0-beta.1` requires operator authorization
  per D-007 (no push to GitHub without explicit
  authorization). The release-receipt step is
  pre-authorized to write; the push + tag step is not.

## Acceptance

- B3.0: `Diagnostic` carries `toolchainVersion` +
  `toolchainDigest`; new `STATIC_ANALYZE_CAPABILITY` port
  exists; default impl is `Unavailable("not configured")`.
- B3.1: a test that loads a script with a syntax error on
  line 3, runs the static analyzer, and asserts the
  diagnostic's `line == 3` and `column` is within ±1 of
  the offending char.
- B3.2: a test that loads a script with `System.exit(42)`
  in its top level, runs the static analyzer, and
  asserts the JVM is still alive afterward (the
  analyzer is in a separate process / classloader).
- B3.3: a `STATIC_ANALYZE_CAPABILITY` port; when
  `STUDIO_STATIC_ANALYZE_DISABLED=1` is set the
  capability flips to `Unavailable("disabled by env")`;
  the `GET /api/static-analyze-capability` route
  returns 200 with `{available: false, reason: "..."}`.
- B3.4: at least one custom DSL Detekt rule that catches
  a real anti-pattern (e.g. `// DETEKT: forbidden-call`
  in the source) and a mutation test that asserts the
  rule fires for the positive case AND that disabling
  the rule (e.g. by deleting the registration) flips
  the verdict.
- B3.5: when a step name appears in the journal but
  has no corresponding source span, the IR returns
  `IR.Unknown(reason)` with the step name, not a
  fabricated `StepIR` block.
- B3.6: `docs/history/B3_RELEASE_RECEIPT.md` with the
  B3 commit SHAs, the 10 S3 mandate items, the test
  count, and the open risks.

## Applicable checks

- `STATIC_ANALYZE_CAPABILITY` env wiring.
- `STUDIO_DETEKT_PLUGIN_JAR` env wiring (default =
  empty; canary must pass with no Detekt).
- Detekt JAR plugin-lock digest (R-22).
- Full suite: `./gradlew check` returns BUILD SUCCESSFUL.
- AAT-CPP-001..006 (no script eval under static analyze).
- AAT-AN-001..008 (analyzer rules + mutation).
- AAT-ABI-004 (toolchain provenance in diagnostic).

## Tasks

- [ ] **B3.0** — capability model: toolchain provenance on
  `Diagnostic`; new `STATIC_ANALYZE_CAPABILITY` port;
  default impl `Unavailable("not configured")`.
- [ ] **B3.1** — source-map exactness: test that asserts
  the diagnostic's `line`/`column` points at the
  offending line in the source. The test is the
  discriminator for AAT-CPP-006.
- [ ] **B3.2** — Detekt in-process isolated: spawn a child
  JVM with bounded timeout, plugin-lock pinned
  version, prove "0 effects" canary. The test loads a
  script with `System.exit(42)` in its top level
  and asserts the JVM is still alive afterward.
- [ ] **B3.3** — `STATIC_ANALYZE_CAPABILITY` port +
  `GET /api/static-analyze-capability` route; env var
  `STUDIO_STATIC_ANALYZE_DISABLED=1` flips the
  capability to `Unavailable`. The route is the
  honest UNSUPPORTED path the SPA reads.
- [ ] **B3.4** — custom DSL Detekt rule + mutation
  test. At least one rule that catches a real
  anti-pattern; mutation that disables the rule
  flips the verdict. The test is the discriminator
  for AAT-AN-001.
- [ ] **B3.5** — IR Explorer no-synthetic-block: when
  a step name appears in the journal but has no
  source span, the IR returns `IR.Unknown(reason)`
  with the step name, not a fabricated `StepIR`
  block. The test is the discriminator for
  AAT-ABI-004.
- [ ] **B3.6** — B3 release receipt + tag v0.4.0-beta.1
  (operator authorization required for the push + tag
  per D-007).

## Open risks

- **Detekt JAR availability**: B3.2 needs a Detekt JAR on
  the test classpath. The studio does not currently
  depend on Detekt. Adding the dep + plugin-lock digest
  is part of B3.2; the dep version is the same as the
  studio's own detekt baseline (`io.gitlab.arturbosch.detekt:detekt-cli`).
- **Detekt child JVM cost**: each static-analyze call
  spawns a child JVM. The S3 mandate does not prescribe
  a perf budget. B3.2 will document the observed
  cold-start latency; if it exceeds 5s, B7 (distribution)
  will revisit.
- **Mutation test stability**: the mutation test depends
  on a stable rule registration. If the rule's
  signature drifts, the test will fail loudly — which
  is the correct behavior per "mutation must flip the
  verdict", but it means future Detekt upgrades need
  test maintenance.

## Next step

B3.0. Capability model + toolchain provenance on
`Diagnostic`. This is a small, mechanical change: add
two fields, write a test, update the HTTP DTO.
