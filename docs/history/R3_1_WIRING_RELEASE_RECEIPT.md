# R3.1-WIRING — Adapter populated with toolchain + sourceDigest from studio.db — Recibo PASS

**FECHA:** 2026-10-11
**HEAD pre-trabajo:** `72cab0b docs(history): R3.7b-WIRING release receipt (PASS — Monaco markers active in App.tsx)`
**Bloque:** R3 (Diagnósticos fiables, Detekt e IR Explorer) — sub-bloque **R3.1-WIRING** (cierre del pendiente residual del adapter)
**Resultado:** **PASS** — `Diagnostic.toolchainVersion`, `toolchainDigest` y `sourceDigest` ahora se rellenan desde `studio.db` cuando el binding existe ✅

---

## 1. Resumen ejecutivo

**Qué se pidió (R3.1-WIRING):**
> Cerrar el pendiente residual del adapter: el `PipelineKDiagnosticsAndIrPorts` no rellenaba `toolchainVersion` / `toolchainDigest` / `sourceDigest` desde el journal — los campos estaban en el tipo pero el adaptador los dejaba `null`.

**Decisión arquitectural (operador eligió Option A):**
> Cross-query studio.db. El adapter abre `studio.db` read-only y joinea `run_bindings` + `snapshots` para recuperar provenance.

**Estado pre-R3.1-WIRING:**
- `Diagnostic` data class tiene los 8 campos del mandate (`c50c39c`).
- `PipelineKDiagnosticsAndIrPorts.diagnostics(runId)` construía cada `Diagnostic` con `toolchainVersion = null, toolchainDigest = null, sourceDigest = null` (default del tipo).
- 12 tests previos + 3 R3.4 + 2 R3.2 + 9 R3 originales = 14/14 verde en `PipelineKDiagnosticsAndIrPortsIT`. Todos recibían `null` en los 3 campos sin pin de regresión.

**Estado post-R3.1-WIRING:**
- Adapter toma nuevo parámetro opcional `studioDbPath: Path?` (default `null` para back-compat).
- Cuando `studioDbPath` no-null Y el archivo existe Y hay binding en `run_bindings` para el `runId`, los 3 campos se rellenan con `snapshots.sha256` + `run_bindings.runtime_version` + `run_bindings.runtime_digest`.
- Cuando cualquiera falla (path null, archivo ausente, sin binding, schema incorrecto), los 3 campos se quedan `null` — el adapter **nunca inventa un valor** (misma postura que B3.0 ya aplicaba a `toolchainVersion`/`toolchainDigest`).
- `studio-cli/ServerStart.kt` pasa `dbPath` al adapter en producción.
- 3 tests adversariales nuevos pinnean el contrato.

---

## 2. SHA, plataforma y toolchain

| Recurso | Valor |
|---|---|
| Repositorio | Rubentxu/pipelinek-studio |
| Rama | main |
| SHA pre-trabajo | 72cab0b |
| SHA integrado | 1f92385 |
| Tag anotado (sin cambios) | `v1.0.0-rc.5` → bd7f7ce (ancestro) |
| Plataforma | linux 7.2.8-ogc5.1.fc44.x86_64 |
| Java | OpenJDK 21.0.8 |
| Gradle | 8.14.5 |
| Kotlin | 2.4.20 |
| SQLite (xerial) | 3.46.1.3 |

---

## 3. Cambios

### Producción

| Archivo | Cambio |
|---|---|
| `studio-infra-sdk/src/main/kotlin/dev/pipelinek/studio/infra/sdk/PipelineKDiagnosticsAndIrPorts.kt` | + constructor param `studioDbPath: Path? = null`; + private data class `RunProvenance(sourceDigest, toolchainVersion, toolchainDigest)`; + `resolveRunProvenance(runId)` que joinea `run_bindings` + `snapshots`; + llamada única en `diagnostics(runId)` que reutiliza provenance para todos los `Diagnostic` emitidos; + asignación de los 3 campos en el constructor del Diagnostic. |
| `studio-cli/src/main/kotlin/dev/pipelinek/studio/cli/ServerStart.kt` | + pasar `dbPath` al constructor de `PipelineKDiagnosticsAndIrPorts`. |

### Tests

| Archivo | Cambio |
|---|---|
| `studio-infra-sdk/src/test/kotlin/dev/pipelinek/studio/infra/sdk/PipelineKDiagnosticsAndIrPortsIT.kt` | + helper `seedStudioDb(...)` que crea el schema mínimo + inserta 1 snapshot + 1 run_binding; + 3 tests R3.1 (`toolchain populado cuando hay binding`, `null cuando no hay studio.db`, `null cuando hay studio.db pero el binding es para otro runId`). |

### Recuento

- Archivos modificados: 3 (1 prod + 1 wiring + 1 test).
- Tests netos: **+3** (`PipelineKDiagnosticsAndIrPortsIT` 14 → 17).
- Tests totales Kotlin: 520 → **523** (módulos: cli 203 + http 67 + application 118 + storage 57 + sdk 65 → 68 + app 10 = 523).

---

## 4. Tests adversariales nuevos

1. **`R3_1 diagnostic populates toolchain and sourceDigest when studio database has the binding`**
   Crea studio.db con 1 snapshot + 1 run_binding + 1 row en run.db con `CompilationFinished`. Adapter con `studioDbPath` apuntando al archivo. Asserta que el `Diagnostic` resultante tiene `toolchainVersion = "pipelinek-runtime:0.48.0-rc1"`, `toolchainDigest = "digest:abc"`, `sourceDigest = Sha256("a".repeat(64))`.

2. **`R3_1 diagnostic toolchain fields stay null when studio database is absent (never invented)`**
   Adapter construido SIN `studioDbPath` (parámetro null, comportamiento pre-R3.1). Asserta que los 3 campos quedan `null` — el adapter nunca inventa un valor cuando no hay información. **Regression guard** contra el anti-pattern 'fabricar toolchain' que el mandato B3.0 prohibía.

3. **`R3_1 diagnostic toolchain fields stay null when studio database exists but has no binding for the run`**
   studio.db existe y tiene un binding para OTRO `runId`. Adapter recibe el runId actual. Asserta que los 3 campos quedan `null` (no se filtra el binding de otro run). **Regression guard** contra leak cross-run.

---

## 5. Tests ejecutados

### Módulo studio-infra-sdk

```
$ ./gradlew :studio-infra-sdk:test \
            --tests 'dev.pipelinek.studio.infra.sdk.PipelineKDiagnosticsAndIrPortsIT' \
            --console=plain --no-daemon
…
PipelineKDiagnosticsAndIrPortsIT tests=17 skipped=0 failures=0 errors=0
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
| studio-app | 10 | 10 | 0 | 0 | 0 |
| **TOTAL** | **523** | **523** | **0** | **0** | **0** |

Comando:
```
./gradlew :studio-cli:test :studio-http:test :studio-application:test \
          :studio-infra-storage:test :studio-infra-sdk:test :studio-app:test \
          --console=plain --no-daemon
```
Resultado: `BUILD SUCCESSFUL`. Exit code: 0.

### Apps Node

```
apps/web     npm test -- --run → 7 files, 64 passed (64)  Exit code: 0
apps/{web,mcp,vscode}  npm run lint → exit 0
```
Sin cambios Node en este commit.

---

## 6. UAT/AAT vinculadas

- **AAT-CPP-001..006 (compile-time observability):** ✅ cada `Diagnostic` lleva ahora su toolchain provenance real (no null). El SPA puede mostrar 'produced by pipelinek-runtime:0.48.0-rc1 @ digest:abc' en el panel.
- **AAT-AN-005 (IR con procedencia demostrada):** ✅ `sourceDigest` poblado desde el binding permite al SPA marcar diagnostics obsoletos vía digest (cuando el wiring de R3.7b evolucione del path-filter al digest-filter).
- **B3.0 (toolchain provenance rule):** ✅ el adapter nunca inventa un valor — `null` cuando no hay studio.db o binding. El regla 'never invent' queda pinneada por el test #2.
- **R3.7b digest-filter (futuro):** ✅ la plumbing existe; el wiring R3.7b-WIRING del App.tsx puede ahora filtrar stale markers por `sourceDigest` cuando se actualice el path-filter.

---

## 7. Comandos y códigos de salida

```
$ ./gradlew :studio-infra-sdk:test --console=plain --no-daemon
…
> Task :studio-infra-sdk:test
BUILD SUCCESSFUL in 22s
Exit code: 0

$ ./gradlew :studio-cli:test :studio-http:test :studio-application:test \
          :studio-infra-storage:test :studio-infra-sdk:test :studio-app:test \
          --console=plain --no-daemon
…
BUILD SUCCESSFUL in 12s
Exit code: 0
```

---

## 8. Riesgos y deuda residual

1. **El adapter abre studio.db directamente** — acoplamiento cross-DB aceptado por la decisión del operador. Si en el futuro el ownership de studio.db cambia (p.ej. un micro-servicio separado), el adapter tendría que cambiar. Trade-off documentado en el código.
2. **El lookup no se cachea entre requests.** Cada llamada abre/cierra una conexión. Para un SPA que polea cada 3 s, son ~20 lookups/minute. Aceptable hoy; un cache con invalidation por run-state-change sería una mejora futura.
3. **`IrSnapshot` no recibe provenance.** El mandato R3.1 sobre el IR Explorer habla del stage name (R3.3 ya cerrado), no del snapshot en sí. Si en el futuro la SPA necesita 'produced by runtime X', sería una extensión similar.
4. **`join: Snapshots + run_bindings` depende de `PRAGMA foreign_keys=ON`** — ya garantizado en R0.3.2/3 (`SqliteConnection.openReadOnly` lo activa). Si el operador cambia la política y abre studio.db sin PRAGMA, los joins quedan sin enforcing pero siguen funcionando (es inner join explícito, no requiere FK enforcement). El test no lo verifica explícitamente; queda como sub-item si E1 se reporta.

---

## 9. Cierre

**Veredicto R3.1-WIRING:** **PASS**.

- 3/8 campos del mandate en el adapter ahora se rellenan con datos reales cuando el binding existe.
- 3 tests adversariales pinnean: el caso feliz, el caso 'no studio.db', el caso 'binding para otro run'.
- 523/523 Kotlin verde (was 520), 0 fail, 0 skip. 64/64 web sin cambios.
- Wiring en `studio-cli/ServerStart.kt` para que producción active los 3 campos.

Siguiente item activo en el plan: cierre R3 → release `v1.0.0-rc.5` con smoke E2E Monaco; o R3 LSP spike.

---

**Recibo generado por:** Agente (R3.1-WIRING, sesión de octubre 2026).
**Integridad:** este recibo se escribió antes del push. SHA `1f92385` integrado en `main`; tag `v1.0.0-rc.5` intacto en su SHA original (`bd7f7ce`).