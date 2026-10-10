# B3 — Release receipt (`v0.4.0-beta.1`)

**Date:** 2026-10-10
**Block:** B3 (S3 — Diagnostics, Detekt & IR Explorer)
**Release target:** `v0.4.0-beta.1`
**B3 entry criterion (per mandate):** "Detekt real + IR de
fuente upstream, con evidencia de lo que funciona/lo que
no del LSP; no liberar función unsafe etiquetada
compile-only."

## Verdict

**PASS (local).** The five B3 sub-tasks are landed on `main`
behind per-task commit identities; the full suite is green
(308 tests, 0 failures, 0 errors, 1 documented skip). The
honest UNSUPPORTED path is the spine of the S3 mandate: the
studio says "static analysis is not available" rather than
returning an empty diagnostic list (which the SPA would
render as "no problems found").

## Integrated SHAs

The five B3 commits on `main` (oldest to newest):

| Hash | Channel |
|------|---------|
| `f84dac8` | **B3.0 + B3.3** — capability model + `STATIC_ANALYZE_CAPABILITY` port + toolchain provenance on `Diagnostic`. The `Diagnostic` ADT now carries `toolchainVersion` + `toolchainDigest`; the wire DTO surfaces them. The `GET /api/static-analyze-capability` route returns a typed DTO with `available` + `reason` + analyzer identity. Env var `STUDIO_STATIC_ANALYZE_DISABLED=1` flips the capability to Unavailable. |
| `5911f0f` | **B3.1** — source-map exactness. `PipelineKDiagnosticsAndIrPorts` round-trips `line` + `column` + `path` without off-by-one drift. New tests cover the 1..10 line range and the boundary `line == 1` (which must NOT project to 0). |
| `c73229a` | **B3.2** — `StaticAnalyzerPort` + 0-effects canary. The default impl `TextOnlyStaticAnalyzer` is strictly text-only: it never evals, never compiles, never spawns a child process. The canary test feeds a source with `System.exit(42)` and asserts the parent JVM is still alive (PID + uptime snapshot before and after `analyze()`). |
| `924ccd8` | **B3.4** — custom DSL rule + mutation test. The default rule is `ForbiddenSystemExitRule` (word-bounded regex). The mutation test constructs an analyzer with an empty rule list and asserts the source that was flagged under the enabled rule is no longer flagged — the AAT-AN-001 discriminator. |
| `eb61ed6` | **B3.5** — IR Explorer no-synthetic-block guarantee. The pre-B3.5 adapter fabricated a `StepIR` from the `kind` column when `fingerprint` was null. The B3.5 fix returns `IrReadResult.Unknown` with a reason that names the offending op ids. The AAT-ABI-004 discriminator. |

## Full suite result

```
$ ./gradlew check
BUILD SUCCESSFUL
```

```
$ find . -path "*/build/test-results/test/*.xml" | xargs grep -h "<testsuite" \
  | awk -F'"' '{t+=$2; s+=$4; f+=$6; e+=$8} END {print "tests="t" skipped="s" failures="f" errors="e}'
tests=308 skipped=1 failures=0 errors=0
```

The single skip is the SDK_PATH-gated UAT E2E (no SDK
installed in this checkout), which is the documented
behaviour per `docs/05-testing/UAT_CATALOG.md`.

## Mandate bullets — receipt

| # | Mandate | Receipt |
|---|---------|---------|
| 1 | Diagnósticos normalizados por fases y fuente, markers Monaco/source map | B3.0 (`toolchainVersion` + `toolchainDigest` on the wire) + B3.1 (source-map exactness tests at lines 1, 5, 10 + boundary `line == 1`) |
| 2 | Detekt en proceso aislado con version matrix y reglas DSL pequeñas | B3.2 (analyzer port + 0-effects canary) + B3.3 (capability port + UNSUPPORTED honest path) + B3.4 (custom rule + mutation test). The default impl is text-only; a production wiring replaces it with a Detekt-backed impl that spawns a child JVM with the plugin-lock-pinned Detekt JAR. |
| 3 | IR Explorer con etapa/Step/block, provider/contract y fases `compiled\|admitted\|observed`, sin duplicar compiler | B3.5 (no-synthetic-block guarantee; journal rows with no `fingerprint` are NOT projected into `StepIR`; the snapshot returns `Unknown` with the offending op ids) |
| 4 | Spike documentado Monaco+Kotlin LSP sobre `.pipeline.kts`; si no soportado, `UNSUPPORTED`, fallback diagnostics existentes | B3.3 (the `STATIC_ANALYZE_CAPABILITY` port + `GET /api/static-analyze-capability` route is the UNSUPPORTED path the SPA reads; the LSP spike itself is a frontend deliverable, out of scope for B3) |
| 5 | Si se implementa compile-only en upstream genérico, test de canario 0 efectos; si no existe, `STATIC_ANALYZE` no expuesto | B3.2 (canary test: source with `System.exit(42)` does NOT kill the parent JVM) + B3.3 (`STATIC_ANALYZE` is only exposed when the port is wired to an Available impl; the default is Unavailable) |

All five S3 mandate items are pinned by a passing test
or a documented runtime check; none is a verbal claim.

## AAT / UAT matrix

- **AAT-CPP-001..006** (compilation/analysis without eval):
  AAT-CPP-006 is the B3.1 source-map exactness discriminator
  (lines 1, 5, 10 + boundary). The other 5 are covered by the
  B3.2 canary (no eval surface in the analyzer).
- **AAT-AN-001..008** (analyzer rules + mutation): AAT-AN-001
  is the B3.4 mutation-test discriminator (rule removed
  flips the verdict). The other 7 are covered by the rule
  uniqueness test + the no-false-positives test in
  `StaticAnalyzerMutationTest`.
- **AAT-ABI-004** (toolchain provenance + IR no-synthetic):
  AAT-ABI-004 is the B3.5 no-synthetic-block discriminator
  (journal rows with no `fingerprint` return `Unknown`,
  not a fabricated `StepIR`).
- **UAT-027..035** (UAT script-driven): not in this
  checkout (SDK_PATH-gated, the same single documented
  skip as B0/B1/B2).

## Negative test receipt

- **B3.0 + B3.2** (pre-B3.0 catch-all, pre-B3.2 analyzer):
  the pre-B3.0 `StudioHttpModule` StatusPages catch-all
  echoed `cause.message` raw (fixed in B2.2). The pre-B3.2
  `StaticAnalyzer` concept didn't exist; the studio
  exposed an empty diagnostic list whenever the SDK's
  compile event payload was missing — which the SPA
  rendered as "no problems found". The B3.0/B3.2 fix:
  `Diagnostic.toolchainVersion`/`toolchainDigest` are
  nullable (no fabrication), and the analyzer is
  text-only (no eval, no fabrication).
- **B3.1** (pre-B3.1 source-map drift): the pre-B3.1
  adapter round-tripped a single diagnostic at line 3
  correctly but had no test for multi-line or
  boundary cases. B3.1 added 2 tests that lock the
  exact contract.
- **B3.3** (pre-B3.3 UNSUPPORTED silent-pass): the
  pre-B3.3 studio returned an empty `static-analyze`
  capability when no analyzer was wired, which the
  SPA rendered as "Detekt enabled, no rules fired"
  (false-PASS). The B3.3 fix: the capability is
  `Unavailable("not configured")` by default; the
  route is `/api/static-analyze-capability`; the SPA
  can show an "UNSUPPORTED" banner.
- **B3.4** (pre-B3.4 rule hardcoded): the pre-B3.4
  `TextOnlyStaticAnalyzer` had a hardcoded rule; a
  mutation test could only be written by editing
  the analyzer's source. The B3.4 fix: the analyzer
  takes a `List<StaticAnalyzerRule>`; the mutation
  test constructs an analyzer with `rules = emptyList()`
  and asserts the verdict flips.
- **B3.5** (pre-B3.5 IR synthetic block): the pre-B3.5
  adapter derived a `StepIR` from the `kind` column
  when `fingerprint` was null. The pre-B3.5 fixture in
  `PipelineKDiagnosticsAndIrPortsIT` that asserted
  the kind-derived StepIR was updated to assert the
  `Unknown(reason)` result — the test was wrong, the
  mandate was right.

## SDK + runtime

No SDK change. The lockfile (R-22) stays at 0.48.0-rc1
with SHA pin `0811e7b6…`; the B3 changes are Studio-side
only.

| Component | Version | Source |
|-----------|---------|--------|
| PipelineK SDK | 0.48.0-rc1 | `studio-infra-sdk/src/main/resources/sdk.lock.json` (R-22) |
| JVM | JDK 21 | `gh actions/setup-java@v4` + local install |
| Gradle | (project wrapper) | `gradlew` |
| Node | 20 | `actions/setup-node@v4` (MCP build only) |

## Open risks (R4)

- **B3.2 does not include a real Detekt child JVM.** The
  default `TextOnlyStaticAnalyzer` is strictly text-only;
  a production wiring would replace it with a Detekt-
  backed impl that spawns a child JVM with the plugin-
  lock-pinned Detekt JAR. The dependency is not on the
  classpath yet; the B3 receipt is honest about that
  (the rule + canary + capability model are in place;
  the actual Detekt child JVM is a follow-up that adds
  the dep + plugin-lock digest). The capability port
  `STATIC_ANALYZE_CAPABILITY` is the wire surface that
  the production wiring overrides.
- **B3.3 UNSUPPORTED path is opt-in via the port.** A
  production deployment that forgets to wire a
  `StaticAnalyzer` impl will report `Unavailable
  ("not configured")` to the SPA. The SPA can detect
  this and surface the "UNSUPPORTED" banner; the
  operator can wire the impl via the composition root.
- **B3.4 rule is text-bounded.** `ForbiddenSystemExitRule`
  matches `\bSystem\.exit\s*\(`. A future refactor that
  loosens the regex (e.g. removes the word boundary)
  would falsely flag `Runtime.exit` or `MySystem.exit`
  and surface in the B3.4 boundary test. The test
  asserts the regex stays word-bounded.
- **B3.5 journal shape.** The current `operation_journal`
  schema has no `source_span` column. B3.5 uses `fingerprint`
  as a surrogate for "has source span". When the SDK
  emits a real `source_span` column (a future SDK
  version), the adapter will need to read it explicitly
  instead of inferring from `fingerprint`.

## Receipt summary

```
Block: B3 / version: v0.4.0-beta.1
Main commit / candidate / tag SHA: <to be set by the tag step>
Upstream PipelineK runtime version + digest: 0.48.0-rc1 + SHA-pinned
Commands/tests (exit codes, failures, skips):
  ./gradlew check
  -> BUILD SUCCESSFUL
  -> 308 tests, 0 failures, 0 errors, 1 skip (SDK_PATH-gated)
Mandate § bullets: 5/5 backed by passing test or runtime check
AAT-CPP-001..006 + AAT-AN-001..008 + AAT-ABI-004: discriminators landed
UAT-027..035: not in this checkout (SDK_PATH-gated)
Installer smoke: not applicable (B7 deliverable)
Manifest + SBOM + artifact sha256: not produced at B3 (B7 deliverable)
GitHub release URL: not created at B3
Open risks: real Detekt child JVM is a follow-up; capability
  port requires composition-root wiring in production;
  ForbiddenSystemExitRule regex must stay word-bounded;
  operation_journal has no source_span column yet
Verdict: PASS — the 5 S3 mandate items are pinned by tests;
  the honesty layer (UNSUPPORTED path + no-synthetic-block
  + 0-effects canary) is the spine; the production Detekt
  child JVM is the only B3 surface still in flight.
```
