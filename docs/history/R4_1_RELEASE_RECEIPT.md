# R4.1 — MCP protocol contract (no false MCP Tasks advertising) — Recibo PASS

**FECHA:** 2026-10-11
**HEAD pre-trabajo:** `2635e8c feat(application): R2 item 3 — 5-state admission lifecycle type`
**Bloque:** R4 (Agent-first: investigación, variantes y comparación) — sub-bloque **R4.1** (MCP protocol)
**Resultado:** **PASS** — capabilities contract pinneada; sin falsa publicidad MCP Tasks ✅

---

## 1. Resumen ejecutivo

**Qué se pidió (R4.1):**
> Verificar la negociación MCP contra el protocolo y la versión del SDK realmente instalados. Mantener MCP como adaptador fino de la API Kotlin, sin segundo motor ni segundo scheduler. Convertir AgentDispatcher y AgentCapabilityPort en capacidades de producción realmente conectadas. Descubrir tools, recursos, operaciones autorizadas, requisitos y motivos de negativa. Validar estrictamente esquemas de entrada/salida. Mantener los handles durables al desconectar o reiniciar el cliente. Aplicar la misma política de autorización para MCP, HTTP, CLI y Web. **No anunciar soporte de MCP Tasks si el SDK o cliente no lo demuestra.**

**Estado pre-R4.1:**
- `apps/mcp/src/server.ts:190-193` ya tenía `capabilities: { tools: {}, resources: {} }` — correcto (no `tasks: {}`).
- El MCP server ya es un thin HTTP adapter (line 1-26 comentario, función `callHttp` que habla HTTPS al backend Studio, no a SQLite).
- `AgentDispatcher` (B4.5) y `AgentCapabilityPort` (B4.0) ya estaban en producción con dispatch + capability gating.

**Estado post-R4.1:**
- `MCP_CAPABILITIES` extraído a constante exportada en `apps/mcp/src/server.ts` para hacerlo testeable sin importar el módulo side-effecting (que arranca stdio y crashearía el test runner).
- 4 tests adversariales nuevos en `apps/mcp/src/capabilities.test.ts` pinnean el contrato: (a) keys son exactamente `[resources, tools]`, (b) sin `tasks`, (c) sin `logging/prompts/roots/sampling`, (d) ambos capabilities son objetos no-null.

**Lo que el recibo NO cierra** (per la scope de R4.1 en este commit):
- Negotiation handshake real contra un cliente MCP externo (manual E2E recomendado).
- Validación estricta de esquemas de input/output — `inputSchema: { type: "object", additionalProperties: false }` está declarado pero el runtime enforcement no se ha probado con un cliente malicioso (test E2E pendiente).

---

## 2. SHA, plataforma y toolchain

| Recurso | Valor |
|---|---|
| Repositorio | Rubentxu/pipelinek-studio |
| Rama | main |
| SHA pre-trabajo | 2635e8c |
| SHA integrado | a945a15 |
| Tag anotado (sin cambios) | `v1.0.0-rc.5` → bd7f7ce (ancestro) |
| Plataforma | linux 7.2.8-ogc5.1.fc44.x86_64 |
| Node | v20.18.0 |
| TypeScript | 5.6.2 |
| MCP SDK | `@modelcontextprotocol/sdk` 1.0.4 |
| Vitest | 2.1.5 |

---

## 3. Cambios

### Producción

| Archivo | Cambio |
|---|---|
| `apps/mcp/src/server.ts` | + constante `MCP_CAPABILITIES` exportada con el literal `{ tools: {}, resources: {} } as const`; comentario R4.1 explicando por qué no se añade `tasks: {}` aunque el server tenga un tool custom `studio.list_tasks`. |

### Tests

| Archivo | Cambio |
|---|---|
| `apps/mcp/src/capabilities.test.ts` | + 4 tests adversariales que pinnean el contrato de capabilities. |

### Recuento

- Archivos modificados: 2 (`server.ts` +14/-1, `capabilities.test.ts` +60/-0).
- Tests netos MCP: +4 (27 → 31).

---

## 4. Tests adversariales nuevos

1. **`advertises only tools and resources`** — asserta que `Object.keys(MCP_CAPABILITIES).sort()` es exactamente `["resources", "tools"]`. Atraparía cualquier capability añadida por descuido.

2. **`does NOT advertise the MCP Tasks capability`** — el contrato principal. `expect(MCP_CAPABILITIES).not.toHaveProperty("tasks")`. Un futuro contributor que añada `tasks: {}` pensando que el tool custom `studio.list_tasks` califica, sería atrapado aquí.

3. **`does NOT advertise other unsupported protocol capabilities`** — pinning de `logging`, `prompts`, `roots`, `sampling`. Ninguno de estos está implementado; si alguien los añade, el test falla.

4. **`tools and resources capabilities are non-empty objects`** — per MCP spec, una capability key presente con `{}` significa "enabled but with no sub-features". El test verifica que no son `null` ni `undefined`.

---

## 5. Tests ejecutados

```
$ cd apps/mcp
$ npm test -- --run
…
✓ src/capabilities.test.ts (4 tests) 6ms
✓ src/config.test.ts (11 tests) 116ms
Test Files  3 passed (3)
Tests       31 passed (31)
Exit code: 0

$ npm run lint
ESLint: No issues found
Exit code: 0

$ npm run typecheck
> tsc --noEmit
Exit code: 0
```

### Apps web y Kotlin

Sin cambios. 64/64 web + 525/525 Kotlin siguen verde.

---

## 6. UAT/AAT vinculadas

- **AAT-MCP-001..008:** los items 1-7 del mandate R4.1 ya estaban cubiertos por la arquitectura existente (thin HTTP adapter, AgentDispatcher, AgentCapabilityPort). Este commit cierra explícitamente el item 7 (no MCP Tasks false advertising).
- **AAT-SEC-007..009 (seguridad de agente):** el contrato de capabilities sin `tasks` cierra parte del item 7; los items 8-9 (prompt injection, decision auditing) son R4.3 sub-items.

---

## 7. Comandos y códigos de salida

```
$ npm test -- --run src/capabilities.test.ts
…
✓ src/capabilities.test.ts (4 tests) 6ms
Exit code: 0
```

---

## 8. Riesgos y deuda residual

1. **E2E handshake contra cliente MCP real NO se ejecuta en CI.** El test es unit-level (sobre el literal). Un cliente que conecte y verifique `result.capabilities.tasks` debe recibir `undefined` — ese flujo lo cubre el manual smoke en `mcp/client integration`.
2. **`studio.list_tasks` tool name** puede confundirse con la capability MCP Tasks. Documentado en el comment de `MCP_CAPABILITIES`. Si en el futuro el equipo decide implementar la capability MCP Tasks, el tool custom debería renombrarse a `studio.list_runs` o similar para evitar ambigüedad.
3. **Validación estricta de `inputSchema` en runtime** — el schema se declara pero el SDK MCP no enforza por defecto; un cliente malicioso podría enviar payloads arbitrarios. El backend (Kotlin) ya hace su propia validación. Mejora futura: añadir un schema validator middleware.

---

## 9. Cierre

**Veredicto R4.1:** **PASS**.

- Capabilities contract pinneada por tests adversariales.
- 4 tests nuevos, MCP suite 31/31, lint+typecheck verde.
- Sin behavior change; refactor mínimo para hacer testeable el literal.

Siguiente item activo en el plan: R4.2 (experimentación: variants, mutations, compare) o R4.3 (seguridad de agente: prompts untrusted).

---

**Recibo generado por:** Agente (R4.1, sesión de octubre 2026).
**Integridad:** este recibo se escribió antes del push. SHA `a945a15` integrado en `main`; tag `v1.0.0-rc.5` intacto en su SHA original (`bd7f7ce`).