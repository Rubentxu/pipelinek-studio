# R5 — VS Code completion acceptance (T5): real Studio, real Extension Host, real VSIX

- Date: 2026-10-17
- Branch: `feat/r5-vscode-completion` (base `91a69fb`)
- Scope: S1, S6, S7 — evidence only. No product behaviour was broadened, no
  runtime connection logic changed, no dependency added, no global config
  written, no production `studio.db` touched.
- Status: **proven by observation where listed, and explicitly not proven
  where listed.** Every claim below is backed by a command that was actually run
  in this workspace; the limits are in [What is NOT proven](#what-is-not-proven)
  and are not silently upgraded to PASS.
- Artifact binding: the Extension Host (§5) and real Studio (§2) observations were
  produced from the **pre-correction** build (`dist/extension.js`
  `305e857d…`, VSIX `149374e5…`) and are labelled as such. The final corrected
  artifact (`fdfbff28…`, `dist/extension.js` `94969ccf…`) has automated-test and
  archive evidence only — see [Correction history](#correction-history-post-review).
  No host/Studio observation is claimed for the final artifact.

## Environment (observed, not assumed)

| Tool | Observed |
|---|---|
| VS Code desktop | `code --version` → `1.121.0` / `f6cfa2ea2403534de03f069bdf160d06451ed282` / `x64` (CLI at `/home/linuxbrew/.linuxbrew/bin/code`) |
| Node | `v25.9.0` |
| JDK | `openjdk 21.0.8 (Temurin)` |
| Gradle | `8.14.5` (`./gradlew`, `--offline`) |
| vsce | `4.0.0`, already installed at `/var/home/rubentxu/.asdf/installs/nodejs/25.9.0/bin/vsce` (no `npx` download) |
| Session | Wayland desktop (`DISPLAY=:0`, `WAYLAND_DISPLAY=wayland-0`), so a real desktop Extension Host can launch |

Every artifact below lives inside the git-ignored `build/r5-acceptance/` tree,
except the packaged VSIX (`apps/vscode/dist/`, also ignored).

## Artifacts

| Artifact | Path | Evidence |
|---|---|---|
| **Final VSIX (corrected)** | `apps/vscode/dist/pipelinek-studio-1.0.0.vsix` | 170,532 bytes, 50 entries; `sha256 = fdfbff2862a9a2a82cff27ec6fc3fbb151b1d21ef00881d977696293fa53252d`; packaged 2026-10-17T09:42:11+02:00 from the corrected tree with the final README inside |
| **Final build byte match** | `apps/vscode/dist/extension.js` vs `extension/dist/extension.js` in the VSIX | both `sha256 = 94969ccff345c4b3c17454f9736f7afc369922d3f04b524867caebf147a46015` — the final artifact carries exactly the current compiled code |
| ~~Superseded (pre-correction) VSIX~~ | same path, 2026-10-17T09:26:48+02:00 | 165,129 bytes; `sha256 = 149374e53f87f5eced7a84bccc7b4c3c4dbbcb81fee2ca9c7995329b224d2335`; `extension.js` `305e857d…`. **This is the artifact the host 9/9 and Studio 295 ms runs belong to.** |
| Real Studio SSE/restart run (pre-correction artifact) | `build/r5-acceptance/real-studio/state-20261017071653/` | studio log + isolated `data/studio.db` |
| Real Extension Host run (pre-correction artifact) | `build/r5-acceptance/extension-host/run-20261017072553/` | `extension-host.log`, `host-report.json`, VS Code logs under `user-data/logs/` |
| VSIX side-load run (pre-correction artifact) | `build/r5-acceptance/extension-host/run-20261017072651/extensions/` | installed `pipelinek-studio.pipelinek-studio-1.0.0/` |

## Commands and observed results

### 1. Project runners (existing tooling, no downloads)

| Command | Exit | Observed |
|---|---|---|
| `cd apps/vscode && npm run build` | 0 | `tsc -p .`; rebuilding the corrected tree is a no-op (`dist/extension.js` stays `94969ccf…`) |
| `cd apps/vscode && npm run typecheck` | 0 | `tsc --noEmit` |
| `cd apps/vscode && npm run lint` | 0 | `eslint . --max-warnings 0` |
| `cd apps/vscode && npm test` | 0 | **`8` files / `131` tests passed** on the corrected tree (`vitest run`); per file: fileAssociation 7, sdk 6, connection 12, treeView 21, studioApi 20, config 13, extension 30, sseClient 22. The earlier pre-correction run on this branch was 124 tests. |
| independent T3/T4 re-verification | (parent) | targeted verifier **63 + 4** probes: original four probes PASS — (1) consent + stream, (2) no stale client after a declined URL change, (3) events survive overlap, (4) one concurrent submit while a deliberate sequential submit is still allowed |
| `./gradlew :studio-cli:installDist --offline --console=plain` | 0 | `BUILD SUCCESSFUL in 5s`; refreshed `studio-cli/build/install/studio-cli` from this working tree so the Studio probe ran the current server code |
| full Gradle suite (parent job3, reported) | 0 | XML on disk now totals **864 tests / 0 failures / 0 errors / 1 skipped** across 135 result files; the single skip is identified in [Skipped test](#skipped-test) |

### 2. Real Studio + SSE create within 2s, restart/reconnect (S6) — PRE-CORRECTION ARTIFACT

> Scope warning: this run drove the compiled transport of the **pre-correction**
> build (`dist/extension.js` `305e857d…`, VSIX `149374e5…`). The corrected build
> (`94969ccf…`) was **not** re-probed against a Studio; these timings are not a
> claim about the final artifact.

```bash
cd apps/vscode && node scripts/real-studio-smoke.mjs
# exit 0
```

Exact observed output (trimmed to the evidence lines):

```text
studio binary: .../studio-cli/build/install/studio-cli/bin/studio-cli
isolated state: .../build/r5-acceptance/real-studio/state-20261017071653
base url: http://127.0.0.1:34005 (plaintext loopback, allowInsecureHttp opt-in)
phase A: studio healthy after 1971ms (pid 3794430)
phase A: SSE open after 33ms; snapshot.open=true
phase A: POST /experiments 081dbf56-6fa7-4f13-a809-3f01c8731b97 -> SSE experiment.created seq=1 in 295ms (limit 2000ms)
phase B: studio pid 3794430 stopped
phase B: stream down detected 1015ms after SIGTERM; backoff attempt=1 nextAttemptInMs=1000 reason="SSE connection failed or stream ended"
phase B: studio restarted after 1660ms (pid 3795699)
phase B: SSE re-opened after 4042ms total; 2 backoff attempt(s) observed: 1000,2000ms
phase B: after reconnect POST /experiments 54dbdfdc-617b-4dff-b2e6-62738bdaffbf -> SSE experiment.created seq=1 in 261ms
REAL STUDIO SMOKE: PASS
```

Measured facts:

- `POST /experiments` → `experiment.created` SSE frame: **295 ms** (limit 2000 ms),
  `seq=1`, on a real `studio-cli` process with an isolated `--data-dir`/`--db`.
- Killing that process mid-stream is detected **1015 ms** later; the client
  enters the documented backoff path (`attempt=1, nextAttemptInMs=1000`) instead
  of reporting a healthy stream, and `snapshot().open` is `false` while down.
- Restart (1660 ms to `/health`) is followed by a second backoff (2000 ms) and a
  real re-handshake (**4042 ms** after the kill). A `POST /experiments` after
  reconnect produces another `experiment.created` on the new stream (261 ms).
- `GET /experiments` on the restarted studio lists **both** experiments, i.e.
  the isolated SQLite row survived the restart and the tree's authoritative read
  (not an SSE replay) is what recovers state. `/events` is a live tail: the
  in-memory bus sequence restarts at 1 with the new process, which is exactly
  why the extension re-reads collections on every `open`.

The plaintext opt-in in this headless probe is `validateStudioConfig({
allowInsecureHttp: true })` — the same pure validation the extension uses. No
TLS verification is bypassed and no consent path is stubbed.

### 3. VSIX packaging (S6)

First packaging runs (pre-correction artifact), same command twice:

```bash
cd apps/vscode && vsce package --out dist
# 09:19 exit 0 — DONE Packaged: dist/pipelinek-studio-1.0.0.vsix (50 files, 159.08 KB)
# 09:26 exit 0 — DONE Packaged: dist/pipelinek-studio-1.0.0.vsix (50 files, 161.26 KB)
#   second run kept extension/dist/extension.js = 305e857d… and produced
#   sha256 149374e53f87f5eced7a84bccc7b4c3c4dbbcb81fee2ca9c7995329b224d2335 (165,129 bytes)
```

Packaging required the pre-existing packaging command to know the repository
(`package.json` gained a `repository` entry pointing at the real origin); no new
tool or dependency was introduced. `vsce` reported two pre-existing warnings
(`LICENSE` missing, no `.vscodeignore`/`files`), so `src/`, `scripts/` and the
test configs are still shipped inside the VSIX — cosmetic bloat, unchanged
behaviour.

Final packaging run (corrected artifact, after the race fixes landed and `dist/`
was rebuilt at 09:37:06), and the delivered artifact:

```bash
cd apps/vscode && vsce package --out dist
# 09:40 exit 0 — DONE Packaged: dist/pipelinek-studio-1.0.0.vsix (50 files, 166.16 KB)
#   170,150 bytes; sha256 a065df25…   ← superseded: README text changed afterwards
# 09:41 exit 0 — DONE Packaged: dist/pipelinek-studio-1.0.0.vsix (50 files, 166.5 KB)
#   170,501 bytes; sha256 80e7db2b…   ← superseded: README stopped pinning an archive hash
# 09:42 exit 0 — DONE Packaged: dist/pipelinek-studio-1.0.0.vsix (50 files, 166.54 KB)
#   170,532 bytes; sha256 fdfbff2862a9a2a82cff27ec6fc3fbb151b1d21ef00881d977696293fa53252d
#   extension/dist/extension.js == dist/extension.js == 94969ccff345c4b3c17454f9736f7afc369922d3f04b524867caebf147a46015
```

The delivered artifact is the 09:42 build (`fdfbff28…`): the same compiled code,
with the final README inside the package (`extension/readme.md` contains the
artifact-bound paragraph, and README.md was last written before the archive).
Every earlier VSIX build is superseded; only this one is delivered, and only its
archive/test evidence is claimable at this revision. Reproducibility note: the
archive is not byte-reproducible across runs (vsce stamps entry mtimes), so the
checksum identifies one build, not a content hash of the tree — which is why the
README points at this receipt for the archive hash instead of embedding it (an
embedded hash would be rewritten by the next repackaging, as it was at 09:40).


### 4. Archive/manifest check — and the inventory defect it had

Final run on the corrected artifact:

```bash
cd apps/vscode && node scripts/extension-host-smoke.mjs dist/pipelinek-studio-1.0.0.vsix
# exit 0
VSIX ARCHIVE/MANIFEST CHECK (not an Extension Host run)
  archive tree OK: 16 dist files
  command inventory OK: 11/11 commands (incl linkExperiment, runDiagnostics)
  activation events OK: 8 onCommand entries all registered
  sidebar view container + tree view OK
VSIX archive/manifest check OK: dist/pipelinek-studio-1.0.0.vsix
```

The same check was also run on the superseded pre-correction archive with
equivalent output (exit 0), which is why it cannot distinguish the correction:
this check validates packaging shape, not race behaviour. The archive check was
run exactly once against the final artifact.

The previous check asserted 9 command ids with presence-only matching, so
`linkExperiment` and `runDiagnostics` were never verified. Under the new
exact-inventory rule the old list fails first (RED, observed before the change,
`node -e` probe against `package.json`):

```text
manifest commands: 11 | old inventory: 9
manifest ids absent from old inventory: ["pipelinek-studio.linkExperiment","pipelinek-studio.runDiagnostics"]
exact inventory: FAIL   (exit 1)
```

GREEN is the run above. The check now also fails on duplicate ids, on
contributed-but-unlisted commands, and on `onCommand:` activation events that
name a command the package does not contribute. Preserve evidence: the base
revision of the same script (`git show HEAD:...` copied to
`build/r5-acceptance/diff-check/extension-host-smoke.base.mjs`) still exits 0
against the same VSIX and prints `16 dist files / 11 commands declared / sidebar
view container` — the structural assertions it owned were kept, only the
inventory rule was strengthened.

### 5. REAL VS Code Extension Host (S6) — PRE-CORRECTION ARTIFACT

> Scope warning: this host run used the extension **before** the T3/T4 race
> corrections (`dist/extension.js` `305e857d…`, VSIX `149374e5…`). The final
> artifact was deliberately **not** re-run through a host in this batch; the
> 9/9 result below must not be read as a claim about the corrected build.


```bash
cd apps/vscode && node scripts/run-extension-host.mjs
# exit 0
```

Launch (real desktop build, isolated state):

```text
vscode binary: /home/linuxbrew/.linuxbrew/Caskroom/.../VSCode-linux-x64/code (desktop app behind /home/linuxbrew/.linuxbrew/bin/code)
isolated user-data: .../build/r5-acceptance/extension-host/run-20261017072553/user-data
isolated extensions: .../build/r5-acceptance/extension-host/run-20261017072553/extensions
isolated shared-data: .../build/r5-acceptance/extension-host/run-20261017072553/shared-data
isolated workspace: .../build/r5-acceptance/extension-host/run-20261017072553/workspace
extension host exited code=0 signal=none after 7102ms
  PASS extension-host: extension present and activated — vscode 1.121.0, extension 1.0.0
  PASS extension-host: all 11 contributed commands are registered — 16 pipelinek-studio commands registered
  PASS extension-host: command handlers run without network and report their guard — openRun/runDiagnostics guards executed
  PASS extension-host: Seal + Run refuses an unlinked document without any request — refused in 1ms (no linked experiment)
  PASS extension-host: CodeLens provider produces the real lenses — ["Studio: Seal + Run","Studio: No linked experiment"]
  PASS extension-host: sidebar view is registered — pipelinek-studio.tree.focus resolved
  PASS extension-host: configuration contribution defaults — baseUrl/allowInsecureHttp/profile defaults match README
  PASS extension-host: a settings change disposes and rebuilds the connection — connection reloaded twice, handlers still callable
  PASS extension-host: no unhandled rejection or exception — no unhandled errors observed
host report: vscode 1.121.0, 9/9 passed, 0 failed
REAL EXTENSION HOST: PASS
```

This is the actual Extension Host: `scripts/extension-host-test.cjs` is loaded
through the CLI's `--extensionTestsPath` and its `require("vscode")` is the real
API (no `vscode` mock; `vitest`'s alias is not involved). The 16 registered
`pipelinek-studio.*` commands are the 11 contributed commands plus VS Code's
generated view commands (`pipelinek-studio.tree.focus`, `.removeView`, …). The
run's `host-report.json` records the same 9/9 and the isolated workspace path.

`--shared-data-dir` is passed explicitly: by default the desktop app reads and
writes the global `~/.vscode-shared/sharedStorage/state.vscdb`, which would be an
out-of-scope write. Two earlier exploratory launches (09:19 and 09:20) ran before
that flag was found; the file's mtime stayed at its pre-existing value after the
final two launches, so both final runs wrote no global state. This is reported
as a residual risk rather than hidden.

### 6. VSIX side-load (S6, "side-load") — PRE-CORRECTION ARTIFACT

> Scope warning: this install listed the pre-correction archive. The final
> corrected VSIX was packaged and archive-checked, but not re-installed; see
> [Correction history](#correction-history-post-review) and
> [What is NOT proven](#what-is-not-proven).


```bash
cd apps/vscode && node scripts/run-extension-host.mjs --install-vsix dist/pipelinek-studio-1.0.0.vsix
# exit 0
SIDE-LOAD CHECK (installs the packaged VSIX into an isolated extensions dir; activation is proven by the host run)
installed via: /home/linuxbrew/.linuxbrew/bin/code
Installing extensions...
Extension 'pipelinek-studio-1.0.0.vsix' was successfully installed.
installed extension: pipelinek-studio.pipelinek-studio@1.0.0 (11 commands, main dist/extension.js)
--list-extensions reports: ["pipelinek-studio.pipelinek-studio@1.0.0"]
VSIX SIDE-LOAD: PASS (installed and listed from the isolated extensions dir)
```

## Correction history (post-review)

Appended, not a rewrite: the evidence above is kept exactly as first observed.

1. **Independent T3/T4 verification found 3 race blockers.** They were fixed in
   one correction batch (`connection.ts`, `extension.ts`, `treeView.ts` and
   their tests; `studioApi`/`sseClient` untouched). The independent re-verifier
   then replayed its original four probes and all PASSED:
   (1) consent/stream under the declined-then-allowed flow, (2) no stale client
   after a URL change with a declined consent, (3) tree events survive an
   overlapping authoritative snapshot, (4) exactly one concurrent submit is
   admitted while a deliberate sequential submit is still allowed.
2. **Automated suite grew 124 → 131 tests** (`connection` 10→12, `treeView`
   20→21, `extension` 26→30). The final observed run on the corrected tree is
   `8 files / 131 tests passed`, exit 0. The targeted verifier run was 63 + 4
   probes (parent-reported, not re-executed here).
3. **`dist/` was rebuilt after the fixes** (`dist/extension.js`
   `305e857d…` → `94969ccf…`), so the previously packaged VSIX became stale.
   The superseded archive (`149374e5…`) is retained above only as the artifact
   the host/Studio runs belong to.
4. **Final packaging + archive check ran in this order:**
   `npm run build` (no-op, exit 0) → `npm test` (131 passed, exit 0) →
   `vsce package --out dist` (09:40, exit 0, sha256 `a065df25…`) →
   `node scripts/extension-host-smoke.mjs …` (exit 0) → README wording corrected
   → `vsce package --out dist` (09:41, exit 0, sha256 `80e7db2b…`) →
   `node scripts/extension-host-smoke.mjs …` (exit 0) → README stopped pinning an
   archive hash (so repackaging cannot invalidate it) → `vsce package --out dist`
   (09:42, exit 0, 170,532 bytes, sha256 `fdfbff28…`) →
   `node scripts/extension-host-smoke.mjs …` (exit 0, identical output). Every
   intermediate archive was superseded only by packaged Markdown; each was
   repackaged rather than delivered, so the shipped README matches the
   repository. The archive check validates packaging shape — it cannot
   distinguish the race correction.
5. **Artifact binding rule adopted:** an observation is evidence for the artifact
   it was produced from and nothing else. Therefore the final artifact's proof
   boundary is: automated 131 tests + archive/manifest check + byte identity of
   `extension/dist/extension.js` with the current `dist/`. It has **no** host run,
   **no** Studio run and **no** side-load listing of its own.
6. **Full Gradle suite (parent job3, exit 0):** XML under
   `*/build/test-results/test/` totals **135 result files / 864 tests /
   0 failures / 0 errors / 1 skipped** — recorded exactly as reported, and the
   skip is identified below rather than counted as a pass.

### Skipped test

| Suite | Test | Reason (from the XML) |
|---|---|---|
| `dev.pipelinek.studio.infra.sdk.PipelineKE2EIT` | `real SDK completes a run and Studio persists run binding(Path)` | `Assumption failed: Skipping E2E: SDK_PATH env not pointing to valid PipelineK distribution` (`assumeTrue(false, …)` in `PipelineKE2EIT.kt:45-50`) |

That skip is the real-engine UAT gap: no PipelineK distribution is installed in
this environment, so no run was executed. It is **not** covered by the 864/0/0
count as a success.

## What is NOT proven

These are limitations, not passes. Items 8–10 apply specifically to the **final
corrected artifact**, which has no host, Studio or side-load run of its own.

1. **No modal consent / no HTTP inside the Extension Host.** Plaintext HTTP
   requires `showWarningMessage({ modal: true })` confirmation. A headless host
   run cannot answer a modal, and the test does not bypass the guard, stub the
   dialog, or flip the setting. Therefore the host evidence covers activation,
   providers, command registration, configuration defaults and the
   settings-reload disposal path, but **not** a live tree/SSE inside the editor.
   That last step needs a human clicking "Allow HTTP" (or an operator-trusted
   TLS certificate for the studio), and is reported as **not covered**.
2. **No click-through run/cancel/Seal+Run in the host.** Those need QuickPick and
   modal interaction against a connected studio, i.e. the same human step as (1).
   What is proven is that the handlers are registered, that guards run without a
   network request (`openRun`, `runDiagnostics`, unlinked `sealAndRun`), and that
   the CodeLens provider produces the real lenses for a real `*.pipeline.kts`
   document.
3. **`deactivate()` disposal is observed, not asserted.** VS Code calls
   `deactivate()` on host shutdown; the run proves a clean exit `code=0` with no
   unhandled errors, and the settings-change test proves the connection is
   disposed and rebuilt while the extension stays responsive. The internal
   `Disposable.dispose()` of the old client is not separately observable through
   the public host API.
4. **Installed-VSIX activation is not exercised**, only installation and
   listing — and that listing belongs to the superseded pre-correction archive.
   Activation was proven for the byte-identical `dist/` code of the
   pre-correction build only.
5. **No UI claim is made from mocks.** The repo's `vitest` suite (131 tests on
   the corrected tree, including `__mocks__/vscode.ts`) is a unit/integration
   suite; it passed, and it is *not* offered as host or Studio proof here.
6. **No PipelineK run was executed.** The probe's experiments are created and
   persisted; no `--sdk-classpath` PipelineK runtime was available (the Gradle
   suite's single skip names the same gap), so no run was submitted. Run
   execution/step streaming UAT remains unavailable in this environment rather
   than simulated.
7. **Global VS Code state.** Full isolation was verified for the pre-correction
   runs (`user-data`, `extensions`, `shared-data`, `workspace` all inside
   `build/r5-acceptance`), but the two exploratory launches described in §5
   opened the pre-existing global `~/.vscode-shared/sharedStorage/state.vscdb`.
   No production Studio database or user settings file was involved. No desktop
   launch of any kind was performed in the correction batch.
8. **Final-artifact host behaviour is unproven.** The corrected code was not
   loaded into a real Extension Host after the race fixes; the 9/9 host result
   describes the pre-correction build.
9. **Final-artifact Studio behaviour is unproven.** The 295 ms SSE and 4,042 ms
   reconnect figures describe the pre-correction transport build.
10. **Full editor consent, installed-VSIX activation and real-engine UAT remain
    NOT PROVEN** for any artifact in this branch.

## Residual blockers

- A human must confirm plaintext HTTP once (or the studio must serve a
  certificate the extension accepts) before an in-editor end-to-end
  tree/run/cancel acceptance can be recorded. Until then S6's "package and test
  in a real Extension Host" is satisfied at the transport/host level, not at the
  modal-consent level.
- The corrected artifact needs its own host run (and Studio probe) before any
  race-fix behaviour is claimed as observed in a real editor. The archive check
  cannot distinguish the correction.
- Installed-VSIX activation is unproven; side-load listing exists only for the
  superseded archive.
- `repo: apps/vscode` still ships `src/`, `scripts/` and test configs inside the
  VSIX (no `.vscodeignore`); harmless but noisy for reviewers.
- The Studio probe requires the built CLI at
  `studio-cli/build/install/studio-cli`; on a clean checkout `./gradlew
  :studio-cli:installDist --offline` must run first (documented in the runner's
  `UNAVAILABLE` message path).
- One Gradle test is skipped by assumption (`SDK_PATH`), so the 864-test total
  should be quoted as 863 executed + 1 skipped, never as 864 passes.

## Key Learnings

1. **A real Studio probe needs the built CLI, not the stale one.** The
   `studio-cli/build/install` tree predated T1's SSE wiring; `./gradlew
   :studio-cli:installDist --offline` (5 s) was enough to make the probe measure
   the code in the working tree. Offline mode proves nothing was downloaded.
2. **The `code` wrapper is not the Extension Host.** `bin/code` launches
   `cli.js` with `ELECTRON_RUN_AS_NODE=1`, hands off to the desktop app and exits
   in ~1.3 s with code 0. Waiting on it produced a false "no report" failure and
   would have mis-reported the host result. Launching the Electron binary the
   wrapper resolves (`<install>/code`) makes the process we wait on *be* the
   host: real exit code, real lifetime, report on disk.
3. **`--extensionTestsPath` does not need `vscode-test` or a test framework.**
   A dependency-free CommonJS module exporting `run(testsRoot, callback)` works;
   `require("vscode")` is the real API inside the host, so activation, commands,
   views, CodeLens and configuration can all be asserted without mocks.
4. **Consent is the real boundary, not a missing feature.** Every in-editor
   network path runs after an explicit modal approval. Testing around it would
   have produced a green that meant nothing, so the honest split is: host-level
   proof automated, modal consent left to a human.
5. **Isolation needs one more flag than expected.** `--user-data-dir` and
   `--extensions-dir` do not redirect VS Code's shared storage; the hidden
   `--shared-data-dir` does. Without it, a "real host" test silently writes the
   developer's `~/.vscode-shared` database.
6. **Presence-only manifest checks rot.** The archive smoke listed 9 commands
   and asserted each was present, so two contributed commands could disappear
   from the inventory unnoticed. Exact bidirectional set comparison plus
   `onCommand:` activation-event validation turns a checklist into a drift
   detector.
7. **Evidence is artifact-bound, and race fixes make it stale instantly.** Any
   `dist/` rebuild silently invalidates a prior host/Studio observation while
   leaving every test green. Recording the artifact hash next to each
   observation — and repackaging plus re-labelling when it changes — is the only
   way to keep a receipt honest without re-running time-expensive real-host
   probes.
8. **A skipped test is not a passing test.** `864 tests / 0 failures / 1 skipped`
   must be quoted as 863 executed + 1 assumption-skip; the skip names the
   real-engine UAT gap (`SDK_PATH` unset), which is exactly the limitation that
   would otherwise hide behind a green suite.
