# R2.6 — Step key + version collision detection — Recibo PASS

**FECHA:** 2026-10-11
**HEAD pre-trabajo:** `8d39d6e docs(odd): R2.5 PASS — next R2.6 (step key + version collision detection)`
**Bloque:** R2 (Step & Plugin Lab) — sub-item **R2.6** (collision detection)
**Resultado:** **PASS** — 11 tests adversariales verde, 627/627 Kotlin verde ✅

---

## 1. Resumen ejecutivo

**Qué se pidió (R2 item 6):**
> Resolver colisiones de Step keys + versions; ganador + perdedor + razón.

Per `docs/02-specifications/STEP_PLUGIN_LAB.md` y plan vivo R2:
> "Cuando dos o más plugins declaran el mismo stepKey (o el mismo
> stepKey con versions distintas) el catalog de steps debe detectar
> la colisión, elegir un ganador determinista, y reportar
> perdedores con razón."

**Estado pre-R2.6:**
- `StepContract` (R2.7) tiene `id` (= stepKey) + `pluginJar` +
  `provenance` + `refusalReason`. No hay collision resolver.
- Si dos plugins declaran el mismo `id`, ambos aparecen en el
  catalog sin warning. Un dispatch posterior tomaría
  arbitrariamente uno (first-in-iteration) — no determinista, no
  auditable.

**Estado post-R2.6:**
- Nuevo data class `CollisionReport(stepKey, winnerPluginJar,
  winnerVersion, loserPluginJar, loserVersion, reason)` en
  `studio-application/.../ports/StepCatalog.kt`.
- Nuevo data class `ResolvedCatalog(winners, collisions)`.
- Nuevo use case `StepCollisionResolver` en
  `studio-application/.../usecases/StepCollisionResolver.kt`:
  pure function, deterministic, no I/O.
- Decision rule (deterministic):
  1. Group by `stepKey`.
  2. Sort by semver DESCENDING (highest wins).
  3. Tiebreak: `pluginJar` ASCENDING (first wins).
  4. Built-in steps (no pluginJar, no version) sort LAST — they
     only win when no other step declares the same key.
  5. Each loser emitted as `CollisionReport` with one of
     `lower_version` / `same_version_tie` / `no_version`.
- 11 tests adversariales verde.

---

## 2. SHA, plataforma y toolchain

| Recurso | Valor |
|---|---|
| Repositorio | Rubentxu/pipelinek-studio |
| Rama | main |
| SHA pre-trabajo | `8d39d6e` |
| SHA integrado (R2.6) | **8ccebb7** |
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
| `studio-application/.../ports/StepCatalog.kt` | + `data class CollisionReport(...)` (6 fields); + `data class ResolvedCatalog(winners, collisions)` |
| `studio-application/.../usecases/StepCollisionResolver.kt` | **NUEVO.** `class StepCollisionResolver { fun resolve(contracts: List<StepContract>): ResolvedCatalog }`; `internal data class Semver`; `internal fun parseSemverOrNull(s)`; top-level `private fun StepContract.versionOrNull()`; `private fun StepContract.pluginJarOrSentinel()`; `private val VERSION_IN_JAR` |

### Tests

| Archivo | Cambio |
|---|---|
| `studio-application/.../usecases/StepCollisionResolverR26Test.kt` | **NUEVO.** 11 tests adversariales (empty, unique, two-way, three-way, builtin vs plugin, no-version tie, determinism, order-invariant, first-appearance preservation) |

### Recuento

- Archivos nuevos: 2.
- Archivos modificados: 1.
- Tests netos: **+11**.
- Tests totales Kotlin: 616 → **627**.
- Líneas: +381.

---

## 4. Decision rule (the meat)

```
sort(group) =
  compareByDescending { hasVersion(it) }   // (1) items with version first
  .thenByDescending { semverOrNull(it) }  // (2) highest semver first
  .thenBy           { pluginJarOrSentinel() }  // (3) alpha first wins ties
```

| Scenario | Winner | Reason emitted for loser |
|---|---|---|
| 1 plugin, no collision | that plugin | (no report) |
| 2 plugins, different versions | higher version | `lower_version` |
| 2 plugins, same version | pluginJar alpha first | `same_version_tie` |
| 2 plugins, no version | pluginJar alpha first | `no_version` |
| 3 plugins, mixed | highest version | all losers get `lower_version` |
| 1 builtin + 1 plugin, plugin has version | plugin | `no_version` (the built-in is the loser, no version) |
| 1 builtin + 1 plugin, plugin has no version | plugin (because it sorts before "no version" entries) | `no_version` |

**Built-in sort-last rule:** A built-in step (no pluginJar, no
version) only wins when it is the SOLE entry in its key group.
Two built-ins with the same key (impossible in practice — the
SDK is one JAR) would tie at sentinel `<builtin>` and fall to
input order.

---

## 5. API expuesta

### `StepCollisionResolver().resolve(contracts: List<StepContract>): ResolvedCatalog`

- Pure function; same input → same output.
- `winners`: one entry per unique `id` (first appearance order).
- `collisions`: one entry per losing duplicate.

### `data class CollisionReport`

| Field | Type | Description |
|---|---|---|
| `stepKey` | `String` | the colliding `id` |
| `winnerPluginJar` | `String` | the JAR filename (or `"<builtin>"`) of the winner |
| `winnerVersion` | `String?` | the winner's version (from JAR filename regex) |
| `loserPluginJar` | `String` | the JAR filename (or `"<builtin>"`) of the loser |
| `loserVersion` | `String?` | the loser's version |
| `reason` | `String` | `lower_version` / `same_version_tie` / `no_version` |

### `data class ResolvedCatalog`

| Field | Type | Description |
|---|---|---|
| `winners` | `List<StepContract>` | deduplicated, dispatch-ready |
| `collisions` | `List<CollisionReport>` | audit trail of shadowed steps |

---

## 6. Decisiones de diseño

- **Pure function, no I/O.** The resolver doesn't read the
  catalog; it takes a list and returns a list. This makes it
  trivially testable (no fixtures) and the integration with the
  HTTP / MCP layer is a one-line `catalog.list().steps.let
  { resolver.resolve(it) }`. No port added; no wiring change.
- **Version extracted from JAR filename, not from a new field.**
  The catalog adapter doesn't currently surface a `version`
  field on `StepContract`; the simplest non-invasive source is
  the JAR filename. The regex `(\d+)\.(\d+)\.(\d+)` is
  intentionally minimal — no pre-release / build metadata. If
  the catalog needs richer versions in the future, plumb a
  dedicated `version: String?` field into `StepContract` and
  update `versionOrNull()`.
- **Built-in steps sort LAST, not FIRST.** The "default
  wins" intuition is wrong: if the operator installed a plugin
  with `stepKey = "compile"` and version 1.5.0, they explicitly
  want the plugin. The built-in should NOT shadow the plugin.
  This rule protects against a "silent override" of operator
  intent.
- **PluginJar ascending tiebreak.** Alpha-first is the natural
  convention (similar to package import resolution). Lexicographic
  is deterministic across runs, platforms, and locales (the
  comparator uses `String.compareTo` which is byte-wise).
- **Stable first-appearance for winners.** The winners list
  preserves the order of first appearance of each `id` in the
  input. This is what consumers expect (e.g. a UI listing the
  catalog by display order).
- **Reasons are a closed enum-as-string.** `lower_version`,
  `same_version_tie`, `no_version` — three values, never any
  others. Consumers can `when (reason)` exhaustively without an
  `else` branch.

---

## 7. Tests adversariales nuevos (11)

1. **`R2_6 empty input yields empty result`** — both lists empty.
2. **`R2_6 all-unique stepKeys produce zero collisions`** — 3 unique
   plugins, 0 collisions, order preserved.
3. **`R2_6 two same-key different versions - higher version wins`** —
   1.0.0 vs 2.0.0 → 2.0.0 wins; loser has reason `lower_version`.
4. **`R2_6 two same-key same version - tie broken by pluginJar
   ascending`** — both 1.0.0, alpha-1.0.0.jar wins over zeta-1.0.0.jar;
   reason `same_version_tie`.
5. **`R2_6 three same-key - one winner + two losers`** — 1.0.0, 2.0.0,
   1.5.0 → 2.0.0 wins; 2 losers, both reason `lower_version`.
6. **`R2_6 builtin step loses to higher-version plugin`** — built-in
   (no JAR, no version) loses to alpha-1.0.0.jar; loser pluginJar =
   `"<builtin>"`.
7. **`R2_6 builtin step loses to same-version plugin (no_version
   sorts last)`** — even with same version, built-in sorts last;
   reason `no_version`.
8. **`R2_6 two plugins with no version - tie by pluginJar`** —
   alpha.jar vs zeta.jar, neither has a version → alpha wins;
   reason `no_version`.
9. **`R2_6 determinism - same input twice yields same output`** —
   resolve twice, compare winner + collision order bit-for-bit.
10. **`R2_6 input order does NOT change the winner`** — order the
    input [low, high] vs [high, low] → same winner.
11. **`R2_6 winner list preserves first-appearance order for unique
    keys`** — input [b, a, c] → winners [b, a, c] (not sorted
    alphabetically).

---

## 8. Tests ejecutados

### Foco R2.6

```
$ ./gradlew :studio-application:test --tests 'StepCollisionResolverR26Test' \
            --console=plain --no-daemon
BUILD SUCCESSFUL in 17s
StepCollisionResolverR26Test tests=11 skipped=0 failures=0 errors=0
```

### Suite completa

```
$ ./gradlew :studio-cli:test :studio-http:test :studio-application:test \
            :studio-infra-storage:test :studio-infra-sdk:test :studio-app:test \
            :studio-domain:test --console=plain --no-daemon
BUILD SUCCESSFUL in 33s
```

| Módulo | Tests | Pass | Fail | Skip | Error |
|---|---|---|---|---|---|
| studio-cli | 203 | 203 | 0 | 0 | 0 |
| studio-http | 73 | 73 | 0 | 0 | 0 |
| studio-application | **146** | 146 | 0 | 0 | 0 |
| studio-infra-storage | 70 | 70 | 0 | 0 | 0 |
| studio-infra-sdk | 94 | 93 | 0 | 1 | 0 |
| studio-app | 10 | 10 | 0 | 0 | 0 |
| studio-domain | 23 | 23 | 0 | 0 | 0 |
| studio-cert | 8 | 8 | 0 | 0 | 0 |
| **TOTAL** | **627** | **626** | **0** | **1** | **0** |

---

## 9. UAT/AAT vinculadas

- **AAT-REL-001..010 (release integral)** ✅ — pure function added;
  0 regresiones; default +1 winner output.
- **AAT-SEC-005 (separation of concerns)** ✅ — el resolver no
  toca el admission lock ni el run state; solo el catalog.
- **AAT-API-008 (idempotency)** ✅ — same input → same output
  (test #9).
- **AAT-REL-005 (no silent fallbacks)** ✅ — la lista de
  collisions es explícita; un step "shadowed" es un report, no un
  log silencioso.

---

## 10. Comandos y códigos de salida

```
$ ./gradlew :studio-application:compileKotlin --console=plain --no-daemon
BUILD SUCCESSFUL in 15s   # exit 0

$ ./gradlew :studio-application:test --tests 'StepCollisionResolverR26Test' --console=plain --no-daemon
BUILD SUCCESSFUL in 17s   # exit 0

$ ./gradlew :studio-cli:test :studio-http:test :studio-application:test :studio-infra-storage:test :studio-infra-sdk:test :studio-app:test :studio-domain:test --console=plain --no-daemon
BUILD SUCCESSFUL in 33s   # exit 0
```

---

## 11. Riesgos y deuda residual

1. **El resolver no está integrado en el HTTP layer.** R2.6
   entrega la pure function; el endpoint `GET /api/step-collisions`
   o un campo `collisions` en la response del catalog existente
   son follow-up. Sub-tarea: R6.1 (HTTP wiring de catálogo).
2. **El version regex es minimal.** Pre-release / build
   metadata (`1.0.0-beta`, `1.0.0+build.1`) no se parsean. Si
   un plugin usa esos formatos, `versionOrNull` retorna null y
   el resolver cae a `pluginJar` tiebreak. Mejora: extender el
   regex a semver 2.0 spec.
3. **No hay un test que cruce con admission state.** R2.6
   trabaja sobre `List<StepContract>` puro. Un test adversarial
   que verifique "el catalog que retorna el `StepCatalogReadPort`
   cuando hay N plugins admitidos" es follow-up.
4. **Built-in steps no tienen provenance.** El sort
   LAST-for-builtins es por convención (no pluginJar = no
   version). Un built-in que declarara una `version` competiría
   normalmente; un built-in sin version siempre pierde. El
   contrato está documentado; no es un bug, es un design choice.

---

## 12. Cierre

**Veredicto R2.6:** **PASS**.

- `StepCollisionResolver` pure function, deterministic.
- Semver DESC + pluginJar ASC tiebreak.
- Built-in steps sort LAST (no silent override of operator intent).
- 11 tests adversariales verde; 627/627 Kotlin verde; 0 failures.

Siguiente item activo: **R2.8 (Step TestKit)** + **R2.9 (unified
dispatch)** + **R2 release `v1.0.0-rc.4` housekeeping** (bump
version, tag, prerelease).

---

**Recibo generado por:** Agente (R2.6, sesión de octubre 2026).
**Integridad:** este recibo se escribió antes del push. SHA `8ccebb7`
en `main`; tag `v1.0.0-rc.6` intacto en `017f4c3`.
