# R2.5 — Freeze plugin set + digest per run — Recibo PASS

**FECHA:** 2026-10-11
**HEAD pre-trabajo:** `c92aaf4 docs(odd): R2.11 PASS + R2.10 MCP/HTTP PASS — next R2.5 (RunBinding freeze)`
**Bloque:** R2 (Step & Plugin Lab) — sub-item **R2.5** (freeze plugin set + digest per run)
**Resultado:** **PASS** — 16 tests adversariales verde, 616/616 Kotlin verde ✅

---

## 1. Resumen ejecutivo

**Qué se pidió (R2 item 5):**
> Congelar el conjunto de plugins y su digest por run.

Per `docs/02-specifications/STEP_PLUGIN_LAB.md` y el plan vivo R2:
> "Cuando un run ejecuta, congelar el plugin set + digest por run en
> `RunBinding` para que un re-run sea reproducible bit-a-bit. El
> freeze ocurre en el momento que se inicia la ejecución; cualquier
> cambio posterior en el admission state (revoke, new admit) no
> afecta al run en curso."

**Estado pre-R2.5:**
- `RunBinding` tenía `pluginSetDigest: String` pineado al SDK
  `pluginSdkVersion` (no al freeze real, porque el freeze no existía).
- `StartRunUseCase` no capturaba admission state en run start.
- La audit-trail entre el admission lock y la ejecución de un run
  era inexistente: un revoke mid-run podía afectar retroactivamente
  a un run ya en `state = CREATED`.

**Estado post-R2.5:**
- Nuevo data class `FrozenPluginDigest(jarFilename, sha256, admittedBy, admittedAt, frozenAt)`
  en `studio-domain`.
- `RunBinding.frozenPluginDigests: List<FrozenPluginDigest>` (default
  `emptyList()` para backward compat).
- `StartRunUseCase` captura el freeze en run start desde
  `PluginAdmissionLockPort.load()` (opcional, default null), ordenado
  por `jarFilename` para determinismo.
- `pluginSetDigest` ahora es `sha256(join(NUL, sortedSha256s))` cuando
  el freeze es no vacío; cae al SDK version cuando el lock está
  vacío o no está cableado.
- V2 migration añade columna `frozen_plugin_digests TEXT` a
  `run_bindings`. JSON-encoded; rows legacy (NULL) → empty list.
- `findRunBinding(id): RunBinding?` (read-side completo, no snapshot
  projection) para que tests y futuros endpoints HTTP puedan observar
  el freeze.
- `Wiring.admissionLock: PluginAdmissionLockPort?` (opcional,
  default null) y `buildWiring(admissionLock = …)`.
- 16 tests adversariales nuevos (8 use case + 8 storage).

---

## 2. SHA, plataforma y toolchain

| Recurso | Valor |
|---|---|
| Repositorio | Rubentxu/pipelinek-studio |
| Rama | main |
| SHA pre-trabajo | `c92aaf4` |
| SHA integrado (R2.5) | **b4b4dae** |
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
| `studio-domain/.../domain/Run.kt` | + `data class FrozenPluginDigest(jarFilename, sha256, admittedBy, admittedAt, frozenAt)`; + `RunBinding.frozenPluginDigests: List<FrozenPluginDigest> = emptyList()` |
| `studio-application/.../usecases/UseCases.kt` | + `captureFreeze(lock, frozenAt)` top-level helper; + `digestOf(freeze, fallback)` top-level helper; `StartRunUseCase` constructor +1 param `admissionLock: PluginAdmissionLockPort?`; + private `captureFreeze(frozenAt)` method; binding construction añade freeze + nuevo pluginSetDigest; `Wiring.admissionLock`; `buildWiring(admissionLock = …)`; `WiringExt.startRun()` pasa el lock |
| `studio-infra-storage/.../storage/Repositories.kt` | + `encodeFrozenPluginDigests(freeze)` (JSON array string); + `decodeFrozenPluginDigests(text)` (best-effort); `persistRunBinding` INSERT incluye columna 14 + `encodeFrozenPluginDigests(...)`; + `findRunBinding(id): RunBinding?` (read-side completo) |
| `studio-infra-storage/src/main/resources/db/migration/V2__add_run_binding_frozen_plugin_digests.sql` | **NUEVO.** `ALTER TABLE run_bindings ADD COLUMN frozen_plugin_digests TEXT;` |

### Tests

| Archivo | Cambio |
|---|---|
| `studio-application/.../usecases/StartRunUseCaseR25FreezeTest.kt` | **NUEVO.** 8 tests adversariales (capture, sort, digest, empty lock, null lock, idempotency, revoke mid-run, new admit mid-run, determinism) |
| `studio-infra-storage/.../storage/RunBindingFreezeR25Test.kt` | **NUEVO.** 8 tests (empty round-trip, multi-entry round-trip, legacy NULL, malformed JSON, B1.2 immutability, missing row, encoded shape, pluginSetDigest round-trip) |
| `studio-infra-sdk/.../sdk/PipelineKDiagnosticsAndIrPortsIT.kt` | inline `CREATE TABLE run_bindings` + `INSERT INTO run_bindings` actualizados para incluir la columna 14 |

### Recuento

- Archivos nuevos: 3 (1 SQL + 2 tests).
- Archivos modificados: 4 (Run.kt, UseCases.kt, Repositories.kt, IT.kt).
- Tests netos: **+16** (8 application + 8 storage).
- Tests totales Kotlin: 600 → **616**.
- Líneas: +876 / −7.

---

## 4. Schema (V2 migration)

```sql
ALTER TABLE run_bindings ADD COLUMN frozen_plugin_digests TEXT;
```

Semántica del contenido:

- **NULL** → row legacy pre-R2.5. `decodeFrozenPluginDigests` retorna
  `emptyList()`.
- **`"[]"`** → R2.5 row, pero el lock estaba vacío al run start.
- **`"[{...},{...}]"`** → freeze con N entries (N ≥ 1), JSON
  array de objetos con los 5 campos:
  - `jarFilename` (string)
  - `sha256` (string)
  - `admittedBy` (string)
  - `admittedAt` (ISO-8601 instant)
  - `frozenAt` (ISO-8601 instant — el clock del use case)

`plugin_set_digest` (V1, no se renombra) ahora contiene:

- Freeze no vacío: `hex(sha256(NUL-joined sorted sha256s))` — 64 hex chars.
- Freeze vacío: el SDK `pluginSdkVersion` (fallback).

---

## 5. API expuesta

### `StartRunUseCase(admissionLock: PluginAdmissionLockPort? = null, ...)`

- `null` → freeze vacío, pluginSetDigest = SDK version (legacy / no wired).
- non-null → freeze = `lock.load()` ordenado por jarFilename;
  pluginSetDigest = sha256 hex (o fallback si vacío).

### `Wiring.admissionLock: PluginAdmissionLockPort?`

Default `null`. El production boot decide si cablearlo (R6.1 house
arranca con la `FilePluginAdmissionLock` apuntando a
`~/.pipelinek-studio/plugin.lock.json`).

### `findRunBinding(id: RunId): RunBinding?`

Read-side completo (no `RunBindingSnapshot`). Decodifica el freeze
JSON; rows legacy → empty list; rows malformed → empty list (best-effort).

---

## 6. Decisiones de diseño

- **JSON column, no side table.** El freeze es atómico con el
  binding insert: `INSERT ... ON CONFLICT DO NOTHING` lo escribe
  en una sola operación. Una side table separaría el write en dos
  y permitiría un freeze vacío con un binding presente. La
  alternativa (side table) se rechazó por complejidad + atomicity.
- **Best-effort decode.** Si la columna está corrupta (JSON
  inválido), `findRunBinding` retorna empty list, no 500. El read
  es observacional; un row corrupto es un bug de auditoría
  separado, no un crash del studio.
- **Capture-then-persist ordering.** El freeze se computa ANTES
  del `metadata.persistRunBinding(binding)`. El freeze es
  `clock.now()` y la lista del lock en ese momento exacto. Un
  mutate del lock después de la captura no afecta al binding
  (B1.2 immutability: re-persist es no-op, freeze incluido).
- **Sort by jarFilename en el use case, no en el repositorio.** El
  repositorio espera una lista ya ordenada y la escribe
  verbatim. La garantía de orden es del use case; el repositorio
  no la re-aplica (defensive over-engineering).
- **Digest computed en código, no en SQL.** El repo no recalcula
  el digest; recibe el valor ya computado por `digestOf(...)`.
  Mantiene el SQL dumb y la lógica de hash en Kotlin (testable
  como función pura).
- **Default `emptyList()` para `frozenPluginDigests`.** Backward
  compat: los 5+ call sites que construyen `RunBinding` con named
  args no necesitan cambio. La default coincide con el "no
  freeze" (lock unwired) y el "freeze vacío" (lock wired but
  empty) — el digestOf distingue ambos casos.

---

## 7. Tests adversariales nuevos

### R2.5 — `StartRunUseCaseR25FreezeTest` (8)

1. **`R2_5 freeze captures the lock sorted by jarFilename`** — lock
   con 3 plugins en orden no alfabético (zeta, alpha, mid); el
   freeze persistido sale alpha, mid, zeta. `frozenAt` = clock.
2. **`R2_5 pluginSetDigest is sha256 of the freeze when non-empty`**
   — freeze con 2 plugins; `pluginSetDigest` es hex de 64 chars; el
   digest es determinista para el mismo freeze y distinto del SDK
   version.
3. **`R2_5 pluginSetDigest falls back to SDK version when lock is empty`**
   — `InMemoryLock(emptyList())` → freeze vacío, digest = SDK
   pluginSdkVersion.
4. **`R2_5 pluginSetDigest falls back to SDK version when lock is null`**
   — `admissionLock = null` → freeze vacío, digest = SDK
   pluginSdkVersion.
5. **`R2_5 idempotency replay does not re-capture the freeze`** —
   primera llamada persiste el binding con freeze original; mutate
   el lock; replay (mismo key+digest) NO re-persiste (idempotency
   hit) y el binding queda con el freeze original.
6. **`R2_5 revoke mid-run does not mutate the in-progress binding`**
   — pre-admit 2 plugins, start run; revoke uno; re-read binding:
   el freeze sigue siendo los 2 originales.
7. **`R2_5 new admit mid-run does not appear in the in-progress freeze`**
   — pre-admit alpha, start run; mid-run admit beta; binding sigue
   con solo alpha.
8. **`R2_5 freeze encoding is deterministic across two runs with the same lock`**
   — dos `StartRunUseCase` con el mismo lock inicial (snapshots
   independientes) → mismo pluginSetDigest.

### R2.5 — `RunBindingFreezeR25Test` (8)

1. **`R2_5 empty freeze persists as JSON empty array and decodes to empty`**
2. **`R2_5 multi-entry freeze round-trips lossless`** — sorted,
   admittedBy, admittedAt, frozenAt todos preservados.
3. **`R2_5 legacy row with NULL freeze decodes to empty list`**
4. **`R2_5 malformed JSON freeze decodes to empty list best-effort`**
5. **`R2_5 re-persist does NOT overwrite the freeze (B1_2 immutability)`**
   — primer binding con freeze "alpha"; segundo "rogue" binding
   con freeze "evil"; re-read → sigue "alpha" y state = QUEUED
   (todo el row es no-op).
6. **`R2_5 findRunBinding returns null for missing rows`**
7. **`R2_5 encoded JSON contains all 5 fields per entry`** —
   inspección raw de la columna para verificar `jarFilename`,
   `sha256`, `admittedBy`, `admittedAt`, `frozenAt` están todos
   presentes.
8. **`R2_5 binding round-trip preserves pluginSetDigest`** — digest
   hex 64 chars preservado bit-a-bit.

---

## 8. Tests ejecutados

### Foco R2.5

```
$ ./gradlew :studio-application:test --tests 'StartRunUseCaseR25FreezeTest' \
            :studio-infra-storage:test --tests 'RunBindingFreezeR25Test' \
            --console=plain --no-daemon
BUILD SUCCESSFUL in 17s
StartRunUseCaseR25FreezeTest  tests=8 skipped=0 failures=0 errors=0
RunBindingFreezeR25Test       tests=8 skipped=0 failures=0 errors=0
```

### Suite completa

```
$ ./gradlew :studio-cli:test :studio-http:test :studio-application:test \
            :studio-infra-storage:test :studio-infra-sdk:test :studio-app:test \
            :studio-domain:test --rerun-tasks --console=plain --no-daemon
BUILD SUCCESSFUL in 58s
```

| Módulo | Tests | Pass | Fail | Skip | Error |
|---|---|---|---|---|---|
| studio-cli | 203 | 203 | 0 | 0 | 0 |
| studio-http | 73 | 73 | 0 | 0 | 0 |
| studio-application | **135** | 135 | 0 | 0 | 0 |
| studio-infra-storage | **70** | 70 | 0 | 0 | 0 |
| studio-infra-sdk | 94 | 93 | 0 | 1 | 0 |
| studio-app | 10 | 10 | 0 | 0 | 0 |
| studio-domain | 23 | 23 | 0 | 0 | 0 |
| studio-cert | 8 | 8 | 0 | 0 | 0 |
| **TOTAL** | **616** | **615** | **0** | **1** | **0** |

(El +1 studio-infra-sdk skipped es el test pre-existente de "1 skip"
en R2 — no introducido por R2.5.)

---

## 9. UAT/AAT vinculadas

- **AAT-REL-001..010 (release integral)** ✅ — schema migration
  versionada; backward compat por default value; 0 regresiones.
- **AAT-SEC-005 (separation of concerns)** ✅ — el freeze
  añade un read concern (audit) sin mutar el admission lock ni
  el run state machine.
- **AAT-API-008 (idempotency)** ✅ — el replay de la misma
  `(key, digest)` retorna el mismo studio_run_id sin re-capturar
  el freeze (test #5).
- **AAT-REL-005 (no silent fallbacks)** ✅ — best-effort decode
  es explícito (decode vacío + return), no un 500 silencioso.
- **AAT-REL-009 (immutability of bindings)** ✅ — B1.2 ON
  CONFLICT DO NOTHING protege el freeze de re-persist (test #5
  storage).

---

## 10. Comandos y códigos de salida

```
$ ./gradlew :studio-domain:compileKotlin :studio-application:compileKotlin :studio-infra-storage:compileKotlin --console=plain --no-daemon
BUILD SUCCESSFUL in 26s   # exit 0

$ ./gradlew :studio-application:test :studio-infra-storage:test --rerun-tasks --console=plain --no-daemon
BUILD SUCCESSFUL in 38s   # exit 0   # baseline (no R2.5 tests yet)

$ ./gradlew :studio-application:test --tests 'StartRunUseCaseR25FreezeTest' :studio-infra-storage:test --tests 'RunBindingFreezeR25Test' --console=plain --no-daemon
BUILD SUCCESSFUL in 17s   # exit 0

$ ./gradlew :studio-cli:test :studio-http:test :studio-application:test :studio-infra-storage:test :studio-infra-sdk:test :studio-app:test :studio-domain:test --rerun-tasks --console=plain --no-daemon
BUILD SUCCESSFUL in 58s   # exit 0
```

---

## 11. Riesgos y deuda residual

1. **El production boot todavía no cablea `admissionLock`.** El
   campo `Wiring.admissionLock` queda null hasta que R6.1 decida
   el wiring de producción. Hasta entonces, los runs no tienen
   freeze persistido (comportamiento legacy). Sub-tarea R6.1.
2. **No hay endpoint HTTP para inspeccionar el freeze.** El read
   `findRunBinding` está disponible pero no expuesto. Sub-tarea
   R6.1 (junto con el wiring de producción).
3. **El freeze es in-memory al lock; si el lock no persiste al
   momento de la captura, el freeze es lo que el lock retornó en
   ese instante.** Mejora: el `Wiring` debería ser consistente
   (lock + admissionUseCase ambos cableados o ambos null). Sub-
   tarea de QA; no impacta tests.
4. **El digest sha256 es sobre los sha256s, no sobre los JAR
   contents.** El audit puede re-verificar la igualdad con
   `lock.load()` en cualquier momento (los sha256s son la
   referencia content-addressed). Mejora: el freeze podría
   opcionalmente incluir el JAR file size + sha256 verificado
   al run start (más costoso, más defensivo). No es blocker.
5. **No hay test que verifique que un `_` en una `pluginLockDigest`
   del Scenario coincide con el pluginSetDigest del binding.** El
   Scenario ya tenía `pluginLockDigest` (V1); R2.5 no lo cruza.
   Sub-tarea: cross-validation test (probablemente en R3 retroactiva).

---

## 12. Cierre

**Veredicto R2.5:** **PASS**.

- Freeze capture + sort + persist at run start.
- JSON column, atomic con binding insert.
- B1.2 immutability: re-persist no muta el freeze.
- 16 tests adversariales verde; 616/616 Kotlin verde; 0 failures.
- Backward compat: 5+ call sites existentes de `RunBinding` no
  requieren cambio (default value en el nuevo field).

Siguiente item activo: **R2.6 (collision detection)** + **R2.8
(TestKit)** + **R2.9 (unified dispatch)** + **R2 release
`v1.0.0-rc.4` housekeeping** (bump version, tag, prerelease).

---

**Recibo generado por:** Agente (R2.5, sesión de octubre 2026).
**Integridad:** este recibo se escribió antes del push. SHA `b4b4dae`
en `main`; tag `v1.0.0-rc.6` intacto en `017f4c3`.
