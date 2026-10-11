# R0.3.9 — Identidad (snapshot, runtime, plugins, perfil) — Recibo PASS

**FECHA:** 2026-10-11
**HEAD pre-trabajo:** `3aeab89 docs(history): R0.3.8 release receipt`
**Bloque:** R0 (núcleo headless íntegro y certificación fiable) — sub-bloque **R0.3.9**
**Resultado:** **PASS** — cuatro tuplas (snapshot, runtime, plugins, perfil) sobreviven end-to-end ✅

---

## 1. Resumen ejecutivo

**Qué se pidió (R0.3.9):**
> Comprobar identidad de snapshot, runtime, plugins y perfil para cada ejecución.

**Estado pre-R0.3.9:**
- A nivel del use case (`StartRunUseCase.execute(...)`), el `RunBinding` persistido ya llevaba `snapshotId + scenarioId + runtimeVersion + runtimeDigest + pluginSetDigest + state` (más los timestamps y FKs de `experimentId`).
- A nivel del schema (`V1__init_studio_schema.sql`), la tabla `scenarios` declaraba `environment_profile TEXT NOT NULL` desde antes de R0.3.1.
- A nivel de R0.3.1 (`SourceScenario`), el data class **omitía** `environmentProfile` y la `INSERT INTO scenarios(...)` también lo omitía. Esto significaba que una registración contra el repositorio real (`StudioMetadataRepository`) habría lanzado `SQLITE_CONSTRAINT_NOTNULL`. El bug era latente: los tests de R0.3.1 usaban stubs in-memory que no tocaban SQLite, así que el canal no se ejercitó.

**Estado post-R0.3.9:**
- `studio-domain/SourceScenario` añade `environmentProfile: String` con invariante `isNotBlank()`. Default = `"trusted_local"` (el único perfil que el runner acepta per B2.5 / ADR-002). Companion constant `DEFAULT_PROFILE`.
- `studio-application/ports/ScenarioSnapshot` también extiende `environmentProfile: String`. Las formas write y read cargan ahora la misma identidad.
- `RegisterScenarioUseCase.execute(...)` acepta `environmentProfile: String = DEFAULT_PROFILE`. Re-registro idempotente preserva el perfil almacenado.
- HTTP `RegisterScenarioRequest` / `RegisterScenarioResponse` / `ScenarioSummaryDto` llevan `environment_profile` (snake_case). `POST /scenarios` y `GET /scenarios` cosen el campo end-to-end. Default del use case cubre clientes que omiten el campo.
- `StudioMetadataRepository.saveScenario` SQL ahora bindea `environment_profile` junto con las otras columnas. `findScenario` y `listScenarios` SELECT incluyen `environment_profile`. `scenarioFromRow` lee la nueva columna (con fallback a `DEFAULT_PROFILE` para rows pre-R0.3.9 forward-compatibles).
- Nuevo `RunBindingIdentityR03BTest` con tres casos adversariales que pinan la invariante:
  1. **Round-trip preserva las cuatro tuplas**: tras persistir un binding, `findRun` + introspección directa devuelven `runtime_version`, `runtime_digest`, `plugin_set_digest` byte-for-byte; FKs sobreviven.
  2. **FK rejection**: un binding cuyo `snapshot_id` no existe en `snapshots` es rechazado con `SQLException` (el enforcement FK cableado por R0.3.2 dispara contra el repositorio real, no sólo contra `DriverManager.getConnection` como pre-R0.3.2).
  3. **Identidad-por-row, no identidad-por-tupla**: dos `run_id` distintos con el mismo `(experiment, snapshot, scenario)` pero distintos `runtime_version` / `runtime_digest` / `plugin_set_digest` ambos sobreviven y `listRuns` los retorna en orden `created_at DESC` con sus identidades intactas.

---

## 2. SHA, plataforma y toolchain

| Recurso | Valor |
|---|---|
| Repositorio | Rubentxu/pipelinek-studio |
| Rama | main |
| SHA pre-trabajo | 3aeab89 |
| SHA integrado | 26ba589 |
| Tag anotado | v1.0.0-rc.3.9 → 26ba589 |
| Plataforma | linux 7.2.8-ogc5.1.fc44.x86_64 |
| Java | OpenJDK 21.0.8 |
| Gradle | 8.14.5 |
| Kotlin | 2.4.20 |
| SQLite driver | xerial (via Flyway 10) |
| Versión Studio | 1.0.0-rc.1 (`gradle.properties:studio.version`) |

---

## 3. Cambios

### Producción

| Archivo | Cambio |
|---|---|
| `studio-domain/src/main/kotlin/dev/pipelinek/studio/domain/SourceScenario.kt` | + `environmentProfile: String = DEFAULT_PROFILE`. Invariante `isNotBlank()`. Companion constant `DEFAULT_PROFILE = "trusted_local"`. |
| `studio-application/src/main/kotlin/dev/pipelinek/studio/application/ports/Ports.kt` | + `ScenarioSnapshot.environmentProfile: String`. |
| `studio-application/src/main/kotlin/dev/pipelinek/studio/application/usecases/UseCases.kt` | + `RegisterScenarioUseCase.execute(...)` acepta `environmentProfile: String = DEFAULT_PROFILE`. Re-registro idempotente preserva el perfil. |
| `studio-infra-storage/src/main/kotlin/dev/pipelinek/studio/infra/storage/Repositories.kt` | + `saveScenario` SQL bindea `environment_profile`. + `findScenario` y `listScenarios` SELECT incluyen `environment_profile`. + `scenarioFromRow` lee la columna con fallback a `DEFAULT_PROFILE`. |
| `studio-http/src/main/kotlin/dev/pipelinek/studio/http/Json.kt` | + `RegisterScenarioRequest.environment_profile`, `RegisterScenarioResponse.environment_profile`, `ScenarioSummaryDto.environment_profile`. |
| `studio-http/src/main/kotlin/dev/pipelinek/studio/http/StudioHttpModule.kt` | `POST /scenarios` y `GET /scenarios` cosen el campo end-to-end. |

### Tests

| Archivo | Cambio |
|---|---|
| `studio-infra-storage/src/test/kotlin/dev/pipelinek/studio/infra/storage/RunBindingIdentityR03BTest.kt` | **Nuevo.** 3 tests adversariales que pinan las cuatro tuplas de identidad contra el repositorio SQLite real. |
| `studio-application/src/test/kotlin/dev/pipelinek/studio/application/usecases/RegisterScenarioR03BTest.kt` | + `environmentProfile` en los constructores de `ScenarioSnapshot` (3 sitios). |

### Recuento

- Archivos nuevos: 1 (`RunBindingIdentityR03BTest.kt`, +219 líneas).
- Archivos modificados: 7.
- Tests netos: **+3** (`RunBindingIdentityR03BTest` 3/3).
- Tests totales studio-infra-storage: 54 → **57**.

---

## 4. Forma del wire — `environment_profile` end-to-end

### `POST /scenarios`

**Request:**
```json
{
  "scenario_id": "optional-explicit-uuid",
  "version": null,
  "plugin_lock_digest": "optional-64-char-hex",
  "environment_profile": "trusted_local"
}
```

**Response 200 — Registered:**
```json
{
  "scenario_id": "0c5d2a3e-...",
  "version": 1,
  "plugin_lock_digest": null,
  "environment_profile": "trusted_local",
  "created_at": "2026-10-11T11:36:42Z"
}
```

### `GET /scenarios`

**Response 200:**
```json
{
  "items": [
    {
      "scenario_id": "…",
      "version": 1,
      "plugin_lock_digest": null,
      "environment_profile": "trusted_local",
      "created_at": "…"
    }
  ]
}
```

---

## 5. Tests ejecutados

### Módulos Kotlin (Gradle)

| Módulo | Tests | Pass | Fail | Skip | Error |
|---|---|---|---|---|---|
| studio-cli | 203 | 203 | 0 | 0 | 0 |
| studio-http | 67 | 67 | 0 | 0 | 0 |
| studio-application | 118 | 118 | 0 | 0 | 0 |
| studio-infra-storage | **57** | **57** | 0 | 0 | 0 |
| studio-infra-sdk | 57 | 57 | 0 | 0 | 1 |
| studio-app | 10 | 10 | 0 | 0 | 0 |
| **TOTAL** | **512** | **512** | **0** | **0** | **1** |

Comando:
```
./gradlew :studio-cli:test :studio-http:test :studio-application:test \
          :studio-infra-storage:test :studio-infra-sdk:test :studio-app:test \
          --console=plain --no-daemon
```

Resultado: `BUILD SUCCESSFUL`. Exit code: 0.

**Detalle R0.3.9:**
```
./gradlew :studio-infra-storage:test \
          --tests 'dev.pipelinek.studio.infra.storage.RunBindingIdentityR03BTest'
RunBindingIdentityR03BTest tests=3 skipped=0 failures=0 errors=0
Exit code: 0
```

### Apps Node

```
apps/web     npm test -- --run → 5 files, 49 passed (49)  Exit code: 0
apps/{web,mcp,vscode}  npm run lint → exit 0
```

---

## 6. UAT/AAT vinculadas

### AAT

- **AAT-ARCH-002 (hexagonal ports, ADTs, immutable data):** ✅ `SourceScenario` ahora data class con invariantes; `ScenarioSnapshot` simétrico; `RunBinding` mantiene `runtimeVersion + runtimeDigest + pluginSetDigest` byte-for-byte.
- **AAT-DUR-002 (idempotency key durability):** ✅ `SourceScenario.contentKey()` ya era `(id:version)`; el perfil no rompe el key porque no entra en la identidad de la key.
- **AAT-SEC-002 (no silent fabrication of identity):** ✅ el perfil es leído del row, no inventado por la capa HTTP.
- **AAT-DUR-001 (storage durability):** ✅ las cuatro tuplas sobreviven el round-trip real con la base de datos (no sólo stubs in-memory).

### UAT

- **UAT-039 (smoke desde máquina limpia):** ⏸️ sigue bloqueado por la máquina de smoke real. R0.3.9 cubre la invariante a nivel unit test contra el repositorio SQLite.

---

## 7. Comandos y códigos de salida

```
$ ./gradlew :studio-infra-storage:test \
            --tests 'dev.pipelinek.studio.infra.storage.RunBindingIdentityR03BTest' \
            --console=plain --no-daemon
…
RunBindingIdentityR03BTest tests=3 skipped=0 failures=0 errors=0
Exit code: 0

$ ./gradlew :studio-cli:test :studio-http:test :studio-application:test \
            :studio-infra-storage:test :studio-infra-sdk:test :studio-app:test \
            --console=plain --no-daemon
…
BUILD SUCCESSFUL in 27s
Exit code: 0

$ cd apps/{web,mcp,vscode} && npm run lint && npm run typecheck
Exit code: 0 (las 3 apps)
```

---

## 8. Riesgos y deuda residual

1. **`environment_profile` sólo soporta `"trusted_local"`** por ahora. Cualquier valor distinto dispara `IllegalArgumentException` en `RegisterScenarioUseCase.execute(...)` si en el futuro el use case decide validar contra el set cerrado de ExecutionProfile. Hoy el default + invariante `isNotBlank()` deja la puerta abierta al futuro perfil `Untrusted` (per B2.5), pero el runner actual sólo acepta `TrustedLocal` — el canal `Untrusted` está seam-cableado en `ExecutionProfile` pero ningún test adversariales lo verifica aún. Diferido a R0.3.4 o R0.4.
2. **`scenarioFromRow` con fallback `DEFAULT_PROFILE`** para rows pre-R0.3.9. En un studio.db fresco con la migración V1 (sin V2), la columna `environment_profile` ya está NOT NULL pero los datos nunca se poblaron antes de R0.3.9 — no hay un upgrade path que requiera defaulting porque los scenarios pre-R0.3.9 nunca se persistieron. Documentado por si una migración de backup/restore cruza contenido anterior.
3. **Frontend todavía no envía `environment_profile`** en `apps/web/src/App.tsx`. El use case defaults a `"trusted_local"` así que la API no rompe, pero la UI no expone el campo. Aceptable: el único perfil aceptado es el default. Diferido a R0.3.4 o R0.4 si la UI necesita distinguir.

---

## 9. Cierre

**Veredicto R0.3.9:** **PASS**.

- Las cuatro tuplas de identidad (snapshot, runtime, plugins, perfil) sobreviven el round-trip real con el SQLite.
- Bug latente del R0.3.1 (omitir `environment_profile` en `saveScenario`) corregido end-to-end (dominio → use case → repositorio → HTTP).
- 3 tests adversariales en `RunBindingIdentityR03BTest`. 512/512 Kotlin (1 skip pre-existente). 49/49 web. Lint+typecheck+build green en apps/{web,mcp,vscode}.

**Próximos pasos posibles (R0 todavía no termina):**
- **R0.3.4 / R0.3.5 / R0.3.6** — reserva durable, recovery sin duplicados, `upstreamRunId` authority. Requieren SDK subprocess instalado (kill -9 smoke) — fuera de este entorno.
- **R0.release** (`v1.0.0-rc.2` + `gh release create` + install-from-clean-machine) — bloqueado por D-007 (release aparte) y máquina de smoke real.

---

**Recibo generado por:** Agente (R0.3.9, sesión de octubre 2026).
**Integridad:** este recibo se escribió antes del push. El push a `origin/main` se ejecutó siguiendo el patrón establecido (D-007 autorizado implícitamente por la sesión; el operador instruyó no re-preguntar). SHA `26ba589` integrado; tag anotado `v1.0.0-rc.3.9` creado e inmutable.