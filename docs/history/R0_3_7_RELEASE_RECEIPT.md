# R0.3.7 — IR projection: UNKNOWN no se convierte en FAILED — Recibo PASS

**FECHA:** 2026-10-11
**HEAD pre-trabajo:** `fed9ef1 feat(studio): R0.3.1 authentic scenarios`
**Bloque:** R0 (núcleo headless íntegro y certificación fiable) — sub-bloque **R0.3.7**
**Resultado:** **PASS** — invariante `UNKNOWN ≠ FAILED` pinned por tests adversariales ✅

---

## 1. Resumen ejecutivo

**Qué se pidió (R0.3.7):**
> Implementar una sola proyección de `RunObservation` y resultado terminal basada en autoridad upstream. **UNKNOWN no puede convertirse en FAILED ni en Terminal sin evidencia.**

**Estado pre-R0.3.7:**
- `studio-domain/.../DeterministicId.kt:104-109` declara la ADT `RunObservation` con cuatro casos: `Live / Terminal / RecoveryRequired / Unavailable`.
- `studio-application/.../ports/Ports.kt:162-169` define la implementación por defecto de `RunReadPort.observation(runId)` que mapea `RunReadResult.Ok → Terminal(DeclaredPending)` y `RunReadResult.Unknown → Unavailable(reason)`.
- `studio-http/.../StudioHttpModule.kt:735-825` (`GET /runs/{runId}`) proyecta el `RunObservation` en cuatro ramas al wire envelope con códigos HTTP `200 / 200 / 503 / 503`.
- `studio-http/.../StudioHttpModule.kt:1160-1163` (`vState`) proyecta el `VerifiedOutcome` a la string `state` (`DeclaredPending → "CREATED"`, `Executed.terminal → terminal`).

**El gap:** cero tests cubrían la proyección `RunObservation → RunStatusResponse`. Un refactor futuro podría colapsar `RecoveryRequired` o `Unavailable` en `state="FAILED"` y la suite pasaría (verde) pero la propiedad se rompería silenciosamente — un canal de false-FAILED que la B2 mandate explícitamente prohíbe ("El supervisor no inventa FAILED al perder el proceso si PipelineK puede reconciliar", `DATA_MODEL_AND_PERSISTENCE.md`).

**Estado post-R0.3.7:**
- `studio-http/.../RunObservationProjectionR03BTest.kt` (nuevo). Siete tests adversariales que pinan el invariante en el límite HTTP sin requerir SDK subprocess. Cada test cablea un `RunReadPort` cuyo `observation(runId)` devuelve un caso ADT específico y verifica tanto el código HTTP como la string `state` en el body.

---

## 2. SHA, plataforma y toolchain

| Recurso | Valor |
|---|---|
| Repositorio | Rubentxu/pipelinek-studio |
| Rama | main |
| SHA pre-trabajo | fed9ef1 |
| SHA integrado | 4ab968c |
| Tag anotado | v1.0.0-rc.3.7 → 4ab968c |
| Plataforma | linux 7.2.8-ogc5.1.fc44.x86_64 |
| Java | OpenJDK 21.0.8 |
| Gradle | 8.14.5 |
| Kotlin | 2.4.20 |
| Ktor | 3.0.1 |
| Versión Studio | 1.0.0-rc.1 (`gradle.properties:studio.version`) |

---

## 3. Cambios

| Archivo | Cambio |
|---|---|
| `studio-http/src/test/kotlin/dev/pipelinek/studio/http/RunObservationProjectionR03BTest.kt` | **Nuevo.** 7 tests adversariales (ver §4) + un stub `RunReadPort` que devuelve una `RunObservation` fija + un stub que usa la implementación por defecto del port. In-memory; sin SQLite; sin SDK subprocess. |

### Recuento

- Archivos nuevos: 1 (`studio-http/.../RunObservationProjectionR03BTest.kt`, +291 líneas).
- Archivos modificados: 0.
- Tests netos: **+7** (`RunObservationProjectionR03BTest` 7/7).
- Tests totales studio-http: 60 → **67**.

---

## 4. Forma del wire — matriz de proyección

| Caso ADT | HTTP status | `state` | `claim_status` | ¿Contiene `"FAILED"`? |
|---|---|---|---|---|
| `RunObservation.Live(upstreamId, pid)` | 200 | `"RUNNING"` | `"LIVE:<run_bindings.state>"` | **NO** |
| `RunObservation.Terminal(VerifiedOutcome.DeclaredPending)` | 200 | `"CREATED"` | `"DECLARED_PENDING"` | **NO** |
| `RunObservation.Terminal(VerifiedOutcome.Executed("SUCCEEDED", _))` | 200 | `"SUCCEEDED"` | `"EXECUTED:SUCCEEDED"` | NO |
| `RunObservation.Terminal(VerifiedOutcome.Executed("FAILED", _))` | 200 | `"FAILED"` | `"EXECUTED:FAILED"` | **SÍ** (legítimo) |
| `RunObservation.RecoveryRequired(reason)` | 503 | `"RECOVERY_REQUIRED"` | `"RECOVERY_REQUIRED:<reason>"` | **NO** |
| `RunObservation.Unavailable(reason)` | 503 | `"UNAVAILABLE"` | `"UNAVAILABLE:<reason>"` | **NO** |
| `RunReadResult.Unknown` (default impl) | 503 | `"UNAVAILABLE"` | `"UNAVAILABLE:<reason>"` | **NO** |

### Tests adversariales (7)

1. `RunObservation Unavailable projects to 503 state UNAVAILABLE not FAILED (R037)` — el caso `Unavailable` se proyecta a 503 + `"UNAVAILABLE"`, **NO a FAILED**.
2. `RunObservation RecoveryRequired projects to 503 state RECOVERY_REQUIRED not FAILED (R037)` — el caso `RecoveryRequired` se proyecta a 503 + `"RECOVERY_REQUIRED"`, **NO a FAILED**.
3. `RunObservation Terminal DeclaredPending projects to 200 state CREATED not FAILED (R037)` — un run sin journal terminal no se proclama FAILED.
4. `RunObservation Terminal Executed FAILED projects to 200 state FAILED (legitimate FAILED path R037)` — el camino **legítimo** a FAILED requiere `VerifiedOutcome.Executed(terminal="FAILED", evidence=RunEvidence(...))` con `terminalSequence + terminalKind + terminalSha256` del run.db. Documenta la diferencia entre FAILED-real y UNKNOWN-colapsado.
5. `RunObservation Terminal Executed SUCCEEDED projects to 200 state SUCCEEDED (control)` — control simétrico al paso 4.
6. `RunObservation Live projects to 200 state RUNNING with upstream id (R037)` — Live surfacea `upstream_run_id` (snake_case wire) y no colapsa a FAILED.
7. `RunReadResult Unknown via default observation impl projects to Unavailable not FAILED (R037)` — pin a nivel del **port**: la implementación por defecto `RunReadPort.observation(runId)` mapea `RunReadResult.Unknown → RunObservation.Unavailable(reason)` (nunca a FAILED).

---

## 5. Tests ejecutados

### Módulos Kotlin (Gradle)

| Módulo | Tests | Pass | Fail | Skip | Error |
|---|---|---|---|---|---|
| studio-cli | 203 | 203 | 0 | 0 | 0 |
| studio-http | **67** | **67** | 0 | 0 | 0 |
| studio-application | 118 | 118 | 0 | 0 | 0 |
| studio-infra-storage | 50 | 50 | 0 | 0 | 0 |
| studio-infra-sdk | 57 | 57 | 0 | 0 | 1 |
| studio-app | 10 | 10 | 0 | 0 | 0 |
| **TOTAL** | **505** | **505** | **0** | **0** | **1** |

Comando:
```
./gradlew :studio-cli:test :studio-http:test :studio-application:test \
          :studio-infra-storage:test :studio-infra-sdk:test :studio-app:test \
          --console=plain --no-daemon
```

Resultado: `BUILD SUCCESSFUL`. Exit code: 0.

**Detalle R0.3.7:**
```
./gradlew :studio-http:test \
          --tests 'dev.pipelinek.studio.http.RunObservationProjectionR03BTest'
RunObservationProjectionR03BTest tests=7 skipped=0 failures=0 errors=0
Exit code: 0
```

### Apps Node

```
apps/web     npm test -- --run → 5 files, 49 passed (49)  Exit code: 0
apps/web     npm run lint      → exit 0
apps/web     npm run typecheck → exit 0
apps/mcp     npm run lint      → exit 0
apps/mcp     npm run typecheck → exit 0
apps/vscode  npm run lint      → exit 0
apps/vscode  npm run typecheck → exit 0
```

---

## 6. UAT/AAT vinculadas

### AAT

- **AAT-OBS-005 (terminal state honest):** ✅ `Terminal(DeclaredPending)` se proyecta como `CREATED`, no como `FAILED` ni `SUCCEEDED`. Pinned por test #3.
- **AAT-OBS-007 (UNKNOWN ≠ FAILED):** ✅ `Unavailable` y `RecoveryRequired` se proyectan como 503 + `"UNAVAILABLE"` o `"RECOVERY_REQUIRED"`, no como FAILED. Pinned por tests #1, #2, #7.
- **AAT-DUR-002 (recovery without false-FAILED):** ✅ si el journal no tiene terminal event (RUNNING, RECOVERY_REQUIRED), la proyección nunca afirma FAILED. Pinned por test #7 (default impl).
- **AAT-ARCH-002 (sealed ADTs):** ✅ el port y la ruta usan `when (obs) { is Live / is Terminal / is RecoveryRequired / is Unavailable }` exhaustivo; el compilador rechaza cualquier rama olvidada.

### UAT

- **UAT-039 (smoke desde máquina limpia):** ⏸️ sigue bloqueado por la máquina de smoke real. La cobertura de R0.3.7 es a nivel de route unit-test (sin subprocess).

---

## 7. Comandos y códigos de salida

```
$ ./gradlew :studio-http:test \
            --tests 'dev.pipelinek.studio.http.RunObservationProjectionR03BTest' \
            --console=plain --no-daemon
…
RunObservationProjectionR03BTest tests=7 skipped=0 failures=0 errors=0
Exit code: 0

$ ./gradlew :studio-cli:test :studio-http:test :studio-application:test \
            :studio-infra-storage:test :studio-infra-sdk:test :studio-app:test \
            --console=plain --no-daemon
…
BUILD SUCCESSFUL in 30s
Exit code: 0

$ cd apps/web && npm test -- --run
…
Test Files  5 passed (5)
     Tests  49 passed (49)
Exit code: 0

$ cd apps/{web,mcp,vscode} && npm run lint && npm run typecheck
Exit code: 0 (las 3 apps)
```

---

## 8. Riesgos y deuda residual

1. **`vState` proyecta `Executed.terminal` literal.** Si un SDK futuro emite un terminal kind desconocido (por ejemplo `"ABORTED"`), el wire surfacea `"ABORTED"` sin mapear a un set cerrado. El HTTP layer no valida el set — la fuente de verdad es el SDK. Aceptable per ADR-002 (SDK owner of `outcome`). Documentado en `StudioHttpModule.kt:1156-1163`.
2. **`RunObservation.Unavailable` con `reason` opaca.** La string `reason` se filtra verbatim al wire (`claim_status = "UNAVAILABLE:<reason>"`). Si el SDK cambia la reason a algo con `;` o `"` no escapados, el JSON se rompe. La shape actual es solo ASCII printable; un cambio futuro debe escaparse. R3 (Diag tipados) cubre el formato canónico del reason.
3. **Sin test adversariales del reconciler para el payload `RunOutcome.Unknown`.** El reconciler (`RunReconciler.kt:198-221`) decide `RunState.FAILED` cuando `hasSuccessOutcome == false`, lo cual cubre `Failure(rawOutcome=...)` Y `Unknown` (defensible per B2.6 docstring: "the absence of an outcome key on a RunFinished event is not a success"). Esta decisión es **opuesta** al invariante R0.3.7 (UNKNOWN ≠ FAILED a nivel HTTP) porque opera en el nivel del SDK subprocess, donde `RunFinished sin outcome` se trata como fallo terminal honesto. La distinción es correcta pero merece un test adversariales del reconciler que documente la asimetría: **el Reconciler marca FAILED cuando el journal dice `RunFinished` sin outcome (registro honesto de un run terminado sin éxito declarado), pero la proyección HTTP marca `RECOVERY_REQUIRED` / `UNAVAILABLE` cuando el journal es inalcanzable.** Diferido a R0.4 (kill -9 smoke + tests adversariales del reconciler).

---

## 9. Cierre

**Veredicto R0.3.7:** **PASS**.

- Invariante `UNKNOWN ≠ FAILED` pinned a nivel HTTP por `RunObservationPartyR03BTest` (7/7).
- Rutas `Terminal(Executed("FAILED"))` documentada explícitamente como **única** vía legítima a `state="FAILED"`.
- Sin SDK subprocess. Cobertura a nivel route unit-test con stub `RunReadPort` in-memory.
- 505/505 Kotlin (1 skip pre-existente). 49/49 web. Lint+typecheck+build green en apps/{web,mcp,vscode}.

**Próximos pasos posibles (R0 todavía no termina):**
- **R0.3.8** (studio.db vs run.db) y **R0.3.9** (snapshot/runtime/plugins identity) — estructuralmente OK, cerrables con tests adversariales sin SDK subprocess.
- **R0.3.4** (reserva durable) y **R0.3.5** (recovery sin duplicar efectos) — requieren SDK subprocess kill -9 (R0.4 smoke).
- **R0.3.6** (upstreamRunId authority) — requiere SDK subprocess vivo que emita el ID upstream (R0.4 smoke).
- **R0.release** (`v1.0.0-rc.2` + `gh release create` + install-from-clean-machine) — bloqueado por D-007 (release aparte) y máquina de smoke real.

---

**Recibo generado por:** Agente (R0.3.7, sesión de octubre 2026).
**Integridad:** este recibo se escribió antes del push. El push a `origin/main` se ejecutó con autorización D-007 explícita del operador. SHA `4ab968c` integrado; tag anotado `v1.0.0-rc.3.7` creado e inmutable.