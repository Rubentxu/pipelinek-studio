# ADR-002 — PipelineK único motor; runner JVM segregado

**Estado:** ACCEPTED (diseño propuesto; implementación pendiente) · **Fecha:** 2026-10-09 · **Proyecto:** PipelineK Studio.

## Contexto

Es tentador ejecutar `ScriptingHost` dentro de Ktor por ser Kotlin. El código de usuario puede ejecutar inicializadores o plugins maliciosos y tirar el servicio.

## Decisión

El runtime/compilación/evaluación de usuario solo viven en runner JVM supervisado y, cuando se declare aislado, dentro de sandbox OS verificado. Backend solo persiste metadatos, examina JAR sin cargar clases y usa los read-ports. No reimplementar interpreter, IR ni outcomes.

## Alternativas y trade-offs

Subproceso aporta coste de startup y classpath, pero fronteras de fallo explícitas. El aislamiento OS todavía necesita prueba; JVM distinta nunca significa sandbox garantizado.

## Invariantes verificables / criterios de invalidez

Mutar top-level del script no afecta al proceso backend; crash de runner se observa con state reconciliado. UAT-009, AAT-SEC-001..006.

## Implicaciones para agentes

El agente debe verificar antes de implementar que el contrato upstream sigue existiendo en el SHA que consume, producir una prueba que distinga la decisión correcta de un falso positivo, documentar cambios de alcance sin alterar el sentido de la ADR y abrir nueva ADR si una medición real la contradice. Nunca modificar arquitectura de PipelineK upstream por comodidad de Studio ni convertir el presente diseño en certificado de compatibilidad.

## Referencias

`../01-architecture/ARCHITECTURE.md`, `../../ROADMAP.md`, `../05-testing/AAT_AND_FITNESS.md`, `../07-research/UPSTREAM_GROUNDING.md`.
