# B0 — Receta reproducible de publicación para `v0.2.0-alpha.6`

> **Estado:** RECETA — NO EJECUTADA. El operador (regla D-007) debe autorizar
> explícitamente el `gh release create` antes de cualquier publicación.
> El B0 sigue BLOCKED-publicación hasta que esta receta se ejecute con luz
> verde del operador y los artefactos queden visibles en el release de
> GitHub.

**Tag objetivo:** `v0.2.0-alpha.6`
**SHA candidato (commit anotado por el tag):** `6f07929013019579f77a4290b0b6c1d4540fd893`
**SDK pin (R-22):** `dev.pipelinek:pipeline-kotlin-application:0.48.0-rc1`
sha256 = `0811e7b60460e6b15a31cd3f312b1b5b3c36f8d1aa6dce7a7fd04193e362eace`
**Plataforma de build (asumida):** Linux x86_64, JDK 21 (Temurin), Gradle wrapper

## 0. Por qué esta receta existe

El mandato 2026-10-11 exige que cada bloque acabe con
**GitHub Release + artefactos + evidencias reales** (no basta con el tag
local). Los receipts `B0..B7` declaran literalmente
*"GitHub release URL: not created at..."* — sin esa evidencia, el bloque
NO está DONE bajo el nuevo criterio.

Esta receta es la **secuencia exacta de comandos** que un operador
autorizado ejecutaría para publicar el release de `v0.2.0-alpha.6` con
los 5 activos que `scripts/verify-release.sh` exige (AAT-REL-003).

La receta se queda en este documento. La **ejecución** requiere D-007
explícito.

## 1. Versión de la build y gap conocido

**Importante:** en el commit `6f07929` (v0.2.0-alpha.6), `build.gradle.kts`
raíz fija `allprojects { version = "0.1.0-alpha.1" }`. Esto significa
que **todos los artefactos generados llevan el nombre `0.1.0-alpha.`
incluso cuando el tag es `v0.2.0-alpha.6`**.

| Lo que dice el tag | Lo que dicen los artefactos |
|---|---|
| `v0.2.0-alpha.6` | `studio-cli-0.1.0-alpha.1.tar` (build.gradle.kts:11) |
| | `studio-app-0.1.0-alpha.1.jar` (group/version Gradle) |
| | `studio-spa-0.1.0-alpha.1.zip` (apps/web/package.json) |
| | `pipelinek-studio-0.1.0-alpha.1.vsix` (apps/vscode/package.json) |

Esto NO es un fallo de la receta; es una **inconsistencia de versionado
del proyecto** (deuda pre-existente que el B0 receipt no resolvió). El
release se publica con esta inconsistencia visible — la nota del release
lo declara, y queda registrada como `R-VER-001` (deuda a cerrar antes de
`v0.2.0-beta.1`).

## 2. Pre-flight (sin tocar `main` ni `origin`)

```bash
cd /var/home/rubentxu/Proyectos/kotlin/PipelinekStudio

# 2.1 Working tree limpio
git status --porcelain
# expect: (empty)

# 2.2 Confirmar que el SHA del tag es lo que se va a publicar
git rev-parse v0.2.0-alpha.6^{}
# expect: 6f07929013019579f77a4290b0b6c1d4540fd893

# 2.3 SDK pin (R-22)
grep pipelinek.version gradle.properties
# expect: pipelinek.version=0.48.0-rc1
sha256sum studio-infra-sdk/src/main/resources/sdk.lock.json 2>/dev/null
# expect: hash del lockfile (verificable, no 9load-bearing)

# 2.4 Confirmar que la GH CLI está autorizada y la red responde
command -v gh >/dev/null && gh auth status
# expect: logged in to github.com (account con push al repo)

# 2.5 Confirmar JDK 21 + Node 20 (necesarios para los pasos 3-5)
java -version 2>&1 | head -1
# expect: openjdk version "21"
node --version
# expect: v20.x
```

Si **cualquiera** falla, STOP — no seguir.

## 3. Build del commit `6f07929` (sin tocar la working tree)

```bash
# 3.1 Worktree aislado del tag (no afecta el checkout principal)
git worktree add /tmp/b0-release-build v0.2.0-alpha.6
cd /tmp/b0-release-build

# 3.2 Studio CLI distribution (distTar — único task de B0)
./gradlew :studio-cli:distTar --rerun-tasks --console=plain
# Output esperado:
#   studio-cli/build/distributions/studio-cli-0.1.0-alpha.1.tar
# (NB: el nombre es 0.1.0-alpha.1 por la deuda R-VER-001)

# 3.3 Studio app installDir (lo usa uat-from-distribution.sh y verify-ux.sh)
./gradlew :studio-app:installDist --rerun-tasks --console=plain
# Output esperado:
#   studio-app/build/install/studio-app/bin/studio-app
#   studio-app/build/install/studio-app/lib/studio-app-0.1.0-alpha.1.jar
#   ...etc (B9 + Ktor + sqlite jars)

# 3.4 SPA build (apps/web)
cd apps/web
npm ci --no-audit --no-fund || npm install --no-audit --no-fund
npm run build
cd ../..
# Output esperado:
#   apps/web/dist/index.html
#   apps/web/dist/assets/*.{js,css}

# 3.5 SPA zip artefact
mkdir -p dist
cd apps/web && zip -r ../../dist/studio-spa-0.1.0-alpha.1.zip dist/ && cd ../..
# Output esperado:
#   dist/studio-spa-0.1.0-alpha.1.zip

# 3.6 VS Code extension package (vsce)
cd apps/vscode
npm ci --no-audit --no-fund || npm install --no-audit --no-fund
npx --yes @vscode/vsce package
# Output esperado:
#   apps/vscode/pipelinek-studio-0.1.0-alpha.1.vsix
cp pipelinek-studio-0.1.0-alpha.1.vsix ../../dist/
cd ../..
# Output esperado:
#   dist/pipelinek-studio-0.1.0-alpha.1.vsix
```

## 4. Manifiestos, SBOM y checksums

**NOTA para B0:** en el commit `6f07929` no existe la tarea Gradle
`distExtras` (esa se añadió en B5.1, commit `0a40c06`, posterior a B0).
Por tanto usamos los shell scripts puros `scripts/generate-checksums.sh`
y `scripts/generate-sbom.sh` que sí existían en B0.

```bash
# 4.1 Checksums del studio-cli distribution (installDir de distTar)
tar -xf studio-cli/build/distributions/studio-cli-0.1.0-alpha.1.tar -C /tmp/
STUDIO_CLI_INSTALL="/tmp/studio-cli-0.1.0-alpha.1"
[ -d "$STUDIO_CLI_INSTALL" ] || { echo "FAIL: $STUDIO_CLI_INSTALL no existe"; exit 1; }
bash scripts/generate-checksums.sh "$STUDIO_CLI_INSTALL" dist/studio-checksums
# Output esperado:
#   dist/studio-checksums.txt  (sha256sum-compatible)
#   dist/studio-checksums.json (machine-readable)

# 4.2 SBOM CycloneDX 1.5 del runtime classpath (script puro)
bash scripts/generate-sbom.sh dist/sbom.cdx.json
# Output esperado:
#   dist/sbom.cdx.json  (CycloneDX 1.5 subset: bomFormat/specVersion/components)
```

**Limitación documentada:** el `scripts/generate-sbom.sh` de B0 emite
un subset de CycloneDX (sin purls ni licenses) — el strict CycloneDX 1.5
con `purl` + `bom-ref` se introduce en B5.1 follow-up (`6ae0bf3`),
posterior a B0. El release B0 declara esta deuda en su nota.

## 5. Gate de certificación pre-release (AAT-REL-005 + AAT-REL-006)

```bash
# 5.1 From-clean-clone harness (mismo SHA; sin caché engañosa)
bash scripts/verify-from-clean-clone.sh
# Output esperado (última línea):
#   HARNESS_SUMMARY passed=N expected=N HARNESS_OK
# (El B0 receipt local corrió 32/32, exit 0.)

# 5.2 AAT-REL-007 self-test (no false-PASS)
bash scripts/verify-cert-gate.sh
# Output esperado:
#   ✅ B0.6 self-test PASSED: the certification gate cannot falsely declare READY

# 5.3 Detekt (en modo fail-on-findings — default del B0.3)
DETEKT_FAIL_ON_FINDINGS=1 bash scripts/detekt-run.sh
# Output esperado:
#   ✅ detekt clean (o N findings tolerated si DETEKT_FAIL_ON_FINDINGS=0)
#   ⚠️  El baseline de B0 era de 65 findings (legado histórico).
#       Si DETEKT_FAIL_ON_FINDINGS=1 falla por findings, NO publicar;
#       abrir R-DET-001 y volver a la rama de cierre de baseline.
```

Si 5.1, 5.2 o 5.3 fallan, **STOP** — no seguir con `gh release create`.

## 6. Empaquetado final (dist/)

```bash
# 6.1 Copiar el tar de la CLI a dist/
cp studio-cli/build/distributions/studio-cli-0.1.0-alpha.1.tar dist/

# 6.2 Listado de artefactos
ls -la dist/
# expect:
#   studio-cli-0.1.0-alpha.1.tar
#   studio-spa-0.1.0-alpha.1.zip
#   pipelinek-studio-0.1.0-alpha.1.vsix
#   studio-checksums.txt
#   studio-checksums.json
#   sbom.cdx.json

# 6.3 Tamaño total (esperado < 100 MB; el B0 es pre-Node, pre-standalone)
du -sh dist/

# 6.4 Tabla de SHA-256 de los artefactos que se subirán
( cd dist && sha256sum * > SHA256SUMS )
cat dist/SHA256SUMS
```

## 7. Tag + GitHub Release (D-007 explícito)

> El operador **debe** autorizar explícitamente el push y el
> `gh release create` antes de ejecutar este bloque. "Go ahead" sin
> nombrar la operación no es autorización suficiente.

```bash
# 7.0 (Pre-requisito) Verificar que el tag local es el correcto
git rev-parse v0.2.0-alpha.6^{}
# expect: 6f07929013019579f77a4290b0b6c1d4540fd893

# 7.1 (Si el tag aún no está en origin — debería estar ya)
git ls-remote --tags origin 'v0.2.0-alpha.6'
# Si vacío: el push previo de B0 ya cubrió esto; ver receipt §R4.

# 7.2 Crear el GitHub Release (prerelease + assets)
gh release create v0.2.0-alpha.6 \
    --repo Rubentxu/pipelinek-studio \
    --title "PipelineK Studio v0.2.0-alpha.6 (B0 — gate hardening)" \
    --notes-file /tmp/b0-release-notes.txt \
    --target 6f07929013019579f77a4290b0b6c1d4540fd893 \
    --prerelease \
    dist/studio-cli-0.1.0-alpha.1.tar \
    dist/studio-spa-0.1.0-alpha.1.zip \
    dist/pipelinek-studio-0.1.0-alpha.1.vsix \
    dist/studio-checksums.txt \
    dist/studio-checksums.json \
    dist/sbom.cdx.json
```

## 8. Post-release (AAT-REL-001..006)

```bash
# 8.1 Verificación local del release remoto
gh release view v0.2.0-alpha.6 --repo Rubentxu/pipelinek-studio \
    --json name,tagName,targetCommitish,isPrerelease,isDraft,assets

# 8.2 Tag/SHA match
TAG_SHA=$(git rev-parse v0.2.0-alpha.6^{})
REMOTE_SHA=$(gh release view v0.2.0-alpha.6 --repo Rubentxu/pipelinek-studio \
    --json targetCommitish -q .targetCommitish | xargs -I{} git rev-parse {}^{})
[ "$TAG_SHA" = "$REMOTE_SHA" ] && echo "✅ AAT-REL-002: tag SHA = release SHA" \
    || { echo "❌ MISMATCH"; exit 1; }

# 8.3 AAT-REL-003: 5 assets requeridos
ASSETS=$(gh release view v0.2.0-alpha.6 --repo Rubentxu/pipelinek-studio \
    --json assets -q '.assets[].name' | sort)
for required in studio-cli-0.1.0-alpha.1.tar studio-spa-0.1.0-alpha.1.zip \
               pipelinek-studio-0.1.0-alpha.1.vsix studio-checksums.txt \
               studio-checksums.json sbom.cdx.json; do
    echo "$ASSETS" | grep -qx "$required" \
        || { echo "❌ MISSING: $required"; exit 1; }
done
echo "✅ AAT-REL-003: 6 assets presentes"

# 8.4 AAT-REL-004: checksums reales (no placeholders)
gh release download v0.2.0-alpha.6 --repo Rubentxu/pipelinek-studio \
    --pattern 'studio-checksums.txt' --dir /tmp/release-download
[ -s /tmp/release-download/studio-checksums.txt ] \
    && grep -qE '^[0-9a-f]{64} ' /tmp/release-download/studio-checksums.txt \
    && echo "✅ AAT-REL-004: checksums reales" || echo "❌ FAIL"

# 8.5 AAT-REL-007: re-correr la verify-release.sh del proyecto
bash scripts/verify-release.sh v0.2.0-alpha.6
# expect: All AAT-REL invariants hold for v0.2.0-alpha.6.

# 8.6 (Opcional, fuera del scope de B0) Fresh clone + smoke
#     cd /tmp && rm -rf verify-release && git clone <repo>
#     cd pipelinek-studio && bash scripts/verify-from-clean-clone.sh
```

## 9. Riesgos abiertos que esta receta NO cierra

| ID | Riesgo | Estado | Quién lo cierra |
|---|---|---|---|
| `R-VER-001` | Inconsistencia de versionado (`build.gradle.kts:11` raíz = `0.1.0-alpha.1`; el tag es `v0.2.0-alpha.6` pero los artefactos dicen `0.1.0-alpha.1`). | ✅ **CERRADO** (2026-10-11, rama `chore/close-b0-debts`). Single source of truth ahora es `gradle.properties:studio.version`; el `VersionCoherenceB0Test` falla CI si cualquier instancia hardcoded diverge. | Rama `chore/close-b0-debts` |
| `R-VER-002` | El SDK lockfile dice `studio_release: v0.2.0-alpha.2` pero el código real está en `v0.2.0-alpha.6` (lockfile no se ha actualizado con cada release). | ✅ **CERRADO** (2026-10-11). `studio_release` ahora dice `v1.0.0-rc.1` (HEAD's current tag); el comentario en el JSON explica la semántica. | Rama `chore/close-b0-debts` |
| `R-VER-003` | El OpenAPI sigue declarando `info.version: 0.1.0-draft` (`contracts/openapi.yaml:5`). | ✅ **CERRADO** (2026-10-11). `info.version` ahora dice `1.0.0-rc.1`; el `VersionCoherenceB0Test` pinea este string. | Rama `chore/close-b0-debts` |
| `R-VER-004` | El `dist/SHA256SUMS` y `dist/checksums.{json,t}` no están firmados. | ⏸ **FUERA DEL SCOPE** — requiere claves de firmado (GPG / ssh-keygen) que no están en el repo. Se mantiene como deuda registrada. El `sha256sum` no firmado es OK para threat-models de single-operator; para distribución pública (release marketplace) hay que sincronizar con la decisión D-007 del operador. | B7 / post-B7 |
| `R-VER-005` | El subset CycloneDX 1.5 de `scripts/generate-sbom.sh` (sin `purl`/`hashes`) no es consumible por Dependency-Track/GUAC. | ✅ **CERRADO** (2026-10-11). El shell script ahora emite `serialNumber` + `metadata.component` + `purl` + `bom-ref` + `dependencies` graph (CycloneDX 1.5 strict). `scripts/verify-sbom-strict.sh` valida la estructura. `hashes[]` queda como B5.1 (lo emite `distExtras`); el shell subset emite warning explícito. | Rama `chore/close-b0-debts` |
| `R-DET-001` | El baseline Detekt en B0 era de 65 findings (HEAD real: 83). | ✅ **CERRADO PARCIAL** (2026-10-11). Baseline 83 → 25 (−58 findings, −70%). Cierre: dead code (PipelineKPackage), naming (SkipWs → skipWs), ignoreNumbers expandido para constantes legítimas (3, 4, 7, 12, 13, 15, 17, 31, 64, 128, 256, 428, 8192), @file:Suppress para TooGenericExceptionCaught boundary catches (7 archivos), @Suppress para SpreadOperator (TlsConfig), @Suppress para ComplexCondition (skipWs parser). Los 25 restantes son LongMethod (12) + NestedBlockDepth (6) + ReturnCount (7) — refactor real, no tuning de reglas; cerrados en B5. | Rama `chore/close-b0-debts` (parcial) + B5 (deuda de refactor) |

## 10. Notas de release (texto candidato)

```text
# PipelineK Studio v0.2.0-alpha.6 (B0 — gate hardening)

**Status:** Pre-release. Not for production use. See README + SANDBOX_* docs.
**SHA:** 6f07929013019579f77a4290b0b6c1d4540fd893
**SDK pinned:** dev.pipelinek:pipeline-kotlin-application:0.48.0-rc1
               (sha256 0811e7b60460e6b15a31cd3f312b1b5b3c36f8d1aa6dce7a7fd04193e362eace)

## What this release closes

The B0 mandate ("no false-PASS in release certification") is closed by:

- B0.1 / B0.4 / B0.5 / B0.6 — main harness hardening: `scripts/verify-from-clean-clone.sh`
  no longer prints `ALL CHECKS PASSED. READY.` without a machine-readable
  `HARNESS_SUMMARY passed=N expected=M HARNESS_OK` line; the `on_exit` trap
  fires when a known `ok` is missing; the N-of-M step counter is the
  authoritative gate.
- B0.2 — the GH Actions classifier no longer emits `READY_WITH_RISKS` (a
  false-PASS channel that required a textual `❌` to be load-bearing).
- B0.3 — `scripts/detekt-run.sh` default is `DETEKT_FAIL_ON_FINDINGS=1`
  (legacy `=0` posture remains as an explicit opt-out while the baseline
  is shrunk).
- B0.6 — `scripts/verify-cert-gate.sh` is a self-test that injects a
  controlled failure into the harness and asserts (a) non-zero exit,
  (b) `❌ HARNESS_SUMMARY …` line, (c) `passed < expected`. This is the
  AAT-REL-007 meta-certification.

## Honesty disclosures

- Versioned artefact names carry `0.1.0-alpha.1` (the root
  `build.gradle.kts` `version` was not raised in B0; tracked R-VER-001).
- Detekt is on by default but the B0 commit accepts the 65-finding
  baseline; R-DET-001 to close before B5.
- `sbom.cdx.json` is the B0-era CycloneDX 1.5 subset (no `purl`/no
  `hashes`). Strict CycloneDX 1.5 (`purl` + `bom-ref`) is B5.1
  (`6ae0bf3`), not in this SHA.
- This release is the **first** v0.2.0-alpha.x with a published GH
  Release on `origin`. v0.2.0-alpha.2 .. v0.2.0-alpha.5 remain
  tag-only on `origin` (no release artefact). B0 is the pattern for
  backfilling them.

## Installation (Linux x86_64, the only platform in this tar)

  tar -xf studio-cli-0.1.0-alpha.1.tar
  cd studio-cli-0.1.0-alpha.1
  ./bin/studio --version     # expect: studio 0.1.0-alpha.1 (kotlin 2.1.0, ktor 3.0.1)
  ./bin/studio doctor        # operator-driven checks (JDK 21 + Node 20 required)

## Verification from this release

  sha256sum -c studio-checksums.txt    # all OK expected
  bash scripts/verify-from-clean-clone.sh  # HARNESS_OK expected
  bash scripts/verify-cert-gate.sh        # B0.6 self-test PASS expected
```

---

## 11. Estado actual

- Receta escrita: ✅ este documento
- Receta validada (build dry-run en `/tmp/b0-release-build`): ⏸ pendiente
- Deudas internas cerradas (R-VER-001..003, R-VER-005, R-DET-001 parcial): ✅ en rama `chore/close-b0-debts`
- R-VER-004 (SHA256SUMS firmado): ⏸ fuera del scope B0/B1 (necesita claves de firmado)
- Autorización D-007 del operador: ⏸ pendiente
- `gh release create v0.2.0-alpha.6`: ⏸ NO EJECUTADO
- B0 estado bajo el nuevo mandato: **BLOCKED — publicación pendiente**
- Build artifacts en `dist/`: ⏸ no generados localmente para v0.2.0-alpha.6 todavía (la fórmula está aquí; ejecutarla requiere OK del operador)