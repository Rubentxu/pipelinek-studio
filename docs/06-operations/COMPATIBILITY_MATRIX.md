# Compatibility Matrix — PipelineK Studio

This document is the canonical record of which versions of the
runtime, the upstream SDK/engine, and the supported plugins are
**tested to work together** at each Studio release. It is
maintained by the same people who run the verify-from-clean-clone
harness; if a row here says "✓", it passed the harness on a fresh
clone.

If you find a version combination that works but is not in the
matrix, file an issue with the exact versions and a `studio
doctor` output. Adding a row requires a new harness run on a
freshly-initialised VM.

## Runtime stack

The host must satisfy these requirements for any version of
Studio to start.

| Component       | Min       | Tested      | Notes                                  |
|-----------------|-----------|-------------|----------------------------------------|
| JDK             | 21 LTS    | 21.0.x LTS  | Temurin / Liberica / Zulu all OK       |
| Gradle (build)  | 8.10      | 8.14.5      | The wrapper pins this exact version     |
| Kotlin (build)  | 2.0.21    | 2.1.0       | Bumped by the Gradle Kotlin plugin      |
| Node (apps)     | 20 LTS    | 20.x        | Required for `apps/mcp`, `apps/web`, VS Code |
| pnpm (apps)     | 9         | 9.x         | Optional; npm also works                |
| OS (Linux)      | 5.15+     | 6.x         | glibc 2.31+ (AlmaLinux 9 / Ubuntu 22.04) |
| OS (macOS)      | 13        | 14 Sonoma   | arm64 native                            |
| OS (Windows)    | 10/11     | 11          | WSL2 preferred for the server tier       |

## Studio release matrix

For each released Studio version, the exact upstream components
it was verified against.

| Studio          | PipelineK SDK | PipelineK Engine | JDK  | Ktor   | Flyway | SQLite (xerial) | MCP SDK | Status |
|-----------------|---------------|------------------|------|--------|--------|-----------------|---------|--------|
| 0.1.0-alpha.1   | 0.48.0-rc1    | 0.48.0-rc1       | 21   | 3.0.1  | 10.10.0| 3.46.1.3        | 1.0.4   | ✓ verified (this repo) |
| 1.0.0-rc.7      | 0.48.0-rc1    | 0.48.0-rc1       | 21   | 3.0.1  | 10.10.0| 3.46.1.3        | 1.0.4   | ✓ verified (this repo, R5 EVO-C close) |

The "verified" mark means: the verify-from-clean-clone.sh harness
ran green on the exact version combination listed, on a fresh
container. The "PipelineK Engine" column is the distribution
Studio runs the user pipeline against; the "PipelineK SDK" is the
compile-time contract for step plugins.

## Plugin compatibility

Step plugins built against the **same major** SDK version as the
Engine column are admitted. Plugins built against a different
major are rejected at admission with a typed
`ABI_INCOMPATIBLE` verdict and a clear remediation message
("rebuild the plugin against SDK 0.48.x and retry").

| Studio engine SDK | Plugin SDK | ABI verdict      | Notes                              |
|-------------------|------------|------------------|------------------------------------|
| 0.48.0-rc1        | 0.48.x     | ✓ ADMIT          | Same major, same minor             |
| 0.48.0-rc1        | 0.47.x     | ✗ REJECT         | Major-equivalent mismatch          |
| 0.48.0-rc1        | 0.49.0-rc1 | ✗ REJECT (forward)| Forward ABI not guaranteed         |

The admission check is enforced by `StepPluginAdmissionPolicy`
(when wired) and surfaced via the `studio.steps.describe` MCP
tool. The verdict is the only authority — Studio does NOT
attempt to bridge or "best-effort" load an incompatible plugin
(R-04 + R-06).

## API surface

Studio's public HTTP surface is versioned by the OpenAPI document
at `/openapi.json`. Backwards-compatible additions (new
endpoints, new optional fields) ship in any release. Breaking
changes (removed endpoints, renamed fields, tightened schemas)
require a minor-version bump and a CHANGELOG entry.

| Studio          | OpenAPI doc version | Breaking vs previous | Migration guide          |
|-----------------|---------------------|----------------------|--------------------------|
| 0.1.0-alpha.1   | 1.0.0 (S0 surface)  | n/a (initial)        | this document (UPGRADE)  |
| 1.0.0-rc.7      | 1.0.0 (R5 surface)  | no (additive)        | this document (UPGRADE)  |

The R5 surface adds `GET /events` (SSE) per [ADR-013](../../03-adrs/ADR-013-sse-tree-events.md), a `frozen_plugin_digests` column on `run_bindings` (V2 migration), and the EVO-C VS Code extension surface. No routes removed; no fields tightened.

## Reproducibility

All 5 release artifacts (tar, manifest.json, sbom.json,
sbom.cdx.json, VSIX) are **byte-reproducible** when
`SOURCE_DATE_EPOCH` is set (default 1700000000). The Gradle
build is configured via `build.gradle.kts` (`Jar` task with
`isReproducibleFileOrder = true` + `isPreserveFileTimestamps
= false` + manifest `Build-Time` + lastModified); the
`distExtras` task derives `buildAt` + `cdxSerial` from
`SOURCE_DATE_EPOCH` (was `System.currentTimeMillis()` +
`UUID.randomUUID()`); the VSIX is post-processed by
`apps/vscode/scripts/reproducible-vsix.py` to pin every
zip entry's mtime to the same epoch.

## SPA / VS Code extension

| Studio          | SPA (apps/web)    | VS Code ext (apps/vscode) | Tested engines         |
|-----------------|-------------------|----------------------------|------------------------|
| 0.1.0-alpha.1   | React 19 + Vite 6 + Monaco 4.6 | VS Code 1.85+     | Chromium 124+, Firefox 127+ |

The SPA bundles Monaco from the official CDN by default. For
air-gapped installs, vendor Monaco locally (S5 polish — not yet
shipped in 0.1.0-alpha.1) and update the `@monaco-editor/react`
loader config.

## Anti-support policy

The following are explicitly **not supported** and may break
without notice:

- **JDK 17** — Studio's `kotlinx.coroutines` and Ktor 3.0 surface
  assume JDK 21 virtual threads. 17 works in practice but is not
  tested.
- **JDK 23+** — Pre-LTS, may be unstable.
- **macOS x86_64** — Apple silicon is the only tested macOS target.
- **Windows native** (non-WSL) — file path conventions differ
  enough that the snapshot seal may produce surprising paths.
- **Anything not in the matrix** — if you need a combination we
  don't list, the burden of proof is yours: run the harness on a
  fresh VM and open a PR to add the row.

## How to add a row

1. Update the version in `build.gradle.kts` and the SDK version
   in `gradle.properties`.
2. Run `./scripts/verify-from-clean-clone.sh` on a freshly
   initialised VM.
3. If green, update the matrix table with the exact versions and
   the harness SHA.
4. Update `CHANGELOG.md` with a "Compatibility" line in the
   release block.
5. Open a PR — the maintainers review the matrix row + the
   harness log together.
