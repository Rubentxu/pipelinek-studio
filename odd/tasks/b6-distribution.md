# B6 — Distribution channels (single-binary + install managers)

**Date:** 2026-10-10
**Block:** B6 (S5 follow-up: distribution for end users)
**Target:** post-`v1.0.0-rc.1`
**Trigger:** operator feedback that end users need a clean
install path that does not require JDK 21 + Node 20 to be
pre-installed.

## Problem

B5 (S5) shipped the CLI as a `studio-cli-X.tar` that bundles
the Kotlin server + dependencies, but the operator still
needs:

- A **JDK 21** install (the studio requires it).
- A **Node 20** install for the MCP stdio adapter.

Two pre-installs is two footguns. The Tauri path was
rejected because the architecture is server-first (Ktor JVM
+ browser SPA + Node MCP), not a greenfield desktop app.

The right framing is **JVM-native packaging**:
`jlink` produces a custom JRE that ships in the tar; the
launcher script sets `JAVA_HOME` to the bundled JRE; the
MCP adapter is launched from a bundled Node binary (B6.x
follow-up). The operator's experience is "download,
extract, run" — no host dependencies.

## Why

The end-user install UX is the single biggest gate to
adoption. The current "tar + JDK 21 + Node 20" sequence is
where a casual user drops off. A single-binary tar that
"just works" on a clean machine is the difference between
"tool I tried" and "tool I kept".

## Scope

- **B6.0** — single-binary JVM distribution via `jlink`.
  Custom JRE bundled in the tar; launcher script uses
  the bundled JRE; no system JDK required.
- **B6.1** — asdf-vm plugin (planned, not executed in
  B6.0).
- **B6.2** — Homebrew tap (planned).
- **B6.3** — Scoop bucket for Windows (planned).
- **B6.4** — Docker multi-stage image (planned).
- **B6.5** — B6 release receipt (after B6.0 ships).

## Constraints

- All tests pass before commit.
- SDK stays at 0.48.0-rc1 (R-22 SHA-pinned).
- No AI attribution in commits; conventional commits.
- The single-binary must pass the B5.4 certification
  protocol (`studio-cert/`) before `v1.0.0-rc.2` /
  `v1.0.0` stable.

## Acceptance (B6.0)

- `gradle :studio-cli:standaloneDist` produces a
  `studio-cli-standalone-X.tar` that contains a custom
  JRE + the studio-cli jar + dependencies + a launcher
  script.
- The launcher works on a machine without JDK 21
  installed (the test pins this: a subshell with `JAVA_HOME`
  unset + `PATH` not containing `java` still runs
  `./bin/studio --version`).
- The custom JRE is smaller than the system JDK 21
  (target: <80MB, vs ~180MB for the full JDK).
- The existing tests pass (424 → 424, no regression).
- The B5.4 harness protocol can consume the new artifact
  (the `verifyChecksums` helper works on the new manifest).

## Applicable checks

- `./gradlew :studio-cli:check` returns BUILD SUCCESSFUL.
- `./gradlew :studio-cli:standaloneDist` produces a tar.
- The launcher runs `studio --version` on a JDK-less
  subshell.
- B5.4 harness passes against the standalone artifact
  (run by the operator at release-tag time, not in CI).

## Open risks

- **JLink needs explicit `--add-modules` for reflective
  deps**. Ktor uses Java's NIO + scripting; Flyway uses
  JDBC + reflection. The test will pin the module list.
- **JLink output is platform-specific**. A Linux build
  produces a Linux JRE; a macOS build produces a macOS
  JRE. The CI matrix needs to build on every target
  platform, OR a future B6.x adds a multi-archive.
- **The launcher is a shell script**. Windows users
  need a `.bat` shim too (B6.3 follow-up with the Scoop
  bucket).

## Next step

B6.0.0 — wire the `:studio-cli:jlinkRuntime` task,
configure the module set, and produce a test tar. Verify
the launcher on a JDK-less subshell.
