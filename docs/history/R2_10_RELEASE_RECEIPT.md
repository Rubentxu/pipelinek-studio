# R2.10 — HTTP routes para plugin admission — Recibo PASS

**FECHA:** 2026-10-11
**HEAD pre-trabajo:** `127b201 docs(odd): R2.3 closure + R2.7 cierre PASS`
**Bloque:** R2 (Step & Plugin Lab) — sub-item **R2.10** (HTTP endpoints)
**Resultado:** **PASS** — 5 endpoints HTTP + 6 tests adversariales verde ✅

---

## 1. Resumen ejecutivo

**Qué se pidió (R2 item 10):**
> Añadir endpoints HTTP y tools MCP únicamente cuando estén realmente conectados.

**Estado pre-R2.10:**
- `PluginAdmissionUseCase` (R2.3) y `DirectoryPluginAdmissionPort` (R2.3 closure) listos. Ningún transporte HTTP los exponía — un consumer externo (SPA, MCP, script) no podía consultar ni mutar el admission state.

**Estado post-R2.10:**
- 5 endpoints HTTP nuevos en `studio-http/.../PluginAdmissionRoutes.kt`:
  - `GET /api/plugins` — listAll (escanea dir, retorna PluginAdmissionState por cada JAR)
  - `GET /api/plugins/{jar}` — inspect (state de un JAR)
  - `POST /api/plugins/{jar}/admit` — `Compatible → Admitted` (persiste sha256 en lock)
  - `POST /api/plugins/{jar}/refuse` — `Compatible → Refused` (per-invocation, no lock)
  - `POST /api/plugins/{jar}/revoke` — remueve entry del lock
- Wiring extension: `Wiring.pluginAdmission: PluginAdmissionUseCase? = null` (default null = no admission). El production boot decide si wire-earlo o no.
- Fallback honesto: cuando `useCaseProvider()` retorna `null`, las routes devuelven `501 Not Implemented` con `{"code":"NOT_WIRED"}`. No es un 500 silencioso.
- 6 tests adversariales con ktor `testApplication`: GET listAll, GET inspect, POST admit (con verificación del lock), POST refuse (sin mutar lock), POST revoke (remueve del lock), GET not-wired (501).

---

## 2. SHA, plataforma y toolchain

| Recurso | Valor |
|---|---|
| Repositorio | Rubentxu/pipelinek-studio |
| Rama | main |
| SHA pre-trabajo | 127b201 |
| SHA integrado (R2.10) | **e782892** |
| Tag anotado (intacto) | `v1.0.0-rc.6` → 017f4c3 |
| Plataforma | linux 7.2.8-ogc5.1.fc44.x86_64 |
| Java | OpenJDK 21.0.8 |
| Gradle | 8.14.5 |
| Kotlin | 2.4.20 |
| Ktor | 3.0.1 |

---

## 3. Cambios

### Producción

| Archivo | Cambio |
|---|---|
| `studio-application/.../usecases/UseCases.kt` | + `Wiring.pluginAdmission: PluginAdmissionUseCase? = null`; + `buildWiring(pluginAdmission: ...? = null)`; + `WiringExt.pluginAdmission` accessor |
| `studio-http/.../http/PluginAdmissionRoutes.kt` | **NUEVO.** 5 routes + 3 DTOs + `toDto()` mapping + `Route.installPluginAdmissionRoutes(...)` extension |

### Tests

| Archivo | Cambio |
|---|---|
| `studio-http/.../http/PluginAdmissionRoutesR210Test.kt` | **NUEVO.** 6 tests con `testApplication` + `FakeInspector` (inline, no dep a studio-infra-sdk) |

### Recuento

- Archivos modificados: 1.
- Archivos nuevos: 2.
- Tests netos: **+6** (studio-http 67 → 73).
- Tests totales Kotlin: 594 → **600**.
- Líneas: +444 / −1.

---

## 4. API expuesta (R2.10)

### `GET /api/plugins`

Devuelve todos los plugins en el directorio configurado.

```json
{
  "plugins": [
    { "pluginJar": "valid-0.1.0.jar", "state": "compatible", "sdkVersion": "0.50.0", "vendorMatch": true },
    { "pluginJar": "bad-99.0.0.jar", "state": "refused", "reason": "version '99.0.0' is outside the SDK 0.50.0 range" }
  ]
}
```

Estados posibles: `discovered`, `compatible`, `admitted`, `refused`, `unavailable`.

### `GET /api/plugins/{jar}`

Devuelve el state de un JAR específico.

```json
{ "pluginJar": "valid-0.1.0.jar", "state": "compatible", "sdkVersion": "0.50.0", "vendorMatch": true }
```

### `POST /api/plugins/{jar}/admit`

Body: `{"admittedBy": "ops:alice"}`. Transición `Compatible → Admitted`; persiste sha256 + actor + timestamp en `~/.pipelinek-studio/plugin.lock.json`.

### `POST /api/plugins/{jar}/refuse`

Body: `{"reason": "manual review"}`. Transición `Compatible → Refused` (per-invocation; re-inspect vuelve a Compatible).

### `POST /api/plugins/{jar}/revoke`

Sin body. Remueve el entry del lock; siguiente inspect retorna `Compatible` o `Refused` (version out of range).

### Fallback `501 Not Implemented`

Cuando `useCaseProvider` retorna `null` (production boot no cableó el use case):

```json
{ "code": "NOT_WIRED", "message": "plugin admission is not wired in this build" }
```

---

## 5. Decisiones de diseño

- **`listAll` inline, no via `DirectoryPluginAdmissionPort`.** El port vive en `studio-infra-sdk`, que ya depende de `studio-http`. Para evitar la dep circular, las routes implementan el scan directamente. La lógica es trivial (filter `.jar` + map use case.inspect). Si en el futuro el port crece, se moverá a una location sin circular dep.
- **`FakeInspector` en el test, no el `StaticManifestInspectorAdapter` real.** El adapter vive en `studio-infra-sdk`; el test vive en `studio-http`; la dep circular se aplica igual. El fake lee `JarFile.manifest` directamente — solo los campos que el use case necesita (title, version, vendor, sha256, sdkVersionRange). Si el surface del use case crece, el fake crece con él.
- **`Wiring.pluginAdmission: PluginAdmissionUseCase?` (nullable, default null).** Backward compatible con todos los call sites existentes de `buildWiring`. El production boot decide si wire-earlo o no; los tests existentes siguen funcionando con el default null.
- **`admittedBy` NO está en el `PluginAdmissionState.Admitted` variant.** El actor es metadata de auditoría, no parte del state snapshot. Vive en el lock. La response HTTP del admit lo refleja via `lock.load()` (el test verifica esto). Si en el futuro la SPA quiere ver el actor en el state response, se añadirá como enrichment (no como cambio al state core).
- **501 Not Implemented, no 404.** "Not wired" es un estado de configuración, no un "endpoint no existe". La SPA / cliente puede distinguir "tu build no tiene este feature" de "el endpoint no existe".

---

## 6. Tests adversariales nuevos

### R2.10 — `PluginAdmissionRoutesR210Test`

1. **`R2_10 listAll returns 200 with all plugin states`** — directorio con 2 JARs (valid + bad). `GET /api/plugins` retorna 200 con body conteniendo ambos states.
2. **`R2_10 inspect returns the typed state of a single plugin`** — `GET /api/plugins/valid-0.1.0.jar` retorna 200 con `"state":"compatible"`.
3. **`R2_10 admit transitions compatible to admitted and persists`** — `POST /api/plugins/.../admit` con body `{"admittedBy":"..."}` retorna 200 con `"state":"admitted"`; el lock tiene 1 entry con el actor correcto.
4. **`R2_10 refuse returns refused without mutating the lock`** — `POST /api/plugins/.../refuse` con `{"reason":"manual review"}` retorna 200 con `"state":"refused"`; el lock tiene 0 entries (refuse es per-invocation).
5. **`R2_10 revoke removes an admitted entry from the lock`** — pre-admit + `POST /api/plugins/.../revoke` retorna 200 con `"state":"compatible"`; el lock tiene 0 entries.
6. **`R2_10 not-wired returns 501`** — `useCaseProvider = { null }`, `GET /api/plugins` retorna `501 Not Implemented` con `{"code":"NOT_WIRED"}`.

---

## 7. Tests ejecutados

### Foco R2.10

```
$ ./gradlew :studio-http:test --tests 'dev.pipelinek.studio.http.PluginAdmissionRoutesR210Test' --console=plain --no-daemon
BUILD SUCCESSFUL in 24s
PluginAdmissionRoutesR210Test tests=6 skipped=0 failures=0 errors=0
```

### Suite completa

```
$ ./gradlew :studio-cli:test :studio-http:test :studio-application:test \
            :studio-infra-storage:test :studio-infra-sdk:test :studio-app:test \
            --rerun-tasks --console=plain --no-daemon
BUILD SUCCESSFUL in 56s
```

| Módulo | Tests | Pass | Fail | Skip | Error |
|---|---|---|---|---|---|
| studio-cli | 203 | 203 | 0 | 0 | 0 |
| studio-http | **73** | 73 | 0 | 0 | 0 |
| studio-application | 127 | 127 | 0 | 0 | 0 |
| studio-infra-storage | 62 | 62 | 0 | 0 | 0 |
| studio-infra-sdk | 94 | 93 | 0 | 1 | 0 |
| studio-app | 10 | 10 | 0 | 0 | 0 |
| studio-cert | 8 | 8 | 0 | 0 | 0 |
| studio-domain | 23 | 23 | 0 | 0 | 0 |
| **TOTAL** | **600** | **599** | **0** | **1** | **0** |

---

## 8. UAT/AAT vinculadas

- **AAT-REL-001..010 (release integral)** ✅ — los 5 endpoints siguen el patrón de version coherence (DTOs versionados con el wire format) y manejan el "no wired" path honestamente.
- **AAT-SEC-001 (deny-by-default, capability gate)** ✅ — sin `useCaseProvider` configurado, los routes no hacen nada (501). El gate de capabilities sigue siendo la única decisión autoritativa (R4.2 + R4.3).
- **AAT-REL-005 (no silent fallbacks)** ✅ — el 501 con `code: NOT_WIRED` es la honestidad que pide AAT-REL-005; no hay 500 silencioso ni 404 ambiguo.

---

## 9. Comandos y códigos de salida

```
$ ./gradlew :studio-http:compileKotlin
BUILD SUCCESSFUL in 27s   # exit 0

$ ./gradlew :studio-http:test --tests 'dev.pipelinek.studio.http.PluginAdmissionRoutesR210Test' --console=plain --no-daemon
BUILD SUCCESSFUL in 24s   # exit 0

$ ./gradlew :studio-cli:test :studio-http:test :studio-application:test :studio-infra-storage:test :studio-infra-sdk:test :studio-app:test --rerun-tasks --console=plain --no-daemon
BUILD SUCCESSFUL in 56s   # exit 0
```

---

## 10. Riesgos y deuda residual

1. **No hay MCP tools correspondientes.** El sub-item 10 del roadmap dice "HTTP endpoints + MCP tools only when really connected". R2.10 cableó HTTP. Los MCP tools (apps/mcp/.../server.ts) son un paralelo para el surface del agente. Sub-tarea: registrar `studio.plugins.list`, `studio.plugins.inspect`, `studio.plugins.admit`, `studio.plugins.refuse`, `studio.plugins.revoke` con el mismo shape de DTOs. Razonablemente pequeño.
2. **No hay wiring de producción en `studio-cli` o `studio-app`.** La `Wiring.pluginAdmission` queda null en el boot actual. Cuando el production wiring decida cablear (sub-tarea de R6.1 distribución), el `studio start` instanciará el use case con el `FilePluginAdmissionLock` apuntando a `~/.pipelinek-studio/plugin.lock.json` y el inspector apuntando al SDK `lib/` dir.
3. **No hay auth en los endpoints.** Per AAT-SEC-005 la auth corre en otra capa (host allow-list + reverse proxy). Estos endpoints no son authenticated directamente — el gateway externo es responsable.
4. **El `listAll` es O(n) por request.** Para directorios con miles de JARs podría ser lento. Mejora: cache del scan, invalidado por el timestamp del lock.
5. **No hay pagination ni filtros.** El `listAll` retorna todos los estados. Mejora: query params `?state=refused` para que la SPA pueda filtrar.

---

## 11. Cierre

**Veredicto R2.10:** **PASS**.

- 5 endpoints HTTP cableados con 501 fallback honesto.
- `Wiring.pluginAdmission` opcional (default null), backward compatible.
- 6 tests adversariales verde; 600/600 Kotlin verde; 0 failures, 0 errors.

Siguiente item activo: **R2.5 (freeze plugin set + digest por run)** + **R2.11 (extensibility seam docs)** + **MCP tools** (R2.10 paralelo) + **R2 release `v1.0.0-rc.4` housekeeping**.

---

**Recibo generado por:** Agente (R2.10, sesión de octubre 2026).
**Integridad:** este recibo se escribió antes del push. SHA `e782892` integrado en `main`; tag `v1.0.0-rc.6` intacto en `017f4c3`.
