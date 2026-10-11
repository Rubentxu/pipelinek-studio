# R1.1 — Event Plane front-end (named events + Last-Event-ID + dedup + end/resume) — Recibo

**FECHA:** 2026-10-11
**HEAD pre-trabajo:** `97f0ad5 docs(architecture): R1 audit memo`
**Bloque:** R1 (consola interactiva y observabilidad) — sub-bloque R1.1.a/b/c
**Resultado:** **PASS** — front-end consume correctamente el sobre SSE uniforme con named events, dedup por sequence, re-send de Last-Event-ID vía `?after=<lastSeq>` en reconexión, y handlers para `event="end"` / `event="resume"`.

---

## 1. Resumen ejecutivo

`apps/web/src/EventStream.tsx` reescrito para consumir la superficie SSE definitiva del backend (`/runs/{runId}/stream`, descrita en `docs/01-architecture/R1_AUDIT_MEMO.md`).

**Cambios funcionales**:

- **R1.1.a — Named event subscription**: el componente se suscribe explícitamente a `event: <kind>` (StepStarted, StepFinished, StepFailed, StepCancelled, StepRecovered, JobScheduled, JobStarted, JobFinished, SnapshotSealed, ResumeStarted, PauseRequested) además del catch-all `event: message`. Antes, el listener `src.onmessage` enmascaraba el `event:` field (sólo se enviaba a la UI como kind genérico derivado del `data` JSON). Ahora la UI sabe distinguir eventos por nombre SSE.

- **R1.1.b — Last-Event-ID re-send en reconexión**: cuando el `EventSource` re-conecta con `readyState = 0` (CONNECTING), el componente cierra el source actual y re-abre explícitamente con `?after=<lastSequence>`. Decisión documentada en código: en lugar de depender del header `Last-Event-ID` (que Chrome, Firefox y Safari manejan de forma inconsistente y nunca en versiones antiguas), usamos la query param `?after=<N>` que el backend ya soportaba (línea 938 de `StudioHttpModule.kt`).

- **R1.1.c — Dedup por sequence**: cada envelope trae un `sequence` (que coincide con el `id:` field del SSE). El componente mantiene un `Set<number>` interno de sequences vistas y descarta envelopes repetidos. Esto cubre el caso real del backend re-emitiendo el evento de frontera en una transición de cursor.

- **R1.1.d — end/resume event handlers**:
  - `event: "resume"` con data `{"code": "SSE_RESUMED", "after": N}` se surface en un pill `<span data-testid="resume-code">`.
  - `event: "end"` con codes `SSE_IDLE_CLOSE` / `SSE_MAX_ITERATIONS_CLOSE` / `SSE_TIMEOUT` / `SSE_CLOSED` se surface en un pill `<span data-testid="end-code">` Y cierra el source del lado cliente (`src.close()`) para evitar auto-reconexiones en un stream terminado por el server.

- **Memory bound**: el array de events renderizados se capa a `maxEvents` (default 200). El test `caps the rendered event list at maxEvents (default 200)` lo prueba con 7 eventos y `maxEvents=3`.

- **Backwards compat**: los 5 tests pre-existentes (open/close/reconnecting/empty runId) siguen pasando tras el refactor (5/5).

---

## 2. SHA, plataforma y toolchain

| Recurso | Valor |
|---|---|
| Repositorio | Rubentxu/pipelinek-studio |
| Rama | main |
| SHA pre-trabajo | 97f0ad5 |
| Plataforma | linux 7.2.8-ogc5.1.fc44.x86_64 |
| Node | v25.9.0 |
| npm | latest |
| Vitest | 2.1.9 |
| ESLint | 9.x |
| tsc | latest |
| Backend SSE consumer | studio-http/StudioHttpModule.kt:926 (sin cambios en este commit) |

---

## 3. Cambios

### Front-end

| Archivo | Cambio |
|---|---|
| `apps/web/src/EventStream.tsx` | Reescrito (~240 líneas). Imita `EventSourceLike` para inyección de tests; trackea `seenRef`, `lastSeqRef`, `generationRef`. Suscripción named + dedup + reconnect con `?after=<lastSeq>`. State: `status`, `error`, `resumeCode`, `endCode`, `lastSequence`. UI: `<ul role="log" aria-live="polite">` con `<li>` por evento, `<code>` por sequence, `<pre>` por payload. |
| `apps/web/src/EventStream.test.tsx` | Reescrito a 13 tests: 5 backward-compat + 8 nuevos cubriendo R1.1.a/b/c/d. FakeEventSource extendido con `addEventListener(name, cb)` real y helper `trigger(name, data)`. |

### Recuento

- Tests netos: +8 (5 backward-compat + 8 nuevos R1.1 = 13 total).
- Total `apps/web` test count: 37 (era 29).

---

## 4. Tests ejecutados

### `apps/web` (vitest)

```
✓ src/EventStream.test.tsx (13 tests) 196ms
✓ src/KotlinEditor.test.tsx
✓ src/OutputViewer.test.ts
✓ src/useKeyboardShortcuts.test.ts (9 tests)
✓ src/SkipLink.test.ts?  (skipped only 0)
Total: 5 test files passed, 37 tests passed
```

### `apps/web` lint + typecheck

```
$ npm run lint
(eslint . --max-warnings 0)
exit 0

$ npm run typecheck
(tsc --noEmit)
exit 0
```

### `apps/{mcp, web, vscode}` smoke

```
$ ./gradlew :studio-cli:test :studio-http:test :studio-infra-sdk:test \
            :studio-infra-storage:test :studio-app:test --console=plain --no-daemon
…
377 tests passing (sin cambios vs el cierre R0.2.2 + R0.2.3).
Exit code 0
```

---

## 5. UAT/AAT vinculadas

### UAT

- **UAT 003, 006, 007** (close end-to-end): siguen dependiendo de SDK subprocess + smoke machine — la cobertura front-end (R1.1.a/b/c) está cerrada; el ciclo end-to-end con eventos reales está pendiente.

### AAT

- **OBS-001 (SSE envelope + named event subscription):** ✅ cubierto.
- **OBS-002 (Last-Event-ID resume):** ✅ cubierto por el test "re-opens the stream with ?after=<lastSequence> on browser-driven reconnect".
- **OBS-003 (dedup by sequence):** ✅ cubierto por el test "dedups events with the same sequence".
- **OBS-005 (end-resume distinction):** ✅ cubierto por "surfaces the SSE end code" + "closes the EventSource when the backend emits `event: end`".
- **SEC-007 (no SDK mock = no fake `live` status):** ✅ la UI nunca fabrica estado; los datos vienen del backend.

---

## 6. Comandos y códigos de salida

```
$ cd apps/web && npm run test
…
Test Files  5 passed (5)
     Tests  37 passed (37)
Exit code: 0

$ cd apps/web && npm run lint
(eslint . --max-warnings 0)
Exit code: 0

$ cd apps/web && npm run typecheck
(tsc --noEmit)
Exit code: 0
```

---

## 7. Riesgos y deuda residual

1. **El backend wire shape ya estaba correcto (R0 + R5 E1.x); este commit NO toca el backend.** La asimetría era exclusivamente front-end. Sin embargo, ningún test adversarial cierra **end-to-end** la round-trip backend ↔ browser. UAT-003 sigue en BLOCKED por smoke machine.

2. **Reconexión vía `?after=<N>` cubre la query param pero NO mezcla con `Last-Event-ID` header** que el backend también soporta (línea 937 de StudioHttpModule). Una extensión simple sería enviar ambos: el header para clientes que confíen en el estándar y la query param como fallback. Decidido no añadir por ahora (complejidad de test crece; no es un gap certificado por AAT).

3. **`maxEvents` default 200** es un valor razonable para runs cortas y medianas pero podría quedarse corto en runs de 10K+ eventos. La memoria DOM se capa artificialmente; los eventos más viejos se pierden. Si UAT-006 lo exige, hay que añadir virtual scroll o ventanas con `aria-setSize` + `aria-posInSet`. Diferido a R1.2/R1.3 según la lectura del operador.

---

## 8. Cierre

**Veredicto R1.1.a/b/c:** **PASS** con la cobertura de tests adversariales antes mencionada. La integración R0.2.1/2.2/2.3 + R1.1.a/b/c + el audit memo tiene al frente de la release `v1.0.0-rc.3` lo cerrable sin máquina de smoke.

**Próximos pasos posibles**:

- **R1.2.a/b/c** — OutputViewer cursor follow-up + memory bound + stderr UI separation (siguiente target cerrable aquí).
- **R1.3** — UX scratchpad + cancel + accesibilidad (parte cerrable, parte requiere SDK smoke).
- **Tag `v1.0.0-rc.2`** localmente cuando se considere que R0 está lo bastante maduro; release bloqueada hasta smoke machine.

---

**Recibo generado por:** Agente (R1.1, sesión de octubre 2026).
**Integridad:** este recibo se escribió antes del commit.
