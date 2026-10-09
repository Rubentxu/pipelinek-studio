# ADR-010 — Detekt en proceso aislado; LSP no es gate del MVP

**Estado:** ACCEPTED (diseño propuesto; implementación pendiente) · **Fecha:** 2026-10-09 · **Proyecto:** PipelineK Studio.

## Contexto

Detekt y el Kotlin LSP resuelven problemas distintos. El server LSP Kotlin actual debe demostrar soporte scripts `.pipeline.kts` y classpath real.

## Decisión

Detekt como tool adapter de proceso separado con diagnostics normalizados y versiones pinneadas; reglas custom de PipelineK pequeñas y certificadas. LSP por bridge opcional y spike sobre Monaco; si no funciona, no bloquear editor/ejecución y reportar unavailable.

## Alternativas y trade-offs

Análisis sin type resolution no se presenta como semántico. Detekt actual alpha exige compatibilidad toolchain y sandbox de reglas externas.

## Invariantes verificables / criterios de invalidez

UAT-030..034; AAT-AN-001..008.

## Implicaciones para agentes

El agente debe verificar antes de implementar que el contrato upstream sigue existiendo en el SHA que consume, producir una prueba que distinga la decisión correcta de un falso positivo, documentar cambios de alcance sin alterar el sentido de la ADR y abrir nueva ADR si una medición real la contradice. Nunca modificar arquitectura de PipelineK upstream por comodidad de Studio ni convertir el presente diseño en certificado de compatibilidad.

## Referencias

`../01-architecture/ARCHITECTURE.md`, `../../ROADMAP.md`, `../05-testing/AAT_AND_FITNESS.md`, `../07-research/UPSTREAM_GROUNDING.md`.
