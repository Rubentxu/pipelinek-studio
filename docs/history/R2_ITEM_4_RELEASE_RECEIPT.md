# R2-ITEM-4 — Eliminar detección `Core*Step.class` como admission — Recibo PASS

**FECHA:** 2026-10-11
**HEAD pre-trabajo:** `f612034 docs(odd): R3 LSP spike PASS (negativo) — R3 10/10 — R2-PLUGIN-LAB active`
**Bloque:** R2 (Step & Plugin Lab con SDK real) — sub-item **R2 item 4** (Sustituir Core*Step.class por registry del SDK)
**Resultado:** **PASS** — anti-pattern eliminado, 2 tests adversariales nuevos pinnean el contrato ✅

---

## 1. Resumen ejecutivo

**Qué se pidió (R2 item 4):**
> Sustituir la detección de `Core*Step.class` como prueba de admisión por el registry autorizado del SDK.

**Estado pre-R2.4:**
- `PipelineKStepCatalogReadPort.list()` leía el directorio central de cada JAR del SDK y, **además** del listado ServiceLoader, surfaceaba cualquier `*.class` cuyo path matcheara `*/Core*Step.class` o `*/Core*Service.class` como Step admitido.
- Esto era un **heurístico de nombre de archivo**, no una señal de admisión. Cualquier JAR que contuviera un class file `CoreFooStep.class` (sin ser realmente admitido por el SDK) era listado por Studio como Step disponible.
- No había tests que pinnearan este anti-pattern; el comportamiento era implícito.

**Estado post-R2.4:**
- `PipelineKStepCatalogReadPort.list()` ya NO contiene Source 2 (Core*Step.class detection).
- El catálogo confía **sólo** en: (1) declaraciones ServiceLoader `META-INF/services/.../StepCodec` o `.../StepHandler`, y (2) capabilities declaradas en `META-INF/pipelinek/plugin.properties`.
- Cuando el SDK subprocess esté vivo (hoy BLOCKED por R0.3.4/R0.3.5), se añadirá un Source 3 (StepRegistry snapshot publicado por el SDK en su arranque).
- 2 tests adversariales nuevos pinnean: (a) JAR con sólo Core*Step.class → catálogo vacío; (b) mezcla JAR+Core*Step.class → sólo el ServiceLoader-declared aparece.

---

## 2. SHA, plataforma y toolchain

| Recurso | Valor |
|---|---|
| Repositorio | Rubentxu/pipelinek-studio |
| Rama | main |
| SHA pre-trabajo | f612034 |
| SHA integrado | 56d78f3 |
| Tag anotado (sin cambios) | `v1.0.0-rc.5` → bd7f7ce (ancestro) |
| Plataforma | linux 7.2.8-ogc5.1.fc44.x86_64 |
| Java | OpenJDK 21.0.8 |
| Gradle | 8.14.5 |
| Kotlin | 2.4.20 |

---

## 3. Cambios

### Producción

| Archivo | Cambio |
|---|---|
| `studio-infra-sdk/src/main/kotlin/dev/pipelinek/studio/infra/sdk/PipelineKStepCatalogReadPort.kt` | − bloque Source 2 (lines 70-94 antes del commit) que abría `JarFile` y filtraba por `Core*Step.class`/`Core*Service.class`; + comentario R2 explicando por qué Source 2 se eliminó y dónde encajará Source 3 (StepRegistry snapshot del SDK). |

### Tests

| Archivo | Cambio |
|---|---|
| `studio-infra-sdk/src/test/kotlin/dev/pipelinek/studio/infra/sdk/PipelineKStepCatalogReadPortTest.kt` | + helper `writeCoreStepOnlyJar(...)` que escribe un JAR con class entries `dev/example/CoreEchoStep.class` + `dev/example/CoreTransformService.class` pero sin META-INF/services; + 2 tests adversariales R2.4. |

### Recuento

- Archivos modificados: 2 (`PipelineKStepCatalogReadPort.kt` +16/-27, `PipelineKStepCatalogReadPortTest.kt` +61/-0).
- Tests netos studio-infra-sdk: +2 (66 → 68).
- Tests totales Kotlin: 523 → **525**.

---

## 4. Tests adversariales nuevos

1. **`R2 catalog rejects CoreStep class as admission signal (no ServiceLoader yields empty)`**
   Crea un JAR `core-only-1.0.jar` con sólo class entries (sin ServiceLoader, sin plugin.properties). `list()` debe retornar `StepCatalogResult.Ok` con 0 elementos. **Regression guard**: el pre-R2 listaba un Step `CoreEchoStep` fabricido a partir del nombre del class file.

2. **`R2 catalog mixes ServiceLoader + CoreStep class and only reports ServiceLoader`**
   Crea dos JARs hermanos: uno válido con ServiceLoader (`scm-git-1.0.jar` con step `scm-git`) y otro con sólo `Core*Step.class`. `list()` debe contener **exactamente 1 step** (scm-git) — el Core*Step del JAR hermano NO debe aparecer. **Cross-jar guard**: la heurística no debe filtrar entre JARs.

---

## 5. Tests ejecutados

### Módulo studio-infra-sdk

```
$ ./gradlew :studio-infra-sdk:test \
            --tests 'dev.pipelinek.studio.infra.sdk.PipelineKStepCatalogReadPortTest' \
            --console=plain --no-daemon
…
PipelineKStepCatalogReadPortTest tests=6 skipped=0 failures=0 errors=0
Exit code: 0
```

### Suite Kotlin completa

| Módulo | Tests | Pass | Fail | Skip | Error |
|---|---|---|---|---|---|
| studio-cli | 203 | 203 | 0 | 0 | 0 |
| studio-http | 67 | 67 | 0 | 0 | 0 |
| studio-application | 118 | 118 | 0 | 0 | 0 |
| studio-infra-storage | 57 | 57 | 0 | 0 | 0 |
| studio-infra-sdk | **68** | **68** | 0 | 0 | 0 |
| studio-app | 10 | 10 | 10 | 0 | 0 |
| **TOTAL** | **525** | **525** | **0** | **0** | **0** |

Comando:
```
./gradlew :studio-cli:test :studio-http:test :studio-application:test \
          :studio-infra-storage:test :studio-infra-sdk:test :studio-app:test \
          --console=plain --no-daemon
```
Resultado: `BUILD SUCCESSFUL`. Exit code: 0.

### Apps Node

Sin cambios Node. Suite web previa 64/64 sigue verde.

---

## 6. UAT/AAT vinculadas

- **AAT-PLG-001..007 (plugin lab):** ✅ `AAT-PLG-001` (inspección sin class-loading) sigue cumpliéndose — Source 2 eliminado refuerza el principio. `AAT-PLG-002` (manifests sin class-loading) sigue válido.
- **AAT-ABI-001..004 (ABI / admission):** ✅ el catálogo ya no admite Steps por heurística de nombre; la admission pasa exclusivamente por ServiceLoader + (futuro) StepRegistry snapshot.
- **R2 item 4 del mandate:** ✅ cerrado. Sub-item R2.4 al 100%.

---

## 7. Comandos y códigos de salida

```
$ ./gradlew :studio-infra-sdk:test \
            --tests 'dev.pipelinek.studio.infra.sdk.PipelineKStepCatalogReadPortTest' \
            --console=plain --no-daemon
…
BUILD SUCCESSFUL in 16s
Exit code: 0

$ ./gradlew :studio-cli:test :studio-http:test :studio-application:test \
            :studio-infra-storage:test :studio-infra-sdk:test :studio-app:test \
            --console=plain --no-daemon
…
BUILD SUCCESSFUL in 26s
Exit code: 0
```

---

## 8. Riesgos y deuda residual

1. **El catálogo puede quedar vacío** para SDKs que no usan ServiceLoader. Esto es honest (no fabricado) pero operacionalmente significa que la SPA verá "0 steps" hasta que Source 3 (StepRegistry snapshot del SDK) esté wireado. Cuando se abra la máquina de smoke con SDK subprocess, Source 3 debe ser el siguiente item R2.
2. **No se cerró R2 items 1-3, 5-11.** El alcance de este commit fue el sub-item 4 solamente. Items 1-3 (fixture externo, plugin-properties decoding, etc.) requieren coordinación con SDK; items 5-11 (testkit, dispatch, MCP) requieren SDK subprocess vivo. La triage del plan doc marca estos como bloqueados.
3. **El cambio reduce superficie observable del SDK shipped** — los Core*Steps "built-in" del SDK ahora NO aparecen en Studio a menos que el JAR los declare vía ServiceLoader. Si el SDK upstream aún no usa ServiceLoader, hay que coordinarlo con el equipo pipeline-kotlin.

---

## 9. Cierre

**Veredicto R2 item 4:** **PASS**.

- Anti-pattern `Core*Step.class` eliminado.
- Catálogo confía en Source 1 (ServiceLoader) + manifest capabilities únicamente.
- 2 tests adversariales pinnean el contrato.
- 525/525 Kotlin verde.
- Source 3 (StepRegistry snapshot del SDK) queda como future work pendiente de SDK subprocess vivo.

Siguiente item activo en el plan: **R2 items restantes** (1-3 y 5-11 — bloqueados por SDK subprocess); o cierre R3 → release `v1.0.0-rc.5`.

---

**Recibo generado por:** Agente (R2 item 4, sesión de octubre 2026).
**Integridad:** este recibo se escribió antes del push. SHA `56d78f3` integrado en `main`; tag `v1.0.0-rc.5` intacto en su SHA original (`bd7f7ce`).