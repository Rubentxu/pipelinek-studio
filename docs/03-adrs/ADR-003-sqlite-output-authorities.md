# ADR-003 — SQLite dividido y planos Event/Output separados

**Estado:** ACCEPTED (diseño propuesto; implementación pendiente) · **Fecha:** 2026-10-09 · **Proyecto:** PipelineK Studio.

## Contexto

PipelineK ya usa SQLite WAL y Operation Journal; copiar eventos a studio.db crearía una segunda autoridad de verdad. Los logs están en Output Plane, no son una tabla de eventos.

## Decisión

`studio.db` es propiedad del producto para Experiments/Snapshots/Scenarios/Bindings; `run.db` por ejecución es propiedad de PipelineK para journal/eventos; logs bajo Output Plane. Solo PipelineK altera su DB. Cursors de eventos/output son independientes.

## Alternativas y trade-offs

Más ficheros y retención coordinada, menos acoplamiento de migraciones y riesgo de escritor concurrente. Studio puede reconstruir proyecciones a partir de read-ports, nunca reinterpretar internal SQL.

## Invariantes verificables / criterios de invalidez

Test EventCursor no sustituye OutputCursor; lectura por otro proceso y crash/WAL. UAT-011/012, AAT-DUR-001..006.

## Implicaciones para agentes

El agente debe verificar antes de implementar que el contrato upstream sigue existiendo en el SHA que consume, producir una prueba que distinga la decisión correcta de un falso positivo, documentar cambios de alcance sin alterar el sentido de la ADR y abrir nueva ADR si una medición real la contradice. Nunca modificar arquitectura de PipelineK upstream por comodidad de Studio ni convertir el presente diseño en certificado de compatibilidad.

## Referencias

`../01-architecture/ARCHITECTURE.md`, `../../ROADMAP.md`, `../05-testing/AAT_AND_FITNESS.md`, `../07-research/UPSTREAM_GROUNDING.md`.
