# R0.2 (parcial) — Recibo de cierre

> **Bloque:** R0.2 (Corregir el producto en su arranque real).
> **Release objetivo:** `v1.0.0-rc.2` (R0 completo).
> **Estado:** **PASAR** en 3 de 6 sub-items; **3 diferidos**.
> **SHA integrado:** `6e5c4de` (en `origin/main`).
> **Fecha:** 2026-10-11.

R0.2 cubre 6 sub-items. Este recibo cierra **R0.2.4, R0.2.5 y R0.2.6**;
los sub-items 2.1, 2.2 y 2.3 quedan **diferidos** por su envergadura (refactor
de ServerStart vs StudioHttpModule; TLS real con handshake contra app
instalada). El bloque R0.2 NO se considera DONE; este recibo es un
**avance parcial** publicable.

---

## Sub-items cerrados en este recibo

### R0.2.4 — Sustituir `RefusingRun/OutputReadPort` en wiring productivo

**Antes:** `studio-app/Main.kt:121` llamaba `buildWiring(metadata, runner, RefusingRunReadPort, RefusingOutputReadPort, ...)`. Los stubs devolvían `RunReadResult.Unknown` y `OutputReadResult.UnknownStream` con la cadena literal "S0 bootstrap: not implemented" — lo que significaba que `GET /runs/{id}/events`, `GET /runs/{id}/stream` y `GET /runs/{id}/output` siempre degradaban a "no sabemos".

Curiosamente, el módulo `StudioHttpModule` ya estaba cableado correctamente (líneas 158-164 del mismo archivo: `runReadFactory` + `outputReadFactory` apuntan a los adaptadores reales). La contradicción vivía en la capa de **use-cases** (Wiring), no en la capa HTTP. El HTTP fabricaba sus propios ports via factories; los use-cases que iban por `Wiring.read` recibían los stubs.

**Cambio:**
```kotlin
// studio-app/src/main/kotlin/dev/pipelinek/studio/app/Main.kt
val wiringFactory: () -> Wiring = {
    buildWiring(
        metadata, runner,
        PipelineKRunReadPort(controlRoot),
        PipelineKOutputReadPort(controlRoot),
        idempotency, probe,
    )
}
```

Ambos adaptadores (en `studio-infra-sdk`) toman un `Path` (el `controlRoot` donde el subprocess SDK escribe `run.db` y `stdout.log` bajo `<runId>/`). Son **read-only** sobre `run.db` (ADR-003: Studio nunca abre `run.db` en modo writer; solo el subprocess SDK posee ese handle). Devuelven `Unknown` / `UnknownStream` con motivo explícito cuando el archivo no existe — **nunca inventan datos**.

Los stubs (`studio-http/Stubs.kt:RefusingRunReadPort`, `RefusingOutputReadPort`) **se mantienen** en uso en los tests unitarios (`RouteParamsTest`, `SecurityHardeningTest`, `PerfBaselineTest`). Esos tests aíslan el contrato HTTP sin tener que escribir un `run.db` real — el uso de los stubs en tests no es la contradicción que el mandato señala.

**Commit:** `f1b58d1 fix(wiring): wire real PipelineKRunReadPort + OutputReadPort (R0.2.4)`.

### R0.2.5 — Aceptar 200 / 201 / 202 en POST create family

**Antes:**

| Cliente | `createExperiment` | `sealSnapshot` | `startRun` |
|---|---|---|---|
| CLI | requiere 200 | requiere 200 | requiere 200 \|\| 202 |
| Web | (no handler específico) | (no handler específico) | requiere 200 |

El backend distingue tres paths:

| Outcome backend | Significado |
|---|---|
| `200 OK` | legacy / canonical body |
| `201 Created` | `ReservationOutcome.Admitted` — el run se ha iniciado, subprocess spawneado |
| `202 Accepted` | `ReservationOutcome.Reserved` — reserva durable; spawn bloqueado por idempotency |

Los clientes rechazaban el path más importante (`201 Created` = "el run empezó"). El CLI fallaba su `startRun` con "start-run failed: 201 Created"; el Web mostraba "HTTP 201" como submit error.

**Cambios:**

1. `studio-cli/.../StudioHttpClient.kt`:
   ```kotlin
   private companion object {
       val createAcceptableStatuses: Set<Int> = setOf(
           HttpStatusCode.OK.value,        // 200
           HttpStatusCode.Created.value,   // 201
           HttpStatusCode.Accepted.value,  // 202
       )
   }
   ```
   Los tres `require(r.status == HttpStatusCode.OK)` se sustituyen por `require(r.status.value in createAcceptableStatuses)`.

2. `apps/web/src/App.tsx`:
   ```ts
   if ((r.status === 200 || r.status === 201 || r.status === 202) && body.run_id) {
     setCurrentRun({ runId: body.run_id, state: body.state ?? "QUEUED" });
   }
   ```

**Commit:** `6e5c4de fix(http): accept 200/201/202 on POST create family (R0.2.5)`.

### R0.2.6 — Reconciliar /api/v1 OpenAPI vs rutas reales

`contracts/openapi.yaml` declaraba 12 rutas con prefijo `/api/v1` mientras que `StudioHttpModule.install` siempre montó las rutas sin prefijo (`/experiments`, `/runs`, etc.). La contradicción venía del estado B5.0/B5.1; **ningún cliente de producción** (CLI / Web / MCP) usó jamás el prefijo `/api/v1`.

- `StudioHttpClient` solo usa `/api/version` (endpoint especial, no recurso).
- El generador de SDK (`studio generate-sdk-ts`) lee de `StudioHttpModule.current`, **no** de `openapi.yaml`. Por tanto el SDK TS ya seguía las rutas sin prefijo.

**Cambio:** 12 rutas en `contracts/openapi.yaml` pierden el prefijo `/api/v1` (queda `/experiments`, `/runs/{runId}/events`, etc.). La nota informativa al inicio del OpenAPI documenta la decisión y lista los gaps restantes (operation-scoped output ya obsoleto; falta declarar la superficie completa de StudioHttpModule: `/api/admin/lease`, `/api/admin/retention`, `/api/recovery`, `/api/static-analyze-capability`, `/api/metrics`, `/openapi.json`, `/steps`, `/steps/{stepId}`, `/runs/{runId}/diagnostics`, `/runs/{runId}/ir`, `GET /runs`). Los schemas en `components/schemas` no están aún alineados con `studio-http/.../Json.kt`.

**Lo que este commit NO cambia:**
- El URN `studio.pipelinek.dev/api/v1` (línea 203) es un identificador de brand/schema, no una URL path; se mantiene intencionalmente.

**Commit:** `88af425 docs(openapi): drop /api/v1 prefix to match actual server routes (R0.2.6 partial)`.

---

## Sub-items diferidos

### R0.2.1 — Unificar servidor productivo y CLI

**Diagnóstico:** `studio start` (CLI) bootea `MinimalModule` (sólo `/health`, `/api/version`, `/api/metrics`, `/api/ready`, `/openapi.json` — sin experiments/runs/etc.). `studio-app` bootea `StudioHttpModule` con la API completa. Son implementaciones distintas con **diferencias funcionales injustificadas** — exactamente lo que el mandato prohíbe.

**Por qué se difiere:** la unificación requiere uno de:
1. Extraer `StudioHttpModule` (HTTP wiring + controlRoot + probe + TLS) a una función reutilizable que ambos entry points invoquen. Implica refactor de `Main.kt` en `studio-app` + cambios en `ServerStart.kt` en `studio-cli` + decisiones sobre si la CLI debe ejecutar el SDK subprocess (probablemente sí; el smoke server no tiene sentido como "producto").
3. Eliminar `MinimalModule` y hacer que `studio start` arranque el JVM de `studio-app` directamente. Implica crear un classpath compartido y un módulo main compartido.

Cualquiera de las dos es **trabajo de R0.5/R6**, no de esta sesión. El CLI's `MinimalModule` se mantiene mientras el operador decida si el smoke server sigue siendo un surface legítima (operador en entorno restringido que solo quiere /health).

**Riesgo:** `studio start` sigue exponiendo una superficie limitada. La UAT-039 (instalar Studio y ejecutar pipeline real) **NO puede completarse** hasta que R0.2.1 se cierre.

### R0.2.2 — TLS real

**Diagnóstico:** `TlsConfig.buildNettySslContext(dataDir)` genera keystore + cert self-signed, y la CLI confía all-cert en loopback. El handshake contra la app **instalada** (no en dev) no se ha probado contra un cliente externo (curl con `--cacert`, openssl s_client). El keystore es real, pero el path de certificación no.

**Por qué se difiere:** probar TLS real con un cliente externo y un cert específico requiere una máquina limpia y un runbook reproducible — exactamente lo que `v1.0.0-rc.2` haría en su smoke de release. Esto se cierra en el smoke del bloque R0 completo, no antes.

### R0.2.3 — HTTP inseguro opt-in

**Diagnóstico parcial:** el flag `--insecure-http` ya existe en `studio start` (línea 47 de `Main.kt`) y la CLI ajusta scheme + port. **El opt-in existe**; la prueba contra un cliente externo (curl + `--cacert` rechazado, `curl` sin flag en plaintext → connection refused) no se ha hecho end-to-end.

**Por qué se difiere:** mismo motivo que R2.2 — smoke de release.

---

## Evidencia ejecutada (local)

| Comando | Resultado |
|---|---|
| `./gradlew :studio-cli:test :studio-http:test :studio-infra-sdk:test :studio-app:test --rerun-tasks` | 200 / 60 / 57 / 10 tests; 0 failures |
| `cd apps/mcp && npm run lint && npm run typecheck` | exit 0 |
| `cd apps/web && npm run lint && npm run typecheck` | exit 0 |
| `cd apps/vscode && npm run lint && npm run typecheck` | exit 0 |

## Integración remota

| Paso | Resultado |
|---|---|
| SHA integrado en `origin/main` | `6e5c4de fix(http): accept 200/201/202 on POST create family (R0.2.5)` |
| Rango pushed | `88af425..6e5c4de` (3 commits: f1b58d1, 88af425, 6e5c4de) |
| Working tree post-push | Limpio |

## Riesgos / deuda residual

| ID | Riesgo | Estado | Próximo paso |
|---|---|---|---|
| `R-R0.2-001` | R0.2.1 server unification | Diferido | refactor R0.5/R6 |
| `R-R0.2-002` | R0.2.2 TLS real handshake | Diferido | smoke de release rc.2 |
| `R-R0.2-003` | R0.2.3 HTTP opt-in end-to-end | Diferido | smoke de release rc.2 |
| `R-R0.2-004` | OpenAPI schemas no alineados con `studio-http/.../Json.kt` | Diferido | sync R0.6 |
| `R-R0.2-005` | OpenAPI no declara la superficie admin/recovery/metrics/steps | Diferido | sync R0.6 |

## Veredicto R0.2 (parcial)

**`PASS` en R0.2.4, R0.2.5, R0.2.6. `BLOCKED — diferido` en R0.2.1, R0.2.2, R0.2.3.**

El bloque R0.2 NO está cerrado. El end-to-end real (UAT-039) **sigue bloqueado** hasta que R0.2.1 unifique servers y R0.2.2/3 verifiquen TLS. El R0 completo **sigue bloqueado** hasta que R0.3 cierre integridad de ejecución (PRAGMA foreign_keys, pool recovery, upstreamRunId).

Publicación de `v1.0.0-rc.2` queda condicionada a:
1. R0.2.1 unificación
2. R0.3 cierre
3. Smoke desde máquina limpia
4. Tag anotado + `gh release create` (D-007 explícito)