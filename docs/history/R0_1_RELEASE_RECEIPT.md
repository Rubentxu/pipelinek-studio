# R0.1 — Recibo de cierre parcial

> **Bloque:** R0.1 (Reparar el sistema de certificación).
> **Release objetivo:** `v1.0.0-rc.2` (R0 completo).
> **Estado de R0.1:** **PASAR** con salvedades documentadas.
> **SHA integrado:** `fa6d78a` (en `origin/main`).
> **Fecha:** 2026-10-11.

Este recibo cubre **únicamente** R0.1. R0.2 y R0.3 no se cierran
en este commit; están en el backlog. La consolidación del bloque
R0 requiere los tres sub-bloques + la GitHub Release `v1.0.0-rc.2`
publicada, fuera del alcance de este commit.

---

## A. Antes de desarrollar

| Punto | Valor |
|---|---|
| HEAD auditada | `b2cdee6aa0d8691d580db29d2c1a9a3431647552` |
| Rama activa | `main` (sincronizada con `origin/main` antes del cambio) |
| Working tree | Limpio al inicio de R0.1 |
| Java | OpenJDK 21.0.8 LTS |
| Node | v25.9.0 (las apps usan `engines` no declaradas; CI pasa a 22) |
| Gradle wrapper | 8.14.5 |
| Kotlin | 2.4.20 |
| SDK PipelineK | `dev.pipelinek:pipeline-kotlin-application:0.48.0-rc1` sha256 `0811e7b6…` (sin cambio en R0.1) |
| Detekt | 1.23.8 (jar cacheado en `~/.cache/detekt/`) |
| GitHub Releases reales | 1 (solo `v0.1.0-alpha.1`); 11 tags sin release |

## B. Cambios

### R0.1.3 — Task Gradle `detekt` (CI ya no es no-op)

**Antes:** `.github/workflows/ci.yml` invocaba `./gradlew --no-daemon detekt` pero el task no existía en el build → la línea silenciaba "PASS" sin verificar nada.

**Ahora:** `build.gradle.kts` registra un task `detekt` que:

- Envuelve `scripts/detekt-run.sh` (postura coherente con el script externo)
- Lee `-Pdetekt.failOnFindings` (default `1`) y lo reenvía como `DETEKT_FAIL_ON_FINDINGS`
- Pinea inputs para invalidación correcta (`:rootProject:scripts/detekt-run.sh`, `detekt.yml`, los 7 directorios de fuentes Kotlin)
- **No** depende de `test` (encontrado fallo `UnknownTaskException` al intentar; documentado en el comentario del task)

**Postura:** fail-on-findings por defecto. Estado actual del baseline: **25 findings** (LongMethod 12 + NestedBlockDepth 6 + ReturnCount 7), todos refactors de controladores (StudioHttpModule.install 628 líneas, etc.), diferidos a **R5** per mandato R0.

Mientras R5 no cierre el baseline, `./gradlew detekt` **fallará honestamente** en CI — esto ES la nueva verdad (el estado anterior era un false-PASS).

Override para postura legacy (temporal, no recomendado):
```bash
./gradlew detekt -Pdetekt.failOnFindings=0
# o bien
DETEKT_FAIL_ON_FINDINGS=0 bash scripts/detekt-run.sh
```

### R0.1.4 — Lint y typecheck reales en las tres apps

**Antes:**

| App | `lint` | `typecheck` |
|---|---|---|
| `apps/mcp` | `"echo 'lint deferred to S1'"` | `"tsc -b --pretty"` (build mode, EMITE) |
| `apps/web` | `"echo 'lint deferred to S1'"` | `"tsc -b --pretty"` (EMITE) |
| `apps/vscode` | (no existía) | `"tsc --noEmit"` ✅ |

**Ahora:**

| App | `lint` | `typecheck` |
|---|---|---|
| `apps/mcp` | `"eslint . --max-warnings 0"` | `"tsc --noEmit"` |
| `apps/web` | `"eslint . --max-warnings 0"` | `"tsc --noEmit"` |
| `apps/vscode` | `"eslint . --max-warnings 0"` | `"tsc --noEmit"` ✅ |

**Stack de tooling añadido:**

- `eslint@^9` + `@eslint/js@^9` como devDependencies en `apps/{mcp,web,vscode}/package.json`
- Configuración flat en `apps/{mcp,web,vscode}/eslint.config.mjs`:
  - `js.configs.recommended` (reglas JS estándar)
  - Ignora `dist/`, `out/`, `*.d.ts`, `*.vsix`, `node_modules/`, `coverage/`
  - Comentario explícito en cada archivo sobre el alcance (R0.1.4) y el roadmap a endurecer (R3 con typescript-eslint / react-hooks / a11y)
- `package-lock.json` regenerado para reflejar las nuevas devDeps

**Por qué no usar `tsc --noEmit -b`:** los flags son incompatibles (TS5023); `-b` es "build mode" que emite por defecto, `--noEmit` es single-project typecheck. La postura correcta es `tsc --noEmit` con `noEmit: true` o sin él en el tsconfig (cada app tiene su tsconfig.json).

### R0.1.5 — CI workflow con `apps-checks` job

`.github/workflows/ci.yml` ahora declara **cuatro jobs** además de los existentes:

```
unit-tests     -> ./gradlew --no-daemon test
detekt         -> ./gradlew --no-daemon detekt  (postura fail-on-findings)
apps-checks    -> npm ci + lint + typecheck para apps/{mcp,web,vscode}
harness        -> (release-harness.yml, sin cambios en este commit)
```

`apps-checks` usa `actions/setup-node@v4` con Node 22 (compatible con los engines actuales; las apps no declaran `engines` todavía — gap a cerrar en R6).

### R0.1.1 — AsdfPluginB61Test (re-verificación)

El mandato declara "6 tests fallidos". Diagnóstico: en `b2cdee6` los 7 tests del archivo PASAN. Re-verificado tras los cambios de R0.1 con `--rerun-tasks`: 7/7 PASS.

**Conclusión:** el mandato puede haber sido escrito contra un estado anterior (pre-fusión de commits B6.1 + B6.4 + B6.5 + B6.6 que consolidaron el plugin). No hay código que arreglar; queda registrado como hallazgo sin acción.

### R0.1.2 — `asdf-pipelinek-studio/bin/`

Auditado. Contiene:

```
bin/download   (descarga tarball de GH Releases API)
bin/install    (extrae a $ASDF_INSTALL_PATH; chmod +x)
bin/list-all   (lista tags `v*`)
bin/list-bin   (devuelve `studio`)
```

`bin/install` y `bin/list-bin` son obligatorios per docs asdf-vm; `bin/download` y `bin/list-all` son auxiliares. El contrato está completo y documentado en `asdf-pipelinek-studio/README.md`. **No hay código que añadir.**

`bin/latest` (que aparece en algunos plugins asdf) NO es estándar — asdf tiene soporte built-in para `latest`. El plugin no lo necesita.

### R0.1.6 — Branch protection en `main` (impedimento documentado)

Diagnóstico:
```
$ gh api repos/Rubentxu/pipelinek-studio/branches/main/protection
{"message":"Branch not protected","status":404}
```

`main` no tiene branch protection. El setting es a nivel de repo (no se puede declarar desde un workflow). El token actual no tiene scope `admin:repo`.

**Per mandato:** "si no son configurables, registrar el impedimento y mantener el control de SHA verificado". Impedimento registrado aquí. Acción siguiente: pedir al operador (admin del repo) que aplique branch protection con required checks (unit-tests, detekt, apps-checks) en `Settings → Branches → main → Require status checks to pass before merging`.

---

## C. Evidencia ejecutada (local)

| Comando | Resultado |
|---|---|
| `./gradlew detekt -Pdetekt.failOnFindings=0` | `BUILD SUCCESSFUL` — 25 findings aceptados (legacy posture) |
| `./gradlew detekt` | `BUILD FAILED` — 25 findings (postura fail-on-findings honesta) |
| `cd apps/mcp && npm ci && npm run lint && npm run typecheck` | exit 0 |
| `cd apps/web && npm ci && npm run lint && npm run typecheck` | exit 0 |
| `cd apps/vscode && npm ci && npm run lint && npm run typecheck` | exit 0 |
| `./gradlew :studio-cli:test :studio-http:test :studio-infra-sdk:test --rerun-tasks` | 200 / 60 / 57 tests; 0 failures cada uno |
| Detekt (re-corrida) | 25 findings (sin cambios respecto a baseline) |

## D. Integración remota

| Paso | Resultado |
|---|---|
| SHA integrado en `origin/main` | `fa6d78a chore(ci): real ./gradlew detekt task + apps lint/typecheck (R0.1.3/4/5)` |
| Diff vs `b2cdee6` | +3423 / −102 (eslint config nueva + lockfile actualizado + CI + build.gradle.kts) |
| Push | OK (D-007 ya autorizado por el operador en commit previo `b2cdee6`) |
| Working tree post-push | Limpio |

## E. Riesgos / deuda residual

| ID | Riesgo | Estado | Próximo paso |
|---|---|---|---|
| `R-R0.1-001` | Detekt baseline de 25 findings | Diferido a R5 | refactor de controladores (LongMethod StudioHttpModule.install 628 líneas, etc.) |
| `R-R0.1-002` | Branch protection no aplicado | Impedimento (token insuficiente) | operador aplica vía Settings o GitHub Pro token |
| `R-R0.1-003` | `apps/{mcp,web,vscode}` no declaran `engines.node` | advertencia | añadir `engines` en cada `package.json` (R6) |
| `R-R0.1-004` | ESLint config es mínima (recommended JS only) | advertencia | endurecer con typescript-eslint + react-hooks + a11y (R3 area) |
| `R-R0.1-005` | el `verifyCleanClone` Gradle task tiene `dependsOn("test")` que falla (mismo bug que el detekt task anterior) | Diferido | arreglar en R6 o R0.5 cuando se ejecute |
| `R-R0.1-006` | `npm install` añadió 82-83 paquetes por app; lockfile creció (~3.4 KB cada uno) | menor | regenerar con `npm ci` en entorno limpio para confirmar reproducibilidad |
| `R-R0.1-007` | ESLint v9 está marcado deprecated por npm ("no longer supported") | advertencia | upgrade a v10 cuando la app se prepare para Node 24+ |

## F. Veredicto R0.1

**`PASS` con salvedades.** El sistema de certificación ya no produce false-PASS por el gap del task `detekt` ausente ni por los lint scripts ficticios. El único punto bloqueante para una release `v1.0.0-rc.2` que NO está en R0.1 es:

- **R0.2** — sustituir `RefusingRunReadPort`/`RefusingOutputReadPort` en `studio-app/Main.kt:121` por adaptadores reales (CRÍTICO para end-to-end)
- **R0.3** — `PRAGMA foreign_keys=ON` por conexión + resto de integridad de ejecución
- **Release `v1.0.0-rc.2`** — tag anotado + `gh release create` con artefactos reales + smoke desde entorno limpio + este recibo actualizado con el SHA etiquetado

**R0.1 está cerrado localmente y empujado a `origin/main`. No se considera DONE hasta que el bloque R0 completo publique `v1.0.0-rc.2`.**