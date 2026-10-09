# Protocolo obligatorio — integración en main + release remota por bloque

**Política Studio:** CI local, integración frecuente y versiones verificables. No usar GitHub Actions como prueba sustitutiva de release. No confundir `git commit`, `git tag` local, `git push` y GitHub Release: son operaciones diferentes que se verifican una a una.

## R0 — Conciliación y seguridad de Git

```bash
set -euo pipefail
git status --short --branch
git remote -v
git fetch origin --prune --no-tags
git ls-remote --heads origin main
git ls-remote --tags origin 'v*'
git log -1 --format='%H %s' HEAD
```

- Determinar upstream correcto, commit main remoto, rama de trabajo y branches/tags desconocidos.
- **No** `git fetch --tags` si existen tags divergentes; comparar referencias explícitas primero y no sobrescribir anotados publicados.
- No force push ni borrar tags remotos. No ejecutar sobre repo equivocado ni incluir datos de usuario en commits.
- Si existen commits ajenos ahead/behind, no inventar «remote atrasado»: conciliar ancestros antes de merge/rebase; STOP ante historial inesperado.
- Confirmar que los artefactos externos de PipelineK consumidos realmente tienen release/digest y APIs compatibles; usar lock/pinned dependency en Studio.

## R1 — Baseline de ejecución local

Documentar OS/arquitectura/kernel/JDK, versión Kotlin, Gradle, Node/pnpm, MCP SDK, Detekt, PipelineK SHA/artifact. Tests iniciales desde árbol limpio. No interpretar `docs/history` como estado actual de código.

## R2 — Implementación y tests focales

- Commits pequeños `feat(studio):...`, `fix(studio):...`, `test(...):...`, `docs(...):...`.
- Durante desarrollo: tests focales y mutaciones de guards concretos; reparar defectos encontrados antes de ampliar UX.
- No `skip` silenciosos, mocks solo para tests unitarios de UI o errores, nunca para criterio de UAT instalado real.
- No sobrescribir `ROADMAP.md` con progreso aspiracional. Registrar decisiones en SDDK; el `docs/history/` del repo solo recoge receipts verdaderos.

## R3 — Gate de integración

Ejecutar **sin caché engañosa**, desde commit candidato:

```bash
./gradlew check --rerun-tasks --console=plain
pnpm install --frozen-lockfile
pnpm -r lint
pnpm -r typecheck
pnpm -r test
pnpm -r build
```

Ajustar comandos a scripts realmente existentes y validarlos durante S0. Ejecutar UAT/AAT del bloque y regresión de hitos previos. El `--rerun-tasks` solo aplica Gradle; no inventar equivalente para pnpm. Compilar distribución, instalar en path temporal sin árbol de fuentes y correr smoke. Capturar códigos de salida reales, tiempos, recuentos, tests skipped/disabled y motivos.

## R4 — Integración y promoción remota

1. Reconciliar `origin/main`, resolver conflictos de forma explícita y ejecutar suites otra vez si cambió tree o dependencia.
2. Integrar branch autorizada en `main` según estrategia del repo (merge/fast-forward), respetando commits Conventional y upstream; no reescribir refs ajenas.
3. Subir main y comprobar remoto `main` contiene `candidate_sha` como ancestro, y si corresponde es HEAD.
4. Crear tag **anotado, nuevo e inmutable** sobre commit exacto integrado. No usar nombre que ya exista en remoto con otro SHA.
5. Publicar en GitHub Release **prerelease** correspondiente al tag; subir distribuciones, checksums SHA256, SBOM/manifest y UAT/AAT receipts. Nunca marcar estable sin veredicto.
6. Validar desde nueva descarga de artefacto/tag, SHA de commit/tag y checksums, desde clon/instalación limpios.
7. Si el hosting remoto es inaccesible o permisos ausentes, **estado BLOQUEADO, no DONE**; facilitar comandos y evidencia local real sin afirmar que se publicó.

## Esquema de manifest de release

```json
{
  "schema": "studio.pipelinek.dev/release/v1",
  "studioVersion": "0.1.0-alpha.1",
  "gitCommit": "<SHA_REAL_DEL_COMMIT_MAIN>",
  "gitTag": "v0.1.0-alpha.1",
  "pipelinekRuntime": {"version":"<VERSION_REAL>","artifactSha256":"<DIGEST_REAL>"},
  "artifacts": [{"name":"studio-dist.zip","sha256":"<SHA256_REAL>","size":0}],
  "sbom": "studio-sbom.cdx.json",
  "validation": {"uatReceipt":"<RUTA>","aatReceipt":"<RUTA>","installedSmoke":"<RUTA>"}
}
```

Los ángulos son placeholders y **no** son un manifest válido de release. Generarlo automáticamente a partir del SHA real, archivos que existan, checksums y evidencia. Si el artefacto no existe, rechazar release; no crear un ZIP vacío para cerrar un gate.

## Cadena de evidencia

- `candidate SHA` = commit main autorizado; `distribution SHA256` deriva del binario exacto; `runtime artifact SHA256` del SDK/CLI del motor realmente instalado; `plugin digest` del JAR real.
- `test receipts` indican comandos, conteos, fallos/skips, plataforma, timestamp y proceso; UAT instalado ejecuta **los mismos artefactos publicados**, no clases locales recompiladas sin relación.
- Comparar `git rev-parse HEAD`, `git show-ref --tags`, `git ls-remote`, API release/tag y checksums descargados. Un SHA pre-amend no es actual.
- Retener SBOM, información licencia y provenance para terceros; registrar cambios de versión transitorios durante prerelease.
- No generar logs con secretos en artifacts. Revisión manual de redaction canary.

## AAT de release

`AAT-REL-001..010` de `AAT_AND_FITNESS.md` y todas las UAT del bloque. `S5` culmina en RC y stable solo con evaluación de readiness externa si existe, sin fallos graves. La rama principal puede seguir avanzando entre release candidates; no reinterpretar una release anterior como certificación del nuevo HEAD.

## Receipt obligatorio (plantilla)

```text
Block: Sx / version: v...
Main commit / candidate / tag SHA: ...
Upstream PipelineK runtime version + digest: ...
Commands/tests (exit codes, failures, skips): ...
UAT / AAT matrix: ...
Installer smoke / host OS/kernel: ...
Manifest + SBOM + artifact sha256: ...
GitHub release URL and remote verification: ...
Open risks: IDs, reason, owner, next trigger
Verdict: PASS | FAIL | BLOCKED (no other ambiguous DONE)
```

Si no se puede determinar test count/estado de una UAT, reflejar `NOT MEASURED`, nunca asumir «verde por ausencia de errores».
