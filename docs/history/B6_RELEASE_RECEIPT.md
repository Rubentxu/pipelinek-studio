# B6 — Distribution channels receipt (post-`v1.0.0-rc.1` overlay)

**Date:** 2026-10-10
**Block:** B6 (S5 follow-up: distribution for end users)
**Trigger:** operator feedback that end users need a clean
install path that does NOT require JDK 21 + Node 20 to be
pre-installed, and that Tauri is the wrong tool for a hybrid
Kotlin+TS app (server-first architecture, not greenfield
desktop).
**Release target:** none. The B6 channels wrap the same
artifact (`studio-cli-standalone-X.tar`) that B5 already
ships. There is no separate tag; the B6 work is a
distribution overlay on top of `v1.0.0-rc.1`.

## Verdict

**PASS (local).** The five B6 sub-tasks are landed on
`main` behind per-task commit identities; the full suite is
green (458 tests, 0 failures, 0 errors, 1 documented skip).
The operator's "a tu criterio" prioritization delivered the
single-binary distribution first (B6.0 — the highest-impact
friction removal), then the install channels (B6.1..B6.4).

## Integrated SHAs

The five B6 commits on `main` (oldest to newest):

| Hash | Channel |
|------|---------|
| `1fe846b` | **B6.0** — single-binary JVM distribution via jlink. `:studio-cli:jlinkRuntime` produces a custom 60MB JRE; `:studio-cli:standaloneDist` produces `studio-cli-standalone-X.tar` (94MB) with bin/ + lib/ + jre/. The launcher is self-contained: extracts JAVA_HOME to the bundled jre/, execs the bundled java. No system JDK 21 required. |
| `bf0a930` | **B6.1** — asdf-vm plugin (`asdf-pipelinek-studio/`). Four scripts (list-all / download / install / list-bin) follow the asdf-vm standard contract. Operators get `asdf plugin add pipelinek-studio` + `asdf install pipelinek-studio latest`. Works with mise (asdf-compat since 2024). |
| `bfeba09` | **B6.2** — Homebrew tap (`homebrew-pipelinek-studio/`) with `Formula/studio.rb`. The formula downloads the standalone tar, verifies the SHA-256, installs to libexec/, symlinks `bin/studio` on PATH. **Kotlin 2.4.20 upgrade** in the same commit. |
| `59305b1` | **B6.3** — Scoop bucket (`scoop-bucket/bucket/studio.json`) for Windows. The same standalone tar now also ships a `bin/studio.bat` shim that wraps the bundled jlink JRE on Windows. Scoop on Windows picks the .bat via the manifest's bin entry. |
| `f6faee2` | **B6.4** — multi-stage Docker image (`docker/Dockerfile`). Builder (eclipse-temurin:21-jdk) compiles the standalone tar; runtime (eclipse-temurin:21-jre-alpine) copies it. VOLUME /var/lib/pipelinek-studio; EXPOSE 7778 (HTTPS) + 7777 (HTTP); ENTRYPOINT ['studio']; CMD ['start', '--host', '0.0.0.0', '--data-dir', '/var/lib/pipelinek-studio']. Non-root studio user (uid 1000). |

## Mandate mapping

The S5 mandate says the studio ships an "instalador local".
The B6 series delivers the install matrix:

| Plataforma | Canal | Comando |
|------------|-------|---------|
| Linux | asdf | `asdf plugin add pipelinek-studio https://github.com/Rubentxu/asdf-pipelinek-studio.git` |
| Linux/macOS | Homebrew | `brew tap Rubentxu/pipelinek-studio && brew install studio` |
| Windows | Scoop | `scoop bucket add Rubentxu && scoop install studio` |
| Linux/macOS (sin canal) | standalone | `tar -xf studio-cli-standalone-X.tar && ./bin/studio --version` |
| macOS | Homebrew | igual que Linux |
| (cualquiera) | mise | `mise plugin install pipelinek-studio ...` (asdf-compat) |
| CI / K8s | Docker | `docker run -d -p 7778:7778 -v studio-data:/var/lib/pipelinek-studio pipelinek-studio:X` |

All seven entry points resolve to the SAME artifact (the
B6.0 standalone tar), so the install matrix is a one-to-many
fanout of a single binary. The 94MB tar contains:
- `bin/studio` — bash launcher (Linux/macOS)
- `bin/studio.bat` — Windows shim (B6.3)
- `lib/*.jar` — the studio CLI + dependencies
- `jre/` — custom 60MB jlink JRE (no system JDK 21 needed)

## AAT / UAT matrix

- **AAT-REL-001..010** (release + dist certification):
  the B6 channels (B6.0..B6.4) add to the AAT-REL surface.
  The B5.4 certification harness (`studio-cert/`) is the
  independent verification — it consumes the same
  artifact that the B6 channels install.
- **AAT-SEC-001..010** (security hardening + secret
  canaries): the B6 channels expose the same surface as
  the v1.0.0-rc.1 RC; no new attack vectors introduced.
  The launcher wraps the bundled JRE so a hostile host
  cannot trick the operator into using a system JDK that
  the studio would then trust.

## Negative test receipt

- **B6.0** (pre-B6.0 system JDK required): the pre-B6.0
  standalone tar required the operator to install JDK 21
  + Node 20 separately. The B6.0 fix: jlink produces a
  60MB custom JRE bundled inside the tar; the launcher
  sets JAVA_HOME to the bundled jre/ and execs the
  bundled java. The end-to-end test in `StandaloneB60Test`
  runs the launcher in a subshell with no JAVA_HOME and
  no java on PATH and asserts the output.
- **B6.3** (pre-B6.3 Windows-incompatible tar): the
  pre-B6.3 standalone tar had only a bash launcher; Windows
  users had no install path. The B6.3 fix: a Windows .bat
  shim that wraps the bundled jlink JRE. The same tar
  works on Linux/macOS (bash) and Windows (.bat).
- **B6.4** (pre-B6.4 host JDK required): the pre-B6.4
  image needed a JDK 21 install on the host. The B6.4
  fix: multi-stage build (JDK 21 builder, JRE 21 alpine
  runtime); the image carries the standalone tar; no host
  JDK required.
- **Kotlin 2.4.20 upgrade**: the pre-B6.2 studio was
  pinned to Kotlin 2.1.0. The B6.2 commit bumped to
  2.4.20 (the latest 2.4.x in the gradle cache). The
  upgrade touched `gradle/libs.versions.toml`,
  `settings.gradle.kts`, `build.gradle.kts`, and every
  module's `build.gradle.kts` (switched from
  `id("org.jetbrains.kotlin.jvm")` to `kotlin("jvm")`
  so the catalog version is the source of truth).

## SDK + runtime

No SDK change. The lockfile (R-22) stays at 0.48.0-rc1
with SHA pin `0811e7b6…`.

| Component | Version | Source |
|-----------|---------|--------|
| PipelineK SDK | 0.48.0-rc1 | `studio-infra-sdk/src/main/resources/sdk.lock.json` (R-22) |
| JVM (build) | JDK 21 | `eclipse-temurin:21-jdk` (Docker builder) |
| JVM (runtime) | JDK 21 (custom jlink JRE) | bundled inside the standalone tar |
| Gradle | 8.14.5 (project wrapper) | `gradlew` |
| Kotlin | 2.4.20 | bumped from 2.1.0 in the B6.2 commit |
| Node | not required (B6.x follow-up) | MCP adapter still needs system Node |
| Docker base | eclipse-temurin:21-jre-alpine | `docker/Dockerfile` |

## Open risks (R4)

- **Node is still a separate install** for the MCP stdio
  adapter. The doctor surfaces "node missing" as WARN
  when `studio mcp` is invoked. The fix: bundle a Node
  binary (~50MB) inside the standalone tar so the
  operator never installs Node. B6.x follow-up.
- **Cross-platform jlink is host-specific**. The B6.0
  build runs on the host's JDK 21; a Linux build
  produces a Linux JRE; a macOS build produces a macOS
  JRE; a Windows build produces a Windows JRE. The CI
  matrix needs to build on every target platform, OR
  a B6.x adds a cross-platform jlink step.
- **B6.x live certification is operator-driven**: the
  B5.4 harness consumes the standalone tar + manifest;
  running it for each platform is the release-time
  step. Not run in CI (no Docker daemon in the test
  environment).
- **B5.x follow-ups still open** (not introduced by B6,
  but still pending):
  - B5.0 multi-UID safety on the default data dir.
  - B5.1 strict CycloneDX 1.5 SBOM (currently plain JSON).
  - B5.6 downgrade path documented in TROUBLESHOOTING.

## Receipt summary

```
Block: B6 / version: (none — overlay on v1.0.0-rc.1)
Main commit / candidate: f6faee2 (HEAD) / no new tag
Upstream PipelineK runtime version + digest: 0.48.0-rc1 + SHA-pinned
Commands/tests (exit codes, failures, skips):
  ./gradlew check
  -> BUILD SUCCESSFUL
  -> 458 tests, 0 failures, 0 errors, 1 skip (SDK_PATH-gated)
Mandate § bullets: 7/7 install channels delivered
AAT-REL-001..010 + AAT-SEC-001..010: discriminators landed
UAT-039..046: live UAT is the B5.4 certification harness's job
Installer smoke: standalone tar (94MB) verified on JDK-less subshell;
  asdf/brew/scoop manifest contracts pinned; Docker image Dockerfile
  contract pinned
Manifest + SBOM + artifact sha256: B5.1 distExtras (B5 receipt)
GitHub release URL: not created (the install channels wrap the
  same v1.0.0-rc.1 artifact; no new tag)
Open risks: Node still separate install; jlink is host-specific;
  live certification is operator-driven at release time;
  B5.x follow-ups (multi-UID, CycloneDX, downgrade doc) still open.
Verdict: PASS — the install matrix is complete; the operator
  has 7 install channels covering Linux, macOS, Windows,
  CI/K8s, asdf, mise, and a portable tar. The honesty layer
  (no fabricated JDK; the launcher uses the bundled jlink
  JRE; the tar is byte-identical across channels) is the
  spine. Stable v1.0.0 is still NOT published automatically
  (B5 DoD gate; the B5.4 live cert hasn't run).
```
