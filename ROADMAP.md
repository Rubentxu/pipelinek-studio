# ROADMAP — PipelineK Studio

> **Autoridad única de secuenciación** del repo `Rubentxu/pipelinek-studio`.
> Actualizado 2026-10-11 para reflejar la secuencia real **R0–R6** y resolver la
> discrepancia histórica entre los nombres antiguos B0–B7 / S0–S5.
>
> **Mapeo histórico** — los bloques antiguos no se borran porque son trazabilidad:
>
> | Antiguo | Bloque histórico | Estado real | Equivalente R |
> |---|---|---|---|
> | S0 | "Foundation/Vertical" | parcialmente entregado en `v0.1.0-alpha.1` (sólo release publicado); sin run end-to-end demostrable | **R0.3** (integridad de ejecución) |
> | S1 | "Interactive Console" | UI React + Monaco presente, eventos y output no reconciliados con SDK | **R1** (consola interactiva) |
> | S2 | "Step & Plugin Lab" | SDK fixture + admission existen; `runDb` no consolidado | **R2** (Plugin Lab) |
> | S3 | "Diagnostics, Detekt, IR" | Detekt reducido 83→25; IR sintético todavía en código | **R3** (diagnósticos + IR) |
> | S4 | "Agent collaboration" | MCP y VariantAndCompare presentes; tests parciales | **R4** (agent-first) |
> | S5 | "Product readiness" | tags rc.1 publicados pero sin GitHub Release | **R5** (EVO-C) **+ R6** (distribución + certificación) |
> | B0–B7 | "gate hardening / release harness" | recipe + debt register entregados 2026-10-11; sin release remoto | **R6.3** (publicación integral) |
>
> **Regla de autoridad:** este documento es la única fuente de secuenciación.
> `docs/history/*` conserva los receipts históricos pero NO prevalecen sobre
> el código actual ni sobre las pruebas ejecutadas. Las certificaciones locales
> **no equivalen** a certificaciones remotas.

## Vista de entregas (R0–R6 — autoridad vigente)

| Hito | Producto que se puede usar al acabar | Tag | Bloqueante precedente |
|---|---|---|---|
| **R0** Núcleo headless íntegro y certificación fiable | instalar Studio y ejecutar un pipeline auténtico desde CLI, HTTP y MCP | `v1.0.0-rc.2` | — |
| **R1** Consola interactiva y observabilidad sin pérdida de datos | editar pipeline en SPA, ejecutarlo, consultar etapas/output, cancelar, reanudar tras cerrar navegador | `v1.0.0-rc.3` | R0 |
| **R2** Step & Plugin Lab con SDK real | instalar plugin externo, inspeccionarlo, admitirlo, probar sus Steps | `v1.0.0-rc.4` | R1 |
| **R3** Diagnósticos fiables, Detekt e IR Explorer | diagnósticos tipados con source digest, IR con procedencia demostrada, Detekt aislado | `v1.0.0-rc.5` | R2 |
| **R4** Agent-first: investigación, variantes y comparación | agente externo propone modificación, ejecuta escenarios, compara resultados | `v1.0.0-rc.6` | R3 |
| **R5** EVO-C: integración nativa con VS Code | trabajar con Studio desde VS Code (sidebar, SSE, cancelación) | `v1.0.0-rc.7` | R4 |
| **R6** Distribución, operación y certificación integral | instalar Studio desde GitHub Release en máquina limpia; backup/restore/upgrade/downgrade | `v1.0.0-rc.8` | R5 |

**Reglas duras (obligatorias en todos los bloques):**

- No promoción directa a `v1.0.0` tras R6; requiere evaluación independiente sobre RC publicada.
- Cada bloque: integrar a `origin/main` → tag anotado inmutable → GitHub Release prerelease con artefactos reales → descargar y ejecutar smoke desde entorno limpio → receipt en `docs/history/` con `PASS` / `FAIL` / `BLOCKED`.
- `UNAVAILABLE`, `UNKNOWN`, `NOT_MEASURED`, `BLOCKED` **nunca** se convierten en `PASS`.
- PipelineK es la autoridad de ejecución y resultados. Studio no duplica el motor.
- Mocks, fixtures fabricados, regex sobre código fuente y presencia de archivos **nunca** equivalen a evidencia de ejecución.
- Antes de integración y release: full suite real. Sin full suite verde, no release.
- `gh release create` solo con autorización explícita del operador (regla D-007).

## Reglas generales de cada bloque

- Un bloque largo = funcionalidad demostrable usuario/agente; no 'módulo creado'.
- Secuencia **R0 conciliación → decisión SDDK → implementación vertical → UAT real → AAT adversarial → integración main → tag → release → installed smoke desde clon limpio → evidencia remota**.
- Testing quirúrgico durante edición, full suite y empaquetado sin caché engañosa en integración. Release no se certifica con artefactos creados de SHA distinto.
- `main` es autoridad única; trabajar en rama corta `feat/Rn-...` con base/fetch/rebase, proteger tags y verificar `origin/main` antes de integrar. No `force push`, no reescribir historia ajena, no borrar ramas no propias.
- Usar SDDK central del usuario, con estado en directorio de usuario por proyecto y `AGENTS.md` central; `ROADMAP.md` única autoridad de secuencia. No insertar directorios de estado/pipelines en repos de terceros.
- Si bloqueado por upstream (SDK / distribución / compiler): registrar `BLOCKED` con evidencia y continuar con trabajo que **no dependa de afirmar falsamente** que el bloque está terminado.

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

---

# Detalle de los bloques R0–R6 (autoridad vigente — 2026-10-11)

> Los bloques S0–S5 listados arriba quedan como **trazabilidad histórica**;
> esta sección es la que el agente debe usar para planificar. Cada bloque
> tiene su entrada criterio antes de implementar.

## R0 — Núcleo headless íntegro y certificación fiable

**Release objetivo:** `v1.0.0-rc.2` · **Prioridad:** bloqueante · **Resultado:** instalar Studio y ejecutar un pipeline auténtico mediante CLI, HTTP y MCP, consultando su estado y resultado desde cualquiera de los tres clientes.

### R0.1 — Reparar el sistema de certificación

1. Corregir las pruebas fallidas de `AsdfPluginB61Test`. *(Diagnóstico 2026-10-11 en `b2cdee6`: las 7 pruebas actualmente pasan; el mandato pudo escribirse contra estado previo. Re-verificar al iniciar.)*
2. Incorporar los ejecutables reales que faltan en `asdf-pipelinek-studio/bin/`.
3. Unificar Detekt: o tarea Gradle válida o script oficial correctamente invocado. **Confirmado en `b2cdee6`:** CI llama `./gradlew detekt` pero esa tarea **no existe** (sólo hay `detekt-run.sh` externo).
4. Eliminar `lint` ficticios en Web y MCP (`"echo 'lint deferred to S1'"`); establecer herramientas reales de lint y typecheck.
5. Alinear CI, release harness y gate local. Demostrar que una prueba rota provoca veredicto rojo.
6. Introducir protecciones de integración de `main`; si no configurables vía API, registrar impedimento.

### R0.2 — Corregir el producto en su arranque real

1. Unificar el servidor productivo y el de la CLI, eliminando diferencias injustificadas.
2. TLS real, no solamente un keystore.
3. Probar handshake contra la app instalada; HTTP inseguro solo opt-in.
4. Sustituir `RefusingRunReadPort` y `RefusingOutputReadPort` en el wiring productivo por adaptadores reales o `UNSUPPORTED` explícito cuando el SDK no ofrezca capacidad.
5. Corregir la contradicción entre `201 Created` del backend y los códigos aceptados por Web/CLI.
6. Reconciliar rutas, códigos, esquemas y versiones entre `contracts/openapi.yaml`, `StudioHttpModule`, CLI, Web y MCP. Resolver formalmente las rutas sin prefijo frente a `/api/v1`; añadir compatibilidad antes de romper clientes.

### R0.3 — Cerrar integridad de ejecución

| # | Sub-item | Estado | Tag / SHA |
|---|---|---|---|
| 1 | Persistir escenarios auténticos; eliminar `experiment_id` como sustituto de `scenario_id` | **PASS** | `v1.0.0-rc.3.1` (`fed9ef1`) — `docs/history/R0_3_1_RELEASE_RECEIPT.md` |
| 2 | `PRAGMA foreign_keys=ON` por conexión SQLite y probar | **PASS** | commit `f78f828` (sesión previa) — `docs/history/R0_3_RELEASE_RECEIPT.md` |
| 3 | Corregir la recuperación del pool SQLite y comprobar invariantes con conexiones inválidas | **PASS** | commit `f78f828` (sesión previa) — `docs/history/R0_3_RELEASE_RECEIPT.md` |
| 4 | Transformar la reserva de ejecución en un protocolo durable y atómico entre idempotencia y binding | **BLOCKED** (kill -9 smoke; requiere SDK subprocess instalado) | — |
| 5 | Registrar estados de Lanzamiento y recuperación sin duplicar efectos tras interrupciones | **BLOCKED** (kill -9 smoke; requiere SDK subprocess instalado) | — |
| 6 | Obtener el verdadero ID upstream de PipelineK, sin generar UUID que se haga pasar por él | **BLOCKED** (runtime real del SDK; requiere subprocess vivo) | — |
| 7 | Una sola proyección de `RunObservation` y resultado terminal basada en autoridad upstream. `UNKNOWN` no puede convertirse en `FAILED` ni `Terminal` sin evidencia | **PASS** | `v1.0.0-rc.3.7` (`4ab968c`) — `docs/history/R0_3_7_RELEASE_RECEIPT.md` |
| 8 | `studio.db` propiedad de Studio; `run.db` propiedad de PipelineK | **PASS** | `v1.0.0-rc.3.8` (`6869c34`) — `docs/history/R0_3_8_RELEASE_RECEIPT.md` (incluye bug fix: `openReadOnly` ahora es read-only real a nivel SQLite) |
| 9 | Comprobar identidad de snapshot, runtime, plugins y perfil para cada ejecución | **PASS** | `v1.0.0-rc.3.9` (`26ba589`) — `docs/history/R0_3_9_RELEASE_RECEIPT.md` (incluye bug fix: `SourceScenario.environmentProfile` ahora enlaza con `scenarios.environment_profile NOT NULL`) |

7 de 9 sub-items cerrados sin SDK subprocess. R0.3.4/3.5/3.6 cierran con kill -9 smoke (R0.4); fuera de R0 hasta que haya máquina de smoke.

### UAT de salida

Crear experimento, escenario y snapshot; ejecutar un pipeline con `echo` y recuperar su resultado. Ejecutar mediante API y MCP, con respuesta compatible desde CLI. 50 solicitudes simultáneas con la misma clave → único efecto. Interrumpir el backend en las fronteras de reserva y lanzamiento; recuperar sin repetir Steps. Reiniciar Studio y seguir leyendo una run finalizada. HTTPS real, certificado no confiable y opt-in HTTP.

**UAT obligatorias:** `001`, `002`, `013`, `014`, `015`, `039`. **AAT obligatorias:** `ARCH-001..006`, `DUR-001..005`, `API-001..004`, `API-008`, `SEC-001`, `SEC-002`, `SEC-004`, `SEC-005`, más `REL-001..010` aplicables.

### Cierre

Integrar en remoto, crear tag nuevo, publicar `v1.0.0-rc.2`, descargar la distribución y ejecutar el smoke completo.

---

## R1 — Consola interactiva y observabilidad sin pérdida de datos

**Release objetivo:** `v1.0.0-rc.3`. **Resultado:** un usuario abre Studio, escribe un pipeline Kotlin, lo ejecuta, consulta etapas y output, cancela y reanuda la observación tras cerrar navegador o reiniciar el servidor.

### R1.1 — Event Plane correcto
- Unificar el contrato de eventos con el SDK.
- Implementar proyección de estados y stages a partir de eventos auténticos.
- Reparar `EventStream.tsx`: suscribirse a los nombres de evento emitidos o usar un sobre SSE uniforme y versionado.
- Cursores reanudables, duplicados, desconexiones y relectura autoritativa.
- SSE anuncia cambios; no sustituye al journal como autoridad.

### R1.2 — Output Plane correcto
- Separar stdout, stderr y output de Steps donde el SDK permita distinguirlos.
- No etiquetar un stream fusionado como `stdout`.
- `EventCursor` y `OutputCursor` diferentes.
- Rechazar cursores inválidos o procedencia incorrecta; no reiniciar silenciosamente la lectura.
- Lectura por offsets sin recorrer el fichero completo.
- Reconstruir bytes UTF-8 entre fragmentos sin corrupción.
- Consumo sucesivo de páginas en Web con memoria acotada.
- Certificar 100 MiB de output y dos lectores independientes sobre una run viva.

### R1.3 — Experiencia de usuario
- Scratchpad Monaco funcional.
- Varias ejecuciones deliberadas del mismo snapshot con claves diferentes; misma clave solo para reintentos.
- Diferencias claras entre `RUNNING`, `REFUSED`, `RECOVERY_REQUIRED`, `UNKNOWN` y outcomes terminales.
- Evitar acumulación ilimitada de DOM y bytes.
- Accesibilidad básica: teclado, foco, ARIA, errores anunciables, sin CDN obligatorio.

**UAT:** `003`, `006–012`, `016–019`. **AAT:** `OBS-001..008`, `UX-001..006`, `SEC-007`, `SEC-009`, `DUR-004`, `DUR-006`.

---

## R2 — Step & Plugin Lab con SDK real

**Release objetivo:** `v1.0.0-rc.4`. **Resultado:** instalar un plugin compilado fuera de Studio, inspeccionarlo, admitirlo y probar sus Steps mediante el motor real de PipelineK.

1. Proyecto fixture externo usando la versión publicada del Plugin SDK.
2. Inspeccionar manifests, identidad, hashes y compatibilidad sin ejecutar inicializadores ni cargar clases en el backend.
3. Distinguir `DISCOVERED`, `COMPATIBLE`, `ADMITTED`, `REFUSED`, `UNAVAILABLE`.
4. Sustituir la detección de `Core*Step.class` por el registry autorizado del SDK.
5. Congelar el conjunto de plugins y su digest por run.
6. Resolver colisiones de Step keys y versiones antes del despacho.
7. Exponer `StepContract`, codec, capacidades, esquema, provenance y motivo de rechazo.
8. Step TestKit: entrada, escenario, eventos, resultado, artefactos.
9. Mismo dispatch para Step oficial y Step externo.
10. Endpoints HTTP y tools MCP únicamente cuando estén realmente conectados.
11. Seam de extensibilidad para Policies/Assurance sin introducir dependencias de esas proyectos.

**UAT:** `020–026`. **AAT:** `PLG-001..007`, `ABI-001..004`, `ARCH-003`, `SEC-006`.

---

## R3 — Diagnósticos fiables, Detekt e IR Explorer

**Release objetivo:** `v1.0.0-rc.5`. **Resultado:** comprender errores de Kotlin, construcción DSL y ejecución con diagnósticos vinculados a la fuente y un IR con procedencia demostrada.

1. Diagnóstico tipado: fase, severidad, source digest, localización, toolchain, versión, capabilities, procedencia.
2. Parsers regex → decodificadores estructurados contra el contrato auténtico del SDK.
3. Eliminar IR sintético `compiled-runtime` como IR canónico.
4. Separar `COMPILED`, `ADMITTED`, `OBSERVED`.
5. Mostrar qué nodos son verificados y cuáles carecen de correspondencia con código fuente.
6. Detekt aislado, versiones pinneadas, fixtures positivos/negativos, reglas justificadas.
7. Integrar diagnósticos y markers en Monaco; evitar diagnósticos obsoletos.
8. No ejecutar código Kotlin del usuario para simular análisis estático.
9. Si PipelineK no tiene compilación estática segura, publicar `UNSUPPORTED`; evolución upstream como contrato genérico probado antes de consumir.
10. Spike Kotlin LSP reproducible; no convertir su adopción en release requirement si todavía no aporta suficiente precisión.

**UAT:** `004`, `005`, `027–035`. **AAT:** `CPP-001..006`, `AN-001..008`, `ABI-004`. Incluir Kotlin con efecto lateral top-level: el análisis estático no puede ejecutar el efecto.

---

## R5 — EVO-C: VS Code nativo

**Release objetivo:** `v1.0.0-rc.7`. **Resultado:** trabajar con PipelineK Studio desde VS Code, inspeccionando experimentos, ejecutando y cancelando runs y viendo su estado sin abrir la SPA.

1. Evolucionar la extensión actual de cuatro comandos, reutilizando HTTP/OpenAPI.
2. Indicador de estado y conexión.
3. Vista lateral de experimentos, snapshots y runs.
4. Flujo de cambios SSE tipados.
5. Endpoint de cambios del árbol si no existe; enlazarlo a persistencia y eventos reales, no a un emisor en memoria.
6. Snapshot, reconexión con backoff y recuperación de eventos perdidos.
7. Heartbeat; distinguir conexión caída de vista sin actividad.
8. Comandos para abrir run, cancelar, acceder a diagnósticos.
9. CodeLens/decoraciones para `*.pipeline.kts` sin ejecutar código desde el editor.
10. Reconciliar `/experiments/{id}/cancel` (citado por la espec EVO-C) con `/runs/{id}/cancel` real; no dos semánticas incompatibles.
11. TLS, perfil, autorizaciones.
12. Empaquetar VSIX y probar en Extension Host real.

**UAT:** `039` y regresión de `003`, `010–012`. **AAT:** `API-001..008`, `ABI-005..006`, `OBS-005`, `UX-001..004`, `SEC-005`.

---

## R6 — Distribución, operación y certificación integral

**Release objetivo:** `v1.0.0-rc.8`. **Resultado:** instalar y operar Studio en entornos limpios sin depender del checkout fuente, con actualizaciones, recuperación, compatibilidad y límites de recursos verificados.

### R6.1 — Distribución
- Revisar paquetes standalone basados en jlink + Node.
- Alinear backend completo con `studio start`; no ofrecer un servidor mínimo de humo como sustituto del producto.
- Certificar rutas de instalación (tar, asdf/mise, Homebrew, Scoop, Docker) en entornos soportados.
- Un único identificador de release compartido por CLI, backend, Web, MCP y VSIX.
- Assets HTML/JS, servidores y clientes funcionan sin CDN.
- Corregir tareas Gradle incompatibles con configuration cache y retirar APIs obsoletas cuando sea viable.

### R6.2 — Operación y seguridad
- Certificar backup, restore, upgrade y downgrade de `studio.db`.
- Prohibir migraciones o escritura del schema propiedad de PipelineK.
- Permisos, rutas, Origin, Host, leases destructivos.
- Retención con TTL, dry-run, recuperación.
- Concurrencia, crash/restart, limpieza de procesos.
- Cold start, warm start, latencia, memoria, 100 MiB y lectores concurrentes.
- Presupuestos de rendimiento a partir de medidas reales.

### R6.3 — Contratos de publicación
- Validar OpenAPI y clientes de conformidad.
- Matriz de compatibilidad PipelineK Runtime / Plugin SDK / Kotlin / Java / OS.
- Manifest, SHA256, SBOM, licencias desde artefactos reales.
- Dos compilaciones reproducibles o documentar diferencias.
- Descargar todos los binarios publicados y volver a ejecutar UAT/AAT instaladas.
- Registrar limitaciones y capacidades explícitas de la RC.

**UAT:** `039`, `040` y regresión completa R0–R5. **AAT:** `REL-001..010`, `ABI-001..006`, `API-001..008` y gates completos de seguridad, durabilidad, observación y rendimiento.

### Cierre

Integrar, publicar `v1.0.0-rc.8` y certificar todos sus paquetes. No promover a `v1.0.0` estable por haber completado el roadmap; requiere evaluación independiente sin defectos bloqueantes y sin nuevas funcionalidades.

---

# Notas operativas del bloque R0 (estado al 2026-10-11)

Esta sección se sobrescribe en cada receipt; la dejo aquí como
snapshot del diagnóstico de arranque. **No prevalecen sobre el código.**

| Hallazgo | Severidad | Próxima acción |
|---|---|---|
| CI llama `./gradlew detekt` pero la tarea no existe | bloqueante (R0.1.3) | crear tarea `detekt` que invoca `scripts/detekt-run.sh` con classpath aislado, o reemplazar CI por el script |
| `apps/{mcp,web}/package.json` declaran `"echo 'lint deferred to S1'"` como lint | bloqueante (R0.1.4) | añadir `eslint` + `tsc --noEmit` reales; documentar `npm run lint` y `npm run typecheck` |
| `asdf-pipelinek-studio/bin/` sólo tiene `download`, `install`, `list-all`, `list-bin`; asdf también acepta `latest` y/o `bin/list-bin` separado por defecto | advertencia (R0.1.2) | revisar docs asdf; añadir `bin/latest` si hace falta; auditar README |
| AsdfPluginB61Test pasa 7/7 en `b2cdee6` | contradicción con mandato | al iniciar R0.1.1 re-correr `--rerun-tasks`; puede ser fallo de caché |
| Branch protection no detectable vía API en el repo | advertencia (R0.1.6) | documentar; pedir settings; replicar a nivel workflow required-checks |
| `apps/{mcp,web,vscode}/package-lock.json` siguen diciendo `0.1.0-alpha.1` | menor (R-VER-001 follow-up) | regenerar con `npm install` la próxima vez que se toquen las apps |
| GitHub Releases en `origin`: sólo `v0.1.0-alpha.1`; 11 tags sin artefactos publicados | bloqueante (R6.3) | antes de R6, backfillear (o documentar la decisión de no backfillear) todos los tags intermedios |
