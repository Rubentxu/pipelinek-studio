# ADR-015 — Identidades separadas, reintentos y efecto desconocido

**Estado:** PROPOSED, 2026-10-16. Amplía `ADR-005-snapshots-idempotency.md`.

## Decisión

`StudioRunId` identifica una intención/experimento local, `StartRunId` identifica la solicitud que Fabric puede reintentar, `FabricRunId` identifica su ejecución y `ProducerRunId` la del runtime. Son `@JvmInline value class` distintos; no reinterpretar `UUID` ni tomar prefijos. La petición Fabric completa se sella en BD antes de llamar a red.

`StartRunResult = Accepted(FabricRunId) | Rejected(Reason) | UnknownEffect(TransportError)`; `UnknownEffect` no permite mintar un `StartRunId` nuevo ni afirmar FAIL. Reenviar el MISMO comando solo después del fix de admisión/dispatch de Fabric. Igual identidad/diferente contenido = `Conflict`.

## Semántica de cancelación

`CancelRequested` (intención) ≠ `StoppedProcess` (mecanismo) ≠ `Terminal(Interrupted)` (hecho). Una petición tardía puede encontrarse con terminal Success y devolver conflicto; jamás escribir `RunFinished` sintético.

## Pruebas discriminantes

Doble llamada concurrente produce 1 `FabricRunId`, 1 lanzamiento observable. Timeout tras admisión + reinicio devuelve id inicial y no dispara segundo efecto. Cambio parámetros mismo commandId se rechaza. Cruce accidental de id Fabric/Studio provoca rechazo, no lectura del run ajeno.

**Refs:** `ADR-005-snapshots-idempotency.md`, Fabric `RunControl.kt`, `RunRegistry.kt`, `RunAdmission.kt`, `docs/decisions/ADR-0016-run-outcome-is-one-concept.md`.
