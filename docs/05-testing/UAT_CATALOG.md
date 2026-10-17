# UAT — Catálogo ejecutable de aceptación de usuario

**Regla:** UAT son pruebas reales del producto instalado, no capturas decorativas ni tests de DTO. Cada caso debe registrar input, perfil, SHA del motor, SHA Studio, escenario, evidencia/artifact ref, expected/actual, comando/script reproducible, status PASS/FAIL/NOT-MEASURED y motivo. El código actual prevalece sobre documentos. IDs estables; no reclasificar un FAIL como «skipped PASS».

## S0 — Foundation

| ID | Contexto/acción del usuario | Resultado exigido |
|---|---|---|
| UAT-001 | Instalar Studio headless en directorio limpio sin checkout; crear experimento + pipeline `echo` | Una ejecución real de PipelineK; `runId` y outcome verificables, no mocks |
| UAT-002 | Consultar snapshot del run y artefactos seleccionados | Bytes/código exactos, digest, runtime/SDK/plugin/profile identificados |
| UAT-009 | Script Kotlin intenta crear canario fuera de workspace bajo perfil aislado | Rechazo o bloqueo de efectos, canario externo intacto; sin false PASS |
| UAT-013 | Mismo experimento ejecutado por API HTTP y MCP stdio | Mismos estados/outcomes y recursos equivalentes (runs distintos legítimos) |
| UAT-014 | Cliente corta conexión tras solicitud y reintenta con misma clave | Una sola run por request digest; otra clave admite otra run |
| UAT-015 | Perfil no soportado en plataforma local | Rechazo claro, no fallback silencioso a trusted-local |

## S1 — Interactive Console

| ID | Contexto/acción | Resultado exigido |
|---|---|---|
| UAT-003 | Abrir navegador local, editar demo y pulsar Run | Runtime real, logs, stages, status y output recuperables |
| UAT-004 | Código con error Kotlin de sintaxis | Diagnóstico source-mapped y sin ejecución de Step |
| UAT-005 | DSL construye sin spec utilizable | `DSL_CONSTRUCTION_FAILED`, nunca SUCCESS por compile success aislado |
| UAT-006 | Step falla tras emitir stdout/stderr | Outcome FAIL, logs consultables sin perder últimos bytes |
| UAT-007 | Pipeline con dos stages, uno omitido / uno failed | Etapas representadas por eventos reales, sin inferencia textual |
| UAT-008 | Operación sin stdout | Vista vacía real solo si confirmado/sellado; no bloqueo infinito |
| UAT-010 | UI cancela una run larga | `CANCEL_REQUESTED` → terminal confirmado o unresolved; no éxito prematuro |
| UAT-011 | Cerrar/reabrir navegador durante run | Reanudación mediante handle y cursores, sin duplicar efectos |
| UAT-012 | Reiniciar Studio durante run o antes de leer output | Recovery y lectura coherente; ningún terminal inventado |
| UAT-016 | Comparar fuente usada con editor modificado | Run muestra snapshot original y no el borrador posterior |
| UAT-017 | Líneas stdout UTF8 multibyte partidas entre páginas | Reconstrucción exacta, sin reemplazos silenciosos |
| UAT-018 | stdout único; stderr único; ambos; streams inexistentes | Cada fuente representa solo lo que realmente persistió el plano de output |
| UAT-019 | Texto malicioso con HTML/ANSI/URL en stdout | Renderizado inocuo; sin XSS, autolinks peligrosos ni secretos |

## S2 — Step & Plugin Lab

| ID | Contexto/acción | Resultado exigido |
|---|---|---|
| UAT-020 | Plugin externo compilado contra SDK publicado, instalado en runner | Step aparece en catálogo sin modificar código Studio o PipelineK |
| UAT-021 | Plugin sin manifest/manifest incompatible + inicializador canario | Rechazado **antes** de inicialización, canario intacto |
| UAT-022 | Ejecutar Step con capability ausente | Rechazo previo al handler, motivo y acciones correctas |
| UAT-023 | Codec válido/inválido/roundtrip | Resultado tipado sin cast silencioso ni default success |
| UAT-024 | Plugin externo y Step oficial en escenarios comparables | Mismo modelo de dispatch, eventos/outcomes, sin segundo engine |
| UAT-025 | Step sin input/output schema útil | Studio ofrece JSON/raw y marca `SCHEMA_UNAVAILABLE`, no formulario inventado |
| UAT-026 | Seleccionar Step y consultar provenance de artifact | Version/digest/provider verdaderos, sin confundir installed vs admitted vs certified |

## S3 — Diagnostics & IR

| ID | Contexto/acción | Resultado exigido |
|---|---|---|
| UAT-027 | Compilación Kotlin con error en posición conocida | Diagnóstico en línea correcta dentro de Monaco |
| UAT-028 | DSL valido con IR stage/Step/block | Visualización IR coincide con proyección canónica del mismo snapshot |
| UAT-029 | Step desconocido por catálogo dinámico | `UNKNOWN_STEP` en fase apropiada; sin fallback a core Step aproximado |
| UAT-030 | Ejecutar Detekt sobre `.pipeline.kts` con violación conocida | Hallazgo reproducible con rule ID y toolVersion |
| UAT-031 | Regla Detekt custom falso positivo canario | No reporta Step solo porque nombre textual se parece; preservar precisión |
| UAT-032 | Ejecutar Static Analyze sobre Kotlin side-effect top-level | 0 efectos verificables o `UNSUPPORTED`; nunca 'analysis safe' falso |
| UAT-033 | Solicitar análisis con type resolution sin classpath válido | Degradación explícita `SYNTAX_ONLY` o refusal, nunca semantic verified |
| UAT-034 | LSP Kotlin sobre `.pipeline.kts` con Step externo | Si soportado, completion de clase real; si no, limit documentada sin bloquear release |
| UAT-035 | Seleccionar nodo IR y consulta evento runtime | Se distingue diseño/planificado de evento confirmado |

## S4 — Agent Collaboration

| ID | Contexto/acción | Resultado exigido |
|---|---|---|
| UAT-036 | Agente investiga fallo por MCP sin abrir navegador | Enumera operaciones, evidencia y motivos; no inventa veredicto |
| UAT-037 | Agente propone variante y genera test negativo | Original no cambia; variante tiene snapshot digest y resultado propio |
| UAT-038 | Agente compara dos runs con plugins distintos | Señala distinta version/lock y no atribuye diferencia solo al código |
| UAT-041 | Repetir tool MCP start con misma clave tras desconexión | Misma run; no segunda ejecución de Step con efecto |
| UAT-042 | Usuario revisa propuesta del agente | Diff visible, evidencia y autorización explícita de aplicación |
| UAT-043 | Agente solicita efecto fuera de perfil | Denegación idéntica a Web, actor/audit trazables |
| UAT-044 | Test de mutación introduce fallo premeditado | Test lo detecta; el reporte identifica mutación vs run normal |
| UAT-045 | Plugin description contiene instrucción maliciosa para LLM | Solo se trata como dato, sin escalada de permisos |
| UAT-046 | Agent prompt genera escenario sin claims de prueba | Sin ejecutar: muestra `NOT_EXECUTED`; después distingue PASS/FAIL según test |

## S5 — Distribución e interoperabilidad

| ID | Contexto/acción | Resultado exigido |
|---|---|---|
| UAT-039 | Segundo cliente no React consume API documentada | Discover, run, cancel, output y errors coherentes con Web |
| UAT-040 | Upgrade de versión con DB antigua | Migración reversible probada, o rechazo con rollback explícito; no corrompe DB upstream |

## Oráculos adicionales

- UAT de output compara bytes SHA256 del productor conocido con read-side paginado, no líneas stdout capturadas por el wrapper.
- UAT de resultado compara outcome oficial (StepOutcome, RunOutcome/event journal), no `stdout.contains("SUCCESS")`.
- UAT sin instalación real solo es **test de desarrollo**, no cierra el caso.
- UAT no tiene autoridad para autorizar un efecto: admisión y sandbox siempre externos al oráculo de UX.
- Un caso con upstream no certificado o entorno no disponible = `NOT MEASURED/BLOCKED` y debe seguir visible en matriz; no borrar su fila.

## Contrato de veredicto del arnés (T2 / S3+S4)

Regla: **ausencia de ejecución nunca es PASS.** Un script de UAT que termina en
verde sin que PipelineK haya ejecutado el snapshot certifica una distribución
que no ejecuta pipelines. El arnés implementa un único contrato, compartido por
`scripts/uat-from-distribution.sh`, `scripts/run-all-uats.sh` y
`scripts/e2e-host.sh`:

| Exit | Línea de veredicto | Significado |
|---|---|---|
| 0 | `UAT_VERDICT: PASSED` + `ALL UAT STEPS PASSED` | La run alcanzó `SUCCEEDED` **y** el plano de output contenía el marcador sellado en la fuente |
| 1 | `UAT_VERDICT: FAILED` | Ejecutó (o anunció) pero el resultado esperado falta: estado terminal no exitoso, sin `run_id`, sin marcador, o respuesta no documentada |
| 2 | (error de uso, sin HTTP) | Precondición/argumento inválido; no se emite ninguna petición |
| 3 | `UAT_VERDICT: BLOCKED` | El runtime no está presente o no certificó una run: `409 REFUSED`, `503`, sin `run_id`, o timeout |

Consecuencias operativas:

- `409 RUNTIME_UNAVAILABLE` (sin SDK) es **BLOCKED**, no PASS. El banner
  `ALL UAT STEPS PASSED` es inalcanzable desde ese camino.
- Un `202 OUTPUT_REFUSED` impide verificar el marcador: **FAILED**.
- Timeout sin estado terminal: **BLOCKED**. Estado terminal `FAILED`/`CANCELLED`
  o `REFUSED` en la binding: **FAILED**/**BLOCKED** respectivamente.
- Los planos de diagnóstico (`/events`, `/diagnostics`, `/ir`, `/steps`) se
  reportan como diagnósticos: una negativa **documentada** es `NOT MEASURED` y
  no condiciona la aceptación; un status no documentado (p.ej. 500) sí falla.
- `scripts/e2e-host.sh` es un wrapper fino: dist + `studio doctor` + delegación
  en el UAT canónico, propagando el mismo exit code. No reimplementa el flujo.
- El certificador `scripts/verify-from-clean-clone.sh` solo marca verde el paso
  3b si el log contiene `UAT_VERDICT: PASSED`. Sin SDK la certificación es
  **NOT_READY** honesta; para certificar hay que aportar
  `PIPELINEK_SDK_CLASSPATH=...`.

### API pública usada (sin formas obsoletas)

`POST /experiments` → `POST /scenarios` (id real de escenario, nunca el
`experiment_id` como sustituto) → `POST /experiments/{id}/snapshots` →
`POST /runs` con `experiment_id`, `snapshot_id`, `scenario_id` y header
`Idempotency-Key` → `GET /runs/{runId}` (`run_id`, estados
`SUCCEEDED|FAILED|CANCELLED|REFUSED`) → `GET /runs/{runId}/output` (envelope
`chunks[].bytesBase64`, decodificado en orden). La versión se compara con la del
binario instalado (`STUDIO_CLI_BIN --version`); no hay literales de release
(`1.0.0-rc.11`) en el arnés.

### Transporte

No hay default implícito de endpoint: `STUDIO_HTTP` es obligatorio.

El contrato vive en **un solo sitio**, `scripts/lib/studio-http.sh`, y lo
reutilizan todos los scripts que hacen polling a un Studio: `uat-*.sh`,
`verify-*.sh` y `e2e-*.sh`. Antes cada script llevaba
`STUDIO_HTTP="${STUDIO_HTTP:-http://127.0.0.1:7777}"`, así que un Studio por
defecto (TLS en 127.0.0.1:7778) se sondeaba por un esquema que nunca escucha:
el operador veía «studio unreachable» en un servidor sano y el fallo parecía
del producto. Ahora el script **declara** el transporte (o falla como error de
uso, exit 2, antes de la primera petición) en lugar de adivinarlo:

| Variable | Obligatoria | Efecto |
|---|---|---|
| `STUDIO_HTTP` | sí | base URL `https://…` o `http://…` |
| `STUDIO_HTTP_INSECURE=1` | con `http://` | reconoce HTTP en claro (test) |
| `STUDIO_CA_CERT=<pem>` | opcional | PEM de confianza para el certificado loopback autofirmado |

El propio `scripts/lib/studio-http.sh` exporta `CURL_CA_BUNDLE` y
`SSL_CERT_FILE` con el PEM indicado — el mismo bundle que usaría
`curl --cacert` — para que `curl` y `urllib` confíen por la vía documentada de
CA. Un script que **arranca** el servidor declara el endpoint literal que acaba
de abrir y pasa el flag correspondiente (`--insecure-http` cuando sondea
`http://`), como hacen los pasos 3, 3b y 5 de
`scripts/verify-from-clean-clone.sh`.

```bash
# HTTPS canónico (Studio escucha TLS en 127.0.0.1:7778).
# DATA_DIR es el data dir del proceso: ${user.home}/.pipelinek-studio/runtime
# para la distribución `studio-app`, ${user.home}/.pipelinek-studio para
# `studio start`. El alias y la contraseña son las constantes reales de
# `TlsConfig` (KEY_ALIAS, KEY_PASSWORD); con los valores de un keystore de
# ejemplo (`pipelinek-studio` / `changeit`) keytool sale con código ≠ 0 y el
# PEM resultante no es un certificado.
DATA_DIR="$HOME/.pipelinek-studio/runtime"
keytool -exportcert -rfc -alias studio-loopback \
  -keystore "$DATA_DIR/keystore.jks" -storepass studio-loopback-change-me > studio-loopback.pem
STUDIO_HTTP=https://127.0.0.1:7778 STUDIO_CA_CERT=studio-loopback.pem \
  ./scripts/uat-from-distribution.sh

# HTTP de test, opt-in explícito (mismo flag que ya exigen CLI y MCP)
STUDIO_HTTP=http://127.0.0.1:7777 STUDIO_HTTP_INSECURE=1 \
  ./scripts/uat-from-distribution.sh
```

`UatVerdictTest` mantiene dos regresiones baratas sobre esta convención: un
caso de comportamiento (`verify-plugins.sh` sin `STUDIO_HTTP`, o con `http://`
sin opt-in, sale con 2 y **no emite ninguna petición**) y un barrido estático
que falla si algún `scripts/*.sh` reintroduce un default con esquema, un
`STUDIO_HTTP="http://…"` sin el opt-in en la misma línea, un flag de bypass TLS
en un script que usa el contrato compartido, o si menos de 30 scripts lo
reutilizan. Un test adicional exige que cada backend arrancado por
`verify-from-clean-clone.sh` lleve el `--insecure-http` con el que luego se le
sondea.

No existe bypass TLS (`-k`): un fallo de certificado es un fallo real. Curl
negocia **HTTP/2** por defecto contra el listener TLS y el guard de host lo
acepta leyendo `:authority`, igual que acepta `Host` en HTTP/1.1: ya no hace
falta el workaround `--http1.1` (los scripts que aún lo usaban lo han perdido
junto con `-k`). Un `:authority` fuera de la allow-list se
rechaza con `400 HOST_NOT_ALLOWED` y una petición sin autoridad con
`400 HOST_HEADER_MISSING` (ver `docs/04-security/transport.md`).

### Presupuesto de tiempo (un solo mando)

`UAT_TIMEOUT` (1..1800 s, por defecto 120) acota **todo** el recorrido:

- espera de estado terminal,
- cada petición HTTP (`--connect-timeout` ≤5 s y `--max-time` ≤30 s derivados de
  `UAT_TIMEOUT`, de modo que un servidor que acepta la conexión y no responde
  termina en `BLOCKED` en lugar de colgar el arnés),
- la gracia posterior al estado terminal para leer output (≤10 s).

`UAT_POLL_INTERVAL` (0..60 s, por defecto 1) es el único otro ajuste expuesto.
Ambos se validan **antes de la primera petición**: un valor no numérico o fuera
de rango es un error de uso (exit 2), no un timeout de red misterioso. No se
reintenta ningún `POST`.

### Coste del arnés y selección (S4)

- `scripts/run-all-uats.sh` ejecuta cada escenario **una vez**. Antes dormía 30 s
  ciegos tras cada hijo (≈9 min con el set actual) y ejecutaba además cada
  `uat-podman-NNN.sh` dos veces (una directa y otra vía `uat-podman-all.sh`,
  duplicando contenedor y arranque de Studio). El sleep ciego se ha eliminado
  (medido: 120 034 ms → 27 ms con 4 hijos instantáneos) y los escenarios Podman
  se alcanzan solo a través de su agregador (`UAT_STAGGER_SECONDS` queda como
  opt-in explícito para quien apunte toda la tanda a un único servidor vivo,
  por el rate limit 60 req/60 s).
- Veredicto agregado: `0` todo PASSED · `1` algún FAILED · `3` algún BLOCKED
  (sin FAILED) · `2` uso/selección vacía (una selección vacía no certifica).

### Contrato de veredicto del certificador (T9 / S3+S4)

El certificador `scripts/verify-from-clean-clone.sh` publica **dos resúmenes con
alcances distintos**. Mezclarlos era el defecto: un `grep` sobre código fuente
nunca equivale a evidencia de ejecución, así que no puede contar en el veredicto
de ejecución.

| Línea | Qué certifica | Quién la consume |
|---|---|---|
| `HARNESS_SUMMARY passed=N expected=M HARNESS_OK` | **Solo checkpoints ejecutados** (10): Gradle, distribución, servidores arrancados, UAT real, backup/restore, MCP round-trip, self-tests | `.github/workflows/release-harness.yml` (regex exacta; el workflow no cambia) |
| `INVENTORY_SUMMARY passed=N expected=M INVENTORY_OK` o `… INVENTORY_FAIL` | **Checkpoints estáticos** (28): documentos que deben enviarse, scripts de operador, declaraciones de manifiesto sin equivalente conductual | Ningún gate de CI; informa y bloquea por exit code |

Reglas del contrato:

- Un inventario verde **no** certifica ejecución (`HARNESS_SUMMARY` no lo cuenta y
  su valor no depende de él). Un inventario incompleto **sí** tumba la corrida
  (exit ≠ 0, `HARNESS_FAIL: release inventory incomplete`), pero lo hace a través
  de `INVENTORY_SUMMARY`, nunca inflando `HARNESS_EXPECTED`.
- Los dos presupuestos están **fijados** (`HARNESS_MIN_EXPECTED=10`,
  `INVENTORY_MIN_EXPECTED=28`): borrar un checkpoint sin actualizar el suelo y el
  catálogo deja el arnés en rojo. Nada desaparece en silencio.
- `HARNESS_INVENTORY_ONLY=1` ejecuta **solo** el inventario (segundos, sin Gradle,
  sin servidores, sin red), imprime su resumen, termina 0/1 según ese veredicto y
  emite `HARNESS_EXECUTION: NOT_RUN`: es un *dry run*, jamás una certificación.
- `on_exit` captura el `$?` real antes del teardown (antes podía reportar una
  salida limpia) y no imprime `HARNESS_SUMMARY … HARNESS_OK` si no se ejecutó el
  bloque de ejecución.

### Clasificación por checkpoint (T9)

Clases: **EJECUCIÓN** (cuenta en `HARNESS_SUMMARY`), **INVENTARIO** (cuenta en
`INVENTORY_SUMMARY`; garantía sin equivalente conductual), **RETIRE** (existía
una comprobación ejecutada que ya la cubre; se retira y se nombra el reemplazo),
**STALE** (fijaba un símbolo/versión obsoleta; se demuestra con `path:line`).

| Checkpoint | Garantía que protege | Clase | Evidencia ejecutada / motivo |
|---|---|---|---|
| 1 `gradlew clean test` | La suite completa pasa | EJECUCIÓN | el propio paso |
| 2 `distTar` | La distribución CLI se construye | EJECUCIÓN | el propio paso |
| 3 `studio start` smoke | `/health`, `/api/version`, `/openapi.json` de la distribución real | EJECUCIÓN | el propio paso (STALE corregido: el launcher se derivaba de `studio-cli-0.1.0-alpha.1`, obsoleto frente a `gradle.properties:studio.version=1.0.0`) |
| 3b UAT studio-app | Una run real alcanza `SUCCEEDED` y produce el marcador sellado | EJECUCIÓN | `uat-from-distribution.sh` + `UAT_VERDICT: PASSED` |
| 4 backup + restore | Backup/restore de SQLite con integridad | EJECUCIÓN | el propio paso |
| 4b `generate-sdk-ts` | El cliente TS generado expone `StudioClient`/`getHealth`/`getRunsRunId` | EJECUCIÓN | el propio paso |
| 4c detekt | Análisis estático real sobre las fuentes enviadas | EJECUCIÓN | `scripts/detekt-run.sh` |
| 5 MCP round-trip | `resources/list` real contra un backend vivo, sin skip silencioso | EJECUCIÓN | el propio paso |
| 6 cert-gate self-test | El arnés no puede declarar READY tras un fallo inyectado | EJECUCIÓN | `scripts/verify-cert-gate.sh` |
| 7 SFW H1 UAT self-test | Disciplina interna del UAT SFW H1 (o el E2E con Podman) | EJECUCIÓN | `uat-sfw-h1-001-fabric-container.sh` |
| 4d manifiesto VS Code | `pipelinek-studio.health` y `pipelinekStudio.baseUrl` declarados | INVENTARIO | código cubierto por `apps/vscode/src/extension.test.ts`; la declaración del manifiesto no tiene equivalente. STALE retirado: `grep StudioClient apps/vscode/src/extension.ts` — el símbolo actual es `StudioApi` (`apps/vscode/src/extension.ts:27`) y el cableado lo prueba `extension.test.ts` con HTTP real |
| 4e Monaco | El SPA monta el editor (componente + dependencia + `App.tsx`) | INVENTARIO | componente cubierto por `apps/web/src/KotlinEditor.test.tsx`; el montaje en `App` y la dependencia no |
| 4f rate limiter | El módulo HTTP de producción instala el limitador (429 + `RATE_LIMITED`) | INVENTARIO | comportamiento del bucket cubierto por `studio-http/.../RateLimitTest.kt`; la instalación en `StudioHttpModule` no |
| 4g sellado de snapshot | El SPA sella la fuente vía `POST /experiments/{id}/snapshots` | INVENTARIO | no hay test a nivel `App.tsx` |
| 4h consumidor SSE | El SPA monta `EventStream` sobre el stream del run | INVENTARIO | componente cubierto por `apps/web/src/EventStream.test.tsx`; el montaje no |
| 4i visor de output | El SPA lee `/output` y renderiza `OUTPUT_REFUSED` | INVENTARIO | componente cubierto por `apps/web/src/OutputViewer.test.tsx`; el montaje no |
| 4k manifiesto empaquetable | El manifiesto VS Code es `vsce package`-ready (campos, comandos, `activate`/`deactivate`) | INVENTARIO | STALE retirado: la regla de ids duplicados de `scripts/vscode-package-check.sh:60,66` cuenta cada `"command":` y reporta como duplicados los ids que `contributes.menus` re-referencia legítimamente (`apps/vscode/package.json:114-134`): observado rc=1 con 4 falsos duplicados. La parte válida se afirma aquí; reparar ese script queda fuera de esta superficie |
| 4l doc netpolicy | Envía el documento con muestras iptables + nftables | INVENTARIO | no hay equivalente |
| 4m doc seccomp | Envía el documento (deny-by-default, AF_UNIX, aplicación) | INVENTARIO | no hay equivalente |
| 4n atajos de teclado | Cmd-S / Cmd-Enter / Escape cableados | **RETIRE** | `apps/web/src/useKeyboardShortcuts.test.ts` (4 atajos + caso negativo), ejecutado por `ci.yml` apps-checks: `apps/web test` (`vitest run`) |
| 4o metadatos VS Code | `icon`, iconos claro/oscuro y `galleryBanner` | INVENTARIO | no hay equivalente |
| 4p doc namespace | Envía el documento (overlay, unshare, drop-caps) | INVENTARIO | no hay equivalente |
| 4q CHANGELOG | Envía `CHANGELOG.md` en formato Keep a Changelog con anclas | INVENTARIO | no hay equivalente (el ancla `0.1.0-alpha.1` es histórica: no prueba que la release actual esté documentada) |
| 4r RELEASE_NOTES | Envía notas con limitaciones conocidas | INVENTARIO | no hay equivalente |
| 4s doc TLS | Envía el documento (nginx, Caddy, keep-alive SSE, mTLS) | INVENTARIO | no hay equivalente |
| 4t CONTRIBUTING | Envía `CONTRIBUTING.md` con la regla no-fake-PASS | INVENTARIO | no hay equivalente |
| 4u harness SPA | Existe un arnés de test SPA ejecutable | **RETIRE** | `ci.yml` apps-checks: `apps/web test`; sin arnés/config/dependencia/ficheros de test `vitest run` sale ≠ 0 |
| 4v MAINTAINERS | Envía `MAINTAINERS.md` con roles y procedimientos | INVENTARIO | no hay equivalente |
| 4w canarios de secretos | Puerto `SecretRedactor`, patrones AKIA/ghp_/`STUDIO_`, noop y cableado en `ResultRenderers` | **RETIRE** | `studio-application/.../SecretRedactorTest.kt` (6 casos, observados `tests=6 failures=0`) + `studio-cli/.../SecretCanaryB53Test.kt`, en `./gradlew test` (paso 1) y `ci.yml` unit-tests |
| 4x SBOM | `scripts/generate-sbom.sh` emite CycloneDX del classpath de `:studio-app` | INVENTARIO | el emisor Gradle `distExtras` sí está cubierto por `CycloneDxB51Test.kt`, pero es otro camino (script de operador) |
| 4y TROUBLESHOOTING + `--version` | Envía el documento de soporte; el CLI mantiene `--version` offline | INVENTARIO | el documento no tiene equivalente |
| 4z checksums | `scripts/generate-checksums.sh` emite SHA-256 del árbol instalado | INVENTARIO | `manifest.json` de Gradle es otro emisor; no cubre el script |
| 4aa UPGRADE.md | Envía la guía con pre-flight, Flyway, rollback y single-writer | INVENTARIO | no hay equivalente |
| 4ab matriz de compatibilidad | Envía la matriz y fija la versión SDK del árbol | INVENTARIO | no hay equivalente (`0.48.0-rc1` coincide hoy con `gradle.properties:pipelinek.version`) |
| 4ac `/api/metrics` | Endpoint servido, documentado en OpenAPI y alcanzable por CLI | INVENTARIO | el endpoint está cubierto por `studio-http/.../MetricsEndpointTest.kt`; la entrada OpenAPI y el subcomando CLI no |
| 4ad script de retención | Dry-run por defecto, `--apply` y solo runs terminales | INVENTARIO | `RetentionServiceTest.kt` cubre el servicio, no el script del operador |
| 4ae `GET /runs` | Ruta + caso de uso + puerto + repositorio + DTO + CLI + OpenAPI | INVENTARIO | ruta/forma cubiertas por `ListRunsEndpointTest.kt` y `OpenApiDocTest.kt`; el subcomando `list-runs` no |
| 4af AAT-SEC-007 | La fuente del snapshot se depura antes de SHA y persistencia | INVENTARIO | `SnapshotRedactionTest.kt` solo afirma que la respuesta no repite el secreto: seguiría pasando sin el redactor (riesgo abierto) |
| 4ag AAT-SEC-005 | Allow-list de Host instalada con defaults loopback y override por entorno | INVENTARIO | el comportamiento está cubierto de forma amplia por `HostAllowListTest.kt`; la línea de instalación y el override no |
| 4ah scripts de sandbox | Envían los 4 scripts de verificación de superficie | INVENTARIO | necesitan podman/características de kernel; no se ejecutan en CI |
| 4ai verify-release | `scripts/verify-release.sh` conserva los invariantes AAT-REL | INVENTARIO | requiere una release real; ejecución de operador |
| 4j (retirado en T2) | El UAT canónico conduce los canales false-PASS | **RETIRE** | `studio-cli/.../UatVerdictTest.kt` |

Resumen de cuentas: **antes** 41 checkpoints etiquetados — 32 `step` que entraban
en `HARNESS_EXPECTED` (10 realmente ejecutados + 22 estáticos) más los 9 bloques
`4aa`..`4ai`, que se imprimían pero **no se contaban en absoluto**; `4j` ya estaba
retirado en T2. **Después** 38 — 10 ejecutados (`HARNESS_SUMMARY`), 28 de
inventario (`INVENTORY_SUMMARY`) y 3 retirados con reemplazo ejecutado (`4n`,
`4u`, `4w`) —: `41 − 3 = 38` y ningún checkpoint deja de estar contado.

### Decisiones de regresión (promote / replace / retire / defer)

| Candidato | Decisión | Garantía equivalente |
|---|---|---|
| Pasos 4n, 4u, 4w de `verify-from-clean-clone.sh` | **RETIRE** | `apps/web test` (ci.yml apps-checks) y `./gradlew test` (paso 1): shortcuts, arnés SPA y redactor de secretos |
| Pasos 4d (`StudioClient`) y 4k (ids duplicados) — aserciones obsoletas | **RETIRE (STALE)** | `apps/vscode/src/extension.test.ts` para el cableado; contrato de manifiesto afirmado inline en 4k |
| Resto de checkpoints estáticos (4d..4z, 4aa..4ai) | **DEFER** (siguen como INVENTARIO) | No se ha demostrado cobertura conductual equivalente; siguen bloqueando la release desde `INVENTORY_SUMMARY` |
| Flujo runtime duplicado en `e2e-host.sh` | **REPLACE** | Una sola implementación (UAT canónico) + wrapper con `doctor` |
| Duplicado anidado Podman + sleep ciego en `run-all-uats.sh` | **RETIRE** | Agregador único con propagación de FAILED/BLOCKED |
| `UatVerdictTest` (curl real contra un `HttpServer` JDK en 127.0.0.1:0) | **PROMOTE** (regresión permanente) | Cubre cada canal false-PASS (refusal, sin `run_id`, estado no exitoso, timeout, servidor colgado, marcador ausente, refusal de output, status no documentado), el payload público, la selección del agregador y ahora el contrato de los dos resúmenes; es fixture de lógica de veredicto y **no** acredita ejecución real ni Fabric |
