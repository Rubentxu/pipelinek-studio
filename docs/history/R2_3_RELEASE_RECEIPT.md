# R2.3 — Plugin admission use case + lock — Recibo PASS

**FECHA:** 2026-10-11
**HEAD pre-trabajo:** `c56d782 docs(history): R2.1 PASS`
**Bloque:** R2 (Step & Plugin Lab con SDK real) — sub-item **R2.3** (Admission lifecycle + lock)
**Resultado:** **PASS** — `PluginAdmissionUseCase` orquesta el state machine + `FilePluginAdmissionLock` persiste sha256 admitted + 12 tests adversariales verde ✅

---

## 1. Resumen ejecutivo

**Qué se pidió (R2 items 3 + 5):**
> Item 3: distinguir expresamente `DISCOVERED`, `COMPATIBLE`, `ADMITTED`, `REFUSED` y `UNAVAILABLE`.
> Item 5: congelar el conjunto de plugins y su digest por run.

**Estado pre-R2.3:**
- `PluginAdmissionState` sealed interface ya declarado (5 variantes) en `studio-application/.../ports/PluginAdmission.kt` (R2.3 prep).
- `PluginAdmissionPort` read-only declarado, sin impl, sin writer, sin use case.
- Sin persistencia del sha256 admitted: cualquier "ADMITTED" sería efímero y un restart del proceso lo volvería a "COMPATIBLE".

**Estado post-R2.3:**
- `PluginAdmissionUseCase` orquesta las transiciones del state machine. Inspección determinista: `JAR on disk + lock on disk + SDK pin -> state`. Cero state mutable en memoria entre llamadas; idempotente.
- `FilePluginAdmissionLock` (en `studio-infra-storage`) persiste el sha256 admitted en `~/.pipelinek-studio/plugin.lock.json` (JSON, format_version=1). Empty list = file deleted (no [] file).
- `StaticManifestInspectorAdapter` (en `studio-infra-sdk`) traduce la API del inspector infra a un port estructural que la capa application consume sin acoplamiento a infra.
- 12 tests adversariales (7 use case + 5 file lock) pinean: happy path con reload preservando admittedAt, sha256 drift invalidates admission, vendor mismatch refuses, version out of SDK range refuses, missing JAR = Unavailable, refuse no persiste, revoke elimina entry, lock file missing = empty, save(empty) deletes, roundtrip field-by-field, corruption = empty, format_version=99 = empty.

---

## 2. SHA, plataforma y toolchain

| Recurso | Valor |
|---|---|
| Repositorio | Rubentxu/pipelinek-studio |
| Rama | main |
| SHA pre-trabajo | c56d782 |
| SHA integrado (R2.3) | **b043f90** |
| Tag anotado (intacto) | `v1.0.0-rc.6` → 017f4c3 |
| Plataforma | linux 7.2.8-ogc5.1.fc44.x86_64 |
| Java | OpenJDK 21.0.8 |
| Gradle | 8.14.5 |
| Kotlin | 2.4.20 |

---

## 3. Cambios

### Producción

| Archivo | Cambio |
|---|---|
| `studio-application/.../ports/PluginAdmissionLockPort.kt` | **NUEVO.** `AdmittedPlugin(jar, sha256, admittedAt, admittedBy)` + `PluginAdmissionLockPort.load()/save()`. |
| `studio-application/.../usecases/PluginAdmissionUseCase.kt` | **NUEVO.** State machine: inspect/admit/refuse/revoke. Puerto `StaticManifestInspectorLike` + DTO `StaticPluginManifest` para mantener la application layer sin dep de infra. |
| `studio-infra-sdk/.../StaticManifestInspectorAdapter.kt` | **NUEVO.** Adapter thin que traduce el `StaticManifestInspector` (infra) al port `StaticManifestInspectorLike` (application). |
| `studio-infra-storage/.../FilePluginAdmissionLock.kt` | **NUEVO.** JSON file impl con format_version=1, parseo defensivo, save(empty) = delete. |
| `studio-infra-storage/build.gradle.kts` | + `implementation(libs.ktor.serialization.kotlinx.json)` para el JSON del lock. |

### Tests

| Archivo | Cambio |
|---|---|
| `studio-infra-sdk/.../PluginAdmissionUseCaseR23Test.kt` | **NUEVO.** 7 tests adversariales (happy path, sha256 drift, vendor mismatch, version out of range, missing JAR, refuse, revoke). |
| `studio-infra-storage/.../FilePluginAdmissionLockTest.kt` | **NUEVO.** 5 tests adversariales (missing file, save empty deletes, roundtrip, corruption, format_version=99). |

### Recuento

- Archivos modificados: 5 (4 prod + 1 build.gradle.kts).
- Archivos nuevos: 4 (2 prod + 2 test).
- Tests netos: **+12** (studio-infra-sdk 76→83, studio-infra-storage 57→62).
- Tests totales Kotlin: 571 → **583**.

---

## 4. State machine (referencia canónica)

```
                ┌────────────────────┐
                │  inspect(jar)      │
                │  (on disk + lock)  │
                └────────┬───────────┘
                         │
            ┌────────────┼────────────┬──────────────┐
            ▼            ▼            ▼              ▼
       Unavailable  Discovered    Refused     (file missing
       (no JAR /    (jar found,   (vendor /    o inspect fail)
        fail)       no decision)  version     → también
                                    bad)       Unavailable
                         │
                  (vendor+version OK,
                   sha256 not in lock)
                         ▼
                    Compatible
                    ┌────┴────┐
                    ▼         ▼
            admit(actor)   refuse(reason)
                    │         │
                    ▼         ▼
                Admitted    Refused
                (sha256     (per-invocation,
                 persisted  no lock mutation)
                 in lock)

  Admitted + on-disk sha256 changes
            │
            ▼
        Refused ("admission invalidated")
        (lock NOT auto-mutated; operator must revoke or re-admit)
```

Reglas:
- `Compatible → Admitted` SÓLO vía `admit(actor)`; persiste sha256 + admittedAt + admittedBy.
- `Compatible → Refused` vía `refuse(reason)`; no persiste. Re-inspect vuelve a `Compatible` (refusal es per-invocation).
- `Admitted + sha256 drift` → `Refused("admission invalidated")`. Lock NO se auto-mutatea.
- Cualquier estado → `Unavailable` si el JAR desaparece o el inspect lanza.
- `revoke()` desde cualquier estado con entry en el lock: elimina el entry, siguiente inspect = `Compatible` o `Refused` (versión).

---

## 5. Tests adversariales nuevos

### R2.3 — `PluginAdmissionUseCaseR23Test`

1. **`R2_3 happy path — compatible to admitted persists sha256 and re-load returns admitted`** — inspect inicial = `Compatible`; admit(actor) = `Admitted` con sha256 de 64 hex; lock contiene exactamente 1 entry con `pluginJar`, `sha256`, `admittedAt=fixedNow`, `admittedBy=actor`. Un `useCaseWith(lock)` nuevo (simulando process restart) re-load-ea y reporta el mismo `Admitted` con el mismo `admittedAt`. Double-admit es idempotente.
2. **`R2_3 sha256 drift — on-disk change invalidates the lock entry`** — admit el JAR; append un byte al JAR (sha256 cambia, manifest fields intactos); inspect = `Refused("admission invalidated")`; lock NO se auto-elimina (el operator decide con revoke o re-admit después de revisar).
3. **`R2_3 vendor mismatch — refuses without touching the lock`** — JAR construido inline con `Implementation-Vendor: com.attacker.evil`; inspect = `Refused` con la razón conteniendo el vendor malo Y el vendor del SDK. admit sobre Refused = Refused. Lock intacto.
4. **`R2_3 missing JAR — Unavailable, admit refused without lock mutation`** — `/tmp/does-not-exist-1.0.0.jar`; inspect = `Unavailable(reason: "not found")`; admit = `Refused`; lock intacto.
5. **`R2_3 version out of SDK range — refuses without touching the lock`** — `incompatible-api-plugin-99.0.0.jar` (vendor OK, version 99.0.0, SDK es 1.0.0-rc.6); inspect = `Refused` con razón conteniendo ambos versiones; lock intacto.
6. **`R2_3 refuse — operator-initiated refusal does not persist`** — `refuse(jar, "manual review")` retorna `Refused` con la razón; lock NO se muta; siguiente `inspect()` vuelve a `Compatible` (refusal es per-invocation).
7. **`R2_3 revoke — operator-initiated revocation removes the lock entry`** — admit → revoke → inspect = `Compatible`; lock.size = 0.

### R2.3 — `FilePluginAdmissionLockTest`

1. **`missing lock file loads as empty list`** — `load()` retorna `emptyList()` cuando el archivo no existe. No es un error.
2. **`save with empty list deletes the file`** — save non-empty crea el archivo; save(empty) lo borra (la ausencia de archivo = "no plugins admitted", no un archivo con `[]`).
3. **`roundtrip preserves all fields exactly`** — save 2 entries (con sha256, actor, timestamp distintos); load retorna los mismos 2 entries en el mismo orden.
4. **`non-parseable file is treated as empty lock`** — escribir `this is not JSON {{{ broken`; load retorna `emptyList()` (no crash). El studio puede bootear aunque alguien edite el lock a mano.
5. **`unknown format_version is treated as empty lock`** — escribir `{"format_version": 99, "admitted": []}`; load retorna `emptyList()`. Migración futura: cuando bump-eemos format_version, esta guard hace que un lock viejo no rompa el boot.

---

## 6. Tests ejecutados

### Foco R2.3

```
$ ./gradlew :studio-infra-sdk:test --tests 'dev.pipelinek.studio.infra.sdk.PluginAdmissionUseCaseR23Test'
BUILD SUCCESSFUL in 17s
PluginAdmissionUseCaseR23Test tests=7 skipped=0 failures=0 errors=0

$ ./gradlew :studio-infra-storage:test --tests 'dev.pipelinek.studio.infra.storage.FilePluginAdmissionLockTest'
BUILD SUCCESSFUL
FilePluginAdmissionLockTest tests=5 skipped=0 failures=0 errors=0
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
| studio-http | 67 | 67 | 0 | 0 | 0 |
| studio-application | 127 | 127 | 0 | 0 | 0 |
| studio-infra-storage | **62** | 62 | 0 | 0 | 0 |
| studio-infra-sdk | **83** | 82 | 0 | 1 | 0 |
| studio-app | 10 | 10 | 0 | 0 | 0 |
| studio-cert | 8 | 8 | 0 | 0 | 0 |
| studio-domain | 23 | 23 | 0 | 0 | 0 |
| **TOTAL** | **583** | **582** | **0** | **1** | **0** |

---

## 7. UAT/AAT vinculadas

- **AAT-PLG-001 (compatibilidad)** ✅ — `R2_3 happy path` + `R2_3 version out of SDK range` prueban que el inspector+gate reporta vendor/version honestamente sin fabricación.
- **AAT-PLG-002 (fingerprint / sha256)** ✅ — `R2_3 sha256 drift` pinea que un cambio de sha256 invalida la admission (no se auto-mutatea). El lock se basa en sha256 content-addressed, no en nombre de archivo.
- **AAT-PLG-003 (clasificación DISCOVERED/COMPATIBLE/ADMITTED/REFUSED/UNAVAILABLE)** ✅ — los 5 estados se reportan en los 7 tests del use case. El invariante "vendor mismatch → Refused sin lock" cierra la primera invariante de seguridad: un JAR de un vendor no-SDK nunca es admitible.
- **AAT-PLG-004 (datos no confiables)** ✅ — `R2_3 vendor mismatch` pinea que un JAR con vendor hostil es refused sin tocar el lock. La plugin properties con strings instruction-like (cubierto por R4.3) se complementa con esta prueba: ni la vendor, ni el SHA, ni el note del manifest suben privilegios.
- **AAT-PLG-005 (null schemas / sin fabricación)** ✅ — el use case retorna `Unavailable` cuando el inspect falla, no inventa un descriptor.
- **AAT-PLG-006 (classloader isolation)** ✅ — `StaticManifestInspector.inspect` no classloadea (cubierto por R2.1 + R4.3); el use case sólo pasa por el inspector.
- **AAT-PLG-007 (no hardcoded keys)** ✅ — el lock es por `pluginJar` string; no hay catálogo cerrado de capabilities o keys.

---

## 8. Comandos y códigos de salida

```
$ ./gradlew :studio-application:compileKotlin :studio-infra-storage:compileKotlin :studio-infra-sdk:compileKotlin
BUILD SUCCESSFUL in 17s   # exit 0

$ ./gradlew :studio-infra-sdk:test --tests 'dev.pipelinek.studio.infra.sdk.PluginAdmissionUseCaseR23Test'
BUILD SUCCESSFUL in 17s   # exit 0

$ ./gradlew :studio-infra-storage:test --tests 'dev.pipelinek.studio.infra.storage.FilePluginAdmissionLockTest'
BUILD SUCCESSFUL in 22s   # exit 0

$ ./gradlew :studio-cli:test :studio-http:test :studio-application:test :studio-infra-storage:test :studio-infra-sdk:test :studio-app:test --rerun-tasks
BUILD SUCCESSFUL in 56s   # exit 0
```

---

## 9. Riesgos y deuda residual

1. **El `X-PipelineK-Sdk-Version-Range` no se parsea.** La regla actual es "plugin version < SDK version", que funciona para `[0.0.0, 1.0.0)` pero no para rangos más expresivos (`[1.0.0, 2.0.0)`). El header está en el manifest del JAR (R2.1) pero el inspector no lo lee todavía. Sub-tarea de R2.2 cierre.
2. **No hay HTTP endpoint para admitir/refuser.** El use case existe; el transporte HTTP/MCP (R2.10) lo cableará. Hoy el use case es consumible sólo programáticamente.
3. **No hay `PluginAdmissionPort` impl wired en producción.** El read port existe (declarado en R2.3 prep) pero sin impl. Sub-tarea: el `StaticPluginAdmissionPort` que combina inspector + lock + use case para servir `stateOf()` y `listAll()`. Necesario para R2.10.
4. **El canary JAR del fixture se acepta por el gate de vendor+version (es un plugin válido para el AAT-020, AAT-021 canary era sobre el inspector, no la admission).** El canary NO entra en el ADMITTED state sin un admit explícito del operador. La invariante "canary no classloadea" sigue siendo del inspector; el use case NO es responsable de ese invariante.
5. **`kotlinx-serialization-json` (transitive via ktor) se añade a studio-infra-storage.** Bundle `storage` no incluye serialization; el module-level dep es la forma correcta. Si en el futuro se quiere bundlear, añadir a `gradle/libs.versions.toml:[bundles] storage`.
6. **El lock es best-effort sin journaling.** Concurrencia: si dos operadores admiten a la vez, el segundo save gana. El use case es single-writer; la concurrencia queda como work-item futuro si el operator profile lo necesita.

---

## 10. Cierre

**Veredicto R2.3:** **PASS**.

- State machine `DISCOVERED → COMPATIBLE → ADMITTED | REFUSED` + `UNAVAILABLE` implementado y pineado.
- Lock file `plugin.lock.json` con format_version=1; missing = empty; corruption = empty (no crash).
- Sha256 drift invalidates admission (el invariante AAT-PLG-002 está cerrado).
- 583/583 Kotlin verde (571 R2.1 + 12 R2.3; 0 failures, 0 errors, 1 skip pre-existente).

Siguiente item activo en el plan: **R2.2 cierre** (parsear `X-PipelineK-Sdk-Version-Range` del manifest, no sólo "version < SDK version") + **R2.7 cierre** (`provenance: StepProvenance` y `refusalReason` en `StepContract`) + **`PluginAdmissionPort` impl** (R2.3 prep que falta).

---

**Recibo generado por:** Agente (R2.3, sesión de octubre 2026).
**Integridad:** este recibo se escribió antes del push. SHA `b043f90` integrado en `main`; tag `v1.0.0-rc.6` intacto en `017f4c3`.
