# R1 — Auditoría del estado actual (memorando de planificación)

**FECHA:** 2026-10-11
**HEAD `origin/main`:** `c649ee8 feat(security): wire real TLS connector in studio start (R0.2.2 + R0.2.3)`
**Motivación:** el operador eligió pivotar a R1 (consola interactiva y observabilidad) tras cerrar R0.2.1/R0.2.2/R0.2.3. Este memo audita el estado real del front-end y back-end del Event/Output/UX plane para decidir qué sub-bloques se cierran en esta sesión.

---

## 1. R1.1 — Event Plane

### Mandato
- Unificar el contrato de eventos con el SDK.
- Implementar proyección de estados y stages a partir de eventos auténticos.
- Reparar `EventStream.tsx`: suscribirse a los nombres de evento emitidos o a un sobre SSE uniforme explícitamente versionado.
- Soportar cursores reanudables, duplicados, desconexiones y relectura autoritativa.
- El SSE debe anunciar cambios; no sustituir el journal como autoridad.

### Hallazgos del backend (ya hechos)

**`/runs/{runId}/stream` en `studio-http/StudioHttpModule.kt:926`** ya implementa:

- **Sobres JSON uniformes** (`buildJsonObject`) para cada evento, con `sequence`, `kind`, `occurredAt`, `payload` (escapeados por `kotlinx.serialization` desde la auditoría H-16 del 2026-10-10).
- **`id:` field en cada `send(...)`** (línea 1009) — el estándar SSE para que el cliente persista el último evento entregado.
- **`Last-Event-ID` y `?after=<sequence>` ambos soportados** (líneas 937-940). El cliente puede reanudar desde cualquier punto usando el estándar HTTP o la query param.
- **`event="resume"` notificación explícita** (línea 944) con `{"code":"SSE_RESUMED","after":N}` — el cliente recibe la señal de que su `Last-Event-ID` fue aceptado.
- **`event="end"` notificación explícita** (líneas 962, 972, 1015, 1019) con códigos distinguibles:
  - `SSE_IDLE_CLOSE` — stream vacío por >5s
  - `SSE_MAX_ITERATIONS_CLOSE` — alcanzó el cap `maxIterations=600` (server-side length cap)
  - `SSE_TIMEOUT` — loop llegó al final
- **`event="error"` para `READ_PORT_UNAVAILABLE`** (línea 932) — fail-closed cuando no hay SDK port cableado (escenario CLI sin `--sdk-classpath`).

**Backend ya correcto contra el mandato.** Sólo falta integración del lado cliente.

### Hallazgos del frontend — `apps/web/src/EventStream.tsx`

**3 problemas concretos**:

1. **`src.onmessage` ignora `event:` named events** (línea 49). El backend emite `event: <kind>` con nombres como `StepStarted`, `StepFinished`, `JobScheduled`. `onmessage` sólo dispara para el `event: message` por defecto, lo que significa que **`EventSource.addEventListener("StepStarted", ...)` no está siendo invocado** para esos named events. La UI muestra los eventos como genéricos de tipo `kind` del `data` JSON, sin suscribirse al nombre SSE.

2. **No reaprovecha `Last-Event-ID` en reconexión** (líneas 51-60). `EventSource` re-connect no envía automáticamente el header `Last-Event-ID` salvo en un subconjunto de navegadores recientes y con limitaciones; la práctica defensiva es enviarlo manualmente. La implementación actual no lo hace — al reconectar, el stream empieza desde el `head` (no desde el último `id` entregado), y los eventos que llegaron durante la ventana de desconexión **se pierden**.

3. **No maneja `event="end"` ni `event="resume"`** (no hay listeners). La UI no sabe distinguir entre "el backend cerró por idle" y "el backend cerró por cap" — sólo ve `error` o `close` EventSource nativo.

### Lo cerrable en este entorno (sin SDK subprocess)

- Reescribir `apps/web/src/EventStream.tsx` para:
  - Suscribirse a `event="resume"`, `event="end"`, y a los `event=<kind>` named events por separado.
  - Mantener un `lastSequence: number` interno y enviarlo explícitamente como `Last-Event-ID` en la siguiente reconexión.
  - Deduplicar eventos por `sequence` (el backend puede re-emitir el último evento en una transición de páginas).
  - Renderizar el `id` field en un cursor-resume monitor visible para el operador.
- Añadir un test unitario con un `EventSource` mock (o `@mswjs/sse`) que:
  - Verifique la reconexión con `Last-Event-ID` se envía correctamente.
  - Verifique que `event="end"` cierra el stream en lugar de disparar re-conexión.
  - Verifique dedup cuando el backend re-emite un evento en una transición.
- Limitación honesta: la "relectura autoritativa desde journal" necesita un SDK subprocess vivo con eventos reales — está bloqueada por máquina de smoke.

### UAT/AAT vinculadas

- **UAT 003, 006, 007**: cierre end-to-end del scratchpad + run + observability — pendiente SDK.
- **AAT OBS-001..008**: cobertura de smoke.

---

## 2. R1.2 — Output Plane

### Hallazgos del backend

- `/runs/{runId}/output` ya soporta `?after=<byte-offset>` (línea 878) con `OutputCursor(offset, streamId="stdout")` y limit de 16384 bytes por fetch (línea 880).
- **Limitación**: el `streamId` está hardcodeado a `"stdout"` en el cursor (línea 879). El `Reader.read()` puede devolver chunks de varios streamIds (`stdout`, `stderr`, step-output), pero el cursor sólo cuenta stdout.
- **Limitación**: la respuesta tiene `eof: Boolean` por chunk — no hay agregación de cuál fue el EOF final. La UI tendría que sumar `eof`s o detectarlo en la respuesta.
- **Limitación**: no hay límite duro en `OutputReadResult.chunks` (sólo el de 16384 bytes por fetch), pero el servidor ya no entrega los 100MiB de una vez — la paginación está implícita.

### Hallazgos del frontend — `apps/web/src/OutputViewer.tsx`

**3 problemas concretos**:

1. **Single-shot fetch** (línea 96 `void (async () => { ... })()`). El componente hace UNA llamada a `/runs/{id}/output` cuando se monta y nunca vuelve a llamar. Si la run sigue viva y produce más stdout, la UI no se entera. El mandato requiere "consumo sucesivo de páginas en Web con memoria acotada".

2. **`nextCursor` se almacena pero no se reusa** (línea 108 `setNextCursor(env.nextCursor ?? null)`). No hay un follow-up poll que use `?after=<nextCursor>` para traer chunks nuevos cuando aparecen.

3. **Sin memory bound**: el `text` se acumula en el estado (`setText(chunksToText(env.chunks ?? []))`) — toda la salida entra al DOM. Para 100 MiB el render es inviable (R-1.2 mandata explicitamente "100 MiB de output").

4. **No separación stdout/stderr visible en UI**: el `OutputChunkDto.streamId` se ignora; todo se concatena.

5. **No hay re-anclaje al cursor del backend tras desconexión**: si el operador recarga la página de navegador durante una run viva, `OutputViewer` arranca desde 0 y no puede continuar.

### Lo cerrable en este entorno

- Añadir un poll loop con `?after=<cursor>` que siga pidiendo chunks mientras la respuesta no haya reportado EOF en TODOS los streams.
- Implementar memory bound: una ventana virtualizable (sólo los últimos N KiB renderizados + bytes-counter para el total). Esto requiere CSS `overflow:auto` o un componente de scroll-paginado.
- Renderizar stderr en un `<pre>` separado, distinguible por color/aria-label.
- Test unitarios sin SDK: usar un fetch mock que devuelva varias páginas con `eof=true` en la última. Aserciones: el poll para cuando todas las páginas están entregadas, los bytes concatenados son los esperados, el cursor final es estable tras un re-mount.
- Adversariales: el render con 100 KiB sintéticos (no requiere 100 MiB reales, sólo probar el mecanismo) no causa error de DOM — usar un `jsdom` test con count de nodos.
- Limitación honesta: la certificación "100 MiB + dos lectores independientes" requiere una run real viva — requiere SDK subprocess instalado.

---

## 3. R1.3 — UX

### Hallazgos del frontend

- `apps/web/src/KotlinEditor.tsx` y `App.tsx` — ver siguientes inspecciones.
- `apps/web/src/SkipLink.tsx` y `useKeyboardShortcuts.ts` existen como utilities (accesibilidad).

### Lo cerrable en este entorno

- (Auditoría específica al leer los archivos — los siguientes dos pasos requieren leer `App.tsx` + `KotlinEditor.tsx` para diagnosticar.)
- Cuestiones típicas:
  - El scratchpad Monaco permite editar pero ¿sella el snapshot antes de ejecutar?
  - Cancel run: ¿está implementado como `POST /runs/{id}/cancel` con UI hook?
  - Reanudar observación post-refresh: ¿el RunId persiste en URL?
  - Accesibilidad básica: ¿hay role/log/aria-live ya?
  - Sin CDN obligatorio: las aserciones E2E anteriores verificaban `apps/web/package.json` no tiene `cdn` URLs en producción.

---

## 4. Cierre del memo

**Decisión recomendada (sujeto a confirmación del operador):**

| Sub-bloque | Trabajable aquí | Smoke real |
|---|---|---|
| **R1.1.a** — Frontend SSE named-event subscription + dedup | ✅ sí | ✅ requerido para UAT-003/006/007 |
| **R1.1.b** — Frontend `Last-Event-ID` reconnection | ✅ sí | ✅ requerido |
| **R1.1.c** — End-event handling | ✅ sí | ✅ requerido |
| **R1.1.d** — Backend journal como autoridad de relectura | ❌ no (ya está implícito en `/runs/{id}/events` journal) | ✅ requerido |
| **R1.2.a** — Frontend poll loop con `?after=<cursor>` | ✅ sí | ✅ requerido |
| **R1.2.b** — Memory bound (ventana) | ✅ sí | ✅ requerido |
| **R1.2.c** — Separación stdout/stderr en UI | ✅ sí (frontend) | backend streamId hardcodeado queda como POST-R1 |
| **R1.2.d** — 100 MiB certificación | ❌ no (requiere SDK run real) | bloqueado por máquina de smoke |
| **R1.3** — UX scratchpad + cancel + resume observability | parcial | total requiere SDK |

**Próximo paso**: si el operador confirma, implemento **R1.1.a/b/c + R1.2.a/b/c** (todos frontend, todos verificables con mocks) en una o dos sesiones, dejando R1.1.d + R1.2.d + R1.3 total para cuando haya máquina de smoke disponible.
