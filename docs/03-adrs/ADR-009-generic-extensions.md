# ADR-009 — Extensiones tipadas sin Views de plugins particulares

**Estado:** ACCEPTED (diseño propuesto; implementación pendiente) · **Fecha:** 2026-10-09 · **Proyecto:** PipelineK Studio.

## Contexto

Policies/Assurance están evolucionando y exigir sus vistas ahora haría un Studio acoplado a API inestable; aceptar JS arbitrario desde plugins sería peligroso.

## Decisión

Dejar `ResultDescriptor`, `Diagnostic`, `EvidenceRef`, `ArtifactRef`, `ScenarioDescriptor`, `schemaId/viewKind` versionables. Renderers seguros: text/JSON/table/graph/diff/provenance. Policies/Assurance adaptarán sus reports posteriormente sin tipos específicos en núcleo.

## Alternativas y trade-offs

Los UI renderers genéricos no ofrecerán visualización tan rica en MVP, pero sirven para cualquier plugin y constituyen la seam futura. Ninguna interfaz se crea sin consumidor real.

## Invariantes verificables / criterios de invalidez

0 imports pipelinek-policy/assurance en Studio core y plugin desconocido puede mostrarse por JSON; AAT-PLG-007.

## Implicaciones para agentes

El agente debe verificar antes de implementar que el contrato upstream sigue existiendo en el SHA que consume, producir una prueba que distinga la decisión correcta de un falso positivo, documentar cambios de alcance sin alterar el sentido de la ADR y abrir nueva ADR si una medición real la contradice. Nunca modificar arquitectura de PipelineK upstream por comodidad de Studio ni convertir el presente diseño en certificado de compatibilidad.

## Referencias

`../01-architecture/ARCHITECTURE.md`, `../../ROADMAP.md`, `../05-testing/AAT_AND_FITNESS.md`, `../07-research/UPSTREAM_GROUNDING.md`.
