# ADR-016 — Fabric autoridad de hechos y salida; Studio solo presenta

**Estado:** PROPOSED, 2026-10-16. Precisa `ADR-003-sqlite-output-authorities.md`.

## Decisión

Studio no lee `run.db`, `output.sqlite` ni registros JSONL PipelineK. Todo pasa por `FabricClient.ProjectionSource`, `.ConsoleSource`, `.RunIndex` y, cuando esté publicado, `.RunProjectionSource`. Fabric es la autoridad del read side multi-run; la procedencia original de PipelineK se conserva en sus hechos.

SSE Studio representa **avisos de cambio**. Un navegador que reconecta vuelve a leer Fabric desde cursor/tokens emitidos por Fabric y obtiene los bytes Output por offset. No convertir `FactCursor` en `ConsoleOffset` ni `Last-Event-ID` en un token de Fabric sin contrato explícito.

## Alternativas rechazadas

Copiar hechos en `studio.db` como segunda verdad; regex a stdout/JSON; inferir terminal al desaparecer un proceso; SSE in-memory sin replay anunciado como durable.

## Leyes

- Dos lectores independientes recuperan el mismo historial semántico.
- Un error de versión de evento devuelve `Incomplete/Unsupported`, no SUCCESS.
- Unicode fragmentado en dos páginas se recompone por bytes sin pérdidas.
- Cancelación no confunde intento con outcome.

**Refs:** `ADR-003-sqlite-output-authorities.md`, `ADR-013-sse-tree-events.md`, Fabric `ProjectionSource.kt`, `ConsoleSource.kt`, `docs/decisions/ADR-0011-fact-read-contract.md`.
