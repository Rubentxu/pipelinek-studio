# ROADMAP — PipelineK Studio (autoridad única de secuenciación del nuevo repo)

**Estado inicial:** no existe aún un repo público accesible `Rubentxu/pipelinek-studio` a fecha de investigación. **No hay bloques de Studio completados.** Este documento es la autoridad de secuenciación de Studio cuando se incorpore al nuevo repo, no sustituye ROADMAP upstream. Regla: un bloque DONE solo tras integración remota, prerelease GitHub real y gates con evidencia SHA. Mantener reportes históricos en `docs/history/` tras implementar; no inflar este roadmap con prosa diaria.

## Vista de entregas

| Hito | Producto que se puede usar al acabar | Tag orientativo | Dependencias |
|---|---|---|---|
| **S0 — Foundation/Vertical** | CLI/API/MCP ejecutan un pipeline real y observan su resultado | `v0.1.0-alpha.1` | upstream SDK/distribution, aislamiento caracterizado |
| **S1 — Interactive Console** | Monaco web, run real, historial, cancelar, logs/eventos, reconnect | `v0.2.0-alpha.1` | S0, read-side real |
| **S2 — Step & Plugin Lab** | Inspeccionar y probar plugin externo sin tocar engine | `v0.3.0-alpha.1` | S1, Plugin SDK S6 admitido |
| **S3 — Diagnostics & IR** | Detekt + diagnósticos + IR Explorer; LSP spike | `v0.4.0-beta.1` | S2, compile-only separable o UNSUPPORTED honesto |
| **S4 — Agent Collaboration** | Investigación y variantes agent-first, diff, escenarios y mutación | `v0.5.0-beta.1` | S3, identidad/diagnósticos/outcomes fiables |
| **S5 — Product Ready** | Distribución local, compatibilidad, seguridad, recovery, cliente externo | `v1.0.0-rc.1` | S0..S4; verdict de certificación antes de stable |

Si release anterior continúa en alpha por problema real, la numeración será adaptada; los tags propuestos no son deuda histórica ni autorización para saltarse SemVer. Una etiqueta publicada es inmutable.

## Reglas generales de cada bloque

- Un bloque largo = funcionalidad demostrable usuario/agente; no 'módulo creado'. Investigación y tests preceden implementación cuando el riesgo es alto.
- Secuencia **R0 conciliación → decisión SDDK → implementación vertical → UAT real → AAT adversarial → integración main → release → installed smoke desde clon limpio → evidencia remota**.
- Testing quirúrgico durante edición, full suite y empaquetado sin caché engañosa en integración. Release no se certifica con artefactos creados de SHA distinto.
- `main` es autoridad única; trabajar en rama corta `feat/sX-...` con base/fetch/rebase, proteger tags y verificar `origin/main` antes de integrar. No `force push`, no reescribir historia ajena, no borrar ramas no propias.
- Usar SDDK central del usuario, con estado en directorio de usuario por proyecto y `AGENTS.md` central; `ROADMAP.md` única autoridad de secuencia. No insertar directorios de estado/pipelines en repos de terceros.
- Repositorio nuevo no debe depender de pushes upstream simultáneos que no tienen release certificada. Si bloqueado por upstream, registrar necesidad genérica y continuar con mocks **solo para desarrollo UI**; no cerrar UAT end-to-end con mocks.

## S0 — Foundation & real headless vertical

**Valor:** un agente o CLI puede crear un experimento y ejecutar un `.pipeline.kts` real, obtener run durable y leer resultado; se demuestra independencia de la UI.

### Entrada y reconocimiento

1. Resolver `pipeline-kotlin main` y `s6-plugin-sdk` SHA, versión y API SDK publicadas; verificar `ScriptingHost`, `SqliteEventStore`, `OutputReadPort`, `StepRegistry` y distribución instalada. Documentar desajustes.
2. Crear repo Studio si no existe con consentimiento/permisos y proteger `main`; bootstrap Gradle Kotlin/JVM, Ktor, Node/pnpm, TypeScript, formateo/detekt y test config; cliente generado OpenAPI, sin módulos innecesarios.
3. Spike de seguridad: demostrar que `validate`/`ScriptingHost.compile` pueden evaluar; medir contención de runner Linux con canarios de filesystem/red/subprocess/secret. Registrar qué perfiles están disponibles y cuáles deben rechazarse.

### Entregables

- Domain `Experiment`, `SourceSnapshot`, `Scenario`, `RunBinding`, `Operation`, `IdempotencyKey`, states typed, SQLite studio.db + migrations.
- `POST /experiments`, snapshot seal, `POST /runs` y `GET /runs`, API root/health, respuestas tipadas y affordances reales.
- Runner por proceso JVM separado, PipelineK distribución instalada, `--db` durable per-run y control root gestionado, policy de TTL y almacenamiento seguro.
- MCP v2 stdio real: discover, create experiment/snapshot, start run, inspect run; mismo backend que HTTP.
- CLI mínimo independiente/ejemplo agent `studio ... --json` que demuestra la misma secuencia; documentación de instalación local.
- Repo con linter/tests y primeros fixtures; no afirmar estático seguro cuando solo existe eval.

### UAT S0

`UAT-001`, `002`, `009`, `013`, `014`, `015`; desde distribución instalada, navegador no requerido. Un run real `echo` termina, el output durable se consulta por read API cuando exista; si lectura de Output Plane limitada, entregar run + read de estado y registrar un bloqueante explícito para S1, **sin simular salida**.

### AAT S0

`AAT-ARCH-001..004`, `AAT-SEC-001..006`, `AAT-DUR-001..003`, `AAT-MCP-001..004`, `AAT-API-001..004`, `AAT-REL-001..006`. Subproceso infantil malicioso, duplicado concurrente, crash, DB ownership, wire v2 y canario compile/eval.

### DoD

`v0.1.0-alpha.1` remoto sobre main integrado; fuente + ZIP instalable frontend opcional (headless), digest, SBOM si build soportado, manifest, test reports, comandos y SHA. Advertir en release que **sandbox non-trusted** no está garantizado hasta gate y que UX web llega en S1. Bloque no cerrado si no existe run real.

## S1 — Groovy-like Interactive Console

**Valor:** una persona abre Studio local, edita/ejecuta/consulta un pipeline, ve logs/eventos del motor real, lo cancela/reabre sin perder evidencia.

### Entregables

- SPA React + Monaco, navegador de experimentos/snapshots, Run/Stop, problemas/outcomes, historial y vínculo al source exacto.
- API read-side para stage/step/event journal y bytes Output Plane por cursor independiente (no copiar output a SQLite).
- SSE de notificaciones, paginación reanudable, UTF8 incremental, output bounds/virtualización, errores semánticos distinguibles.
- Persistencia de referencias para restart de servidor/navegador, reconcilia run no terminal, snapshot immutability y release UX offline.
- Primer prototipo source location con diagnóstico que existe, sin etiquetar como static analysis safe el `validate` productivo.

### UAT / AAT

`UAT-003..012`, `016..019`; `AAT-OBS-001..008`, `AAT-DUR-004..006`, `AAT-SEC-007..009`, `AAT-UX-001..006`, `AAT-MCP-005..006`. Incluir output silencio/solo stdout/solo stderr, multi-byte dividido, 100MiB logs medidos en plataforma de release, kill -9 y backpressure. Si OBS upstream carece de sellado o separabilidad exigida, `WAIT-FOR-UPSTREAM`, no false PASS.

### DoD

`v0.2.0-alpha.1` con interfaz real integrada main y GitHub Release, prueba instalada en navegador sin CDN, consumidor MCP reproduciendo el mismo run y read results, captura de evidencia no fingida.

## S2 — Step & Plugin Lab

**Valor:** construir un plugin externo, instalarlo en runner, inspectar contrato y probar su comportamiento sin código Studio específico.

### Entregables

- Static manifest inspector (no classloading), trust/admission classification, plugin set lock/fingerprint.
- Snapshot de StepRegistry admitido, `GET /steps`, detalle de StepContract, schemas opcionales, capabilities y action affordances.
- Test runner `Scenario` para Step por camino canónico (si no existe API publica para inline Step, generar `.pipeline.kts` fixture y ejecutar); reconstruir exactamente mismos outcomes.
- CLI/MCP `studio.steps.list`, `studio.steps.describe`, `studio.plugins.inspect`, `studio.scenario.execute`, fixtures plugin SDK externo.
- ResultDescriptor genérico (texto/JSON/tabla/artifact refs) sin UI especializada Policy/Assurance.

### UAT / AAT

`UAT-020..026`; `AAT-PLG-001..007`, `AAT-ABI-001..003`, `AAT-SEC-010`. Manifest faltante, duplicado, bytecode inicializador malicioso, mismatch SDK API, capability absent, 0 core edits, installed SDK consumer.

### DoD

`v0.3.0-alpha.1`; binario Studio instalado y plugin externo compilado contra SDK publicado del SHA vigente; tests contract/end-to-end verde; dos clientes (MCP y Web) ven el mismo catálogo.

## S3 — Diagnostics, Detekt e IR Explorer

**Valor:** un usuario entiende por qué su pipeline no compila, por qué un Step no está admitido y qué IR se produjo, antes de ejecutar una nueva variante.

### Entregables

- Diagnósticos normalizados por fases y fuente, markers Monaco/source map, fallos tipados de `COMPILATION` vs `DSL_CONSTRUCTION` y toolchain provenance.
- Detekt en proceso aislado con version matrix y reglas DSL pequeñas (sin confundir warning vs admission verdict).
- IR Explorer con etapa/Step/block, provider/contract y fases `compiled|admitted|observed`, sin duplicar compiler.
- Spike documentado Monaco+Kotlin LSP sobre `.pipeline.kts`; si no soportado, `UNSUPPORTED`, fallback diagnostics existentes, no bloquear release.
- Si se implementa compile-only en upstream genérico, test de canario 0 efectos; si no existe, `STATIC_ANALYZE` no expuesto y Detekt syntax-only claramente etiquetado.

### UAT / AAT

`UAT-027..035`; `AAT-CPP-001..006`, `AAT-AN-001..008`, `AAT-ABI-004`; mutation tests de reglas custom, exactitud de source mappings.

### DoD

`v0.4.0-beta.1` integra Detekt real + IR de fuente upstream, con evidencia de lo que funciona/lo que no del LSP; no liberar función unsafe etiquetada compile-only.

## S4 — Agent collaboration y escenarios inteligentes

**Valor:** agente revisa run fallida, crea variante, propone corrección, genera escenario y compara evidencia, sin mutar original ni repetir efectos accidentalmente.

### Entregables

- API/MCP para variantes de snapshot y compare de runs con diferencias de runtime/plugins/env perfil; prompts agent-first con resultado verificable.
- Scenario generation con testkit, positivos/negativos; Run Selection **solo Step contextualizado** y con reconstrucción de pipeline legal.
- Mutation scenarios controlados, per-run fingerprints/diffs, acciones explícitas de usuario para promocionar snapshot.
- MCP recursos paginados, discoverability de actions y su procedencia; capability-driven (no tool-per-step).
- Medición de calidad de diagnóstico y agent workflows; fallo por instrucciones maliciosas en metadatos o logs.

### UAT / AAT

`UAT-036..038`, `041..046`; `AAT-MCP-007..008`, `AAT-DUR-006`, `AAT-SEC-008..010`, `AAT-API-005..008`. Prueba de agente que cambia resultado a PASS fingido debe fallar; snapshots inmutables.

### DoD

`v0.5.0-beta.1`, demostración reproducible desde cliente MCP real y web, sin integración de LLM como autoridad; release remoto.

## S5 — Product readiness, distribución, seam VS Code

**Valor:** instalador local, operación fiable, compatibilidad y seguridad caracterizadas, documentación de consumo externo y release candidate evaluable.

### Entregables

- `studio start`, `studio mcp`, `studio doctor`, distribuciones JVM+SPA+MCP, manifests de dependencias, checksums, SBOM, guía de actualización, upgrade DB, backup/restore.
- Contrato versionado OpenAPI y SDK TS generado con segunda app cliente (VS Code minimal o CLI nuevo) que haga: discover, snapshot, run, events/output, cancel/inspect.
- Matriz de runtime/API/plugin compatibles; security hardening, retention cleanup, secret canaries, perf/soak, profiling de cold/steady-state.
- Certificación independiente de artefacto instalado (sin src checkout): fresh clone, tests de dist, MCP stdio v2, browser offline, runner isolated, metadata SQLite restore, recovery.
- QA de UX/keyboard/ARIA, documentación, troubleshooting, ADR review, backlog evolutivos Policies/Assurance sin implementar.

### UAT / AAT

`UAT-039..046`, regresión S0..S4; `AAT-REL-001..010`, `AAT-SEC-001..010`, `AAT-ABI-001..006`, `AAT-OBS-001..008`, rendimiento y memoria baselines medidos, release harness externo si se adopta.

### DoD

`v1.0.0-rc.1` remota y candidata instalable. **Stable `v1.0.0` NO se publica automáticamente:** requiere veredicto externo de readiness y cero P0/P1 sin mitigación aceptada con evidencia real. En caso contrario conservar RC y bloque abierto.

## Evolutivos pos-S5 (NO compromisos del MVP)

EVO-A Policies Lab: source map + policy bundle diff + shadow + waiver, usando plugin publicado. EVO-B Assurance Lab: lens/evidence graph/assertions/counterexample, sin falsificar ausencia de evidencia. EVO-C VS Code completo. EVO-D Plugin compatibility across releases. EVO-E Tauri packaging. EVO-F debugger semántico/diff de filesystem bajo guardrails. Cada evolutivo tendrá ADR, UAT/AAT, propia release.

## Riesgos que bloquean promoción

- `R-01`: `validate`/`compile` realiza Kotlin eval. Mitigación: separador compile-only real o `UNSUPPORTED`.
- `R-02`: sandbox insuficiente. Rechazar perfil no confiable; no relajar por éxito del caso `echo`.
- `R-03`: OBS stdout/stderr/sellado/recovery no certificado. No inferir salida de Event Plane ni convertir refusal en bytes vacíos.
- `R-04`: S6/main/OBS versiones divergentes. Consumir artefactos publicados y test ABI en build limpio.
- `R-05`: MCP transport nuevo/client version. Probar wire y compatibilidad, no copiar ejemplos v1.
- `R-06`: `StepCodec.schema()` puede devolver `{}`; fallback honesto sin autoforms inventadas.
- `R-07`: recuperación de un run no terminal. No reejecutar automáticamente tras reiniciar Studio.
- `R-08`: dos DB SQLite y múltiples procesos; una sola autoridad/escritor por run DB.

## Referencias

`docs/01-architecture/*`, `docs/02-specifications/*`, `docs/05-testing/*`, `docs/06-operations/*`, `docs/07-research/*`; docs upstream ver `docs/07-research/UPSTREAM_GROUNDING.md`. Ante contradicción entre roadmap y código: prevalece test y código; actualizar roadmap tras medición.
