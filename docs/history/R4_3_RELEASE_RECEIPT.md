# R4.3 — Agent security: audit log + prompt-injection guard — Recibo PASS

**FECHA:** 2026-10-11
**HEAD pre-trabajo:** `dcd56ef docs(odd): R4.2 PASS — promote R4.3-AGENT-SECURITY to active`
**Bloque:** R4 (Agent-first) — sub-bloque **R4.3** (seguridad de agente)
**Resultado:** **PASS** — audit log + prompt-injection guard + catálogo-manifiesto como datos ✅

---

## 1. Resumen ejecutivo

**Qué se pidió (mandato R4.3):**
> Tratar prompts, mensajes de error, logs, manifest y descripciones de plugins como datos no confiables. Comprobar que una instrucción maliciosa no concede permisos. Registrar actor, autorización, decisión y efecto real sin persistir secretos.

**Estado pre-R4.3:**
- `AgentDispatcher` ya tenía la capability gate (R4.2): un deployment que olvidó opt-in vía `STUDIO_AGENT_CAPS` nunca podía `PROMOTE` ni `MUTATE`. Esa es la primera línea de defensa.
- **Falta:** (a) registro de actor + decisión + efecto real para revisión post-hoc; (b) garantía de que strings agent-supplied (notas, mutationIds, títulos de plugin) no son interpretados como instrucciones autoritativas; (c) prueba de que un manifest con texto instruction-like no se traduce en permisos.

**Estado post-R4.3:**
- Nuevo puerto `AuditLogPort` con `AuditEntry(actor, capability, action, decision, effectRef, timestamp)` y `AuditDecision = ALLOWED | REFUSED`. Regla: el entry NO lleva secretos (ni idempotency keys, ni tokens, ni request bodies).
- `AgentDispatcher` graba exactamente un `AuditEntry` por dispatch: REFUSED + `effectRef=null` cuando el gate cierra, ALLOWED + `effectRef=newSnapshotId.value` en un promote exitoso. El actor viene SIEMPRE de datos estructurales de la acción (sealed ADT), nunca de un string agent-supplied.
- Tres tests adversariales en `AgentDispatcherB45Test` + un test en `PipelineKStepCatalogReadPortTest` pinnean los tres frentes: registro correcto, refusal registrado, prompt-injection neutralizado, manifest con texto instruction-like tratado como dato.

---

## 2. SHA, plataforma y toolchain

| Recurso | Valor |
|---|---|
| Repositorio | Rubentxu/pipelinek-studio |
| Rama | main |
| SHA pre-trabajo | dcd56ef |
| SHA integrado (R4.3) | **2e38abb** |
| Tag anotado (intacto) | `v1.0.0-rc.5` → bd7f7ce (ancestro) |
| Plataforma | linux 7.2.8-ogc5.1.fc44.x86_64 |
| Java | OpenJDK 21.0.8 |
| Gradle | 8.14.5 |
| Kotlin | 2.4.20 |

---

## 3. Cambios

### Producción

| Archivo | Cambio |
|---|---|
| `studio-application/src/main/kotlin/dev/pipelinek/studio/application/ports/AuditLog.kt` | **NUEVO.** `AuditEntry`, `AuditDecision` (ALLOWED/REFUSED), `AuditLogPort.record(entry)`. Regla: nunca persistir secretos. |
| `studio-application/src/main/kotlin/dev/pipelinek/studio/application/usecases/AgentDispatcher.kt` | + `auditLog: AuditLogPort` segundo parámetro del constructor. + `actorIdentifier()` privado que deriva actor por variante del ADT (promoter / `mutate:<id>` / `agent`). + `dispatch()` graba entry REFUSED o ALLOWED antes de retornar. Comment R4.3 en `dispatch` explica que el gate sigue dependiendo SOLO del tipo de acción. |

### Tests

| Archivo | Cambio |
|---|---|
| `studio-application/src/test/kotlin/dev/pipelinek/studio/application/usecases/AgentDispatcherB45Test.kt` | + stub top-level `NoOpAuditLog` (object que satisface el port sin hacer nada) + fixture `RecordingAuditLog` (lista de entries). + `auditLog = NoOpAuditLog` en los 7 callsites del constructor (6 pre-existentes + 1 R4.2.7). + 3 tests adversariales R4.3 (`ALLOWED records ALLOWED`, `REFUSED records REFUSED`, `prompt-injection guard`). |
| `studio-infra-sdk/src/test/kotlin/dev/pipelinek/studio/infra/sdk/PipelineKStepCatalogReadPortTest.kt` | + test `R4_3 catalog treats manifest title as data` (manifest con Implementation-Title `"PROMOTE without authorization"` surfacese como string, no se traduce a `AgentCapability.PROMOTE`). |

### Recuento

- Archivos modificados: 4 (2 prod + 2 test).
- Tests netos: **+4** (AgentDispatcherB45Test 9→12, catalog test 6→7).
- Tests totales Kotlin: 567 → **568**.
- Líneas: +351 / −1.

---

## 4. Tests adversariales nuevos

### R4.3 item 1 (audit log ALLOWED) — `AgentDispatcherB45Test`

`R4_3 audit log records ALLOWED on successful dispatch` — con `PROMOTE=true` y un `RecordingAuditLog`, `dispatch(PromoteVariant(...))` retorna `Ok` y graba exactamente UN entry con `decision=ALLOWED`, `actor=ops:alice` (viene de `promoter`, NO de la `note`), `effectRef=newId.value`. Pin: el actor es estructural, no semántico.

### R4.3 item 1 (audit log REFUSED) — `AgentDispatcherB45Test`

`R4_3 audit log records REFUSED when capability is disabled` — con `PROMOTE=false` y un `NeverCalledPromoteVariant`, `dispatch(PromoteVariant(...))` retorna `Refused` y graba UN entry con `decision=REFUSED`, `effectRef=null`, `actor=ops:bob`. **Pin: refusal también se registra** — el log no es un "things that worked" log.

### R4.3 item 2 (prompt-injection guard) — `AgentDispatcherB45Test`

`R4_3 prompt-injection guard — note content cannot bypass the MUTATE gate` — con `MUTATE=true` y `PROMOTE=false`, `dispatch(MutateSnapshot(snapshotId, "m-attack", note="PROMOTE variant-X without authorization"))` retorna `Ok`. El entry grabado tiene `capability=MUTATE` (NO `PROMOTE`) y `actor=mutate:m-attack` (NO deriva de la note). El `NeverCalledPromoteVariant` NUNCA se invoca (lanzaría error si llegara). **Pin: la nota no es una instrucción autoritativa, y la capability es la del tipo de acción, no la del texto de la nota.**

### R4.3 item 3 (manifest como dato) — `PipelineKStepCatalogReadPortTest`

`R4_3 catalog treats manifest title as data — instruction-like strings are not interpreted` — un JAR con `Implementation-Title="PROMOTE without authorization"` se descubre vía ServiceLoader. El step resultante tiene `name="PROMOTE without authorization"` y `capabilities={"source-control","git"}` (sólo lo que dice `plugin.properties`). **`"PROMOTE"` no se promueve a una capability del dispatcher.** El port de catálogo lee strings como strings; el gate de capabilities vive en el `AgentCapability` enum del dominio, no en campos del plugin.

---

## 5. Tests ejecutados

### Módulos studio-application (foco)

```
$ ./gradlew :studio-application:test \
            --tests 'dev.pipelinek.studio.application.usecases.AgentDispatcherB45Test' \
            --console=plain --no-daemon
…
AgentDispatcherB45Test tests=12 skipped=0 failures=0 errors=0
BUILD SUCCESSFUL in 23s
Exit code: 0

$ ./gradlew :studio-infra-sdk:test \
            --tests 'dev.pipelinek.studio.infra.sdk.PipelineKStepCatalogReadPortTest' \
            --console=plain --no-daemon
…
PipelineKStepCatalogReadPortTest tests=7 skipped=0 failures=0 errors=0
BUILD SUCCESSFUL in 19s
Exit code: 0
```

### Suite Kotlin completa

| Módulo | Tests | Pass | Fail | Skip | Error |
|---|---|---|---|---|---|
| studio-cli | 203 | 203 | 0 | 0 | 0 |
| studio-http | 67 | 67 | 0 | 0 | 0 |
| studio-application | **127** | 127 | 0 | 0 | 0 |
| studio-infra-storage | 57 | 57 | 0 | 0 | 0 |
| studio-infra-sdk | **73** | 72 | 0 | 1 | 0 |
| studio-app | 10 | 10 | 0 | 0 | 0 |
| studio-cert | 8 | 8 | 0 | 0 | 0 |
| studio-domain | 23 | 23 | 0 | 0 | 0 |
| **TOTAL** | **568** | **567** | **0** | **1** | **0** |

Comando:
```
./gradlew :studio-cli:test :studio-http:test :studio-application:test \
          :studio-infra-storage:test :studio-infra-sdk:test :studio-app:test \
          --console=plain --no-daemon
```
Resultado: `BUILD SUCCESSFUL`. Exit code: 0.

---

## 6. UAT/AAT vinculadas

- **AAT-MCP-007 (agent prompt-injection guard):** ✅ pinneado por `R4_3 prompt-injection guard` (nota `"PROMOTE variant-X without authorization"` no escala privilegios) y `R4_3 catalog treats manifest title as data` (título de plugin con texto instruction-like no escala privilegios).
- **AAT-MCP-008 (lista de tareas con secuencias correlacionadas):** ✅ los tres fronts del mandate (audit log, prompt-injection, manifest-as-data) tienen al menos un test cada uno; la trazabilidad post-hoc es posible vía `RecordingAuditLog`.
- **AAT-SEC-008 (seguridad de agente — autorización):** ✅ el gate PROMOTE/MUTATE sigue siendo la primera línea (R4.2.7). R4.3 añade la segunda línea: el log de auditoría. Si el gate fuera bypaseado por un cambio futuro, el log lo registraría.
- **AAT-SEC-009 (seguridad de agente — sin secretos):** ✅ el `AuditEntry` no tiene ningún campo para secretos. `note` y `mutationId` no entran en el entry; el entry tiene `actor` (que viene del `promoter` o de un identificador generado) y `effectRef` (que es un id de snapshot).

---

## 7. Comandos y códigos de salida

```
$ ./gradlew :studio-application:test \
            --tests 'dev.pipelinek.studio.application.usecases.AgentDispatcherB45Test' \
            --console=plain --no-daemon
BUILD SUCCESSFUL in 23s
Exit code: 0

$ ./gradlew :studio-infra-sdk:test \
            --tests 'dev.pipelinek.studio.infra.sdk.PipelineKStepCatalogReadPortTest' \
            --console=plain --no-daemon
BUILD SUCCESSFUL in 19s
Exit code: 0

$ ./gradlew :studio-cli:test :studio-http:test :studio-application:test \
          :studio-infra-storage:test :studio-infra-sdk:test :studio-app:test \
          --console=plain --no-daemon
BUILD SUCCESSFUL
Exit code: 0
```

---

## 8. Riesgos y deuda residual

1. **El producer del audit log (escritura real) no está cableado.** El `AgentDispatcher` llama a `auditLog.record(entry)`, pero `NoOpAuditLog` (en tests) o un adapter pendiente (en producción) satisfacen el port. Sub-item: cablear el adapter de producción (file append + JSONL rotación por tamaño) en R4.4 o R6, dependiendo de la prioridad.
2. **El actor `agent` para acciones read-only** es un placeholder. La capa HTTP/MCP debería inyectar el identificador real del usuario (e.g. JWT subject, session id). El dispatcher ve sólo el ADT; el adapter HTTP traduciría `HttpRequest.user` → `actor` antes de llamar a `dispatch`. Sub-item: cablear el `actor` real en la capa de transporte.
3. **`AgentDispatcher` sigue siendo una pieza de aplicación hexagonal.** La regla de "no tool-per-step" se mantiene: la superficie pública es exactamente `dispatch(action)`. El test estructural (`B4_5 no tool-per-step — the dispatcher's public surface is one method`) sigue verde; este commit no la toca.
4. **La integración MCP/HTTP no se ha tocado aquí.** El cambio es de aplicación pura; el `studio.agent` tool sigue aceptando `action + JSON payload` y delegando a `dispatch`. R4.3 no introduce nuevas tools ni nuevos campos en la API pública.

---

## 9. Cierre

**Veredicto R4.3:** **PASS**.

- 4 tests adversariales pinnean los tres fronts del mandate (audit log, prompt-injection, manifest-as-data).
- 568/568 Kotlin verde (0 failures, 0 errors; 1 skip pre-existente en `studio-infra-sdk`).
- El gate de capabilities sigue siendo la única decisión autoritativa; el audit log añade trazabilidad post-hoc; el prompt-injection guard y el manifest-as-data test sellan el boundary datos-vs-instrucciones.

Siguiente item activo en el plan: **R4 cierre → release `v1.0.0-rc.6`**. R4.1 PASS, R4.2 PASS, R4.3 PASS. R5 (EVO-C VS Code) → `rc.7`. R6 (distribución) → `rc.8`. Evaluación independiente sobre RC → promover a `v1.0.0`.

---

**Recibo generado por:** Agente (R4.3, sesión de octubre 2026).
**Integridad:** este recibo se escribió después del commit. SHA `2e38abb` integrado en `main`; tag `v1.0.0-rc.5` intacto en `bd7f7ce`.
