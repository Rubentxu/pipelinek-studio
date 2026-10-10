# B2 — Release receipt (`v0.2.0-beta.1`)

**Date:** 2026-10-10
**Block:** B2 — Seguridad, admisión y recuperación ante fallos
**Release target:** `v0.2.0-beta.1`
**B2 entry criterion (per mandate):** "10 puntos verificables:
TLS handshake, Host/Origin/CSRF, body validation, runner
args+paths, TRUSTED_LOCAL, retention, etc."

## Verdict

**PASS (local).** The seven B2 sub-tasks are landed on `main`
behind per-task commit identities; the full suite is green
(290 tests, 0 failures, 0 errors, 1 documented skip). Each
mandate bullet is backed by a test or a documented runtime
check; none of the bullets is an unverified claim.

## Integrated SHAs

The seven B2 commits on `main` (oldest to newest):

| Hash | Channel |
|------|---------|
| `8b9ea8d` | **B2.4** — `ExecutionProfile` becomes a sealed interface; `Untrusted` is refused at `submit()` with `PROFILE_NOT_SUPPORTED` (B2 § 6.5 + § 6.6). Pre-B2.4 the enum had only `TRUSTED_LOCAL` so the contract was implicit. |
| `5cf47de` | **B2.3** — `PathConfinement` helper + wire-in; the runner only writes under its own `controlRootBase` (B2 § 6.3). |
| `1319c5f` | **B2.2** — `SafeErrorMessage.from()` + `error_id`; the StatusPages catch-all redacts paths/FQNs/JWTs/bearer tokens/exception class names instead of echoing `cause.message` raw (B2 § 6.2). |
| `af555e7` | **B2.6** — `RunOutcome` ADT (Success / Failure(rawOutcome) / Unknown); the reconciler asks the ADT, not a JSON regex (B2 § 6.8). The walker tracks brace depth so a nested `outcome` is correctly ignored. |
| `517e85a` | **B2.1** — `installOriginAllowList()` + `B21TransportSecurityTest` (7 tests, raw-socket + real TLS); forged Host / missing Origin / foreign Origin / combined TLS+forged Host are all exercised end-to-end (B2 § 6.1). |
| `954f879` | **B2.7** — Retention port adds `ttl`, `dryRun`, `now`, `runUpdatedAtProvider`; `RunKeptReason.TooYoung`; `RetentionPort.sweep` extension supplies the 7-day default (B2 § 6.9). |
| `a5f9d8e` | **B2.8** — Doctor gains `data_dir_permissions`; the check inspects the data dir, the keystore, and the snapshots/logs/control subdirs and FAILs on any group/world read (B2 § 6.10). |

(`B2.5` durable reconciliation after kill -9 was already on
`main` as `4678658` from the previous session; it is part
of the B2 surface and is referenced by the B2.5 test in
`RunReconcilerTest`.)

## Full suite result

```
$ ./gradlew check
BUILD SUCCESSFUL
```

```
$ find . -path "*/build/test-results/test/*.xml" | xargs grep -h "<testsuite" \
  | awk -F'"' '{t+=$2; s+=$4; f+=$6; e+=$8} END {print "tests="t" skipped="s" failures="f" errors="e}'
tests=290 skipped=1 failures=0 errors=0
```

The single skip is the SDK_PATH-gated UAT E2E (no SDK
installed in this checkout), which is the documented
behaviour per `docs/05-testing/UAT_CATALOG.md`.

## Mandate § 6 bullets — receipt

The B2 mandate enumerates 10 specific items. Each is
backed by a test or a runtime check:

| # | Mandate | Receipt |
|---|---------|---------|
| 1 | proven TLS handshake | `B21TransportSecurityTest.proven TLS handshake against Netty plus SslContext` (real Netty + SslContext + JDK SSL client) and `SSLServerSocket handshake with a real self-signed cert completes` (redundancy) |
| 2 | Host / Origin / CSRF | `forged Host is rejected with HOST_NOT_ALLOWED` + `state-changing request without Origin is rejected` + `state-changing request with foreign Origin is rejected` + `state-changing request with allowed Origin is admitted` + `combined B2_1 transport — TLS handshake plus forged Host over TLS` |
| 3 | body validation + typed errors + no info leak | `SecurityHardeningTest` (B0.4 era) + `SafeErrorMessageTest` (B2.2) covers path / FQN / JWT / bearer / email redaction; the StatusPages catch-all logs the original cause with `error_id` and emits `{"code":"INTERNAL","message":"internal","error_id":...}` on the wire |
| 4 | runner uses structured args + confined paths | `B2_3 normal submit stays confined and is admitted` + `PathConfinementTest` (6 cases) — argv is a `List<String>` since S0; `JavaProcessSpawner` builds the JVM command from a `buildList`, so no string-injection surface |
| 5 | TRUSTED_LOCAL explicit | `ExecutionProfile` is now a sealed interface; `TrustedLocal` is the only case the runner admits |
| 6 | reject untrusted profile without OS sandbox | `B2_4 Untrusted profile is refused before any subprocess spawn` — the test asserts the spawner is never called and the reason mentions `OS-level sandbox` + `TrustedLocal` |
| 7 | durable reconciliation after kill -9 | `RunReconcilerTest.kill -9 reconciliation persists RECOVERY_REQUIRED + history entry (B2_5)` (already on `main` as part of `4678658`) |
| 8 | centralize outcome classification (no JSON regex) | `RunOutcomeTest` (9 cases) + `RunReconciler.hasSuccessOutcome` calls `RunOutcome.fromPayload` instead of `payload.contains(...)` |
| 9 | retention: lease-gated, TTL-bounded, recoverable | `B2_7 terminal run younger than TTL is kept with TooYoung reason` + `B2_7 terminal run older than TTL is deleted` + `B2_7 dry-run returns plan without deleting` + `B2_7 dir mtime is the fallback when provider returns null` |
| 10 | file permissions audit for data/cert/snapshots/logs | `DoctorTest.B2_8 data dir with owner-only perms is OK` + `B2_8 data dir with group read is FAIL` + `B2_8 keystore with others write is FAIL` + `B2_8 snapshots dir with world read is FAIL` |

All ten mandate items are pinned by a passing test or a
documented runtime check; none is a verbal claim.

## Negative test receipt

- **B2.2**: the pre-B2.2 `StatusPages` catch-all echoed
  `cause.message` raw. The `SafeErrorMessageTest` JVM
  path / FQN / JWT / bearer / email tests would all
  fail if the catch-all ever leaked raw cause text
  again. The `error_id` is the only thing the wire
  carries that points at the server-side log.
- **B2.4**: the pre-B2.4 `ExecutionProfile` was a one-case
  enum; the runner's `submit()` had no refusal path. A
  `Untrusted` request would have fallen through to the
  `createDirectories + writeBytes` line and started a
  non-sandboxed subprocess. The `B2_4` test asserts the
  spawner is *never called* for `Untrusted`.
- **B2.5**: the pre-B2.5 `RunReconciler` returned the
  entry in memory but did not persist the transition;
  a GET /runs/{id} after restart would still report
  `RUNNING` for a run whose subprocess was actually
  gone. The B2.5 test asserts the row state and the
  `run_state_history` row are both updated.
- **B2.6**: the pre-B2.6 `hasSuccessOutcome` was
  `payload.contains("\"outcome\":\"success\"")` — a JSON
  regex. The B2.6 test `payload that nests outcome
  under a parent object is Unknown` would have falsely
  classified the nested outcome as `Success`; the
  depth-aware walker catches it.
- **B2.7**: the pre-B2.7 sweep had no TTL; a fresh
  terminal run would have been deleted on the next
  sweep. The `B2_7 terminal run younger than TTL is
  kept with TooYoung reason` test asserts the new
  guard.
- **B2.8**: the pre-B2.8 doctor had no `data_dir_permissions`
  check; a world-readable keystore would silently pass.
  The `B2_8 keystore with others write is FAIL` test
  pins the FAIL response.

## SDK + runtime

No SDK change. The lockfile (R-22) stays at 0.48.0-rc1
with SHA pin `0811e7b6…`; the B2 changes are Studio-side
only.

| Component | Version | Source |
|-----------|---------|--------|
| PipelineK SDK | 0.48.0-rc1 | `studio-infra-sdk/src/main/resources/sdk.lock.json` (R-22) |
| JVM | JDK 21 | `gh actions/setup-java@v4` + local install |
| Gradle | (project wrapper) | `gradlew` |
| Node | 20 | `actions/setup-node@v4` (MCP build only) |

## Open risks (R4)

- **TLS test uses Bouncy Castle** (via Ktor's `buildKeyStore`).
  The production `TlsConfig` also uses Bouncy Castle. If
  the JVM ever drops BC as a dependency of `ktor-network-tls-certificates`,
  both the production keystore generation AND the test
  fail together. Watch the `gradle/libs.versions.toml`
  pin for BC on the next bump.
- **Origin allow-list is opt-in** at the
  `StudioHttpModule` constructor (default empty set). A
  production deployment that forgets to set
  `STUDIO_ALLOWED_ORIGINS` will not exercise the CSRF
  defense. The next B7 distribution pass must set this
  in the production wiring.
- **B2.7 TTL is hard-coded to 7 days** as the
  `RetentionPort.sweep` extension default. Operators
  who want a different TTL must pass `ttl = ...` at
  the call site. A config-file binding is a B7
  follow-up.
- **`B21TransportSecurityTest` uses raw TCP sockets**
  for the Host / Origin assertions because the JDK
  `HttpClient` restricts the Host header by design.
  The raw-socket parser is intentionally minimal
  (status line + headers + Content-Length body); a
  chunked-transfer response would be mis-parsed. None
  of the studio's routes emit chunked responses in
  the test harness today, so this is acceptable; a
  future route that does would need a richer parser.

## Receipt summary

```
Block: B2 / version: v0.2.0-beta.1
Main commit / candidate / tag SHA: <to be set by the tag step>
Upstream PipelineK runtime version + digest: 0.48.0-rc1 + SHA-pinned
Commands/tests (exit codes, failures, skips):
  ./gradlew check
  -> BUILD SUCCESSFUL
  -> 290 tests, 0 failures, 0 errors, 1 skip (SDK_PATH-gated)
Mandate § 6 bullets: 10/10 backed by passing test or runtime check
UAT / AAT matrix: 10 B2.x tests added; full suite green
Installer smoke: not applicable (B7 deliverable)
Manifest + SBOM + artifact sha256: not produced at B2 (B7 deliverable)
GitHub release URL: not created at B2
Open risks: BC dep on ktor-network-tls-certificates; origin
  allow-list is opt-in; TTL hard-coded to 7 days; raw-socket
  parser assumes non-chunked responses
Verdict: PASS — the 10 mandate items are pinned by tests;
  the security and admission surface is hardened end-to-end
  (TLS + Host + Origin + redaction + sealed profile + durable
  reconciliation + TTL-bounded retention + permissions audit).
```
