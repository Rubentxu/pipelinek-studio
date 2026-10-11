# `examples/sdk-external-execution/` — R2.1 external SDK fixture

## What this is

A standalone Gradle subproject that produces **three JARs** used as the
canonical external-plugin fixture for Studio's catalog + admission
pipeline. The fixture proves the R2.1 property: the catalog can
discover, classify, and inspect a plugin JAR **without ever
classloading it**.

Per `docs/02-specifications/STEP_PLUGIN_LAB.md §TestKit` and the
canonical roadmap R2 item 1.

## Scope

The fixture is **SDK-agnostic**. It does NOT depend on
`pipelinek-plugin-api` or any real PipelineK SDK classpath. The
static inspector reads `MANIFEST.MF` and `META-INF/services/*` as
raw files — it does not need the SDK to be on the build classpath
to exercise the contract. This means the fixture can prove
AAT-PLG-001..007 even when the SDK subprocess is unavailable (R0.3.4
/ R0.3.5 are BLOCKED on kill-9 machine).

## The three JARs

| JAR | Purpose | Test |
|---|---|---|
| `valid-plugin-0.1.0.jar` | Valid manifest, SDK vendor, version in range, no initializer | AAT-020 happy path |
| `incompatible-api-plugin-99.0.0.jar` | Vendor OK, version `99.0.0` (out of `[0.0.0,1.0.0)`) | AAT-021 unhappy path |
| `canary-initializer-plugin-0.1.0.jar` | Manifest OK, but ships a fake `.class` + a marker text file containing `CANARY_RAN` | AAT-021 canary invariant (classloader must NOT fire) |

## Build

```sh
./gradlew :examples:sdk-external-execution:assemble
```

Output: `build/libs/{valid-plugin-0.1.0.jar, incompatible-api-plugin-99.0.0.jar, canary-initializer-plugin-0.1.0.jar}`.

## Run the studio tests against the fixture

```sh
# 1. Build the fixture
./gradlew :examples:sdk-external-execution:assemble

# 2. Run the R2.1 test (in studio-infra-sdk)
./gradlew :studio-infra-sdk:test --tests 'dev.pipelinek.studio.infra.sdk.ExternalFixtureR21Test'
```

If the JARs are missing, the test is **skipped** (not failed) and
prints a hint to build first. This keeps the studio-infra-sdk test
suite runnable in isolation.

## Why no `pipelinek-plugin-api` dependency?

The fixture pins the AAT-021 canary invariant: a JAR with a
`.class` file that, if classloaded, would print `CANARY_RAN` must
NEVER surface that marker in the inspector's output. To prove the
property, the inspector must read the JAR statically. If the
fixture compiled against the real SDK, the build itself would
classload the canary (via the SDK's plugin compiler) and the
fixture would no longer be a "static-only" artifact. The
SDK-agnostic approach is a feature, not a limitation.

## A future addition

When R0.3.4 / R0.3.5 unblock and the SDK subprocess is alive, a
**fourth** JAR will be added: `valid-plugin-with-real-codec.jar`
that compiles against the published `pipelinek-plugin-api` and
exercises the SDK's `StepRegistry` admission. That JAR will
require the SDK on the classpath; the three JARs in this project
will continue to be SDK-agnostic and remain the
classloader-invariant test surface.

---

**Owner:** R2 (Step & Plugin Lab).
**Recibo:** `docs/history/R2_1_RELEASE_RECEIPT.md` (se escribe al cerrar el item).
**Plan vivo:** `odd/tasks/r2-plugin-lab.md`.
