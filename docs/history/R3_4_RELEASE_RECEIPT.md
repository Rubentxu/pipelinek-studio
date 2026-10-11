# R3.4 — Separación explícita `COMPILED` / `ADMITTED` / `OBSERVED` — Recibo PASS

**FECHA:** 2026-10-11
**HEAD pre-trabajo:** `49a9e06 feat(sdk): R3.2 structured decoder — replace JSON regex with kotlinx serialization tree walk`
**Bloque:** R3 (Diagnósticos fiables, Detekt e IR Explorer) — sub-bloque **R3.4**
**Resultado:** **PASS** — separación de fases pinneada por tests adversariales ✅

---

## 1. Resumen ejecutivo

**Qué se pidió (R3.4):**
> Separar de forma explícita `COMPILED`, `ADMITTED` y `OBSERVED`.

**Estado pre-R3.4:**
- `IRPhase = enum class IRPhase { COMPILED, ADMITTED, OBSERVED }` ya definido en `studio-application/src/main/kotlin/dev/pipelinek/studio/application/ports/Ir.kt:42`.
- `PipelineKDiagnosticsAndIrPorts.snapshot()` ya computaba cada una desde una superficie distinta del journal:
  - `ADMITTED`: cada fila de `operation_journal` con `fingerprint` no-vacía (línea 149 del adapter).
  - `COMPILED`: cada evento con `kind = 'CompilationFinished'` (línea 173).
  - `OBSERVED`: cualquier evento con `kind NOT LIKE 'Compilation%'` (línea 181).
- **Lo que faltaba:** tests adversariales que demostraran que cada fase se calcula de forma independiente. Un cambio futuro que "combinara" fases por error (p.ej. marcar OBSERVED al ver un CompilationFinished) no estaba pinneado por ningún test.

**Estado post-R3.4:**
- 3 tests adversariales en `PipelineKDiagnosticsAndIrPortsIT` prueban las 3 ramas puras (sólo ADMITTED, sólo COMPILED, sólo OBSERVED).
- Cada test prepara run.db con exactamente una superficie rellena y las otras dos vacías, y asserta que `phasesObserved` contiene sólo la fase correspondiente.
- El guard de empty-journal (`R3.3`) sólo dispara cuando las TRES superficies están vacías; un run con sólo CompilationFinished o sólo StepStarted se proyecta como `IrReadResult.Ok` (no `Unknown`).

---

## 2. SHA, plataforma y toolchain

| Recurso | Valor |
|---|---|
| Repositorio | Rubentxu/pipelinek-studio |
| Rama | main |
| SHA pre-trabajo | 49a9e06 |
| SHA integrado | 22202e7 |
| Tag anotado (sin cambios) | `v1.0.0-rc.5` → bd7f7ce (ancestro de R3.4) |
| Plataforma | linux 7.2.8-ogc5.1.fc44.x86_64 |
| Java | OpenJDK 21.0.8 |
| Gradle | 8.14.5 |
| Kotlin | 2.4.20 |

---

## 3. Cambios

### Tests

| Archivo | Cambio |
|---|---|
| `studio-infra-sdk/src/test/kotlin/dev/pipelinek/studio/infra/sdk/PipelineKDiagnosticsAndIrPortsIT.kt` | + import `IRPhase`; + helper `seedEmpty(tmp, runId)` que crea el schema de `events` + `operation_journal`; + 3 tests adversariales `R3_4 IR phases only X when Y`. |

### Recuento

- Archivos modificados: 1 (`PipelineKDiagnosticsAndIrPortsIT.kt` +106/-0).
- Tests netos: **+3** (`PipelineKDiagnosticsAndIrPortsIT` 11 → 14).
- Tests totales studio-infra-sdk: 62 → **65**.

---

## 4. Tests adversariales nuevos

1. **`R3_4 IR phases only ADMITTED when journal has operations and no events`**
   Pre-crea run.db con `events` vacía + 1 fila en `operation_journal` con `fingerprint = "com.example.EchoStep"`. `snapshot()` retorna `IrReadResult.Ok`. `phasesObserved` contiene `ADMITTED`, NO contiene `COMPILED` ni `OBSERVED`.

2. **`R3_4 IR phases only COMPILED when only CompilationFinished event is present`**
   Pre-crea run.db con `operation_journal` vacía + 1 evento `CompilationFinished`. `snapshot()` retorna `IrReadResult.Ok` (no `Unknown` — el guard empty-journal de R3.3 sólo dispara con las 3 superficies vacías). `phasesObserved` contiene `COMPILED`, NO contiene `ADMITTED` ni `OBSERVED`.

3. **`R3_4 IR phases only OBSERVED when only non-Compilation event is present`**
   Pre-crea run.db con `operation_journal` vacía + 1 evento `StepStarted`. `snapshot()` retorna `IrReadResult.Ok`. `phasesObserved` contiene `OBSERVED`, NO contiene `COMPILED` ni `ADMITTED`.

---

## 5. Tests ejecutados

### Módulo Kotlin (Gradle)

| Módulo | Tests | Pass | Fail | Skip | Error |
|---|---|---|---|---|---|
| studio-cli | 203 | 203 | 0 | 0 | 0 |
| studio-http | 67 | 67 | 0 | 0 | 0 |
| studio-application | 118 | 118 | 0 | 0 | 0 |
| studio-infra-storage | 57 | 57 | 0 | 0 | 0 |
| studio-infra-sdk | **65** | **65** | 0 | 0 | 0 |
| studio-app | 10 | 10 | 0 | 0 | 0 |
| **TOTAL** | **520** | **520** | **0** | **0** | **0** |

Comando:
```
./gradlew :studio-cli:test :studio-http:test :studio-application:test \
          :studio-infra-storage:test :studio-infra-sdk:test :studio-app:test \
          --console=plain --no-daemon
```

Resultado: `BUILD SUCCESSFUL`. Exit code: 0.

**Detalle R3.4:**
```
./gradlew :studio-infra-sdk:test \
          --tests 'dev.pipelinek.studio.infra.sdk.PipelineKDiagnosticsAndIrPortsIT'
PipelineKDiagnosticsAndIrPortsIT tests=14 skipped=0 failures=0 errors=0
Exit code: 0
```

### Apps Node

```
apps/web     npm test -- --run → 5 files, 49 passed (49)  Exit code: 0
apps/{web,mcp,vscode}  npm run lint → exit 0
```
(Verificado en R3.3; sin cambios en código Node en este commit, suite re-ejecutable al cierre de hito.)

---

## 6. UAT/AAT vinculadas

- **AAT-CPP-001..006 (compile-time observability):** ✅ la separación de fases es la base para que el SPA distinga código sólo declarado (ADMITTED) de código compilado (COMPILED) y ejecutado (OBSERVED). Cerrado a nivel unit-test con adversarial coverage.
- **AAT-AN-005 (IR con procedencia demostrada):** ✅ cada fase se ancla a una superficie distinta del journal; la fusión accidental entre fases queda pinneada por los 3 tests adversariales.

---

## 7. Comandos y códigos de salida

```
$ ./gradlew :studio-infra-sdk:test \
            --tests 'dev.pipelinek.studio.infra.sdk.PipelineKDiagnosticsAndIrPortsIT' \
            --console=plain --no-daemon
…
PipelineKDiagnosticsAndIrPortsIT tests=14 skipped=0 failures=0 errors=0
Exit code: 0

$ ./gradlew :studio-cli:test :studio-http:test :studio-application:test \
            :studio-infra-storage:test :studio-infra-sdk:test :studio-app:test \
            --console=plain --no-daemon
…
BUILD SUCCESSFUL in 32s
Exit code: 0
```

---

## 8. Riesgos y deuda residual

1. **El guard empty-journal de R3.3 NO interfiere con estos tests:** verificado porque los 3 tests proyectan `IrReadResult.Ok` cuando al menos una superficie tiene datos. Un refactor futuro que cambie la semántica del guard podría romper estos tests; queda pinneado por la combinación de R3.3 + R3.4.
2. **Las fases se computan como `Set<IRPhase>` (sin orden temporal).** Si el roadmap exige orden cronológico, queda como sub-item futuro (no en este recibo).
3. **Cobertura sigue siendo unit-test level.** Integración end-to-end con SDK subprocess vivo queda pendiente hasta máquina de smoke.

---

## 9. Cierre

**Veredicto R3.4:** **PASS**.

- 3 tests adversariales pinneando la separación explícita de fases.
- 14/14 `PipelineKDiagnosticsAndIrPortsIT`, 520/520 Kotlin (0 skip, 0 fail), web suite y lint verde.
- IR explorer continúa sin fusionar fases; cada `IRPhase` se ancla a una superficie del journal.
- Item activo del loop R3.4 cerrado; siguiente: R3.5 (Detekt aislado).

---

**Recibo generado por:** Agente (R3.4, sesión de octubre 2026).
**Integridad:** este recibo se escribió antes del push. SHA `22202e7` integrado en `main`; tag `v1.0.0-rc.5` intacto en su SHA original (`bd7f7ce`).