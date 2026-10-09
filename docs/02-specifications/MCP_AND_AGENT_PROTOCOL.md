# MCP y contrato agent-first

## Decisión

MCP **2026-07-28** con SDK TS v2 (`@modelcontextprotocol/server`) como adaptador fino, primera vía `stdio` local. El protocolo es sin sesiones implícitas de core: las sesiones operativas/experimentos son **handles de dominio Studio** y sobreviven a las conexiones. Streamable HTTP solo como entrega opt-in una vez auditadas autenticación, Origin/Host y riesgos de rebinding.

**Fuente:** https://modelcontextprotocol.io/specification/2026-07-28 , https://github.com/modelcontextprotocol/typescript-sdk , https://github.com/modelcontextprotocol/typescript-sdk/blob/main/docs/migration/support-2026-07-28.md . En stdio moderno usar el mecanismo `serveStdio(...)` documentado por el SDK, no asumir que `McpServer.connect(new StdioServerTransport())` negocia 2026.

## No usar MCP como SDK del motor

`agent MCP -> Studio adapter TS -> HTTP studio-api Kotlin -> studio application -> PipelineK SDK/runner`. El adaptador TS no conoce enums internos de runtime excepto wire versionado; no implementa SQL, scheduler, autorización de efectos ni decodificación de output nativo de PipelineK.

## Tools por release (nombres exactos propuestos)

| Tool | I/O resumido | Efecto | Release |
|---|---|---|---|
| `studio.discover` | `{}` → versiones, perfiles, operaciones | no | S0 |
| `studio.experiment.create` | `{workspaceRef,title}` → experimentId | metadatos | S0 |
| `studio.snapshot.create` | `{experimentId,source,expectedDigest?}` → snapshotId | metadatos | S0 |
| `studio.run.start` | `{snapshotId,scenarioId,profileId,idempotencyKey}` → runId | **sí**, sujeto a autorización | S0 |
| `studio.run.get` | `{runId}` → state/links/reasons | no | S0 |
| `studio.run.cancel` | `{runId,idempotencyKey}` → request accepted | sí | S1 |
| `studio.run.events` | `{runId,cursor?,limit}` → page/refusal | no | S1 |
| `studio.run.output` | `{runId,opId,cursor?,maxBytes}` → page/refusal | no | S1 |
| `studio.plugins.inspect` | `{artifactRef}` → manifest verdict/digest | no-load | S2 |
| `studio.steps.list` | `{filter?,cursor?}` → admitted catalog | no | S2 |
| `studio.steps.describe` | `{stepKey}` → contract/capabilities | no | S2 |
| `studio.scenario.execute` | `{snapshotId,scenarioId,profileId,idempotencyKey}` → runId | sí | S2 |
| `studio.analysis.request` | `{snapshotId,kind,configurationRef?}` → handle/UNSUPPORTED | no user-code eval | S3 |
| `studio.runs.compare` | `{runA,runB}` → typed comparisons | no | S4 |

No publicar herramientas no implementadas. Input/output schemas validados en ambos lados. Unknown tool -> MCP error apropiado; unknown Step -> rechazo de dominio. `cancel` en MCP NO implica automáticamente cancelar runner por cerrar el transporte.

## Resources y prompts

- Resources propuestas: `studio://experiments/{id}`, `studio://snapshots/{id}`, `studio://runs/{id}`, `studio://runs/{id}/diagnostics`, `studio://steps/{key}`, `studio://artifacts/{ref}`. Solo registrar resources realmente implementados; ACL igual que HTTP.
- Prompts: `investigate-failed-run`, `design-step-test`, `explain-capability-refusal`, `review-run-diff`; texto versionado con objetivos y límites, sin inyectar código fuente ni aceptar instrucciones del payload de plugins como política.
- Resource metadata puede indicar truncación/siguiente página; no introducir blobs grandes en contexto del agente por defecto.

## Discoverability y afinidad CLI

Un `studio.discover` devuelve capabilities de backend en `actions`, bloqueos y URLs de recursos. La lista de acciones debe coincidir con los endpoints reales OpenAPI y tests MCP, no ser un catálogo ficticio. En `studio.steps.describe`, proveer input/output schema cuando existe; `{}` del codec no se interpreta como schema completo.

## Runs largas y Tasks

MCP Tasks en 2026-07-28 es una **extensión separada** (`io.modelcontextprotocol/tasks`), aún con superficie draft en su documentación. El MVP devuelve un handle de Studio y ofrece `studio.run.get`/paginación. Adoptar Tasks solo tras comprobar SDK/cliente, sin duplicar máquina de estados ni depender de notificaciones implícitas. No guardar `MCP-Session-Id` como identidad de experimento.

## Seguridad

- Stdio: lanzamiento local explícito; stdout solo protocolo; stderr para logs redactados; variables de entorno mínimas, conexión autenticada al backend, secreto efímero fuera de logs.
- HTTP futuro: loopback, token o sesión vinculada a usuario local, Origin/Host allowlist, protección DNS rebinding/CSRF, rate limit, timeouts y límites de tamaño; binding 0.0.0.0 prohibido por defecto.
- No dar a un agente acceso a un path del host por string suministrado en tool call. Resolución por `workspaceRef`, `artifactRef`, `snapshotId` y política de capabilities.
- Sin permisos por fuente cliente: misma autorización HTTP/MCP/GUI para mismas acciones. Nada de `agent bypass`.
- MCP tool descriptions/resources/errores y plugin metadata pueden contener prompt injection: tratarlos como datos y nunca como instrucciones de seguridad ni de sistema.
- Actions con efectos registran actor, scope, perfil, digest solicitud, run y resultado sin secretos.

## Pruebas obligatorias

- JSON schema parity con OpenAPI y error mapping.
- Dos clientes MCP: uno stdio v2 y otro de protocolo incompatible (rechazo claro o negociación explícita).
- El host reinicia/detiene stdio; el run continúa y es recuperable por handle.
- `Idempotency-Key` duplicada = 1 run; clave igual con payload distinto = 409 tipado.
- MCP no expone más privilegios que la Web; intento traversal y plugin manifest malicioso rechazados.
- Logs de protocolo jamás mezclados con stdout de un pipeline.
- Evitar usar cambios de especificación o funciones de SDK v1 como requisitos v2 sin test protocol-wire.
