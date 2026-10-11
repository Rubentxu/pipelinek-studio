# R6+1 Release Receipt — `v1.0.0-rc.9` (2026-10-12)

**Verdict:** `PASS` at code level. Identical surface to `v1.0.0-rc.8`
(794/794 tests verde) plus the R5 EVO-C.T follow-up (`ce6d634`).
The D-007 triple-gate for `v1.0.0` stable is **NOT** yet met;
the operator chose to publish another prerelease while the
external evaluation + R4 B5.4 cert on a clean machine are
still pending out-of-sandbox.

## Identifiers

| Field | Value |
|---|---|
| Tag (annotated) | `v1.0.0-rc.9` |
| Tag SHA | `ce6d6345...` (HEAD of `main` at release) |
| GitHub Release | https://github.com/Rubentxu/pipelinek-studio/releases/tag/v1.0.0-rc.9 |
| Pre-release | yes |
| Studio version | `1.0.0-rc.7` (unchanged; the `gradle.properties:studio.version` value carried through R2..R6) |
| PipelineK SDK pin | `0.48.0-rc1` |

## Changes since `v1.0.0-rc.8`

| SHA | Item |
|---|---|
| `ce6d634` | fix(vscode): R5 EVO-C.T follow-up — exclude `__mocks__/` and `scripts/` from ESLint (the in-tree test mock and CI smoke script use Node APIs that the recommended ruleset flagged as no-undef) |

No new functionality, no new tests; the eslint exclusion is a
config change only.

## Tests (unchanged from rc.8)

- Gradle check: **654/654 Kotlin** verde
- detekt: **0 findings**
- vscode: **45/45** verde
- mcp: **31/31** verde
- web: **64/64** verde
- **Total: 794/794**

## Assets (built 2026-10-12 00:20 UTC)

| File | Size | SHA-256 |
|---|---|---|
| `studio-cli-1.0.0-rc.7.tar` | 36 358 144 B (34.7 MB) | `7027d0c66a7eb20ca40c6b1ecc60274b5027aef0960024a75c227de8745e9072` |
| `manifest.json` | 10 069 B (9.8 KB) | `01c9e6dc7f1e75bc11a1502a9d6ac815dcde13350ba340c005fffc2552edf239` |
| `sbom.json` | 10 438 B (10.2 KB) | `9c9bd831166951e3ba5dcb3b1639ecba4796aea639a87667f6e064ef2ed9a946` |
| `sbom.cdx.json` | 26 099 B (25.5 KB) | `bb73f20ce1c0cfd74fc4b37afdfa331b760b42bcef5adf00d14707a1918e25d2` |
| `pipelinek-studio-1.0.0-rc.7.vsix` | 93 485 B (91.29 KB) | `deeaed16fcd7bd7d74cfffb2d354685d10ae89c7a0bdb4f66f1b38a316821e1f` |

## §D step 8 — install smoke from the published asset

```
$ curl -L -o studio-cli.tar https://github.com/.../v1.0.0-rc.9/studio-cli-1.0.0-rc.7.tar
$ sha256sum studio-cli.tar | grep -q 7027d0c6... && echo "rc.9 tar SHA256 OK"
rc.9 tar SHA256 OK
$ tar -xf studio-cli.tar
$ studio-cli-1.0.0-rc.7/bin/studio-cli --help
ok    studio_cli_version       studio 1.0.0-rc.7 (kotlin 2.1.0, ktor 3.0.1)
ok    sdk_lockfile_present     sdk.lock.json found on classpath

$ curl -L -o pipelinek-studio.vsix https://github.com/.../v1.0.0-rc.9/pipelinek-studio-1.0.0-rc.7.vsix
$ sha256sum pipelinek-studio.vsix | grep -q deeaed16... && echo "rc.9 VSIX SHA256 OK"
rc.9 VSIX SHA256 OK
$ node apps/vscode/scripts/extension-host-smoke.mjs pipelinek-studio.vsix
VSIX smoke OK: pipelinek-studio.vsix
  14 dist files
  9 commands declared ✓
  sidebar view container ✓
```

## Forward-gates for `v1.0.0` (D-007)

| Gate | Status |
|---|---|
| 1. External evaluation | **PENDING** (out of this sandbox) |
| 2. R4 B5.4 cert on a clean machine | **PENDING** (R0.3.5 kill-9 smoke BLOCKED in sandbox per `R-VER-001`) |
| 3. Operator explicit approval | **RECEIVED** in this session (`apruebo`) |

The operator chose the conservative path: publish another
prerelease (`v1.0.0-rc.9`) and leave `v1.0.0` stable out of
reach until gates 1 and 2 clear. This honours the spec's
"No promover directamente a `v1.0.0` estable por haber
completado el roadmap" rule.

---

**Recibo generado por:** Agente (rc.9 closure, sesión de
2026-10-12). **SHA del release:** `ce6d634`.
