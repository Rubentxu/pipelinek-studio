# Receipt — R6.3 GitHub Releases: decisión sobre `v1.0.0-rc.1`..`rc.4` backfill

**Bloque:** R6.3 (Contratos de publicación) — ítem D6 del snapshot
[`docs/04-roadmap/ROADMAP_NUEVO_2026-10-17.md`](../04-roadmap/ROADMAP_NUEVO_2026-10-17.md).
**Fecha:** 2026-10-17.
**SHA base:** `839ce9b` (`main`).
**Operador (R-VER-001, ADR-011):** puede sobrescribir esta decisión.

## Estado observado

- `v1.0.0-rc.1`..`v1.0.0-rc.4` existen como **tags** anotados en el
  historial local. La etiqueta `^{}` (commit peeled) confirma que cada
  tag apunta a un commit real.
- Esas tags **NO** tienen un GitHub Release adjunto. Sólo `v0.1.0-alpha.1`
  lleva release page con assets; los 11+ tags sin release fueron
  registrados en L20/L22 de `odd/tasks/operational-verification.md`.
- `v1.0.0-rc.5` (R3) y `v1.0.0-rc.6` (R4) sí tienen release + assets.
  `v1.0.0-rc.7` (R2 closure) igual. La línea de releases vivos empieza
  en rc.5.

## Decisión

**NO se hace backfill** de `v1.0.0-rc.1`..`v1.0.0-rc.4`. Se documenta
el porqué en este recibo. La acción de release arranca canónicamente
en `v1.0.0-rc.5`.

### Por qué no backfillear

1. **ADR-011 — Release identifier is the main authority.** "Un único
   identificador de release compartido por CLI, backend, Web, MCP y
   VSIX." Reutilizar el identificador para un artefacto construido
   después, o publicar binarios sobre un commit sin contrato de
   release, diluye la trazabilidad.
2. **No hay `standaloneDist` reproducible en esos commits.** Los
   artefactos que se necesitan para un release real
   (jlink JRE + Node bundle + SBOM + checksums) no existen como output
   de CI en los commits `rc.1`..`rc.4`. Publicar `v1.0.0-rc.4`
   con assets significa construir AHORA sobre un commit viejo y
   etiquetar el resultado con un nombre viejo — el SHA de los
   binarios no coincidirá con el SHA del tag, rompiendo
   `SHA256 verified download`.
3. **El historial de receipts ya documenta lo que esos tags
   significan.** `docs/history/R0_*_RECEIPT.md`,
   `R1_1/R1_2/R1_3_RELEASE_RECEIPT.md`, `R2_CLOSURE_v1_0_0_rc_7.md`
   llevan la traza de cada milestone. `v1.0.0-rc.1`..`rc.4` son los
   *pre-closure markers*; el primer release con assets descargables
   es `v1.0.0-rc.5`. La "historia" está en `docs/history/`, no
   necesita un release page para sostenerse.
4. **G0 (Fabric contracts no publicadas) sigue activo.** Hasta que
   `io.jenkins.pipelinek.fabric:{model,run-control,observation}`
   resuelva en Maven Central, NO hay certificación R6.3 para
   NINGUNA release v1.0.0; la decisión de no backfillear es
   coherente con un release plan que no puede firmar certs
   todavía.

### Cómo se cierra D6

- Estado final: **DECIDED — NO BACKFILL** (este recibo).
- Acción en código: ninguna (no es código, es decisión).
- Próxima acción que **sí** es código: cuando el operador cierre G0 +
  G2 y publique el primer release con assets, esa release cubre la
  promesa de R6.3 desde rc.5 en adelante. Los tags `rc.1`..`rc.4`
  quedan como milestones históricos sin release page.

## Reversibilidad

Esta decisión es reversible: si el operador quiere más adelante
adjuntar assets a `v1.0.0-rc.4` (rebuild determinista + relabel + nuevo
SHA), el camino es:

1. Reconstruir el commit `v1.0.0-rc.4` en una rama efímera.
2. Crear un tag `v1.0.0-rc.4-2026-rebuild` (NO reciclar el
   identificador viejo, per ADR-011).
3. Publicar el release bajo el nuevo identificador.
4. Cerrar este recibo con un apéndice "superseded by
   v1.0.0-rc.4-2026-rebuild".

Hasta entonces, este recibo es la verdad operativa.

— Studio Bootstrap Agent, ciclo R0.6 (2026-10-17)
