# SFW H1 (Studio sobre Fabric Workers) Status — 2026-10-13

**Authoridad:** este documento es el snapshot de auditoría SFW H1
tras la sesión del 2026-10-13. **NO reemplaza ROADMAP.md** — es un
complemento de una sola sesión para situar al operador en lo que
queda para el release candidate del H1.

**HEAD auditado:** `378ac8b test(sfw,h2): close coverage gap — 3 falsifier tests for the H2.2 Rejection arms`
**Rama:** `b1-sfw-h2-2c-studio-infra-fabric` (push OK; no merged a `main` aún)
**Plataforma:** linux 7.2.9-ogc3.1.fc44.x86_64 · OpenJDK 21.0.8 · Gradle 8.14.5
**PipelineK v0.56.0 publicada upstream:** `2dfe7396`
**Fabric HEAD actual:** `134dfd8a` (en `b1-sfw-h2-snapshot-contract`)
**Studio HEAD actual:** `378ac8b` (Phase B Part 2 + A.2 chain + 3 Rejection tests)

---

## Estado por unidad cerrada en esta sesión

| Hito SFW | Commit Studio | Función | Tests añadidos | Suite total |
|---|---|---|---|---|
| **H1 Phase B Part 2** | `54c715d` | `FabricContainerLauncher` (wraps `PodmanCommandRunner` para arrancar/parar container Fabric) | 5/5 | 989 PASS |
| **H1 Phase A.2 Part 1** | `e0b7df8` | `verifyFabricContracts` (gradle task; guard de 3-arm status) | 7/7 | 996 PASS |
| **H1 Phase A.2 Part 2** | `3465759` | `publishFabricContractsLocal` (gradle task; auto-publish idempotente) | 5/5 | 1001 PASS |
| **H1 Phase A.2 Part 3** | `789a2e2` | `compileKotlin.dependsOn(publish)` (el wire; cierre del failure mode) | (sin tests nuevos — wiring) | 1001 PASS |
| **H1 Phase B Part 3** | `6017ad0` | UAT script `uat-sfw-h1-001-fabric-container.sh` (smoke test del container) | 5/5 self-test | 1001 PASS |
| **H1 H2.2 coverage** | `378ac8b` | 3 falsifier tests para `Rejection.PackageTooLarge`, `PackageDigestMismatch`, `AlreadyExistsWithDifferentBytes` | 3/3 | **1004 PASS** |

**Total commits Studio H1 esta sesión: 6.** Test suite: 984 → 1004 (+20).

---

## Estado por unidad pendiente (operator-driven)

| ID | Tarea | Quién | Bloquea |
|---|---|---|---|
| **B0** | Pin Fabric → PipelineK v0.56.0 (ninguna rama live de Fabric apunta a `v0.56.0` publicada aún) | operator | cierre H1 release candidate |
| **B1** | Decidir el routing de los 3 `Rejection` package-arms (¿añadir al `Rejection` proto de 4 arms, o usar el `PackageRejection` proto de 5 arms?) — **Studio ya está listo para consumir cualquiera de las dos opciones** (los 3 tests recién añadidos cierran el gap del lado consumer) | operator | opcional; Studio OK como está |
| **B2** | Correr `uat-sfw-h1-001-fabric-container.sh` contra la imagen `fabric-server:0.5.0-R1-alpha.8` real (B1 debe estar cerrado primero si la decisión es añadir 3 arms al `Rejection` proto, porque la imagen debe regenerar) | operator | verificación remota |
| **B4** | Cert H1-pair-certified en máquina limpia | operator | tag `v1.0.0` |
| **B5** | Tag `v1.0.0` (D-007 + B4 doble-gate) | operator | release |

---

## Resumen del codec gap (B1) — para el operador

Descubierto en auditoría 2026-10-13 con `codegraph_explore` + grep manual:

### El gap

El sealed interface `Rejection` en `PipelinekFabric/contracts/model/src/main/kotlin/io/jenkins/pipelinek/fabric/contracts/model/CommandVocabulary.kt` declara **7 arms** (líneas 58, 63, 70 + las originales):

- `NotFound`, `Conflict`, `NotPermitted`, `UnsupportedVersion` (4 originales, en proto + codec)
- `PackageTooLarge`, `PackageDigestMismatch`, `AlreadyExistsWithDifferentBytes` (3 nuevos — **no en el proto, no en el codec**)

El `Rejection` proto en `fabric_control.proto` líneas 709-716 tiene solo los 4 originales. El `CommandCodec.kt` líneas 178-217 tiene un `when` exhaustivo solo para los 4.

El `PackageRejection` proto SÍ tiene 5 arms (incluyendo los 3 nuevos) — pero vive en un mensaje separado, no en `Rejection`.

### Lo que ya está bien (Studio-side)

`FabricRunnerAdapter.mapRejection` (Studio `studio-infra-fabric/src/main/kotlin/dev/pipelinek/studio/infra/fabric/FabricRunnerAdapter.kt` líneas 178-197) ya maneja los 3 arms nuevos con un renderizado human-readable. **3 falsifier tests recién añadidos (commit `378ac8b`) cierran la cobertura.**

### Las dos opciones para el operador

**Opción A — añadir los 3 arms al `Rejection` proto de 4 arms:**
- Pro: el `Rejection` Kotlin sealed interface ya los declara; alinea el wire con el contract.
- Contra: contradice el KDoc del codec que dice "Rejection es la familia cerrada de command-side refusals". Mezcla command-side y package-side.

**Opción B — usar el `PackageRejection` proto existente (5 arms):**
- Pro: respeta la separación command-side / package-side.
- Contra: requiere refactor del `Rejection` Kotlin sealed interface para mover los 3 arms a `PackageRejection`.

**Studio está listo para ambas opciones** — el consumer es agnóstico al wire shape. La decisión es puramente de diseño de API en el lado Fabric.

---

## Cómo se usa lo que ya está (operador + devs)

### Build normal (todo ya automatizado)

```bash
# El primer build corre publishFabricContractsLocal automáticamente
# (idempotente; skip si todos los 6 contratos están a la versión esperada).
./gradlew build
```

### Verificación manual del estado

```bash
# Solo el guard (sin auto-publish).
./gradlew verifyFabricContracts
# → verifyFabricContracts OK — all 6 Fabric contracts at 0.5.0-R1-alpha.8
```

### Forzar el publish (escape hatch)

```bash
./gradlew publishFabricContractsLocal
# → publishFabricContractsLocal — nothing to do (all 6 contracts at 0.5.0-R1-alpha.8)
```

### Override del path de Fabric

```bash
./gradlew publishFabricContractsLocal -Pfabric.dir=/path/to/PipelinekFabric
```

### Smoke test del container (cuando la imagen exista)

```bash
# Construir la imagen (gated por B1):
cd PipelinekFabric && ./scripts/build-fabric-image.sh

# Smoke test:
./scripts/uat-sfw-h1-001-fabric-container.sh
# o contra una versión específica:
./scripts/uat-sfw-h1-001-fabric-container.sh fabric-server:0.5.0-R1-alpha.8

# Self-test (no requiere podman, corre en CI):
./scripts/uat-sfw-h1-001-fabric-container.sh --self-test
```

---

## Estado del test suite (snapshot)

```
Total tests: 1004
Studio: 1004/1004 PASS
Fabric: n/a (no corrido en esta sesión)
PipelineK: n/a (no corrido en esta sesión)
```

---

## Próximas unidades (no Studio-side; todas operator-driven)

1. **B0** — pin Fabric → PK v0.56.0
2. **B1** — decisión sobre los 3 `Rejection` arms (documentada arriba)
3. **B4** — cert en máquina limpia
4. **B5** — tag `v1.0.0`

Cuando B1 esté cerrado, el H1 release candidate es un `git tag v1.0.0` desde el HEAD actual de la rama `b1-sfw-h2-2c-studio-infra-fabric` (o desde un merge a `main`).
