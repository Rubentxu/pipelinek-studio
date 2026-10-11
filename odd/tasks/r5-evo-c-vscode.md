# R5 — EVO-C: integración nativa con VS Code — plan vivo

**META:** llevar la extensión actual de 4 comandos a una integración
nativa con VS Code (sidebar, status bar, SSE event stream,
cancelación, CodeLens para `*.pipeline.kts`).

**Autoridad de secuenciación:** este documento y `docs/04-roadmap/EVO-C_VSCODE_SPEC.md`.

**Tag objetivo:** `v1.0.0-rc.7` (cerrado, ya en `origin` como R2 closure; R5
hereda el mismo tag, con la entrega EVO-C en HEAD).

## Estado por item (snapshot 2026-10-11, HEAD tras este commit)

| # | Item | Estado | SHA / recibo |
|---|---|---|---|
| **EVO-C.0** | Server: SSE endpoint `GET /events` | **PASS** | `14a436b` |
| **EVO-C.A** | ADR-013: SSE endpoint contract | **PASS** | `91627b0` (`docs/03-adrs/ADR-013`) |
| **EVO-C.7** | SSE client + lifecycle + backoff | **PASS** | `91627b0` (`apps/vscode/src/sseClient.ts` + 15 tests) |
| **EVO-C.5** | Use cases wired to bus (real events) | **PASS** | este commit (`UseCases.kt` + `TreeEventBusPublishingR5Test`) |
| **EVO-C.1** | Status bar item (idle / running / error) | ABIERTO | — |
| **EVO-C.2** | Sidebar tree view (SSE-sourced) | ABIERTO | — |
| **EVO-C.3** | openRun command | ABIERTO | — |
| **EVO-C.4** | cancelRun command | ABIERTO | — |
| **EVO-C.5** | CodeLens para `*.pipeline.kts` | ABIERTO | — |
| **EVO-C.6** | Settings hardening (TLS, profile) | ABIERTO | — |
| **EVO-C.T** | Tests: 7 unit + 2 integration | ABIERTO | — |
| **EVO-C.D** | README + CHANGELOG update | ABIERTO | — |
| **EVO-C.P** | VSIX package + Extension Host smoke | ABIERTO | — |

## Cola priorizada (orden natural)

1. **EVO-C.0** (server SSE) — sin el endpoint, EVO-C.1+.7 no pueden
   recibir eventos en vivo; el sidebar y el status bar funcionarían
   con polling (regresión funcional).
2. **EVO-C.A** (ADR-013) — antes de empezar EVO-C.7 (SSE client), el
   contrato del endpoint debe estar pinneado en un ADR para que la
   implementación del cliente no se desvíe.
3. **EVO-C.7** (SSE client) — sin el cliente, EVO-C.1+.6 no pueden
   suscribirse; sería código muerto.
4. **EVO-C.1, EVO-C.2, EVO-C.3, EVO-C.4, EVO-C.5, EVO-C.6** — en
   paralelo, cada uno pequeño y testeable.
5. **EVO-C.T** (tests) — después de los items, los tests adversariales
   cierran el contrato.
6. **EVO-C.D, EVO-C.P** — housekeeping.

## Item activo (single)

- **ID:** EVO-C.0 (server SSE endpoint)
- **Mandato:** exponer `GET /events` como un endpoint SSE que streamea
  mutaciones del tree (experiments, runs, snapshots). El studio ya
  tiene SSE infrastructure (`app.install(SSE)` y `/runs/{runId}/stream`);
  EVO-C.0 añade el evento-tree side que el sidebar va a consumir.
- **Cierre cuando:** (a) endpoint `GET /events` abierto en StudioHttpModule
  con content-type `text/event-stream`; (b) emite los 6 eventos del
  taxonomy (`experiment.created`, `experiment.deleted`, `run.started`,
  `run.step.changed`, `run.completed`, `snapshot.created`); (c) emite
  ping cada 30s; (d) tests adversariales con un SSE consumer que
  verifica shape + cadence; (e) OpenAPI doc actualizado.

## Cómo se actualiza este plan

- Al cerrar un item: marcar `PASS`, escribir SHA + receipt path.
- Al abrir un nuevo item: añadir a la cola priorizada.
- Si aparece un nuevo `BLOCKED`: agregar a Riesgos con dueño + siguiente trigger.
- El **item activo** es siempre uno (o ninguno en transiciones de inter-block).
- El espejo Engram vive bajo topic `odd/r5-evo-c/tasks`; ambos se actualizan juntos.

## Riesgos abiertos

| ID | Riesgo | Bloquea | Mitigación |
|---|---|---|---|
| RK-R5-01 | El event bus de Studio puede no tener los 6 hooks; `experiment.created` puede no estar conectado al bus HTTP | EVO-C.0 | En la primera iteración, emitir los eventos desde un in-memory `TreeEventBus` simple con un único productor; los hooks reales se conectan cuando las use cases publiquen |
| RK-R5-02 | SSE en Extension Host puede tener race conditions en la reconexión | EVO-C.7 | Backoff exponencial 1s→30s + cursor de eventos (last-event-id) para re-sincronizar |
| RK-R5-03 | CodeLens provider necesita un parser ligero de `*.pipeline.kts`; usar regex es el anti-pattern del spec | EVO-C.5 | Tree-sitter o parser manual; el spec dice "sin regex sobre código fuente" — el CodeLens se renderiza sobre el parse AST |
| RK-R5-04 | Multi-workspace no está en MVP; per-workspace state del extension puede confundir al operador | EVO-C.6 | Una sola `pipelinekStudio.baseUrl` por workspace; documentar EVO-C.12 deferred |
