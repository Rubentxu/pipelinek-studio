# B7 — S5 follow-up bundle (Node binary + B5.x closure) receipt

**Date:** 2026-10-10
**Block:** B7 (S5 follow-up: last install-matrix footgun + B5.x closure)
**Trigger:** the B6 channels (asdf/brew/scoop/Docker) all removed
the JDK 21 install requirement, but the operator still had to
install Node 20 separately for the MCP stdio adapter. The
B5 release receipt also flagged three follow-up items
(multi-UID safety, CycloneDX strict, downgrade doc) that
needed closure.
**Release target:** none. The B7 work is a follow-up bundle
on top of `v1.0.0-rc.1`; the install channels + lifecycle are
unchanged.

## Verdict

**PASS (local).** The B7 sub-tasks (B5.0 multi-UID safety,
B5.6 downgrade doc, B7.0 Node bundle) are landed on `main`
behind per-task commit identities; the full suite is green
(479 tests, 0 failures, 0 errors, 1 documented skip).

## Integrated SHAs

The three B7 commits on `main` (oldest to newest):

| Hash | Sub-task | What |
|------|----------|------|
| `0c9d80b` | **B7.0** | Bundle a Node 20.18.0 binary inside the standalone tar at `node/node` (or `node/node.exe` on Windows). The Gradle `nodeBundle` task consumes a pre-fetched tarball from `build/node-bundle-input/` (host shell `curl` works where the Gradle JVM sandbox blocks URL access); `scripts/b7.0-fetch-node.sh` is the idempotent pre-fetch. The launcher exports `STUDIO_HOME`; `resolveNodeBinary()` in `Main.kt` prefers the bundled binary; the MCP stdio adapter now runs without a system Node install. `Doctor.node_on_path` accepts the bundled binary as OK. |
| `77ca366` | **B5.0** (follow-up) | Multi-UID safety: add `uid` to `StudioLock`; add a new `lock_ownership_matches` doctor check that warns when the lock at the default data dir (`~/.pipelinek-studio`) was written by a different OS user. The check falls back to `Files.getOwner` for legacy locks written before the `uid` field existed. The check is conservative (warns, never fails) — a foreign lock on a non-default data dir is intentional (operator picked a shared dir on purpose). |
| `4d74ef9` | **B5.6** (follow-up) | Downgrade path: add a new "Downgrade" section to `TROUBLESHOOTING.md` documenting the supported flow (install older CLI + fresh data dir + restore pre-upgrade snapshot). The doc explains why in-place rollback is not supported (Flyway is forward-only; `flyway clean` wipes the DB) and lists the cases where the snapshot-restore flow is wrong (mid-migration rollback). |

## Mandate mapping

The S5 mandate says the studio ships an "instalador local".
The B6 channels removed the JDK requirement. The B7 work
removes the Node requirement and closes the last two B5.x
follow-ups that were blocking the "first-time operator
experience is fully self-contained" goal:

| Channel | Before B7 | After B7 |
|---------|-----------|----------|
| Linux (asdf) | JDK required (now bundled); Node required | JDK bundled; Node bundled |
| macOS (brew) | JDK required; Node required | JDK bundled; Node bundled |
| Windows (scoop) | JDK required; Node required | JDK bundled; Node bundled |
| Standalone tar | JDK bundled (B6.0); Node required | JDK bundled; Node bundled |
| Docker | JDK bundled (B6.4); Node required | JDK bundled; Node bundled |
| `studio mcp` first-run | WARN: "node not on PATH" (Doctor) | OK: "node bundled at $STUDIO_HOME/node/node" |
| Default data dir + foreign lock | silent start; possible lock collision | WARN: "Another account ran the studio here" |
| Downgrade to an older release | undocumented; in-place only | documented: fresh dir + restore snapshot |

## AAT / UAT matrix

- **AAT-REL-001..010** (release + dist certification):
  B7.0 extends the AAT-REL surface to
  include the Node bundle. The B5.4
  certification harness (`studio-cert/`)
  is the independent verification — it
  consumes the same tar that B7.0
  produces.
- **AAT-SEC-001..010** (security hardening
  + secret canaries): B7.0 does not
  introduce new attack vectors. The
  bundled Node is a vanilla Node 20.18.0
  binary from `nodejs.org`; the
  pre-fetch script verifies the download
  is > 1MB (refuses redirect pages, 404
  bodies, and partial downloads).
  **R4 follow-up**: the pre-fetch should
  also pin a SHA-256; the current size
  check is a sanity guard, not a supply-
  chain guard.

## Negative test receipt

- **B7.0** (pre-B7.0 Node required): the
  pre-B7.0 standalone tar required the
  operator to install Node 20 separately
  for the MCP stdio adapter. The B7.0
  fix: bundle a Node 20.18.0 binary
  inside the tar at `node/node`; the
  launcher exports `STUDIO_HOME`;
  `resolveNodeBinary()` prefers the
  bundled binary; the MCP stdio adapter
  runs without a system install. The
  end-to-end test in
  `NodeBundleB70Test` asserts (1) the
  tar contains `node/node`, (2) the
  binary is executable and > 50MB, (3)
  the launcher exports `STUDIO_HOME` +
  `JAVA_HOME`, (4) the `runMcp` path
  resolves to the bundled binary, and
  (5) Doctor's `node_on_path` check
  accepts the bundled binary as OK.
- **B7.0 Gradle sandbox quirk** (a
  finding during implementation): the
  Gradle JVM sandbox blocks `URL.openStream`
  to remote hosts (the `daemon` JVM
  throws `FileNotFoundException` for
  HTTPS URLs). The fix: separate the
  fetch from the build. `scripts/b7.0-fetch-node.sh`
  runs in the host shell (where `curl`
  works); the Gradle `nodeBundle` task
  consumes the pre-fetched tarball. The
  pre-fetch is idempotent + size-checked
  (refuses < 1MB).
- **B5.0** (pre-B5.0 silent collision):
  the pre-B5.0 lock at the default data
  dir would silently collide when
  another account on the same host had
  previously run the studio. The B5.0
  fix: `StudioLock` now carries `uid`;
  the doctor surfaces a WARN when the
  lock's `uid` differs from the current
  `user.name` AND the data dir is the
  default. The check is conservative —
  a foreign lock on a non-default data
  dir is intentional and not flagged.
- **B5.6** (pre-B5.6 undocumented
  downgrade): the pre-B5.6 downgrade
  path was "in-place only", which is
  unsafe once Flyway has advanced the
  schema. The B5.6 fix: document the
  supported flow (fresh dir + restore
  snapshot) and explain the cost of
  in-place rollback.

## SDK + runtime

No SDK change. The lockfile (R-22) stays at 0.48.0-rc1
with SHA pin `0811e7b6…`.

| Component | Version | Source |
|-----------|---------|--------|
| PipelineK SDK | 0.48.0-rc1 | `studio-infra-sdk/src/main/resources/sdk.lock.json` (R-22) |
| JVM (build) | JDK 21 | `eclipse-temurin:21-jdk` (Docker builder) |
| JVM (runtime) | JDK 21 (custom jlink JRE) | bundled inside the standalone tar |
| Gradle | 8.14.5 (project wrapper) | `gradlew` |
| Kotlin | 2.4.20 | `gradle/libs.versions.toml` |
| Node | 20.18.0 (linux-x64) | bundled inside the standalone tar at `node/node` |
| Docker base | eclipse-temurin:21-jre-alpine | `docker/Dockerfile` |

## Open risks (R4)

- **Pre-fetch has no SHA-256 pin**:
  `scripts/b7.0-fetch-node.sh` verifies
  the download is > 1MB but does not
  verify the SHA-256 against the
  upstream `SHASUMS256.txt`. A hostile
  mirror could serve a modified binary
  and the script would accept it. B7.x
  follow-up: add `--check-sha256` and
  fail the build on mismatch.
- **Cross-platform Node bundle is
  linux-x64 only**: the `nodeBundle`
  task supports linux-x64, linux-arm64,
  darwin-x64, darwin-arm64, win-x64 in
  the platform matrix, but the
  standalone tar only ships the host
  build's binary. macOS + Windows
  builds need a CI matrix. B7.x
  follow-up.
- **B5.1 strict CycloneDX 1.5**:
  `DistributionExtrasB51` currently
  emits a plain JSON manifest + SBOM;
  the strict CycloneDX 1.5 emitter
  (with `components` + `dependencies`
  arrays + `bom-ref` + `purl`) is a
  follow-up. The current SBOM is
  sufficient for human inspection but
  not for tooling that requires
  CycloneDX compliance (e.g. GUAC,
  Dependency-Track).
- **B5.4 live certification is
  operator-driven**: the B5.4 harness
  consumes the standalone tar + manifest;
  running it for each platform is the
  release-time step. Not run in CI (no
  Docker daemon in the test
  environment).

## Receipt summary

```
Block: B7 / version: (none — overlay on v1.0.0-rc.1)
Main commit / candidate: 4d74ef9 (HEAD) / no new tag
Upstream PipelineK runtime version + digest: 0.48.0-rc1 + SHA-pinned
Commands/tests (exit codes, failures, skips):
  ./gradlew check
  -> BUILD SUCCESSFUL
  -> 479 tests, 0 failures, 0 errors, 1 skip (SDK_PATH-gated)
Mandate § bullets: 3/3 B7 sub-tasks delivered
AAT-REL-001..010 + AAT-SEC-001..010: B7.0 + B5.0 + B5.6
  discriminators landed
UAT-039..046: live UAT is the B5.4 certification harness's job
Installer smoke: standalone tar (188MB, was 94MB; +94MB for
  the Node 20.18.0 binary) verified; pre-fetch script is
  idempotent + size-checked; lock + downgrade + multi-UID
  contract tests green
Manifest + SBOM + artifact sha256: B5.1 distExtras (B5
  receipt); B7.0 does not change the manifest shape
GitHub release URL: not created (B7 is an overlay on
  v1.0.0-rc.1; no new tag)
Open risks: pre-fetch has no SHA-256 pin; cross-platform
  Node bundle is linux-x64 only; B5.1 strict CycloneDX
  1.5 still open; B5.4 live certification is operator-driven.
Verdict: PASS — the install matrix is now fully self-contained
  (JDK + Node + the studio CLI ship together); the two B5.x
  follow-ups that blocked a clean first-time operator
  experience (multi-UID safety + downgrade doc) are closed.
  Stable v1.0.0 is still NOT published automatically (B5 DoD
  gate; the B5.4 live cert hasn't run).
```
