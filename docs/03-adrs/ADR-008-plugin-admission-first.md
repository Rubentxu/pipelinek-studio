# ADR-008 — Inspect manifest antes de ejecutar código de plugin

**Estado:** ACCEPTED (diseño propuesto; implementación pendiente) · **Fecha:** 2026-10-09 · **Proyecto:** PipelineK Studio.

## Contexto

`ServiceLoader` y constructor estático pueden ejecutar código antes de que una autoridad haya admitido un plugin. Metadata no es verdict.

## Decisión

Inspección de JAR/manifest/digest/version sin clase; admisión por interfaz real `PreLoadPluginAdmission` cuando disponible; composición/loader solo en runner/scanner aislado. No guardamos classloader de plugin en backend. Registry inmutable por snapshot.

## Alternativas y trade-offs

Discovery post-admisión requiere proceso de plugin, no siempre puramente estático. Es una frontera explícita: `MANIFEST_INSPECTED` ≠ `ADMITTED` ≠ `CERTIFIED`.

## Invariantes verificables / criterios de invalidez

Plugin con static initializer malicioso no ejecuta en inspección y rechazo de manifest; UAT-021, AAT-PLG-002/005.

## Implicaciones para agentes

El agente debe verificar antes de implementar que el contrato upstream sigue existiendo en el SHA que consume, producir una prueba que distinga la decisión correcta de un falso positivo, documentar cambios de alcance sin alterar el sentido de la ADR y abrir nueva ADR si una medición real la contradice. Nunca modificar arquitectura de PipelineK upstream por comodidad de Studio ni convertir el presente diseño en certificado de compatibilidad.

## Referencias

`../01-architecture/ARCHITECTURE.md`, `../../ROADMAP.md`, `../05-testing/AAT_AND_FITNESS.md`, `../07-research/UPSTREAM_GROUNDING.md`.
