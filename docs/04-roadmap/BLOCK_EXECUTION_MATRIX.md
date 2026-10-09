# Matriz ejecutiva — de hitos a evidencias

| Bloque | Vertical del usuario | Backend | Frontend/agent | Release | Gates clave | Resultado a demostrar |
|---|---|---|---|---|---|---|
| S0 | Pipeline `echo` real desde API/MCP | metadata SQLite, ProcessBuilder, durable run | CLI JSON/MCP stdio | `v0.1.0-alpha.1` | UAT-001/002/013/014; SEC/ARCH/DUR/MCP | Proceso real, output no inventado, una run por clave |
| S1 | Scratchpad local persistente | Event/Output read ports, cancel, SSE | Monaco + historial y panels | `v0.2.0-alpha.1` | UAT-003..019; OBS/SEC/DUR | Consola y eventos sin confundir cursor, reconnect |
| S2 | Plugin externo comprobable | manifest/admission/registry/test harness | Catálogo + Step Inspector + MCP | `v0.3.0-alpha.1` | UAT-020..026; PLG/ABI | Plugin externo sin core changes, negativa de capability |
| S3 | Diagnóstico e IR trazables | Detekt, compiler adapter, IR projection | Monaco markers + IR browser | `v0.4.0-beta.1` | UAT-027..035; CPP/AN | 0 efectos de análisis estático o UNSUPPORTED |
| S4 | Agente revisa y propone | snapshot variant, diff, mutation scenarios | Tools MCP + Web diff | `v0.5.0-beta.1` | UAT-036..038,041..046; MCP/SEC/API | Cambios propuestos sin tocar origen |
| S5 | Producto instalable certificado | migrations, performance, lifecycle | client VS Code seam + doc UX | `v1.0.0-rc.1` | AAT release íntegro | Instalación sin árbol fuente, certificados SHA |

## Cadencia interna del agente

**R0 — Baseline/grounding (cada bloque):** fetch y `git status`; Git upstream/main SHA y artefactos publicados; registrar mismatch de doc vs código; abrir decision en SDDK; comprobar qué deja de ser cierto respecto del bloque anterior.

**R1 — Diseño quirúrgico:** una vertical determinista con resultado observable + sus tests negativos. Primero probar el seam más riesgoso; no implementar 10 módulos a la vez.

**R2 — Desarrollo local:** cambios pequeños Conventional Commits, tests focales, mutación de gates; comprobar no inventar éxito/mocks.

**R3 — Integración:** full suite sin caché engañosa, installed UAT/AAT, matriz OS/toolchain aplicable, baseline rendimiento.

**R4 — Publicación:** merge/fast-forward autorizado a main, publicar tag inmutable apuntando al commit integrado, subir release artefactos/SHA/SBOM/manifest y smoke desde instalación remota.

**R5 — Cierre:** evidencia en `docs/history/SX_RELEASE_RECEIPT.md` (después de implementar), roadmap actualizado al estado observado, árbol limpio y reporte de deuda residual con dueño/precedencia. Sin gate verde, estado no es DONE.

## Separación de trabajo entre repos

`pipelinek-studio`: UI/API/MCP, adapters, experiment metadata, distributed tests, releases. `pipeline-kotlin`: exclusivamente puertos genéricos/requisitos de SDK demostrados por fixture externo y pruebas propios, preferir release publicado antes de adoptar. `pipelinek-policy` y `pipelinek-assurance`: no modificar ni consumir como dependencias en S0..S5. Cualquier PR upstream debe ser independiente, certificado y adoptado posteriormente por Studio con SHA de artefacto.
