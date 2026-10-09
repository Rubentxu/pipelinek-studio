# AAT y Fitness — Contratos técnicos y pruebas adversariales

**Norma:** un gate debe fallar al mutar justamente la propiedad que dice verificar. No usar métricas de performance no medidas como requisito inventado. Contadores y evidencia de corrida obligatorios. El scope de las suites se amplía por hito sin sustituir las viejas.

## Arquitectura `AAT-ARCH-*`

| ID | Contrato |
|---|---|
| AAT-ARCH-001 | `studio-domain` no depende de React, MCP, Ktor, JDBC ni implementaciones de PipelineK |
| AAT-ARCH-002 | `studio-application` utiliza puertos, no `java.sql.*` ni clases Node |
| AAT-ARCH-003 | Ningún plugin StepKey concreto aparece en un `when/if` de Studio core |
| AAT-ARCH-004 | El único runner de pipeline invoca distribución SDK/engine real; tests detectan bypass con Step ejecutado fuera coordinator |
| AAT-ARCH-005 | Backend Ktor no carga bytecode del usuario ni providers externos; mutation de import/classloader capturada |
| AAT-ARCH-006 | MCP/CLI/Web no aplican reglas de admisión divergentes ni retornan outcome especial |

## Seguridad `AAT-SEC-*`

| ID | Contrato |
|---|---|
| AAT-SEC-001 | `ProcessBuilder` recibe argv sin interpolación shell; payload con `; && $(...)` permanece argumento |
| AAT-SEC-002 | `../../`, symlink y path relativo malicioso no escapan managed workspace; canario host no se altera |
| AAT-SEC-003 | Script que intenta filesystem externo/red/subprocess bajo perfil aislado no cruza límite certificado |
| AAT-SEC-004 | Perfil sin sandbox disponible rechaza *antes* de ejecutar; no fallback trust |
| AAT-SEC-005 | API HTTP/mcp HTTP no admite forged Host/Origin/CSRF ni DNS rebinding en configuración opt-in |
| AAT-SEC-006 | No ejecuta static init de plugin durante manifest inspection; manifest inválido rechazado |
| AAT-SEC-007 | Secret canaries no aparecen en API/CLI/MCP/logs/SSE/error/fichero exportable |
| AAT-SEC-008 | Permisos de Web/MCP/CLI iguales; agente no bypass de autorización ni filesystem |
| AAT-SEC-009 | XSS, HTML, ANSI y URL maliciosa en output/metadata tratados como datos no confiables |
| AAT-SEC-010 | Workspace Attached: operación root-destructiva protegida por lease real; negativa sin efecto destructivo |

## Durabilidad y SQLite `AAT-DUR-*`

| ID | Contrato |
|---|---|
| AAT-DUR-001 | Studio nunca escribe tablas `events` / `operation_journal` upstream ni migra schema ajeno |
| AAT-DUR-002 | Idempotency transaccional: N solicitudes concurrentes misma clave y digest => un launch |
| AAT-DUR-003 | Misma clave, payload distinto => 409 sin efecto; restart antes/después commit no duplica |
| AAT-DUR-004 | Kill -9 backend mientras runner vive => Estado reconciliable; sin replay automático de Steps |
| AAT-DUR-005 | SQLite WAL/read-after-commit/unique per run; lector separado de writer; errores BUSY/refusal no se falsean |
| AAT-DUR-006 | Retención/cleanup solo elimina runs terminales sin lease vivo; crash cleanup recuperable |

## Output/observación `AAT-OBS-*`

| ID | Contrato |
|---|---|
| AAT-OBS-001 | EventCursor y OutputCursor son tipos separados, no intercambiables |
| AAT-OBS-002 | `UnknownStream`, `ForeignStream`, `RecoveryNotCompleted` => refusal, jamás página 0 bytes |
| AAT-OBS-003 | Streams vacíos/solo stdout/solo stderr se sellan o revelan estado pending según verdad upstream |
| AAT-OBS-004 | Byte range paginado devuelve exactamente digest de productor; multi-byte split sin pérdida |
| AAT-OBS-005 | SSE desconectado/duplicado → relectura authoritative sin perder/duplicar bytes en cliente |
| AAT-OBS-006 | 100 MiB output con memoria acotada medible; registrar RSS de browser/backend, no fingir bound |
| AAT-OBS-007 | Dos lectores independientes consultan mismo run mientras está vivo, uno muere y otro continúa |
| AAT-OBS-008 | Pérdida de output vs salida no emitida distinguibles por refusal/gaps, no success artificial |

## Compilador/análisis `AAT-CPP-*`, `AAT-AN-*`

| ID | Contrato |
|---|---|
| AAT-CPP-001 | STATIC_ANALYZE nunca llama host `evalWithTemplate` de código usuario |
| AAT-CPP-002 | Canario de escritura en top-level no produce efecto por análisis; mutation activa test rojo |
| AAT-CPP-003 | Fallo construcción DSL no se reporta como compiled viable/success |
| AAT-CPP-004 | Classpath real participa identidad de análisis; plugin alterado invalida cache y conclusiones |
| AAT-CPP-005 | Compilation report identifica fase, sourceDigest, versión toolchain y posiciones reales |
| AAT-CPP-006 | Dependencias de compiler-adapter no filtran `kotlin.script.experimental.*` al dominio |
| AAT-AN-001 | Detekt ejecuta versión realmente pinneada con fixture `.pipeline.kts` |
| AAT-AN-002 | Regla custom positive/negative + mutation test falla al desactivar guard |
| AAT-AN-003 | Sin type resolution, marca `SYNTAX_ONLY` y no finge comprobación de Step identity |
| AAT-AN-004 | Con type resolution, classpath de plugins y versión coinciden con runner, o refusal |
| AAT-AN-005 | Findings llevan source range real; synthetic unmapped no se marca a línea ficticia |
| AAT-AN-006 | LSP spike con `.pipeline.kts`, error Kotlin y Step externo, registra limit real |
| AAT-AN-007 | Diagnostics no dependen del resultado anterior ni quedan stale tras editar source |
| AAT-AN-008 | Detekt rule externa no ejecuta dentro backend Ktor |

## UX / accesibilidad `AAT-UX-*`

| ID | Contrato |
|---|---|
| AAT-UX-001 | Navegación básica por teclado, focus visible y sin trampas de foco en Monaco/paneles |
| AAT-UX-002 | Estados `FAILED/UNKNOWN/REFUSED/RUNNING` se diferencian por texto/icono además de color |
| AAT-UX-003 | Mensajes de error y cambios de run anunciables mediante ARIA, sin bloqueos ni spam |
| AAT-UX-004 | Sin CDN: bundle de producción funciona offline; assets servidos localmente |
| AAT-UX-005 | 100 MiB de output no se acumula como nodos DOM ni bloquea interacción; virtualización medida |
| AAT-UX-006 | Snapshot exacto al seleccionar historial; el usuario nunca confunde source borrador con run sellada |

## Plugins/ABI `AAT-PLG-*`, `AAT-ABI-*`

| ID | Contrato |
|---|---|
| AAT-PLG-001 | Manifest real/canonical digest, origen y versión expuestos con provenance |
| AAT-PLG-002 | Manifest inválido no inicializa clases y no carga provider en backend |
| AAT-PLG-003 | Registry congelado y sin colisiones; colisión rechazada determinísticamente |
| AAT-PLG-004 | StepCodec entrada/salida invalidas generan error tipado, no default success |
| AAT-PLG-005 | SDK plugin externo compila fuera de repo PipelineK contra artifact publicado |
| AAT-PLG-006 | UI fallback para `schema() == "{}"`; no afirmar formulario con garantías inexistentes |
| AAT-PLG-007 | 0 imports `pipelinek-policy` / `pipelinek-assurance`; 0 Steps hardcoded |
| AAT-ABI-001 | Plugin apiRange/runtime version mismatch se rechaza antes de handler |
| AAT-ABI-002 | Mismos Step contracts para plugin oficial y externo; test path canónico |
| AAT-ABI-003 | Identity y plugin digest inmutables por run, actualización concurrente no altera run actual |
| AAT-ABI-004 | Kotlin/detekt/compiler matrix de versiones publicada con resultados |
| AAT-ABI-005 | Cliente generado OpenAPI viejo ignora campos opcionales sin false PASS |
| AAT-ABI-006 | Cambio incompatible requiere API v2 y fixtures de migración |

## HTTP/MCP/Release `AAT-API-*`, `AAT-MCP-*`, `AAT-REL-*`

| ID | Contrato |
|---|---|
| AAT-API-001 | OpenAPI schema y server JSON validan; no rutas efectivas indocumentadas |
| AAT-API-002 | Cliente TS generado no duplica enums/outcomes a mano |
| AAT-API-003 | Excepciones traducidas a `code/phase/retryable/actions` sin stack/secret |
| AAT-API-004 | Same request HTTP/MCP → misma decisión/errors/security, aparte del transporte |
| AAT-API-005 | Body size, cursor limit, query limit y output streaming acotados |
| AAT-API-006 | Actions/links offered existen y reflejan autorización/estado real |
| AAT-API-007 | Segundo cliente headless consume API sin importar Web |
| AAT-API-008 | Idempotency/ETag/concurrency semantics estables entre releases |
| AAT-MCP-001 | MCP stdio v2 2026-07-28 negotiates; clients antiguos compatibles o rechazados claramente |
| AAT-MCP-002 | stdout de stdio solo JSON-RPC; logs por stderr redactado |
| AAT-MCP-003 | Lista tools coincide con capacidades implementadas/ autorizadas; no inert tools |
| AAT-MCP-004 | Tools input/output schema validan; erreurs mapeados sin payload leak |
| AAT-MCP-005 | Desconectar/reiniciar cliente MCP no mata run ni la duplica |
| AAT-MCP-006 | Dos clientes (Web y MCP) ven mismo run/digests/outcomes |
| AAT-MCP-007 | Texto prompt-injection en tool result, plugin description, log no concede acciones |
| AAT-MCP-008 | Tasks extension opcional no crea otra state machine; sin soporte de cliente, polling handles funciona |
| AAT-REL-001 | Merge de block SHA visible/ancestral en `origin/main` antes de tag |
| AAT-REL-002 | Tag remoto anotado inmutable sobre commit exacto certificado |
| AAT-REL-003 | Release GitHub apunta mismo tag/SHA y contiene ZIP/binarios reales |
| AAT-REL-004 | sha256 de artefactos, manifest y SBOM reales correspondencia exacta |
| AAT-REL-005 | Full suite de release forzada sin caché, 0 failures; skipped justificados |
| AAT-REL-006 | Installed tests desde distribución en clon limpio/ sin clases de build |
| AAT-REL-007 | Ningún GH Actions sustituye CI local obligatorio (puede ser accesorio, no fuente) |
| AAT-REL-008 | Dos copias de release producen checksum igual de fuentes reproducibles o diferencia explicada |
| AAT-REL-009 | Upgrade/downgrade segura de studio.db; migración no toca run.db |
| AAT-REL-010 | External harness verdict antes de stable, si lo exige protocolo aplicable |

## Perf/soak (presupuestos basados en medidas)

Medir first-run cold, warm compile, SDK/plugin discovery, DB startup, p50/p95 API, 100MiB output read, 2 readers + 1 writer, plugin jar > normal, browser heap/RSS, memory de process child y DOM. Gate de regresión definido *después* de recoger baseline en plataforma estable y justificar umbral/variancia; nunca adoptar número arbitrario como producto ya certificado. Archivar resultados completos con platform fingerprints.

## Modelo de ejecución AAT

Tests locales unit/integration durante dev; full `./gradlew check --rerun-tasks --console=plain` (o equivalente medido) y `pnpm test/build/lint/typecheck` en release; instalar ZIP en ruta limpia, certificar contra artefacto real. Fallo/excepción/timeout/rechazo como ADTs, no absorber excepción en `runCatching` y marcar PASS. Tests negativos comprueban que el handler nunca se invocó, usando contador/canario real, no solo mensaje.
