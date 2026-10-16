# ADR-018 — UAT/AAT/E2E de ciclo, mínima regresión permanente

**Estado:** PROPOSED, 2026-10-16.

## Decisión

Usar `delivery-first-testing` como router, seleccionar lentes **ready** según `feature|merge|integration|release|audit` y riesgo observado. Cada hito crea escenarios UAT/AAT/E2E efímeros en directorio temporal **fuera de repo**, los ejecuta contra binarios de la etapa pertinente y los elimina después del registro de evidencia y la decisión `PROMOTE|REPLACE|RETIRE|DEFER`. Nunca crear un harness paralelo ni conservar todos los scripts por defecto.

Desarrollo: afectados por cambio. Merge: impacto real, lint/compilación/contratos y smoke mínimo. Integración: suite amplia apropiada según ROADMAP actual. Release: instalación desde artefactos publicados y verificación de integridad/completitud. La skill no certifica tests por sí sola.

## Regla de conservación

Seguridad, aislamiento, secretos, atomicidad, idempotencia, integridad, recuperación y formato de persistencia conservan test de regresión pequeño o contrato equivalente medido ANTES de retirar tests antiguos. Eliminar pruebas no útiles a partir de evidencia, no por nombre.

## Contención de deuda

Para cada script candidato legacy: garantía → verificador actual → sustituto → coste/fiabilidad → decisión. Nunca corregir producto para pasar un test que solo valida implementación obsoleta. Tasa de falso verde y tiempo p50/p95 medidos, sin cifras inventadas.

**Refs:** `../05-testing/AAT_AND_FITNESS.md`, `../05-testing/UAT_CATALOG.md`, `../../ROADMAP.md`, `Rubentxu/agent-skill/skills/delivery-first-testing/SKILL.md`.
