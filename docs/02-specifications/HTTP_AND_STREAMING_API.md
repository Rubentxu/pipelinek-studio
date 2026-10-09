# Contrato HTTP y streaming v1 (propuesto)

**Autoridad:** aplicación Kotlin; cliente TS generado desde OpenAPI. **Estado:** contrato de partida, debe formalizarse en `contracts/openapi.yaml`, validarse con fixtures y ampliarse solo por demanda real.

## Transporte y límites

- Servidor local inicial `http://127.0.0.1:<puerto-configurado>` (puerto asignado dinámicamente o declarado; ejemplos usan 8765). `localhost` no es autorización; validar Origin/Host, CSRF y credencial de sesión para mutaciones.
- Todos los endpoints `/api/v1`; media `application/json; charset=utf-8`; protobuf no necesario en MVP.
- `Content-Length` bounded: primer tope de propuesta 1 MiB para texto por snapshot, 4 MiB para request JSON; parametrizable y medible antes de certificar. JAR/plugin bytes se importan desde rutas autorizadas, **no** request Base64 arbitrariamente grande.
- Los identificadores son opacos; path, plugin class y SQL nunca se derivan directamente de un ID sin resolución interna.
- `Idempotency-Key` obligatoria para `POST /runs`, `POST /scenarios/{id}/execute` y operaciones reintentables con efectos; la misma clave + distinto payload = `409`.
- `ETag` y `If-Match` para editar borradores o metadatos mutables; snapshots/runs son inmutables.

## Recursos iniciales

| Método y URI | Semántica | Versión de entrega |
|---|---|---|
| `GET /api/v1` | API root autodescubrible con `links` y `actions` válidas | S0 |
| `GET /api/v1/health` | readiness/local version/features; sin secretos | S0 |
| `POST /api/v1/experiments` | crear contenedor local | S0 |
| `GET /api/v1/experiments/{id}` | inspeccionar experimento | S0 |
| `POST /api/v1/experiments/{id}/snapshots` | sellar source snapshot inmutable | S0 |
| `GET /api/v1/snapshots/{id}` | bytes/texto y digest, sujeto a ACL | S0 |
| `POST /api/v1/runs` | admitir + programar una ejecución | S0 |
| `GET /api/v1/runs/{id}` | estado tipado y referencias | S0 |
| `POST /api/v1/runs/{id}/cancel` | petición idempotente de cancelación, jamás éxito terminal adelantado | S1 |
| `GET /api/v1/runs/{id}/events` | leer eventos por cursor de EVENT PLANE | S1 |
| `GET /api/v1/runs/{id}/operations/{opId}/output` | output por cursor independiente | S1 |
| `GET /api/v1/runs/{id}/updates` | SSE de avisos y nuevos handles, NO autoridad de bytes | S1 |
| `GET /api/v1/steps` | registro admitido filtrable y paginado | S2 |
| `GET /api/v1/steps/{key}` | descriptor, esquema, compatibilidad, provenance | S2 |
| `POST /api/v1/plugins/inspect` | manifest estático e identidad de artefacto; no cargar clases | S2 |
| `POST /api/v1/scenarios/{id}/execute` | instancia de run, usa mismo launcher | S2 |
| `POST /api/v1/analysis` | Detekt/diagnósticos estáticos o rechazo tipado | S3 |
| `GET /api/v1/analyses/{id}` | findings, flags `typeResolution` y origen | S3 |
| `POST /api/v1/experiments/{id}/compare` | diff de snapshots/diagnósticos/runs, no inventar replay | S4 |

## Envelope común de respuesta

```json
{
  "schema": "studio.pipelinek.dev/api/v1",
  "requestId": "req_abc",
  "data": { "id": "run_123", "state": "QUEUED" },
  "links": [ { "rel": "run", "href": "/api/v1/runs/run_123", "method": "GET" } ],
  "actions": [ { "rel": "cancel", "href": "/api/v1/runs/run_123/cancel", "method": "POST" } ]
}
```

Las URLs y acciones ofrecidas derivan del estado real y permisos: no ofrecer `cancel` para una run terminal, ni `analyze` como estático si evalúa código. No generar URL absoluta a partir de header Host externo sin control.

## Errores tipados

```json
{
  "schema": "studio.pipelinek.dev/error/v1",
  "requestId": "req_abc",
  "code": "CAPABILITY_REFUSED",
  "phase": "ADMISSION",
  "message": "El perfil de ejecución no autoriza el acceso solicitado",
  "details": { "capability": "filesystem.write" },
  "retryable": false,
  "actions": [ { "rel": "inspect-profile", "href": "/api/v1/profiles/local-managed", "method": "GET" } ]
}
```

Mínimos `INVALID_ARGUMENT`, `UNKNOWN_RESOURCE`, `FORBIDDEN`, `IDEMPOTENCY_CONFLICT`, `COMPILATION_FAILED`, `DSL_CONSTRUCTION_FAILED`, `PLUGIN_REFUSED`, `CAPABILITY_REFUSED`, `EXECUTION_FAILED`, `RECOVERY_REQUIRED`, `OUTPUT_CURSOR_REFUSED`, `OUTPUT_NOT_YET_AVAILABLE`, `UNSUPPORTED`, `INTERNAL_ERROR`. `INTERNAL_ERROR` nunca incluye stack trace, secreto, filesystem host ni classpath completo.

## Contrato de inicio de run

```http
POST /api/v1/runs
Idempotency-Key: 6b46…
Content-Type: application/json

{"snapshotId":"snap_01","scenarioId":"scenario_default","executionProfileId":"managed-local-v1"}
```

`202 Accepted` + `runId` + `operationId` + `Location` y acciones válidas. `409` para snapshot/perfil incompatible, `403` para permiso denegado, `422` para contrato inválido; usar códigos HTTP coherentes, no semánticas distintas MCP/Web.

## Observación y SSE

- `GET /runs/{id}/updates` envía **avisos** `runChanged`, `operationChanged`, `outputAvailable` con IDs/cursor de cambio de Studio, no transmite todo el log.
- El cliente recupera `events`/`output` mediante GET con cursor de cada plano y longitud máxima. No afirmar orden total combinado.
- `Last-Event-ID` del SSE no sustituye `OutputCursor` ni `EventCursor`; tras desconexión el cliente reconsulta el snapshot de estado y retoma paginación.
- Los payloads preservan `stream`, `contentEncoding`, `nextCursor`, `terminal`, `refusal` y `truncated`; si el output es binario ofrecer bytes base64 paginados o `application/octet-stream`, nunca deformar bytes UTF-8.
- Si el upstream no expone un read-port utilizable para una variante de run, responder `UNSUPPORTED` con causa hasta que exista adapter certificado.

## Derivación OpenAPI y pruebas

OpenAPI en `contracts/openapi.yaml` cubre el núcleo S0/S1. En cada bloque ampliar **antes** de implementar adaptadores, generar cliente TS y verificar compatibilidad (`breaking-check`); el servidor se prueba contra ejemplos JSON golden y dos clientes independientes. No editar API TS manualmente para ocultar discrepancias.
