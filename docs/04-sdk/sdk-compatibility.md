# SDK Compatibility Matrix (R-04)

**Audit reference**: R-04 (2026-10-10 senior technical audit). The studio
was previously pinned to `pipelinek = "0.48.0-rc1"` by version string only.
A new SDK release could silently change the wire contract (capability
markers, event names, payload shapes) and the studio would accept it
because no SHA was recorded.

This document pins the SDK to a **SHA-256 fingerprint** so a drift
between the expected and the installed SDK surfaces as a mismatch in
`/api/version`'s `pipelinek_runtime_digest` field.

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
