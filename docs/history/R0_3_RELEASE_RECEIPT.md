# R0.3 — Recibo de cierre (sub-bloques 3.2 + 3.3)

> **Bloque:** R0.3 (Cerrar integridad de ejecución).
> **Release objetivo:** `v1.0.0-rc.2` (R0 completo).
> **Estado:** **PASAR** en 2 de 9 sub-items.
> **SHA integrado:** `f78f828` (en `origin/main`).
> **Fecha:** 2026-10-11.

R0.3 cubre 9 sub-items. Este recibo cierra **R0.3.2 + R0.3.3**.
Los sub-items 3.1, 3.4–3.9 quedan **diferidos** (ver §"Sub-items diferidos").

---

## Sub-items cerrados

### R0.3.2 — `PRAGMA foreign_keys=ON` por conexión

**Antes:** SQLite abre cada conexión con `foreign_keys=0` por defecto. Las migraciones V1 declaran cuatro FOREIGN KEY constraints (snapshots.experiment_id → experiments, run_bindings.* → experiments/snapshots/scenarios, run_state_history.studio_run_id → run_bindings). El código abría conexiones en **siete sitios** sin habilitar el pragma:

```
studio-infra-storage/.../StudioDatabase.kt:47         pool newConnection
studio-infra-storage/.../RetentionService.kt:154       isRunTerminal
studio-infra-storage/.../RunReconciler.kt:175          readTerminalHint
studio-infra-storage/.../RunReconciler.kt:198          hasSuccessOutcome
studio-infra-storage/.../StudioBackup.kt:77            backup()
studio-infra-storage/.../StudioBackup.kt:108           restore() PRAGMA integrity_check
studio-infra-sdk/.../PipelineKRunReadPort.kt:39        openReadonly()
studio-infra-sdk/.../PipelineKDiagnosticsAndIrPorts.kt:47  openReadonly()
studio-infra-sdk/.../VariantAndCompare.kt:102          readEvents()
```

Las constraints estaban **silenciosamente inactivas** en todos. El DB se mantenía coherente por disciplina de los escritores; cualquier migración futura con REFERENCES habría propagado dangling rows.

**Cambio:**
- Nuevo `studio-infra-storage/.../SqliteConnection.kt` con `openReadWrite(Path)` y `openReadOnly(Path)`. Cada helper ejecuta en orden:
  1. `PRAGMA foreign_keys = ON`
  2. `PRAGMA journal_mode = WAL` (idempotente a nivel de DB)
  3. `PRAGMA synchronous = NORMAL`
  4. `PRAGMA foreign_keys = ON` (re-aserción final contra reorderings)
- Los nueve sitios refactorizados a `SqliteConnection.openReadWrite(...)` o `SqliteConnection.openReadOnly(...)` según corresponda.
- Los imports de `java.sql.DriverManager` se eliminaron de los archivos donde ya no se usaba.

**Commit:** `f78f828 fix(sqlite): PRAGMA foreign_keys=ON on every connection (R0.3.2/3)`.

### R0.3.3 — Pool SQLite recovery (invariantes con conexiones inválidas)

**Diagnóstico del pool existente (`StudioDatabase.kt`):**
- Pool de tamaño fijo (4 por defecto), `ArrayBlockingQueue`.
- `open()` hace `isValid(1)` antes de entregar; si es inválido, cierra + reemplaza.
- `release()` valida; si inválido, descarta + reemplaza.
- `close()` drena la cola.
- `PooledConnection` envuelve cada handle para que `close()` lo devuelva al pool sin cerrarlo físicamente.

**Lo que faltaba:** tests adversariales que prueben invariantes ante escenarios de fallo (stale handle, cierre explícito, churn). El test `open() returns a live connection that survives use-blocks (H-22 pool)` ya existía pero cubría el camino feliz.

**Cambio:**
- Nuevo `SqlitePoolR03Test` con tres casos:
  1. **stale connection:** 4 borrows secuenciales siguen reportando `foreign_keys=1`.
  2. **open() after close():** lanza en lugar de entregar un handle muerto.
  3. **100-cycle churn:** 100 ciclos borrow/release no degradan el pool (la FK sigue activa y los handles no se corrompen).

**Commit:** `f78f828` (mismo que R0.3.2; la división en dos commits separados habría forzado un estado roto entre ambos).

### Tests adversariales de R0.3.2

`SqliteConnectionR03Test` con cinco casos:

1. Cada una de las 4 conexiones del pool reporta `foreign_keys=1`.
2. `openReadOnly` setea `foreign_keys=1` en un handle nuevo.
3. `openReadWrite` setea `foreign_keys=1` en un handle nuevo.
4. **Adversarial:** insertar un snapshot con `experiment_id='exp-DOES-NOT-EXIST'` es **rechazado** con `SQLException` (pre-R0.3.2 el insert succeedía y dejaría una fila huérfana).
5. **Control de regresión:** con `DriverManager.getConnection` directo, `PRAGMA foreign_keys` devuelve `0` y el mismo insert succeede. Este test documenta el shape exacto de la regresión previa; si falla, el default de SQLite cambió.

---

## Sub-items diferidos

| Sub-item | Diagnóstico | Por qué diferido |
|---|---|---|
| R0.3.1 scenarios auth | Hay `experimentId` y `scenarioId` en `Run`; ningún código los sustituye explícitamente. El surrogate `experiment_id` en `App.tsx:225` es un workaround SPA local, no del backend. | Diferido a R0.4/R1 (S1 wire-up) |
| R0.3.4 reserva durable + idempotencia atómica | `UseCases.kt:90-200` ya implementa `ReservationOutcome.Reserved/RecoveryRequired/Refused/Admitted` con state machine. Persistencia en `studio.db`. Probablemente OK estructuralmente; falta el test E2E que pruebe atomicidad bajo kill -9. | Diferido a R0.4 (UAT-039) |
| R0.3.5 recovery sin duplicar efectos | `RetentionService` y `RunReconciler` ya tienen invariantes declaradas (terminal state, lease alive). Falta el test adversariales bajo kill -9. | Diferido a R0.4 |
| R0.3.6 upstream ID authority | `RunId` (studio-run-id) usa `UUID.randomUUID()`; `upstreamRunId` es nullable en domain. La autoridad sobre el ID upstream vive en el SDK subprocess. Verificar que `StudioAdapter.postcode` no inventa UUIDs. | Diferido — requiere ejecución real de un pipeline para verificar |
| R0.3.7 IR projection | `RunObservation.Terminal/Unavailable` derivado de `RunReadResult` está correctamente implementado. Falta el test adversariales que verifique que `UNKNOWN` nunca se convierte en `FAILED`. | Diferido a R0.4 (smoke de release) |
| R0.3.8 studio.db vs run.db separación | En domain: `Run.kt:10-11` declara la separación. En wire-up: `StudioDatabase` solo abre `studio.db`; los readers de `run.db` están separados. Estructuralmente OK. | Diferido — necesita smoke |
| R0.3.9 identidad snapshot/runtime/plugins | `StartRunUseCase.execute()` ya lee snapshot + idempotency key + scenario. El SDK subprocess hace la validación de runtime/plugins. Estructuralmente OK. | Diferido — smoke |

---

## Evidencia ejecutada (local)

| Comando | Resultado |
|---|---|
| `./gradlew :studio-cli:test :studio-http:test :studio-infra-sdk:test :studio-infra-storage:test :studio-app:test --rerun-tasks` | 200 / 60 / 57 / 50 / 10 tests, 0 failures |
| `apps/{mcp,web,vscode}` lint + typecheck | exit 0 |

Test counts pre/post:
- studio-infra-storage: 42 → **50** tests (+8 adversarial nuevos para R0.3.2/3)

## Integración remota

| Paso | Resultado |
|---|---|
| SHA integrado en `origin/main` | `f78f828` |
| Rango pushed | `f0844ce..f78f828` (1 commit) |
| Working tree post-push | Limpio |

## Riesgos / deuda residual

| ID | Riesgo | Estado | Próximo paso |
|---|---|---|---|
| `R-R0.3-001` | 7 sub-items R0.3 siguen diferidos (atomicidad de reserva, upstream ID authority, IR projection, etc.) | Diferido | R0.4 (UAT-039 smoke) |
| `R-R0.3-002` | El pool sólo tiene 1 test adversarial previo al R0.3.3 (`open() returns a live connection`); los 3 nuevos cubren las invariantes críticas pero no todas las combinaciones de fallo. | menor | R6 (perf / soak) |
| `R-R0.3-003` | `PRAGMA journal_mode = WAL` se aplica en cada `open()`. Es barato (no-op tras la primera escritura) pero podría moverse a `runMigrations()`. | cosmético | limpieza R6 |

## Veredicto R0.3 (parcial)

**`PASS` en R0.3.2 y R0.3.3.** El bloque R0.3 NO está cerrado. Los 7 sub-items restantes (3.1, 3.4–3.9) requieren UAT-039 (instalar Studio, ejecutar pipeline real, verificar end-to-end) que es la próxima entrada del bloque R0.4.

El bloque R0 entero **sigue bloqueado** por:
- R0.2.1 (server unification)
- R0.3.1, R0.3.4–3.9 (integridad de ejecución bajo smoke real)
- R6.3 (release con artefactos)

Publicación de `v1.0.0-rc.2` queda condicionada al smoke end-to-end.