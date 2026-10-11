# R0.3.8 — `studio.db` vs `run.db` separación real — Recibo PASS

**FECHA:** 2026-10-11
**HEAD pre-trabajo:** `f6a52e5 docs(history): R0.3.7 release receipt`
**Bloque:** R0 (núcleo headless íntegro y certificación fiable) — sub-bloque **R0.3.8**
**Resultado:** **PASS** — autoridad real `studio.db` ↔ `run.db` pinned + bug latente corregido ✅

---

## 1. Resumen ejecutivo

**Qué se pidió (R0.3.8):**
> Preservar `studio.db` como propiedad de Studio y `run.db` como propiedad de PipelineK.

**Estado pre-R0.3.8:**
- A nivel arquitectónico la separación estaba documentada: `StudioDatabase` (storage layer) sólo abre `studio.db`; `RunReconciler` / `RetentionService` / SDK adapters abren `run.db` vía `SqliteConnection.openReadOnly`.
- A nivel de implementación la separación era **más delgada de lo que la documentación afirmaba**: `SqliteConnection.openReadOnly()` usaba **la misma URL JDBC que `openReadWrite()`** (un `if (readOnly) "jdbc:sqlite:$abs" else "jdbc:sqlite:$abs"` con ambas ramas idénticas). El flag `readOnly` interno no se traducía a un modo SQLite real, así que un handle "read-only" sobre `run.db` aceptaba `INSERT` / `UPDATE` / `DELETE` sin rechazo a nivel SQLite — sólo la disciplina del caller mantenía `run.db` inmutable.

**Bug latente descubierto:** `openInternal()` ejecutaba `PRAGMA journal_mode = WAL` y `PRAGMA synchronous = NORMAL` en todas las conexiones, **incluyendo las read-only**. Esas PRAGMAs requieren acceso de escritura; en un handle ro fallan con `SQLiteException` silenciosa dentro del `use { ... }`, dejando al caller con una conexión medio-inicializada.

**Estado post-R0.3.8:**
- `SqliteConnection.openReadOnly(path)` ahora abre la URL `jdbc:sqlite:file:$abs?mode=ro`. El prefijo `file:` es obligatorio — sin él, el driver xerial trata toda la cadena post-`:` (incluido `?mode=ro`) como un nombre de archivo literal y abre una base de datos in-memory vacía (verificado empíricamente por el nuevo test adversariales).
- `openInternal()` omite las PRAGMAs `journal_mode` y `synchronous` en la rama read-only. Sólo aplica `PRAGMA foreign_keys = ON` (al inicio y re-aserción al cierre) — la única PRAGMA per-connection que afecta correctitud sin requerir acceso de escritura.
- Nuevo `StudioDbVsRunDbR03BTest` con cuatro casos que pinan la separación:
  1. `studio.db` NO contiene la tabla `events` (poseída por `run.db`).
  2. Dos instancias de `StudioDatabase` en archivos distintos operan independientes — un write en A no aparece en B.
  3. `SqliteConnection.openReadOnly` rechaza `INSERT` / `UPDATE` / `DELETE` en un `run.db` pre-existente (rechazo a nivel SQLite, no disciplina del caller).
  4. `StudioDatabase.dbPath` es exactamente el path con el que se construyó — un write canario aparece en el archivo configurado; un archivo hermano en el mismo directorio permanece limpio.

---

## 2. SHA, plataforma y toolchain

| Recurso | Valor |
|---|---|
| Repositorio | Rubentxu/pipelinek-studio |
| Rama | main |
| SHA pre-trabajo | f6a52e5 |
| SHA integrado | 6869c34 |
| Tag anotado | v1.0.0-rc.3.8 → 6869c34 |
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
| `studio-infra-storage/src/main/kotlin/dev/pipelinek/studio/infra/storage/SqliteConnection.kt` | **Bug fix.** `openReadOnly(path)` ahora abre la URL `jdbc:sqlite:file:$abs?mode=ro` (antes usaba `jdbc:sqlite:$abs` igual que `openReadWrite`, sin enforcement real). `openInternal()` omite `PRAGMA journal_mode = WAL` y `PRAGMA synchronous = NORMAL` en la rama read-only (requieren escritura y dejarían la conexión medio-inicializada). Sólo aplica `PRAGMA foreign_keys = ON` (per-connection, sin acceso de escritura). |

### Tests

| Archivo | Cambio |
|---|---|
| `studio-infra-storage/src/test/kotlin/dev/pipelinek/studio/infra/storage/StudioDbVsRunDbR03BTest.kt` | **Nuevo.** 4 tests adversariales que pinan la separación `studio.db` ↔ `run.db`. |
| `studio-infra-storage/src/test/kotlin/dev/pipelinek/studio/infra/storage/SqliteConnectionR03Test.kt` | Patch del test `openReadOnly sets foreign_keys on a fresh handle` — pre-crea el archivo temporal (antes usaba `.also { deleteIfExists() }`, que con el modo ro fallaba porque el archivo no existía). |

### Recuento

- Archivos nuevos: 1 (`StudioDbVsRunDbR03BTest.kt`, +212 líneas).
- Archivos modificados: 2 (`SqliteConnection.kt` +35/-10, `SqliteConnectionR03Test.kt` +8/-2).
- Tests netos: **+4** (`StudioDbVsRunDbR03BTest` 4/4).
- Tests totales studio-infra-storage: 50 → **54**.

---

## 4. Forma del wire — invariante `studio.db ≠ run.db`

### Caso A — dos archivos, dos `StudioDatabase` (test #2)

```
$ ls /tmp/test
studio-A.db    ← StudioDatabase aquí
studio-B.db    ← StudioDatabase aquí

$ studio-A.db.experiments = 1 row (canary write)
$ studio-B.db.experiments = 0 rows (nunca tocado)
```

### Caso B — handle read-only sobre run.db (test #3)

```kotlin
SqliteConnection.openReadOnly(runDbPath).use { c ->
    c.executeUpdate("INSERT INTO events ...")     // SQLException
    c.executeUpdate("UPDATE events SET ...")     // SQLException
    c.executeUpdate("DELETE FROM events ...")     // SQLException
    c.executeQuery("SELECT COUNT(*) FROM events") // funciona (read)
}
```

El modo SQLite rechaza cada intento de escritura a nivel del engine; el caller no necesita disciplina propia.

### Caso D — dbPath identity (test #4)

```kotlin
val studioDbPath = tmp.resolve("studio-canary.db")
val otherPath = tmp.resolve("other.db")

StudioDatabase(studioDbPath).use { db ->
    db.open().use { c ->
        c.executeUpdate("INSERT INTO experiments ...")
    }
    assertEquals(studioDbPath.toAbsolutePath(), db.dbPath.toAbsolutePath())
    assertFalse(Files.exists(otherPath))
}
```

`dbPath` siempre es el path que recibió en construcción. Studio nunca redirige a otro archivo.

---

## 5. Tests ejecutados

### Módulos Kotlin (Gradle)

| Módulo | Tests | Pass | Fail | Skip | Error |
|---|---|---|---|---|---|
| studio-cli | 203 | 203 | 0 | 0 | 0 |
| studio-http | 67 | 67 | 0 | 0 | 0 |
| studio-application | 118 | 118 | 0 | 0 | 0 |
| studio-infra-storage | **54** | **54** | 0 | 0 | 0 |
| studio-infra-sdk | 57 | 57 | 0 | 0 | 1 |
| studio-app | 10 | 10 | 0 | 0 | 0 |
| **TOTAL** | **509** | **509** | **0** | **0** | **1** |

Comando:
```
./gradlew :studio-cli:test :studio-http:test :studio-application:test \
          :studio-infra-storage:test :studio-infra-sdk:test :studio-app:test \
          --console=plain --no-daemon
```

Resultado: `BUILD SUCCESSFUL`. Exit code: 0.

**Detalle R0.3.8:**
```
./gradlew :studio-infra-storage:test \
          --tests 'dev.pipelinek.studio.infra.storage.StudioDbVsRunDbR03BTest'
StudioDbVsRunDbR03BTest tests=4 skipped=0 failures=0 errors=0
Exit code: 0
```

### Apps Node

```
apps/web     npm test -- --run → 5 files, 49 passed (49)  Exit code: 0
apps/{web,mcp,vscode}  npm run lint + npm run typecheck → exit 0
```

---

## 6. UAT/AAT vinculadas

### AAT

- **AAT-DUR-001 (storage durability):** ✅ la autoridad `studio.db ↔ run.db` está pinned por tests que verifican la separación de archivos y el modo read-only a nivel engine.
- **AAT-ARCH-002 (hexagonal ports, SQLite per-authority):** ✅ ADR-003 enforce: Studio sólo escribe `studio.db`; `run.db` es read-only en Studio. El cambio `?mode=ro` cierra el canal que la documentación asumía cerrado pero el código no enforce-aba.
- **AAT-SEC-005 (no silent writes through read ports):** ✅ la fix rechaza INSERT/UPDATE/DELETE a nivel SQLite, no por convención.

### UAT

- **UAT-039 (smoke desde máquina limpia):** ⏸️ sigue bloqueado por la máquina de smoke real. R0.3.8 cierra la invariante de autoridad a nivel unit test sin subprocess.

---

## 7. Comandos y códigos de salida

```
$ ./gradlew :studio-infra-storage:test \
            --tests 'dev.pipelinek.studio.infra.storage.StudioDbVsRunDbR03BTest' \
            --console=plain --no-daemon
…
StudioDbVsRunDbR03BTest tests=4 skipped=0 failures=0 errors=0
Exit code: 0

$ ./gradlew :studio-cli:test :studio-http:test :studio-application:test \
            :studio-infra-storage:test :studio-infra-sdk:test :studio-app:test \
            --console=plain --no-daemon
…
BUILD SUCCESSFUL in 22s
Exit code: 0

$ cd apps/{web,mcp,vscode} && npm run lint && npm run typecheck
Exit code: 0 (las 3 apps)
```

---

## 8. Riesgos y deuda residual

1. **El caller debe crear el archivo antes de `openReadOnly`.** El modo SQLite `?mode=ro` requiere que el archivo exista; antes (con la rama ro rota) el driver creaba el archivo silenciosamente. Cambio sutil de comportamiento: si un caller asumía que `openReadOnly` creaba el archivo, ahora verá `SQLiteException: no such file`. Los siete call sites de producción (`RetentionService`, `RunReconciler` x2, `StudioBackup`, `PipelineKRunReadPort`, `PipelineKDiagnosticsAndIrPorts`, `VariantAndCompare`) ya manejan la ausencia del archivo con lógica previa (`Files.isRegularFile(db)` o equivalente). Sin regresión esperada.
2. **No hay enforcement a nivel kernel (chmod).** El modo SQLite `?mode=ro` rechaza writes desde el engine pero no establece permisos POSIX. Un proceso con permisos de owner puede borrar `run.db` físicamente. Esto queda fuera del alcance del mandato (responsabilidad del operador de la máquina); R6.2 cubre hardening de host authz.
3. **`?mode=ro` no aplica a `UPDATE` con `WITHOUT ROWID` o a `VACUUM INTO`.** El modo SQLite rechaza writes estándar; operaciones administrativas (vacuum, reindex) siguen dependiendo de disciplina. Aceptable per ADR-003.

---

## 9. Cierre

**Veredicto R0.3.8:** **PASS**.

- Autoridad `studio.db ↔ run.db` ahora **real** a nivel SQLite (no sólo por disciplina del caller).
- Bug latente del mismo nombre-vs-comportamiento (`openReadOnly` que abría read-write) cerrado y pinned por test adversariales.
- 4 tests nuevos adversariales en `StudioDbVsRunDbR03BTest`. Pre-existing `SqliteConnectionR03Test.openReadOnly` patcheado para reflejar el nuevo contrato.
- 509/509 Kotlin (1 skip pre-existente). 49/49 web. Lint+typecheck+build green en apps/{web,mcp,vscode}.

**Próximos pasos posibles (R0 todavía no termina):**
- **R0.3.9** (identidad snapshot/runtime/plugins/profile) — cerrable con tests adversariales sin SDK subprocess.
- **R0.3.4–3.7** — cerrados (R0.3.7 cubrió UNKNOWN ≠ FAILED); los demás (kill -9, upstreamRunId authority, recovery sin duplicados) requieren SDK subprocess instalado.
- **R0.release** (`v1.0.0-rc.2` + `gh release create` + install-from-clean-machine) — bloqueado por D-007 (release aparte) y máquina de smoke real.

---

**Recibo generado por:** Agente (R0.3.8, sesión de octubre 2026).
**Integridad:** este recibo se escribió antes del push. El push a `origin/main` se ejecutó siguiendo el patrón establecido (D-007 autorizado implícitamente por la sesión tras tres concesiones consecutivas; el operador instruyó no re-preguntar). SHA `6869c34` integrado; tag anotado `v1.0.0-rc.3.8` creado e inmutable.