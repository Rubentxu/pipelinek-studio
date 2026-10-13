# SFW H1 (Studio sobre Fabric Workers) — Interim Release Receipt

**FECHA:** 2026-10-13
**HEAD pre-trabajo:** `6017ad0 feat(sfw,h1): B1/H1 Phase B Part 3 — UAT-SFW-H1-001 (Fabric container smoke test)`
**HEAD post-trabajo:** `b982383 docs(sfw,h1): SFW H1 status snapshot (2026-10-13)`
**Bloque:** SFW (Studio sobre Fabric Workers) — sub-bloque **H1-CLOSE-INTERIM** (release housekeeping previo al tag `v1.0.0`)
**Resultado:** **INTERIM PASS** — todo el trabajo Studio-side cerrado (7 commits, 20 falsifier tests añadidos, 1004/1004 PASS). Tag `v1.0.0` NO publicado: bloqueado por los 4 gaps operator-driven (B0, B1, B2, B4) que requieren handoff del operador.

---

## 1. Resumen ejecutivo

**Qué se pidió:** cerrar el H1 del mandato SFW (Studio sobre Fabric Workers) con código, tests, evidence, y operador-ready surface.

**Estado pre-H1-CLOSE-INTERIM:** Phase A (Maven contracts) cerrado (`3ad2360`); Phase B Part 1 (Dockerfile.fabric-server) cerrado en Fabric (`134dfd8a`). La rama `b1-sfw-h2-2c-studio-infra-fabric` tenía 3 commits.

**Estado post-H1-CLOSE-INTERIM:**
- 7 commits nuevos en `b1-sfw-h2-2c-studio-infra-fabric` (4 Studio-side closes + 1 wire + 1 coverage + 1 status doc).
- Phase B Part 2 (Studio-side container launcher) cerrado.
- Phase A.2 chain (guard + fix + wire) cerrado end-to-end.
- Phase B Part 3 (UAT smoke test) cerrado.
- Rejection coverage gap (3 arms nuevos) cerrado.
- 20 falsifier tests añadidos (Studio suite 984 → 1004 PASS).
- Status doc publicado en `docs/04-roadmap/SFW_H1_STATUS_2026_10_13.md` con análisis del codec gap.

**Lo que queda para `v1.0.0`:** 4 gaps operator-driven documentados; el studio está listo para consumir cualquier resolución.

---

## 2. SHA, plataforma y toolchain

| Recurso | Valor |
|---|---|
| Repositorio | Rubentxu/pipelinek-studio |
| Rama | `b1-sfw-h2-2c-studio-infra-fabric` |
| SHA pre-trabajo | `6017ad0` |
| SHA del tag candidato `v1.0.0` | **`b982383`** (provisional, pendiente de B4/B5) |
| Tag anotado (push) | ❌ NO publicado (B5) |
| Predecesor intacto | `v1.0.0-rc.17` en `f2e13d9` (tag rc, sin tocar) |
| Plataforma | linux 7.2.9-ogc3.1.fc44.x86_64 |
| Java | OpenJDK 21.0.8 |
| Gradle | 8.14.5 |
| Kotlin | 2.4.20 |
| Node | 25.9.0 |
| Podman | (operator's host) |
| Versión Studio | `1.0.0-rc.17` (`gradle.properties:studio.version` — sin bump) |
| PipelineK pin | `v0.56.0` upstream (2dfe7396) — B0 pendiente de pin en Fabric |
| Fabric HEAD | `134dfd8a` (Phase B Part 1 cerrado; contracts publish `64b8213a`) |

---

## 3. Cambios de H1-CLOSE-INTERIM (7 commits)

| SHA | Subject | Tests añadidos | Suite total |
|---|---|---|---|
| `54c715d` | feat(sfw,h1): B1/H1 Phase B Part 2 — Studio-side FabricContainerLauncher | 5/5 | 989 PASS |
| `e0b7df8` | feat(sfw,h1): B1/H1 Phase A.2 — verifyFabricContracts guard (Studio-side) | 7/7 | 996 PASS |
| `3465759` | feat(sfw,h1): B1/H1 Phase A.2 Part 2 — publishFabricContractsLocal auto-publish | 5/5 | 1001 PASS |
| `789a2e2` | feat(sfw,h1): B1/H1 Phase A.2 Part 3 — compileKotlin dependsOn publishFabricContractsLocal | (wiring, 0) | 1001 PASS |
| `6017ad0` | feat(sfw,h1): B1/H1 Phase B Part 3 — UAT-SFW-H1-001 (Fabric container smoke test) | 5/5 self-test | 1001 PASS |
| `378ac8b` | test(sfw,h2): close coverage gap — 3 falsifier tests for the H2.2 Rejection arms | 3/3 | 1004 PASS |
| `b982383` | docs(sfw,h1): SFW H1 status snapshot (2026-10-13) | (docs, 0) | 1004 PASS |

**Suma: 20 falsifier tests nuevos** (5+7+5+0+5+3+0 = 25 nominales, pero el wiring commit no añade tests; 5+7+5+5+3 = 25 tests, aunque 5 del UAT son self-test y no se cuentan en la suite Gradle).

**Recuento real en la suite Gradle: 1004 - 984 = 20 tests nuevos** (los del UAT self-test no son JUnit; se ejecutan vía `bash uat-sfw-h1-001-fabric-container.sh --self-test`).

---

## 4. Artefactos y hashes

### Studio (este commit)

- **No hay artefactos de release** — el H1 no es un release de Studio. Es un work-in-progress cuyo release es el tag `v1.0.0` futuro (B5).
- El `v1.0.0-rc.17` previo (tag `f2e13d9`) NO se ha movido.

### UAT script (`scripts/uat-sfw-h1-001-fabric-container.sh`)

- 256 líneas de bash ejecutable, exit codes 0/1/2/3/4 documentados.
- Self-test mode: 5/5 falsifier PASS sin podman.
- End-to-end mode: 4 fases (pre-checks → start → wait-for-port → stop), verificado contra `localhost/mock-grpc:test` (Python http.server mock).

### Status doc (`docs/04-roadmap/SFW_H1_STATUS_2026_10_13.md`)

- 143 líneas Markdown.
- Tabla per-iteration con commits, funciones, tests.
- Sección del codec gap (B1) con las 2 opciones para el operador.
- Recetas de uso end-to-end.

### Rejection coverage (commit `378ac8b`)

- 3 falsifier tests añadidos a `FabricRunnerAdapterTest.kt`:
  - `submit with Rejection PackageTooLarge is refused with size detail`
  - `submit with Rejection PackageDigestMismatch is refused with both digests`
  - `submit with Rejection AlreadyExistsWithDifferentBytes is refused with both digests`
- Cubre el código existente en `FabricRunnerAdapter.mapRejection` (líneas 178-197).

### Gradle plugin (`studio-build-logic/`)

- Composite build (`pluginManagement { includeBuild("studio-build-logic") }`).
- 3 tasks registradas en el root build:
  - `verifyFabricContracts` (group: verification) — guard, 3-arm status.
  - `publishFabricContractsLocal` (group: verification) — fix, idempotent.
  - El `compileKotlin.dependsOn(":publishFabricContractsLocal")` wire está en `studio-infra-fabric/build.gradle.kts`.
- 12/12 falsifier tests PASS (7 verifier + 5 publisher) en `:studio-build-logic:test`.

---

## 5. Tests ejecutados

### Studio suite completa

```bash
$ ./gradlew test --no-configuration-cache
BUILD SUCCESSFUL in 36s
51 actionable tasks: 34 executed, 17 up-to-date

$ find . -name "TEST-*.xml" -path "*/test-results/*" \
    | xargs grep -h "tests=" \
    | grep -oE 'tests="[0-9]+"' \
    | grep -oE '[0-9]+' \
    | awk '{s+=$1} END {print "Total tests:", s}'
Total tests: 1004
```

### `:studio-build-logic` (falsifier del Phase A.2)

```bash
$ ./gradlew :studio-build-logic:test --no-configuration-cache
> Task :studio-build-logic:compileKotlin
> Task :studio-build-logic:compileTestKotlin
> Task :studio-build-logic:test
BUILD SUCCESSFUL in 11s
```

7 falsifier tests `FabricContractsVerifierTest` + 5 falsifier tests `FabricPublisherTest` = **12/12 PASS**.

### `:studio-infra-fabric` (falsifier del Phase B Part 2 + Rejection coverage)

```bash
$ ./gradlew :studio-infra-fabric:test \
    --tests "FabricContainerLauncherH1PhaseBTest" \
    --no-configuration-cache
BUILD SUCCESSFUL in 2s
```

5/5 falsifier PASS.

```bash
$ ./gradlew :studio-infra-fabric:test \
    --tests "FabricRunnerAdapterTest" \
    --no-configuration-cache
BUILD SUCCESSFUL in 4s
```

13/13 falsifier PASS (10 originales + 3 nuevos del commit `378ac8b`).

### UAT self-test (bash, no Gradle)

```bash
$ bash scripts/uat-sfw-h1-001-fabric-container.sh --self-test
== self-test: 5 falsifier scenarios (no podman required) ==
  ✅ F1 --help exits 0
  ✅ F2 default tag is non-empty: fabric-server:0.5.0-R1-alpha.8
  ✅ F3 ok/fail helpers are defined
  ✅ F4 EXIT trap is set (cleanup will run)
  ✅ F5 GRPC_PORT is a valid port number: 50051

== self-test: 5/5 PASS ==
```

### UAT end-to-end (mock image)

```bash
$ podman build -f /tmp/Dockerfile.mock -t mock-grpc:test /tmp/
# (mock = python:3.12-alpine + http.server on 50051)

$ bash scripts/uat-sfw-h1-001-fabric-container.sh localhost/mock-grpc:test
== UAT-SFW-H1-001: Fabric server container ==
  pre-checks PASS
  container started: uat-sfw-h1-fabric-609706
  port 127.0.0.1:50051 is open (after 0s)
  ⚠️  grpcurl not on PATH; skipping gRPC health probe
  container stopped cleanly
== UAT-SFW-H1-001: PASS ==
```

### End-to-end del auto-publish (gradle, real repo)

```bash
$ ./gradlew publishFabricContractsLocal --no-configuration-cache
> Task :publishFabricContractsLocal
publishFabricContractsLocal — nothing to do (all 6 contracts at 0.5.0-R1-alpha.8)
BUILD SUCCESSFUL in 8s
```

### End-to-end del wire (gradle, --rerun-tasks)

```bash
$ ./gradlew :studio-infra-fabric:compileKotlin --no-configuration-cache --rerun-tasks
> Task :publishFabricContractsLocal          ← corre automático como pre-step
> Task :studio-domain:compileKotlin
> Task :studio-application:compileKotlin
> Task :studio-infra-podman:compileKotlin
> Task :studio-infra-fabric:compileKotlin
BUILD SUCCESSFUL in 6s
12 actionable tasks: 12 executed
```

---

## 6. Verificación remota

**No realizada** — el gate B4 (cert en máquina limpia) está pendiente. La verificación remota del H1 completo requiere:

1. Construir la imagen Fabric (`PipelinekFabric/scripts/build-fabric-image.sh`) — gated por B1 (codec gap).
2. Correr `scripts/uat-sfw-h1-001-fabric-container.sh` contra la imagen real — listo.
3. Cert H1-pair-certified en máquina limpia — B4.
4. Tag `v1.0.0` desde este SHA o desde un merge a `main` — B5.

---

## 7. UAT/AAT vinculadas

- `scripts/uat-sfw-h1-001-fabric-container.sh` — el UAT del H1 (recién añadido, `6017ad0`).
- Sin AATs nuevos (el H1 es work-in-progress, no un release con AAT formal).

---

## 8. Comandos y códigos de salida

| Comando | Exit | Notas |
|---|---|---|
| `./gradlew test` | 0 | 1004/1004 PASS |
| `./gradlew verifyFabricContracts` | 0 | "all 6 Fabric contracts at 0.5.0-R1-alpha.8" |
| `./gradlew publishFabricContractsLocal` | 0 | Idempotente (skip cuando ALL_OK) |
| `./gradlew :studio-infra-fabric:compileKotlin` | 0 | El publish task corre como pre-step |
| `bash scripts/uat-sfw-h1-001-fabric-container.sh --self-test` | 0 | 5/5 falsifier PASS |
| `bash scripts/uat-sfw-h1-001-fabric-container.sh <image>` | 0/1/2/3/4 | 0=PASS, 1=pre-cond, 2=liveness, 3=gRPC, 4=cleanup |
| `bash scripts/uat-sfw-h1-001-fabric-container.sh` (sin imagen) | 1 | Mensaje claro con build command + codec gap |

---

## 9. Riesgos y deuda residual

### Studio-side — cerrado en esta sesión

- ✅ "I forgot to republish" failure mode cerrado (guard + fix + wire).
- ✅ Phase B container launch cerrado (Studio-side; complement de la imagen Fabric).
- ✅ Rejection coverage cerrado (3 falsifier tests para los 3 arms nuevos).

### Operator-driven — pendiente

| ID | Tarea | Quién | Bloquea |
|---|---|---|---|
| B0 | Pin Fabric → PipelineK v0.56.0 | operator | v1.0.0 |
| B1 | Decisión sobre los 3 `Rejection` arms (option A: add to Rejection proto, option B: use PackageRejection proto) | operator | imagen Fabric (gated) |
| B2 | Run UAT-SFW-H1-001 contra `fabric-server:0.5.0-R1-alpha.8` real | operator | B1 + verificación remota |
| B4 | H1-pair-certified cert en máquina limpia | operator | v1.0.0 |
| B5 | Tag `v1.0.0` (D-007 + B4) | operator | release |

### Estado del codec gap (B1) — análisis

`Rejection` proto: **4 arms** (NotFound, Conflict, NotPermitted, UnsupportedVersion).
`Rejection` Kotlin sealed interface: **7 arms** (los 4 + 3 nuevos: PackageTooLarge, PackageDigestMismatch, AlreadyExistsWithDifferentBytes).
`PackageRejection` proto: **5 arms** (TooLarge, DigestMismatch, NotFound, AlreadyExistsWithDifferentBytes, CapabilitiesNotMet).
`CommandCodec.kt` (en `adapters/control-api/codec/src/main/kotlin/io/jenkins/pipelinek/fabric/control/codec/`): `when` exhaustivo solo para los 4 arms del `Rejection` proto.

**Studio está listo para consumir ambas opciones de B1.** La decisión es de diseño de API en el lado Fabric.

### Deuda menor

- `FabricPublisher.resolveFabricDir()` companion object es dead code (el plugin resuelve fabricDir via `target.findProperty` + `target.rootProject.projectDir` directamente). Se podría refactorizar; no es bloqueante.
- El path del `FABRIC_DIR` env var en `FabricPublisher` no se usa (Gradle property `fabric.dir` tiene precedencia; documentado en el comment).

---

## 10. Tags publicados en este commit

**NINGUNO.** El tag `v1.0.0` está pendiente de B5 (operator handoff). El tag `v1.0.0-rc.17` previo (en `f2e13d9`) sigue intacto.

---

## Próximos pasos

1. Operator handoff para B0 + B1 (los 2 gaps estructurales).
2. Una vez B1 cerrado, build de la imagen Fabric + run UAT-SFW-H1-001 = B2 done.
3. Operator handoff para B4 (cert en máquina limpia).
4. Tag `v1.0.0` desde `b982383` o desde un merge a `main` (decisión del operator) = B5 done.
5. PR a `main` con la rama `b1-sfw-h2-2c-studio-infra-fabric` (D-007 aplica — push ya hecho; PR pendiente).
