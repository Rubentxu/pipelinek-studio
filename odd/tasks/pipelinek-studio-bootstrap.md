# PipelineK Studio — Plan S0..S5 + bootstrap S0

**Feature:** `pipelinek-studio-bootstrap`
**Estado:** S0 bootstrap scaffolding; S1..S5 planificados pero no iniciados.
**Scope decisión:** Plan completo S0..S5 + bootstrap S0 (sin release remota).
**Upstream baseline 2026-10-09:** `Rubentxu/pipeline-kotlin main` activo; release `v0.48.0-rc1 "SDK S6 consumible desde fuera"` prerelease; `Rubentxu/pipelinek-policy`, `Rubentxu/pipelinek-assurance` existen; **`Rubentxu/pipelinek-studio` no existe**.
**Toolchain:** Java 25 (Temurin), Node (asdf), gh CLI autenticado, sin docker, sin kotlin standalone (Gradle lo baja). Sin red confirmada para fetch masivo; release artefactos consumibles vía Gradle coordinate + SHA pin.

## Estrategia SDDK para S0..S5

### Decisión router

Ruta **direct inline + delegated direct writer** para bootstrap S0. SDD sólo cuando el usuario lo pida explícitamente (no es el caso). El estado vive en este documento + su mirror Engram topic `odd/pipelinek-studio-bootstrap/tasks`.

### Principios (ratificados de docs/00..02, AGENT_HANDOFF, RELEASE_PROTOCOL)

1. **Un motor real**: PipelineK V2 es autoridad de ejecución. Studio no compila, evalúa ni decide outcomes.
2. **No inventar releases**: DONE sólo con GitHub release, SHA en tag, SBOM, manifest, smoke desde instalación limpia y UAT/AAT pasados.
3. **3 planos, no mezclarlos**: Control (Studio coordina, motor ejecuta efectos), Event Plane (motor), Output Plane (motor — cursor independiente, no derivar de sequence).
4. **2 DB separadas**: `studio.db` (solo Studio) vs `run.db` (PipelineK durable). Studio no migra DML de motor.
5. **Adapters sobre SDK público**, no reflexión sobre internals. Si falta seam, abrir entrega genérica upstream.
6. **UNKNOWN ≠ PASS**: refusal, recovery, no-medido se reportan como tales.

### Gaps upstream identificados (R0 carried-over)

| ID | Descripción | Impacto | Estado hoy |
|---|---|---|---|
| U0 | Artefactos S6/main publicados y compatibles JVM/SDK | Fijar coordinate + SHA pin | v0.48.0-rc1 prerelease disponible. **Pin en S0** |
| U1 | Compile-only sin eval real | Bloquea `STATIC_ANALYZE` safe | No demostrado upstream. **S3 → UNSUPPORTED honesto** |
| U2 | Protocolo cancelación/read-side/admisión para ejecutor externo | Necesario para runner Studio | Caracterizar contrato en S0; generar entrega genérica si gap |
| U3 | Sandboxing linux trust-level | Necesario para perfil no confiable | No certificado. **S0 sólo perfil `trusted` por defecto** |
| U4 | Output Plane: source channels/sellado/recovery | Cursor seguro | Caracterizar v0.48.0-rc1 en S0; `BLOCKED-BY-OBS` si faltare |
| U5 | Plugin SDK schema útil + LSP `.pipeline.kts` | S2/S3 | Schema vacío posible. **Fallback honesto `NO_SCHEMA`** |

### Decisión de packaging

- **BUILD_FIRST**: Gradle multi-módulo Kotlin/JVM (consistente con PipelineK upstream) + módulo Ktor + módulos CLI/MCP/Node separados vía pnpm workspaces.
- **Por qué no Tauri/Electron en S0**: el package lo descarta ("fuera de alcance") y S5 sólo lo menciona como evolutivo EVO-E. React+Vite+pnpm en `apps/web`.
- **Distribución JVM**: shaded jar (Gradle `application` plugin + `shadow`) con `bin/studio` script. Empaquetado `studio-dist-{version}.{zip,tar.gz}` con manifest + checksums en R3.

### Testing stack

- **Kotlin**: JUnit5 + kotlin-test + AssertJ + Kotest property (mutación).
- **DB**: SQLite JDBC xerial + `sqlite-jdbc` + Flyway-core para migraciones Studio (sin tocar DB upstream).
- **HTTP**: Ktor 3.x server-test + openapi-validator.
- **Frontend**: Vitest + Playwright (S1+).
- **E2E**: testkit con cliente doble (HTTP + MCP) sobre instalación local.

### Política de commits

Conventional Commits + work-unit commits en `feat/sX-...` con base/fetch/rebase diario. **No** forzar push, **no** reescribir historia ajena, **no** borrar tags. Cada work-unit commit cierra una tarea del feature document y registra evidencia (test counts, exit codes).

---

## S0 — Foundation & real headless vertical (PRIORIDAD SESIÓN)

**Valor esperado:** un agente o CLI puede crear un experimento, ejecutar `.pipeline.kts` real, obtener run durable y leer resultado; demuestra independencia de UI.

### Entregables (per docs/04 + ROADMAP S0)

| E0.1 | Monorepo Gradle + pnpm + linter + detekt + tests infra + cliente OpenAPI placeholder |
| E0.2 | Módulo `studio-domain`: `Experiment`, `SourceSnapshot`, `Scenario`, `RunBinding`, `Operation`, `IdempotencyKey`, `RunState` typed, sin frameworks |
| E0.3 | Módulo `studio-infra` SQLite: schema v1 con `studio_schema_version`, migraciones idempotentes con backup/restore |
| E0.4 | Módulo `studio-application`: puertos `PipelineRunnerPort`, `RunReadPort`, `OutputReadPort`, `CompilerDiagnosticsPort` (interface, impl en E0.7) |
| E0.5 | Runner supervisor `ProcessBuilder` con `--db` durable per-run, control-root administrado, TTL configurable, **perfil `trusted` ONLY en S0** |
| E0.6 | API Ktor: `POST /experiments`, `POST /experiments/{id}/snapshots`, `POST /runs` (Idempotency-Key), `GET /runs/{id}`, `GET /runs/{id}/events`, `GET /runs/{id}/output`, `GET /health`, `GET /api/version` |
| E0.7 | Adapter PipelineK real: dependencia Maven coord pinned SHA-256 de `v0.48.0-rc1`, instanciación +JUnit integ test que arranca proceso real + script `echo.pipeline.kts` |
| E0.8 | CLI: subcommands `studio create-experiment`, `studio snapshot`, `studio run`, `studio inspect`, `studio doctor`, `--json` |
| E0.9 | MCP stdio v2 thin adapter: tools `studio.create_experiment`, `studio.run`, `studio.inspect`; recursos `studio://experiments`; **mismo backend** que HTTP |
| E0.10 | Spike seguridad: contención runner Linux caracterizada con canarios filesystem/red/subprocess; documentar qué perfiles admitidos |
| E0.11 | Lectura output real por `OutputReadPort`; **NO** copiar output a SQLite Studio |

### DoD S0 (verbatim ROADMAP)

`v0.1.0-alpha.1` remoto sobre main integrado; fuente + ZIP instalable frontend opcional (headless), digest, SBOM si build soportado, manifest, test reports, comandos y SHA. Bloque no cerrado si no existe run real. **DONE requiere release GH; en esta sesión llegamos a "BUILDABLE+TESTABLE" pero no "DONE".**

### Tareas esta sesión (bootstrap)

- [ ] **T-S0-1**: Crear feature document + Engram mirror ✅ (este archivo)
- [ ] **T-S0-2**: Git init + gitignore + estructura raíz monorepo
- [ ] **T-S0-3**: `settings.gradle.kts`, `build.gradle.kts` raíz, `gradle.properties`, `libs.versions.toml` con Pin v0.48.0-rc1
- [ ] **T-S0-4**: `gradle/wrapper` (Gradle 8.10+) + `gradlew` ejecutable, validar `./gradlew tasks`
- [ ] **T-S0-5**: Módulo `studio-domain`: tipos puros, sin dependencias de frameworks, paquete `dev.pipelinek.studio.domain.*`
- [ ] **T-S0-6**: Módulo `studio-application`: puertos, use cases, validadores de transición de estado
- [ ] **T-S0-7**: Módulo `studio-infra`: `StudioDatabase` (xerial + Flyway) con migración V1 + tests repos
- [ ] **T-S0-8**: Módulo `studio-infra`: `PipelineKRunnerAdapter` con ProcessBuilder + integración a v0.48.0-rc1
- [ ] **T-S0-9**: Módulo `studio-http`: Ktor + endpoints REST + OpenAPI placeholder + SSE minimal skeleton
- [ ] **T-S0-10**: Módulo `studio-cli`: argumentos, salida `--json`, comandos T-S0
- [ ] **T-S0-11**: `apps/mcp/`: package.json + TS skeleton, tools thin que llaman HTTP local
- [ ] **T-S0-12**: `apps/web/`: Vite + React + Monaco placeholder (sin lógica S0, sólo shell)
- [ ] **T-S0-13**: Spike seguridad (E0.10): reportar contención, documentar `trusted` ONLY
- [ ] **T-S0-14**: Tests focales: unit domain + integration happy-path (un `echo.pipeline.kts` real) + mutex SQLite WAL
- [ ] **T-S0-15**: README bootstrap + scripts `bin/dev` para arranqar backend local
- [ ] **T-S0-16**: **NO** crear GitHub release (alcance sesión = plan + bootstrap). Reportar como `BUILDABLE+TESTABLE-NOT-DONE`

### Riesgos activos S0

- **R-S0-A**: v0.48.0-rc1 prerelease puede cambiar API entre sesiones → pin SHA artifact
- **R-S0-B**: Sin docker, containerización se difiere a S5
- **R-S0-C**: Sin `kotlin` standalone → Gradle lo baja en init (primer build lento)
- **R-S0-D**: Network restrictions no conocidas → fallback: tests con stubs de PipelineK + simulated output si el binario no descargable; reportar honestamente

### Decomposición S1..S5 (referencia, no ejecución esta sesión)

#### S1 — Groovy-like Interactive Console

**Valor:** editar/ejecutar/consultar pipeline, ver logs/eventos reales, cancelar/reabrir con evidencia.

| ID | Entregable | Dependencia U |
|---|---|---|
| E1.1 | Webpack/Vite dev server + React Router + Monaco DSL highlighting | U5 |
| E1.2 | `OutputReadPort` pagination + cursor independiente por stream | U4 |
| E1.3 | SSE notifications + UTF-8 incremental + output virtualization | U4 |
| E1.4 | Snapshot immutability + restart of server/browser (`RECOVERY_REQUIRED`) | U0 |
| E1.5 | Primer prototype source location con diagnóstico existente | — |
| E1.6 | Release `v0.2.0-alpha.1` con interfaz real, sin CDN, capturada | — |

**UAT S1:** UAT-003..012, 016..019 · **AAT S1:** AAT-OBS-001..008, AAT-DUR-004..006, AAT-SEC-007..009, AAT-UX-001..006, AAT-MCP-005..006

**Bloqueos upstream S1:** U4 (output sellado), U5 (LSP/higlighting).

#### S2 — Step & Plugin Lab

| ID | Entregable | Dependencia U |
|---|---|---|
| E2.1 | Static manifest inspector (no classloading) + trust/admission classification | U0,U5 |
| E2.2 | Snapshot StepRegistry, `GET /steps`, detalle StepContract + schemas opcionales | U5 |
| E2.3 | Test runner Scenario para Step por camino canónico (si no hay API inline, generar `.pipeline.kts` fixture) | U0 |
| E2.4 | CLI/MCP `studio.steps.list`, `studio.steps.describe`, `studio.plugins.inspect`, `studio.scenario.execute` | — |
| E2.5 | ResultDescriptor genérico (text/JSON/table/artifact refs) **sin** UI especializada Policy/Assurance | U5 |
| E2.6 | Fixture plugin SDK externo + tests ABI | — |

**UAT S2:** UAT-020..026 · **AAT S2:** AAT-PLG-001..007, AAT-ABI-001..003, AAT-SEC-010

#### S3 — Diagnostics, Detekt & IR Explorer

| ID | Entregable | Dependencia U |
|---|---|---|
| E3.1 | Diagnósticos normalizados por fase y fuente + markers Monaco | U1 |
| E3.2 | Detekt aislado con version matrix + reglas DSL pequeñas | U5 |
| E3.3 | IR Explorer etapad/Step/block + provider/contract + fases `compiled\|admitted\|observed` | U0 |
| E3.4 | Spike documentado Monaco + Kotlin LSP; si no soportado → `UNSUPPORTED` | U5 |
| E3.5 | Test canario 0 efectos si hay compile-only upstream | U1 |

**UAT S3:** UAT-027..035 · **AAT S3:** AAT-CPP-001..006, AAT-AN-001..008, AAT-ABI-004

**Bloqueo crítico S3:** U1 (compile-only sin eval real). Si upstream no entrega, **`STATIC_ANALYZE` NO se expone** y Detekt syntax-only etiquetado.

#### S4 — Agent collaboration y mutation scenarios

| ID | Entregable | Dependencia U |
|---|---|---|
| E4.1 | API/MCP para variantes de snapshot + compare runs | U0 |
| E4.2 | Scenario generation con testkit, positivos/negativos | U0 |
| E4.3 | Run Selection **contextualizado** (no selección arbitraria) | U0 |
| E4.4 | Mutation scenarios controlados, per-run fingerprints/diffs | — |
| E4.5 | MCP recursos paginados + discoverability de actions | U0 |
| E4.6 | Medición de calidad de diagnóstico y agent workflows | — |

**UAT S4:** UAT-036..038, 041..046 · **AAT S4:** AAT-MCP-007..008, AAT-DUR-006, AAT-SEC-008..010, AAT-API-005..008

#### S5 — Product readiness

| ID | Entregable | Dependencia U |
|---|---|---|
| E5.1 | `studio start`, `studio mcp`, `studio doctor` + distribuciones JVM+SPA+MCP | — |
| E5.2 | SBOM + checksums + manifests + upgrade DB + backup/restore | — |
| E5.3 | OpenAPI versionado + SDK TS + segunda app cliente (VS Code minimal/CLI nuevo) | U0 |
| E5.4 | Matriz runtime/API/plugin compatibles + security hardening + retention cleanup + secret canaries | U3,U4 |
| E5.5 | Certificación independiente desde fresh clone (sin src checkout) | — |
| E5.6 | QA UX/keyboard/ARIA + docs + ADR review | — |

**UAT S5:** UAT-039..046 + regresión S0..S4 · **AAT S5:** AAT-REL-001..010, AAT-SEC-001..010, AAT-ABI-001..006, AAT-OBS-001..008

### Bloqueos críticos entre hitos (resueltos inteligentemente)

| Bloqueo | Bloquea a | Resolución inteligente |
|---|---|---|
| U1 (compile-only) | S3 | En S0 abrir entrega genérica upstream `pipeline-kotlin`; tracking issue; mientras, S3 expone `UNSUPPORTED` y Detekt syntax-only etiquetado |
| U4 (output sellado) | S1 | Caracterizar v0.48.0-rc1 en S0; si sellado < OK, registrar `BLOCKED-BY-OBS` y exigir release upstream antes de cerrar S1 |
| U2 (cancel/admisión externa) | S0 | Caracterizar con test; si gap, abrir entrega genérica upstream; S0 entrega con adapter interno aunque no sea óptimo |
| U3 (sandbox) | S5 | S0 solo perfil `trusted` (evita pretender seguridad no certificada); S5 hace hardening o `UNSUPPORTED` |
| U5 (LSP/schema) | S2,S3 | Schema vacío → `NO_SCHEMA` honesto; LSP → `UNSUPPORTED` con spike documentado |
| GitHub no pusheable | S0 DOFF | Decisión sesión: no crear release esta sesión. Reportar como `BUILDABLE+TESTABLE-NOT-DONE` con PR/push como acción usuario-explícita |

---

## Recepciones previstas al cierre

Cuando complete T-S0-16, debo entregar al usuario:

1. Listado exacto de archivos creados (paths), total LOC, tests count.
2. Resultado de comandos Gradle corridos (./gradlew tasks, ./gradlew :studio-domain:test, ./gradlew assemble o equivalente).
3. Estado de cada DoD S0: ✅/❌/⚠️ con justificación.
4. Próximo paso sugerido al usuario (autorizar push a GH + crear release v0.1.0-alpha.1).
5. Riesgos activos con nivel y dueño propuesto.

## Cómo reanudo

`mem_context` → `mem_search project=pipelinekstudio` → leer este doc → reconciliar contra `git status` → continuar desde la primera tarea `[ ]` no marcada.

## Decisiones registradas (ADR-light)

| ID | Decisión | Por qué | Tradeoffs |
|---|---|---|---|
| D-001 | Monorepo Gradle multi-módulo + pnpm workspaces | Coherente con upstream PipelineK + WEB/MCP/CLI mixtos | Build inicial lento; invertido en tooling |
| D-002 | Pin `v0.48.0-rc1` SHA en S0 | SDK consumible publicado; pin evita drift API | Breaking change upstream entre sesiones; pin actualiza con SHA nuevo |
| D-003 | SQLite Studio vía Flyway con `studio_schema_version` | Migraciones idempotentes + backup/restore testeable | Más deps (Flyway); alternativa: scripts SQL propios |
| D-004 | MCP v2 thin adapter llama HTTP local, no DB directa | Una sola autoridad de negocio; MCP no reinventa reglas | Latencia extra en MCP→HTTP; aceptable para stdio local |
| D-005 | Runner supervisor con perfil `trusted` ONLY en S0 | Evita pretender sandbox no certificada | No ejecutar código host; aceptable en S0 |
| D-006 | `STATIC_ANALYZE` no se expone en S3 si U1 sigue gap | Cumple "no llamar analyze al eval" + honestidad usuario | S3 pierde capacidad; ganamos verdad observable |
| D-007 | No hacer push a GitHub sin autorización explícita del usuario | Cumple "no descubrir/inspeccionar/reusar credenciales" + ROADMAP exige consentimiento | Esta sesión queda local; release es decisión usuario |
| D-008 | S5 backup/restore usa SQLite Online Backup API vía xerial `NativeDB.backup(String, String, ProgressObserver)` | API nativa es la única forma segura de copiar SQLite sin lock de escritor; no requiere dep nueva | Necesita reflection (progreso observer es nested class); documentado en código |
| D-009 | S5 E5.4 cuerpo máximo 1 MiB enforced en interceptor propio | ktor-server-body-limit no estaba en classpath; interceptor `installBodySizeLimit` consulta `Content-Length` y rechaza con 413 + JSON | Chunked requests sin `Content-Length` no se rechazan; aceptable porque estudio es local single-user |
| D-010 | S5 E5.1 `studio start` arranca servidor MÍNIMO (no `studio-app` completo) | Permite smoke server sin distribución; CLI no debe pull del SDK ni levantar runner | Operador que necesita full surface debe usar `studio-app`; documentado en `/api/version` body |
| D-011 | S5 E5.1 `studio mcp` solo localiza+exec; nunca instala npm | Cumple D-005 (no network) + D-007 (no credenciales) | Operador corre `npm install && npm run build`; CLI solo delega |

