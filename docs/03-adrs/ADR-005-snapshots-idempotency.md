# ADR-005 — SourceSnapshot inmutable y acciones idempotentes

**Estado:** ACCEPTED (diseño propuesto; implementación pendiente) · **Fecha:** 2026-10-09 · **Proyecto:** PipelineK Studio.

## Contexto

Un editor mutable, retries HTTP y MCP desconectable crean riesgo de ejecutar el mismo pipeline dos veces o perder el código que se ejecutó.

## Decisión

Cada Run referencia snapshot sellado, scenario/profile/runtime/plugins digests. `Idempotency-Key` transaccional vincula operación, scope y request digest antes de iniciar child; duplicado con mismo payload retorna handle, con otro devuelve conflicto.

## Alternativas y trade-offs

Exige más metadata y saneamiento de retención, pero habilita reproducibilidad, comparación honesta, agent-first y recovery. No asumir que el run es determinista si interactúa con el entorno.

## Invariantes verificables / criterios de invalidez

Dos requests concurrentes y crash entre commit/launch producen como máximo un launch confirmado o estado reconciliable. AAT-DUR-002/003, UAT-014.

## Implicaciones para agentes

El agente debe verificar antes de implementar que el contrato upstream sigue existiendo en el SHA que consume, producir una prueba que distinga la decisión correcta de un falso positivo, documentar cambios de alcance sin alterar el sentido de la ADR y abrir nueva ADR si una medición real la contradice. Nunca modificar arquitectura de PipelineK upstream por comodidad de Studio ni convertir el presente diseño en certificado de compatibilidad.

## Referencias

`../01-architecture/ARCHITECTURE.md`, `../../ROADMAP.md`, `../05-testing/AAT_AND_FITNESS.md`, `../07-research/UPSTREAM_GROUNDING.md`.
