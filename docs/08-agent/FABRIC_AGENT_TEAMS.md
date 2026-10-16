# Agent teams — paralelización por propiedad de seam

Los agentes son una optimización, no una nueva autoridad. Trabajos paralelos solo con archivos disjuntos y contratos escritos. El integrador inspecciona HEAD de `origin/main` antes de fusionar. Ningún agente declara que la rama ajena está verde sin SHA y prueba real.

## Equipo F0 (Fabric; ruta crítica)

| Agente | Responsabilidad | Archivos/puertos propietarios | Puede ejecutarse en paralelo | Handoff |
|---|---|---|---|---|
| A — ADMISSION | `RunRegistry`/`LocalRunControl` decisión de admisión y claim durable; recovery y cancel | `control-plane/runs`, `control-plane/application/execution` | Sí con B/C, no con otro agente editando el mismo RunControl | `StartRunId`→un dispatch, efecto real, conflictos |
| B — PK WORKER | params/creds y semántica PK observada, compatibilidad SDK | `worker/pipelinek-runtime-adapter`, pruebas de `WorkerWork` | Sí con A/C, contrato `WorkerWork` acordado primero | Valores efectivamente entregados/limitaciones |
| C — DISTRIBUCIÓN | Fabric client libs, Maven/POM/CLI smoke, identidad release | Gradle publication/distribution, consumer externo | Sí con A/B hasta gate | coordenadas, versión, dependencia remota, digest |
| D — INTEGRADOR | Revision ADR, ABI, tests cross-repo, merge y release | exclusiva authority de integración/push/tag | Solo después de handoff | recibo de hito + SHA remoto |

## Equipo F1 (Fabric + Studio)

| Agente | Propiedad | Regla de secuencia |
|---|---|---|
| A — FABRIC-CAS | CAS/read/seal/digest, catálogo dinámico y permisos de scripts | Contrato de fuente y digest acordado antes de tocar runtime |
| B — FABRIC-NORTH | RPC/codec/client/ACL de PreparePipeline (archivos disjuntos de A) | No anunciar disponible hasta servidor+client publicados |
| C — STUDIO-DOMAIN | Gateway interface, ADTs, idempotencia y migración studio.db sin `io.grpc` | Puede empezar con fake solamente para leyes de dominio; no cerrar UAT |
| D — STUDIO-CONSUMER | adapter real FabricClient y CLI/HTTP/MCP vertical instalado | Tras publicar Fabric actualizado |
| E — INTEGRADOR | Agrega releases secuenciales, compat y smoke desde binarios | Única autorización de push/release por producto |

## Equipo F2/F3

- WEB/VS CODE: React/Monaco/SSE/TS UI, no toca Fabric gRPC ni interpreta SQLite PK.
- STUDIO HTTP/MCP: servicios/rutas, JSON typed, no toca UI interna.
- FABRIC OBS: hechos incrementales y proyecciones; solo publica versión upstream con contrato probado.
- FABRIC RUNTIME: capability metadata y `InteractionControl` real cuando el runtime lo soporte.
- INTEGRADOR: E2E único, release, TS contract client si aplica.

## Equipo F4

- HARNESS-AUDIT: inventario coste/duplicidades/falsos verdes, propuesta de borrados por garantía; **no elimina nada crítico unilateralmente**.
- DISTRIBUTION: binarios, SDK Maven, POM, inventario assets remotos y smoke clean-room.
- ARCH-CLEANUP: retirar runners/readers locales sin consumidores, reducir módulos y imports prohibidos.
- INTEGRADOR: pruebas release y eliminación explícita de temporales tras registrar evidencia.

## Intercambio mínimo entre agentes

```
ID: F1/PreparePipeline
Repo/SHA: ...
Contrato: URL a proto o diff real + nuevos permisos NorthboundOperation
Garantía demostrada: ...
Prueba real: comando, oráculo, PASS|FAIL|BLOCKED, artifact digest
API consumible: Maven coordinate/version, SHA256, compatible PK
Pendientes: ...
```

**No** compartir un archivo de estado global en Git, ni dos agentes escribiendo el mismo Gradle/proto/ADR, ni aplicar todas las branches sin revisar diff. Rebase selectivo, commits atómicos y revisión de eventuales conflictos semánticos.
