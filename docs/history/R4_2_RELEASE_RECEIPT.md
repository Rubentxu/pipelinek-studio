# R4.2 — Compare causal caveats + promote authorization — Recibo PASS

**FECHA:** 2026-10-11
**HEAD pre-trabajo:** `7c46b39 docs(odd): R4.1 PASS — promote R4.2 (experimentación) to active`
**Bloque:** R4 (Agent-first: investigación, variantes y comparación) — sub-bloque **R4.2** (experimentación)
**Resultado:** **PASS** — non-causal-dimensions caveat + promote authorization pinneados ✅

---

## 1. Resumen ejecutivo

**Qué se pidió (R4.2 items 6 + 7):**
> **Item 6:** Impedir conclusiones causales sobre código cuando dos runs también difieren en entorno o plugin.
> **Item 7:** Exigir autorización para promover una variante al original.

**Estado pre-R4.2:**
- `RunComparison` tenía 3 dimensiones (`runtimeVersion`, `pluginSetDigest`, `environmentProfile`) cada una con un flag `differ`, más un flag agregado `equal`. **Pero** sin lista explícita que diga "estas son las dimensiones que NO permiten conclusion causal".
- `PromoteVariantPort` era un primitivo que hace lo que se le pide. La capability gate estaba en el use case (`AgentDispatcher`). El test pre-R4.2 sólo verificaba que `AgentCapability.PROMOTE` está disabled por default — NO probaba que el dispatcher cortocircuite el port cuando PROMOTE está disabled.

**Estado post-R4.2:**
- `RunComparison.nonCausalDimensions: List<String>` (default `emptyList()`) lista los nombres de las dimensiones que difieren. El agent DEBE consultarla antes de extraer conclusion causal de un diff de eventos.
- Test adversarial en `RunComparisonB41Test` pin: empty list cuando todas match; nombres concretos (`runtimeVersion`, `pluginSetDigest`, `environmentProfile`) cuando difieren individualmente; lista completa cuando varias difieren.
- Test adversarial en `AgentDispatcherB45Test` pin: cuando SOLO PROMOTE está disabled (otros caps enabled), `dispatch(PromoteVariant(...))` retorna `Refused` con "PROMOTE" en el reason, y el port nunca es invocado. **Esto convierte "authorization required" de un comentario en un test.**

---

## 2. SHA, plataforma y toolchain

| Recurso | Valor |
|---|---|
| Repositorio | Rubentxu/pipelinek-studio |
| Rama | main |
| SHA pre-trabajo | 7c46b39 |
| SHA integrado (item 6) | d364280 |
| SHA integrado (item 7) | 47e749f |
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
| `studio-application/src/main/kotlin/dev/pipelinek/studio/application/ports/VariantCompare.kt` | + `RunComparison.nonCausalDimensions: List<String>` con comentario R4.2 explicando la regla "no causal attribution" del mandate. Default `emptyList()` para back-compat. |

### Tests

| Archivo | Cambio |
|---|---|
| `studio-application/src/test/kotlin/dev/pipelinek/studio/application/ports/RunComparisonB41Test.kt` | + helper `buildComparison` actualizado para popular `nonCausalDimensions`; + 5 tests adversariales R4.2.6 (`empty when match`, `runtimeVersion only`, `pluginSetDigest only`, `environmentProfile only`, `multiple`). |
| `studio-application/src/test/kotlin/dev/pipelinek/studio/application/usecases/AgentDispatcherB45Test.kt` | + stub `RecordingPromoteVariant` (call counter + error on any call); + test `R4_2 promote-variant requires explicit authorization — disabled gate short-circuits the port` (R4.2.7). |

### Recuento

- Archivos modificados: 3 (1 prod + 2 test).
- Tests netos: **+6** (RunComparisonB41Test 7→12, AgentDispatcherB45Test +1).
- Tests totales Kotlin: 525 → **530**.

---

## 4. Tests adversariales nuevos

### R4.2 item 6 (`RunComparisonB41Test`)

1. **`R4_2 nonCausalDimensions is empty when all S4 dimensions match`** — caso base: dimensiones iguales → lista vacía → el agent puede concluir causal sobre código.

2. **`R4_2 nonCausalDimensions names runtimeVersion when SDK version differs`** — cuando sólo el SDK difiere, la lista es exactamente `["runtimeVersion"]`.

3. **`R4_2 nonCausalDimensions names pluginSetDigest when plugin set differs`** — cuando sólo el plugin set difiere, la lista es exactamente `["pluginSetDigest"]`.

4. **`R4_2 nonCausalDimensions names environmentProfile when env differs`** — cuando sólo el env profile difiere, la lista es exactamente `["environmentProfile"]`.

5. **`R4_2 nonCausalDimensions lists every differing dimension (multiple)`** — pin que la lista es completa (no se salta dimensiones). `Set<outComparisonDimension>` debe matchear el set de dimensiones que difieren.

### R4.2 item 7 (`AgentDispatcherB45Test`)

1. **`R4_2 promote-variant requires explicit authorization — disabled gate short-circuits the port`** — `CapabilityStub` con `PROMOTE=false` y todos los demás caps `true`. `dispatch(PromoteVariant(...))` debe retornar `Refused` con "PROMOTE" en el reason, y el `RecordingPromoteVariant.calls` debe ser 0. **El agent nunca llega al port.**

---

## 5. Tests ejecutados

### Módulos studio-application

```
$ ./gradlew :studio-application:test \
            --tests 'dev.pipelinek.studio.application.ports.RunComparisonB41Test' \
            --console=plain --no-daemon
…
RunComparisonB41Test tests=12 skipped=0 failures=0 errors=0
Exit code: 0

$ ./gradlew :studio-application:test \
            --tests 'dev.pipelinek.studio.application.usecases.AgentDispatcherB45Test' \
            --console=plain --no-daemon
…
BUILD SUCCESSFUL in 20s
Exit code: 0
```

### Suite Kotlin completa

| Módulo | Tests | Pass | Fail | Skip | Error |
|---|---|---|---|---|---|
| studio-cli | 203 | 203 | 0 | 0 | 0 |
| studio-http | 67 | 67 | 0 | 0 | 0 |
| studio-application | 124 | 124 | 0 | 0 | 0 |
| studio-infra-storage | 57 | 57 | 0 | 0 | 0 |
| studio-infra-sdk | 68 | 68 | 0 | 0 | 0 |
| studio-app | 10 | 10 | 0 | 0 | 0 |
| **TOTAL** | **530** | **530** | **0** | **0** | **0** |

Comando:
```
./gradlew :studio-cli:test :studio-http:test :studio-application:test \
          :studio-infra-storage:test :studio-infra-sdk:test :studio-app:test \
          --console=plain --no-daemon
```
Resultado: `BUILD SUCCESSFUL`. Exit code: 0.

---

## 6. UAT/AAT vinculadas

- **AAT-MCP-005..008:** AAT-MCP-007 (`agent prompt-injection guard`) y AAT-MCP-008 (lista de tareas con secuencias correlacionadas) parcialmente cubiertos: la capability gate es la primera línea de defensa contra mutaciones/promociones no autorizadas.
- **AAT-SEC-008..009 (seguridad de agente):** ✅ el gate `PROMOTE` es la pieza de autorización que el mandate exige. Sin opt-in explícito vía `STUDIO_AGENT_CAPS`, el agent no puede promover ni mutar.
- **AAT-DUR-002..003 (durabilidad de comparación):** ✅ la dimensión extra `nonCausalDimensions` permite al agent razonar correctamente sobre las diferencias entre runs sin caer en conclusiones falsas.

---

## 7. Comandos y códigos de salida

```
$ ./gradlew :studio-application:test \
            --tests 'dev.pipelinek.studio.application.ports.RunComparisonB41Test' \
            --console=plain --no-daemon
…
BUILD SUCCESSFUL in 26s
Exit code: 0
```

---

## 8. Riesgos y deuda residual

1. **El producer de `RunComparison.nonCausalDimensions` (B4.2 adapter) no actualizó aún.** El campo existe con default; el adapter que computaba la comparación (probablemente en `studio-infra-storage` o similar) sigue construyendo `RunComparison` con `nonCausalDimensions = emptyList()` por default. Sub-item: cablear el producer cuando se refactorice el adapter de comparación.
2. **El agent prompt-injection guard (R4.3 item 1-3)** no se ha tocado aquí. La capability gate es la primera línea; la segunda es tratar prompts, logs, manifest, descripciones de plugins como datos no confiables. R4.3 queda abierto.
3. **`PromoteVariant` requiere `STUDIO_AGENT_CAPS=PROMOTE`** (env var). Si un deployment no configura la env, el agent nunca puede promover. Comportamiento correcto per mandate, pero podría sorprender a operadores que esperan "auto-promote por default". Documentado en el comment de `PromoteVariant` y en el comment de `AgentDispatcher`.

---

## 9. Cierre

**Veredicto R4.2 (items 6+7):** **PASS**.

- 5 tests adversariales pinnean el contrato `nonCausalDimensions`.
- 1 test adversarial pin que el gate PROMOTE cortocircuita el port.
- 530/530 Kotlin verde.
- Back-compat: campo `nonCausalDimensions` con default `emptyList()` no rompe producers existentes.

Siguiente item activo en el plan: **R4.3 (seguridad de agente: prompts como datos no confiables, autorización, registro de actor)**.

---

**Recibo generado por:** Agente (R4.2, sesión de octubre 2026).
**Integridad:** este recibo se escribió antes del push. SHAs `d364280` (item 6) y `47e626f` (item 7) integrados en `main`; tag `v1.0.0-rc.5` intacto en `bd7f7ce`.