# SDK Compatibility Matrix (R-04 + R-22)

**Audit reference**: R-04 and R-22 (2026-10-10 senior technical audit).
The studio was previously pinned to `pipelinek = "0.48.0-rc1"` by version
string only. A new SDK release could silently change the wire contract
(capability markers, event names, payload shapes) and the studio would
accept it because no SHA was recorded.

This document pins the SDK to a **SHA-256 fingerprint** so a drift
between the expected and the installed SDK surfaces as a mismatch in
`/api/version`'s `pipelinek_runtime_digest` field.

The companion **lockfile** at
`studio-infra-sdk/src/main/resources/sdk.lock.json` is the executable
form of the pin matrix. The studio probe loads it at boot, computes the
SHA-256 of the installed SDK jar, and refuses to admit runs if the
SHA does not match. See "How the lockfile is enforced" below.

## Pin matrix

| Studio version | SDK version | SDK SHA-256 (canonical jar) | Status |
|----------------|-------------|----------------------------|--------|
| v0.1.0-alpha.1 | 0.48.0-rc1 | (not pinned — see audit) | deprecated |
| v0.2.0-alpha.2 | 0.48.0-rc1 | `0811e7b60460e6b15a31cd3f312b1b5b3c36f8d1aa6dce7a7fd04193e362eace` | current |
| v0.3.0-alpha.x | (TBD) | (TBD) | planned |

The canonical jar is `pipeline-application-0.48.0.jar` in the SDK
distribution. Compute the SHA with:

```bash
sha256sum ~/.cache/pipelinek-studio/sdk/pipelinek-0.48.0/pipelinek-0.48.0/lib/pipeline-application-0.48.0.jar
```

## How the studio reports the SHA

`/api/version` returns:

```json
{
  "studio": "0.2.0-alpha.2",
  "pipelinek_runtime": "0.48.0",
  "pipelinek_runtime_digest": "0811e7b6...",
  "pipelinek_runtime_capabilities": ["compile", "eval", "durable", "run-cancel-partial"]
}
```

The `pipelinek_runtime_digest` field is the SHA-256 of the jar the
probe discovered at boot. Operators can compare this against the
expected SHA in the pin matrix above. A mismatch means the SDK was
upgraded without a corresponding studio release — proceed with
caution or roll the SDK back.

## Why version-only pin is not enough

`version.ref = "pipelinek"` in `gradle/libs.versions.toml` would only
work if the studio consumed the SDK as a Gradle dependency. The
studio currently consumes it via the `PIPELINEK_SDK_CLASSPATH` env
var (a list of jar paths), which the launcher reads and passes to
the spawned JVM. The `dev.pipelinek.studio.infra.sdk.PipelineKRunner`
spawns the SDK subprocess with this classpath; the
`PipelineKRuntimeProbe` then reads the jar's manifest to extract
`Implementation-Version` and computes the SHA-256 of the jar bytes.

Version strings are mutable; SHAs are not. The combination of the
two is the only honest way to assert "this studio was tested
against this SDK and no other".

## How the lockfile is enforced

`studio-infra-sdk/src/main/resources/sdk.lock.json` is a tiny JSON
file (no external dep — the SDK module ships a hand-rolled reader
to avoid pulling in a serialization library for one file) with one
entry per pinned SDK:

```json
{
  "format_version": 1,
  "pinned_sdks": [
    {
      "version": "0.48.0-rc1",
      "sha256": "0811e7b60460e6b15a31cd3f312b1b5b3c36f8d1aa6dce7a7fd04193e362eace",
      "status": "current",
      "studio_release": "v0.2.0-alpha.2"
    }
  ]
}
```

`PipelineKRuntimeProbe.describe()` reads the manifest `Implementation-Version`
of the discovered SDK jar, computes the SHA-256 of the jar bytes, and
asks `SdkLockfileVerifier.verify(version, actualSha256)`. The result is
one of:

- **Match** — the SHA matches the lockfile entry for this version.
- **Mismatch** — the version is in the lockfile but the SHA differs
  (an upgraded SDK was swapped in without a corresponding studio
  release).
- **Unknown** — the version is not in the lockfile at all (a new SDK
  was installed before the studio release was cut).

The `STUDIO_SDK_PIN` env var controls what happens on Mismatch/Unknown:

| Value   | Behavior on Mismatch or Unknown                       |
|---------|-------------------------------------------------------|
| `enforce` (default) | Probe returns `Failure`; studio refuses to admit runs |
| `warn`   | Probe logs the mismatch but still returns `Available` |
| `off`    | No comparison at all; any SHA is accepted             |

The default is `enforce` so a silent SDK upgrade fails closed. To
unblock a development environment with a non-pinned SDK, set
`STUDIO_SDK_PIN=off`.

## How to add a new pin

1. Install the new SDK jar on the studio's classpath.
2. Boot the studio with `STUDIO_SDK_PIN=off` to see the actual SHA
   in `/api/version`'s `pipelinek_runtime_digest`.
3. Append a new entry to `sdk.lock.json`:

   ```json
   {
     "version": "0.49.0-rc1",
     "sha256": "<paste the full 64-char hex>",
     "status": "current",
     "studio_release": "v0.3.0-alpha.1"
   }
   ```

4. Re-boot without the override; the studio now refuses to admit
   runs on any other SHA for that version.

## Updating the pin (release procedure)

1. Bump `gradle/libs.versions.toml`'s `pipelinek = "X.Y.Z-rcW"`.
2. Download the SDK into `~/.cache/pipelinek-studio/sdk/pipelinek-X.Y.Z/`.
3. Compute the SHA: `sha256sum .../pipeline-application-X.Y.Z.jar`.
4. Add a row to the pin matrix above.
5. Re-run the audit's UAT suite; AAT-ABI-004 must pass with the
   new SDK.
6. Commit the version bump + the matrix update in the same commit.

## Why not Gradle dependency?

A Gradle dependency on the SDK would tie the studio's release cadence
to the SDK's release cadence. The current model (classpath-driven)
lets the operator pin their own SDK for compliance reasons (e.g.
internal SDK with security patches) while the studio can release
without waiting for the SDK. The trade-off is: no automatic SHA
verification by Gradle, which is why this document exists.
