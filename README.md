# PipelineK Studio — S0 bootstrap

Status: **BUILDABLE + TESTABLE (NOT DONE — release requires explicit user authorization and a real GitHub release pipeline run).**

This is the bootstrap from `ROADMAP.md` block **S0**. Full multi-block plan in [`odd/tasks/pipelinek-studio-bootstrap.md`](odd/tasks/pipelinek-studio-bootstrap.md).

## Layout

```text
studio-domain         // pure types: Experiment, SourceSnapshot, RunBinding, RunState, Sha256
studio-application    // hexagonal ports + use cases
studio-infra-storage  // SQLite (Flyway-migrated) + repositories
studio-infra-sdk      // PipelineK runtime probe + ProcessBuilder runner
studio-http           // Ktor REST API
studio-cli            // clikt CLI talking to the HTTP API
studio-app            // main runner binding the modules together
apps/mcp              // MCP v2 stdio adapter (TypeScript, S0 skeleton)
apps/web              // React+Vite+Monaco shell (S0 placeholder; real UI in S1)
```

## Toolchain

- JDK 21 LTS (Temurin) — pinned in `.tool-versions`.
- Gradle 8.14.5 — wrapper bundled.
- Node 25 — for `apps/mcp` + `apps/web`.
- No Docker required (deferred to S5).

## Run locally

```bash
# 1. Compile + unit tests
./gradlew test

# 2. Build distribution
./gradlew :studio-app:assemble

# 3. Run backend on http://127.0.0.1:7777
./gradlew :studio-app:run --args='--sdk-classpath=/absolute/path/to/pipeline-kotlin-application-0.48.0-rc1.jar'

# 4. CLI smoke
java -jar studio-cli/build/libs/studio-cli-0.1.0-alpha.1.jar doctor --json

# 5. Probe pipeline runtime version
curl -s http://127.0.0.1:7777/api/version | jq
```

> If `PipelineKRuntimeProbe.describe()` returns `Unavailable`, the backend prints a warning and refuses to admit runs. This is by design — see ADR-006 + D-007. Provide the SDK JAR via `--sdk-classpath=`.

## Honest status by S0 DoD

| DoD item | Status | Note |
|---|---|---|
| Domain types | ✅ | `studio-domain` — pure, framework-free |
| SQLite schema + migrations | ✅ | Flyway V1, idempotent |
| API endpoints | ✅ | Ktor routes for `experiments`, `snapshots`, `runs`, `events`, `output`, `health`, `version` |
| Runner supervisor | ⚠️ SKELETON | ProcessBuilder wired; SDK adapter lazy-resolves; forking + supervisor lands in next work-unit |
| MCP v2 stdio | ⚠️ SKELETON | `studio.health` only; tools/resources in S1 |
| CLI | ✅ | clikt subcommands, `--json` |
| Security spike (E0.10) | 🔲 Deferred | Documented in feature file; S0 only profile `TRUSTED_LOCAL` |
| Output read port (E0.11) | ⚠️ Refuses | Honest `OUTPUT_REFUSED`; lands S1 |
| UAT-001/002/009/013/014/015 | 🔲 Deferred | Require real SDK invocation + installed smoke; not in S0 bootstrap |
| AAT series | 🔲 Deferred | Per-block |
| GitHub release v0.1.0-alpha.1 | 🔒 **NOT DONE (deliberate)** | D-007: no remote mutation without explicit user authorization |

## What this bootstrap intentionally does NOT do

- Does not push to GitHub (no remote mutation without explicit user authorization).
- Does not mark `DONE` — release evidence (SHA, tag, SBOM, receipt) is the next gate and is owned by you.
- Does not pretend `analyze` is static analysis; that's U1 territory.
- Does not pretend runner is sandboxed; profile `TRUSTED_LOCAL` is JVM isolation, not OS sandbox.

## Next work-units (queued)

- E0.10: Security spike measuring runner containment on this OS.
- S0.Real-Run: integration test that downloads/pins `v0.48.0-rc1` artifact and runs an `echo.pipeline.kts` end to end.
- Release gate: PR to remote `main`, tag `v0.1.0-alpha.1`, GitHub prerelease with SBOM, smoke from clean clone.
