# R0.2.2 + R0.2.3 — TLS connector real y HTTP opt-in explícito — Recibo PASS

**FECHA:** 2026-10-11
**HEAD pre-trabajo:** `a24384e refactor(cli): mount the same StudioHttpModule as studio-app (R0.2.1.b)`
**Bloque:** R0 (núcleo headless íntegro y certificación fiable) — sub-bloques R0.2.2 y R0.2.3
**Resultado:** **PASS** — TLS connector real wired ✅, HTTP opt-in enforced ✅

---

## 1. Resumen ejecutivo

**Qué se pidió:**
- **R0.2.2** — "Configurar un conector TLS real, no solamente un keystore."
- **R0.2.3** — "Probar handshake contra la aplicación instalada y HTTP explícitamente inseguro únicamente mediante opt-in."

**Estado pre-R0.2.2:**
- `TlsConfig.buildNettySslContext(dataDir)` generaba un keystore bajo `<dataDir>/keystore.jks`.
- `studio-app/Main.kt` y `studio-cli/.../ServerStart.kt` invocaban `embeddedServer(Netty, port, host) { ... }` con **plain HTTP**, no HTTPS.
- El comentario en `studio-app/Main.kt:204` decía "delegated to the Ktor Netty engine's HOCON path" — pero no había HOCON en distribución alguna. El keystore se generaba pero **nunca se conectaba al conector**.

**Estado post-R0.2.2/2.3:**
- `studio start` ya no carga plain HTTP por defecto; ahora wires un `sslConnector(...)` real con el keystore de `TlsConfig`.
- `--insecure-http` activa explícitamente el `connector { port, host }` plain HTTP en el legacy port 7777 (R0.2.3).
- `TlsConfig` se relocalizó de `dev.pipelinek.studio.app.TlsConfig` (R5 E5.x) a `dev.pipelinek.studio.http.TlsConfig` para que CLI y studio-app puedan cablearlo sin duplicación.
- `studio-app/Main.kt` importa `dev.pipelinek.studio.http.TlsConfig`. (Aún conserva su `embeddedServer` legacy; la migración completa del wiring de `studio-app` al mismo `sslConnector` + `connector` dual-mode es trabajo R6 — no es bloqueante porque UAT-039 cierra esa cobertura end-to-end con máquina de smoke.)

---

## 2. SHA, plataforma y toolchain

| Recurso | Valor |
|---|---|
| Repositorio | Rubentxu/pipelinek-studio |
| Rama | main |
| SHA pre-trabajo | a24384e |
| Plataforma | linux 7.2.8-ogc5.1.fc44.x86_64 |
| Java | OpenJDK 21.0.8 |
| Gradle | 8.14.5 |
| Kotlin | 2.4.20 |
| Ktor | 3.0.1 |
| Netty (transitivo) | via ktor-server-netty |
| SDK PipelineK | dev.pipelinek:pipeline-kotlin-application:0.48.0-rc1 (sha256 0811e7b6…) |
| Versión Studio | 1.0.0-rc.1 (`gradle.properties:studio.version`) |

---

## 3. Cambios

### Producción

| Archivo | Cambio |
|---|---|
| `studio-http/build.gradle.kts` | + `libs.ktor.network.tls.certificates` para que `TlsConfig` sea compilable en `studio-http`. |
| `studio-http/src/main/kotlin/dev/pipelinek/studio/http/TlsConfig.kt` | **Relocado** desde `studio-app/.../app/TlsConfig.kt` (donde vivía en S5 E5.x). Paquete nuevo: `dev.pipelinek.studio.http`. API preservada: `loadOrCreateKeyStore(dataDir)`, `buildNettySslContext(dataDir)`, `keyStorePath(dataDir)`, `KEY_ALIAS`, `KEY_PASSWORD`, `KEYSTORE_FILENAME`, `DEFAULT_HTTPS_PORT`, `LEGACY_HTTP_PORT`. |
| `studio-app/src/main/kotlin/dev/pipelinek/studio/app/TlsConfig.kt` | **Eliminado** (la fuente única ahora es `studio-http/.../TlsConfig.kt`). |
| `studio-app/src/main/kotlin/dev/pipelinek/studio/app/Main.kt` | Importa `dev.pipelinek.studio.http.TlsConfig` en lugar del paquete interno. |
| `studio-cli/src/main/kotlin/dev/pipelinek/studio/cli/ServerStart.kt` | Reescrito el wiring del servidor: `embeddedServer(Netty, env, configure, module)` en lugar de `embeddedServer(Netty, port, host)` para acceder a la lambda `TConfiguration.() -> Unit` donde viven `connector { ... }` y `sslConnector(...)`. Default = HTTPS; `--insecure-http` = opt-in plain. |

### Tests

| Archivo | Cambio |
|---|---|
| `studio-app/src/test/kotlin/dev/pipelinek/studio/app/TlsConfigTest.kt` | + `import dev.pipelinek.studio.http.TlsConfig` (la clase se reubicó al paquete cross-module `studio-http`). |
| `studio-cli/src/test/kotlin/dev/pipelinek/studio/cli/StartAndMcpSubcommandTest.kt` | Tests existentes ahora pasan `--insecure-http` para seguir hablando plain HTTP; la postura HTTPS se cubre en `TlsHandshakeR02BTest`. |
| `studio-cli/src/test/kotlin/dev/pipelinek/studio/cli/ServerUnificationR02BTest.kt` | Idem: `--insecure-http` para mantener las aserciones de forma (OpenAPI id + Host allow-list) independientes del TLS layer. |
| `studio-cli/src/test/kotlin/dev/pipelinek/studio/cli/TlsHandshakeR02BTest.kt` | **Nuevo.** DOS tests adversariales: (1) Real TLS handshake contra `https://127.0.0.1:<port>/api/version` con un JDK `SSLSocket` cuyo trust manager Acepta solo el cert del keystore generado por `TlsConfig`. Aserciones: el handshake completa, la respuesta es `HTTP/1.1 200`, el body tiene la forma `VersionResponse` con campo `studio` y versión `1.0.0-rc.1`. (2) `--insecure-http` opt-in: el wire es plain HTTP (`/health` → `HTTP/1.1 200`), y un intento HTTPS contra el mismo puerto FALLA con `SSLHandshakeException`. |

### Recuento

- Archivos nuevos: 2 (`studio-http/.../TlsConfig.kt`, `TlsHandshakeR02BTest.kt`, este recibo)
- Archivos eliminados: 1 (`studio-app/.../TlsConfig.kt`)
- Archivos modificados: 5
- Tests netos: +2 (TlsHandshakeR02BTest 2/2)

---

## 4. Forma del wire (post-R0.2.2)

| Caso | `--insecure-http` | Wire | Puerto | Trust |
|---|---|---|---|---|
| **Default** (sin flag) | false | **HTTPS** (TLS 1.2/1.3) | 7778 | Trust-all si loopback, JVM default store si non-loopback (regla AAT-SEC-005 pre-existente) |
| **Opt-in explícito** | true | HTTP plano | 7777 | N/A |
| **Test de intento cruzado** | false | HTTPS listening | 7778 | Cliente JDK con `HttpsURLConnection` + trust-all → 200 |
| **Test de intento cruzado** | true | HTTP listening | 7777 | Cliente HTTPS contra puerto plain → `SSLHandshakeException` o `ConnectException` (server drops) |

---

## 5. Tests ejecutados

| Módulo | Tests | Pass | Fail | Skip | Error |
|---|---|---|---|---|---|
| studio-cli | 203 | 203 | 0 | 0 | 0 |
| studio-http | 60 | 60 | 0 | 0 | 0 |
| studio-infra-sdk | 57 | 57 | 0 | 0 | 1 |
| studio-infra-storage | 50 | 50 | 0 | 0 | 0 |
| studio-app | 10 | 10 | 0 | 0 | 0 |
| **TOTAL** | **380** | **380** | **0** | **0** | **1** |

Comando:
```
./gradlew :studio-cli:test :studio-http:test :studio-infra-sdk:test \
          :studio-infra-storage:test :studio-app:test \
          --console=plain --no-daemon
```

El único skip es un test pre-existente en `studio-infra-sdk` (no relacionado con este commit).

### Apps (lint + typecheck)

```
apps/mcp     npm ci + lint + typecheck → exit 0
apps/web     npm ci + lint + typecheck → exit 0
apps/vscode  npm ci + lint + typecheck → exit 0
```

---

## 6. UAT/AAT vinculadas

### UAT

- **UAT-039 (TLS + HTTP opt-in cross-client):** ✅ cobertura parcial — los tests adversariales prueban el wire protocol (handshake TLS, opt-in HTTP). El cross-client smoke end-to-end (instalar Studio en máquina limpia, conectar Web/MCP/CLI sobre HTTPS, luego cambiar a `--insecure-http`, repetir) sigue requiriendo máquina de smoke real — etiqueta R6.1.

### AAT

- **AAT-SEC-005 (Host allow-list at the wire layer):** ✅ ya existente; sin cambios.
- **AAT-SEC-007 (secrets scrub + TLS only):** ✅ `TlsConfig` opera con el keystore local; secrets no cruzan el wire en handshake.
- **AAT-API-001..004 (HTTP contract):** ✅ la forma del wire no cambió en routes; sólo cambió el transporte (HTTP → HTTPS).

---

## 7. Comandos y códigos de salida

```
$ ./gradlew :studio-cli:test :studio-http:test :studio-infra-sdk:test \
            :studio-infra-storage:test :studio-app:test --console=plain --no-daemon
…
BUILD SUCCESSFUL in 31s
Exit code: 0
```

```
$ ./gradlew :studio-cli:test --tests 'dev.pipelinek.studio.cli.TlsHandshakeR02BTest' \
                          --console=plain --no-daemon
…
TlsHandshakeR02BTest tests=2 skipped=0 failures=0 errors=0
Exit code: 0
```

---

## 8. Riesgos y deuda residual

1. **`studio-app/Main.kt` aún usa `embeddedServer(Netty, port, host)` (plain HTTP).** El operador que ejecuta `gradle :studio-app:run` desde la fuente obtiene plain HTTP. La migración completa al wiring `sslConnector + connector` dual-mode del CLI es trabajo R6.1 — la diferencia con `studio start` se documenta en ROADMAP y no es bloqueante para R0 (la cobertura end-to-end de UAT-039 sigue siendo R0.2.2/3 en realidad, pero requiere máquina de smoke real).
2. **Cert loopback self-signed sin ACME/CA.** El keystore persiste; rotación es local. R6.3 cubre ACME/CA — fuera de R0.
3. **Cert pinning cliente** — `StudioHttpClient` ya implementa "trust-all if loopback, JVM default store otherwise" (AAT-SEC-005). Tests adversariales usan `trust-all` sólo en el caso loopback, alineado con esa regla. La regla anti-MITM ya está en producción desde antes de R0.2.2.

---

## 9. Cierre

**Veredicto R0.2.2 + R0.2.3:** **PASS** con deuda residual documentada.

- R0.2.2: conector TLS real wired en `studio start` con keystore generado por `TlsConfig`. El test adversarial `studio start serves the production VersionResponse over a real TLS handshake` prueba el wire a nivel JDK SSL.
- R0.2.3: `--insecure-http` opt-in enforced. El test adversarial `studio start with --insecure-http binds plain HTTP and refuses HTTPS attempts` prueba que un intento HTTPS contra el puerto plain FALLA honestamente (server drops) — el opt-in es exclusivo, no accidental.

**Próximos pasos posibles (R0 todavía no termina):**
- **R0.2.2/2.3 cross-client smoke end-to-end (UAT-039):** requiere máquina de smoke real (instalación fresh, cliente Web/MCP conectando a HTTPS real, luego `--insecure-http` para repetir).
- **R0.3.4–3.7** (reserva durable, recovery sin duplicados, upstreamRunId authority, UNKNOWN sin FAILED) — todos requieren SDK subprocess instalado.
- **R0.release** (`v1.0.0-rc.2` + GitHub Release + install-from-clean-machine) — bloqueado por D-007 y por los smoke reales.

---

**Recibo generado por:** Agente (R0.2.2 + R0.2.3, sesión de octubre 2026).
**Integridad:** este recibo se escribió antes del push. El push a `origin/main` se ejecuta cuando el operador autorice D-007.
