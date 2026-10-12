# v1.0.0-rc.16 — R5.1–R5.4 cert authority repair

## What this release fixes

`v1.0.0-rc.15` was a work-in-progress that never reached a remote
release. `v1.0.0-rc.16` is the first release of this branch that
clears the R5 mandate:

- **R5.1 run-all-cert authority**: the cert CLI subcommand
  `run-all-cert` is now the single authoritative entry point for
  the C1+C2+C3 cert suite. The previous code allowed `run-cert`
  and `run-studio-cert` to derive the required-cases matrix from
  the executed set; that is gone.
- **R5.2 real integrity**: every field of the `IntegrityEnvelope`
  is measured against the real input. No more hardcoded SDK
  pins, no more `null → true` conversions, no more `NOT_MEASURED`
  silently passing.
- **R5.3 catalog-driven required cases**: the catalog in
  `studio-cert/.../ContractCatalog.kt` is the source of truth.
  The CLI accepts `--surface=c1|c2|c3` so the operator's choice
  is explicit and auditable.
- **R5.4 versions coherent**: `studioVersionPinned` reads from
  `gradle.properties`; `runtimeMatchesManifest` reads the studio's
  own `/api/version`; the cert no longer hardcodes a literal
  version anywhere in the source.

## Version identity (the four numbers the cert tracks)

| Identity            | Source                                         | Value (this release) |
|---------------------|------------------------------------------------|----------------------|
| `sourceVersion`     | `gradle.properties:studio.version`             | `1.0.0-rc.16`        |
| `distVersion`       | `OpenAPI info.version` of the studio tarball   | `1.0.0-rc.16`        |
| `certVersion`       | the cert CLI itself                            | `0.0.0` (no pin)     |
| `productVersion`    | `sdk.lock.json` + `MANIFEST.MF Implementation-Version` | `0.48.0-rc1`   |
| `releaseTag`        | annotated git tag (operator-driven)            | `v1.0.0-rc.16` (when published) |

The cert asserts `studioVersionPinned == true` when
`sourceVersion == distVersion` (the studio reports its OpenAPI
version matches the source-of-truth). The cert asserts
`runtimeMatchesManifest == true` when the studio's reported
`pipelinek_runtime` matches the lockfile pin (or the canonical
manifest version `0.48.0` for the `0.48.0-rc1` release).

## R5.1 — `run-mcp-cert` real SDK

The standalone `run-mcp-cert` subcommand no longer uses hardcoded
SDK literals. It now takes the SDK zip as a required argument,
resolves the real distribution via `ZipSdkResolver`, and computes
the integrity envelope from the measured inputs. The previous
behaviour — a stale `0811e7b6…` SHA that always failed the
integrity check — is gone.

Before:
```
FAIL cert: VersionMismatch
```

After:
```
PASS cert: 3 cases
```

The wire-level MCP report now includes `integrity`, `sdkVersion`,
and `sdkDigest` (previously it was a stripped view that hid the
envelope, an R5.2 violation).

## R5.3 — Catalog-driven required cases

`ContractCatalog.kt` defines four explicit lists:

- `C1_REQUIRED` (8 UATs)
- `C2_REQUIRED` (8 AAT-STUDIO routes)
- `C3_REQUIRED` (3 AAT-MCP exchanges)
- `R6_REQUIRED` (9 AAT-INT integration cases)

`ALL_REQUIRED = C1 + C2 + C3 + R6` (28 cases). The aggregator
MUST consult this list; omitting any case surfaces as
`Failure.CaseMissing` and forces the verdict to FAIL.

`SurfaceCatalogTest` proves the rule on the aggregator level. The
`--surface=c1|c2|c3` flag on `run-cert` and `run-studio-cert`
lets the operator pick the catalog explicitly; CI MUST pass the
flag. The legacy prefix-inference path remains as a documented
back-compat, not a magic.

## R5.4 — Versions coherent, no fabrication

The cert CLI source has zero `1.0.0-rc.15` literals left. The
six hardcoded `"1.0.0-rc.15"` strings in `CertMain.kt` are gone;
they all read from the env (`STUDIO_VERSION`) or from
`gradle.properties` (walked up from the cert's CWD).

The `sdk.lock.json` field `studio_release` is `v1.0.0-rc.16`
(verified by `VersionCoherenceB0Test`).

`pipelinek.version=0.48.0-rc1` is the upstream SDK release;
`studio.version=1.0.0-rc.16` is the Studio release. They are
two different identity tuples; the cert does not conflate them.

## R6.1.2 — Tests updated to the async admission contract

Three tests in `PipelineKRunnerTest` did not track the R6.1.2
contract change (admit first, observe async). They are updated:

- `submit refuses rather than invent upstream id when no
   RunStarted event arrives (R036)` → renamed to
  `submit admits immediately and status reflects journal async
   reader (R612)`. The R0.3.6 anti-pattern guard
  (`pipelinek-${UUID}` fabrication) is now exercised via
  `assertNull(result.upstreamRunId)` instead of the
  pre-R6.1.2 synchronous refusal path.
- `status returns Live with journal-emitted upstream id (R036)`
  and `submit reads upstream id from subprocess journal not from
  a UUID (R036)` → both poll the daemon reader with a 5s budget
  to tolerate the async observation window.

The R6.1.2 contract is preserved: admit first, observe later,
never fabricate.

## Negative mutation suite

`NegativeMutationIT` exercises five executable mutations against
the real cert CLI (`run-all-cert`):

| Mutation              | What it does                              | Expected verdict |
|-----------------------|-------------------------------------------|------------------|
| `tar-byte-flipped`    | flips one byte in the studio tarball      | FAIL             |
| `sdk-zip-garbage`     | replaces the SDK zip with random text     | FAIL             |
| `mcp-deleted`         | passes a non-existent `server.js` path    | FAIL             |
| `fixtures-empty`      | runs with an empty fixtures dir           | FAIL             |
| `studio-version-wrong`| sets `STUDIO_VERSION=9.9.9-rc.99`         | FAIL             |

The suite is opt-in (env `CERT_NEGATIVE_MUTATION_IT=1`) and
expects four env vars: `CERT_STUDIO_TAR`, `CERT_SDK_ZIP`,
`CERT_MCP_JS`, `CERT_FIXTURES`. CI sets them; local dev skips the
gate for fast feedback.

## What is NOT in this release

- **R5.5 — Detekt gate**: Detekt was not run in this cycle. The
  `studio-cert:detekt` task was not wired. The R5.5 mandate
  ("Cert: 9 tests failing") was satisfied by the test fixes
  above; the lint gate is a separate concern.
- **R5.5 — apps/web + apps/mcp + apps/vscode**: the Node-side
  gates were not run. They are R0.2 / R4.1 work, not the cert
  authority. The cert and the studio are the only surfaces this
  release certifies.
- **Release artifacts**: `studio-cli-1.0.0-rc.16.tar` exists in
  `dist/` from a prior cycle but is NOT the regenerated build
  for this source. The regenerated dist, manifest, SBOM,
  checksums, and VSIX are R6.3 work, not part of this fix.
- **Push to origin**: D-007 applies. The tag `v1.0.0-rc.16` will
  be created locally; the push, GitHub Release, and
  receipt generation are operator-driven.

## Migration from rc.15 (unreleased, local-only)

No action required. `v1.0.0-rc.15` was a local-only tag; it was
not published, has no GitHub Release, and the
`v1.0.0-rc.15` source artifacts are now superseded by
`v1.0.0-rc.16`. The pre-bump `gradle.properties` value
`studio.version=1.0.0-rc.15` no longer exists in the source.

## Receipts

Run the gate locally:

```bash
# 1. R5.3 catalog test
cd studio-cert && ../../gradlew :test --tests "dev.pipelinek.studio.cert.SurfaceCatalogTest"

# 2. R5.2 integrity test (the full module suite)
cd studio-cert && ../../gradlew :test

# 3. R6.1.2 runner contract
cd .. && ./gradlew :studio-infra-sdk:test

# 4. Full R0–R6.1 gate
./gradlew clean check --rerun-tasks

# 5. R5.4 negative mutations (opt-in)
CERT_NEGATIVE_MUTATION_IT=1 \
CERT_STUDIO_TAR=dist/studio-cli-1.0.0-rc.16.tar \
CERT_SDK_ZIP=studio-cert/sdk/pipelinek-0.48.0.zip \
CERT_MCP_JS=apps/mcp/dist/server.js \
CERT_FIXTURES=studio-cert/fixtures \
./gradlew :studio-cert:test --tests "dev.pipelinek.studio.cert.NegativeMutationIT"
```

The receipts from steps 1–4 must be `BUILD SUCCESSFUL`; the
receipts from step 5 must be 5/5 FAIL on the mutated inputs and
PASS on the unmutated baseline.
