# Fix CRITICALs — v0.2.0-alpha.2

**Feature**: close all 7 CRITICAL findings from the 2026-10-10 senior audit.
**Why**: ROADMAP DoD for v1.0.0 requires "0 P0/P1 sin mitigación aceptada con evidencia real". These are the P0 set. They must be closed in code, not documented as "accepted risk".
**Where**: 7 modules, 9 files (C-1: 3 files; C-2..C-4: 1 file UseCases.kt + 1 AgentWorkflows.kt; C-5: 1 RateLimit.kt; C-6: 1 PipelineKRunner.kt; C-7: 1 PipelineKRuntimeProbe.kt; F-P0-2: 1 VariantAndCompare.kt + 1 test; F-P0-3: 1 PipelineKRunner.kt).
**Constraints**: conventional commits, each CRITICAL = 1 commit. Test changes in same commit as the fix. No unrelated refactors. Tests must fail without the fix and pass with it (no FAKE PASS).

## User decisions (2026-10-10)

| Fork | Decision |
|------|----------|
| C-1 TLS | HTTPS loopback autofirmado default, --insecure-http opt-in |
| F-P0-3 status | Híbrido: implementar supervisor real con fallback Unavailable |
| F-P0-2 variant | Persistir realmente en studio.db.snapshots |
| Tag version | v0.2.0-alpha.2 |

## Tasks

| ID | Task | Commit | Est. |
|----|------|--------|------|
| C-6 | spawnedHandles leak → Process.onExit() | `fix(sdk): use Process.onExit to auto-prune spawnedHandles map (audit C-6)` | 30 min |
| C-5 | RateLimit @Volatile → AtomicLong | `fix(http): atomic check-then-act in rate limiter (audit C-5)` | 30 min |
| C-7 | capabilities fake → empty + inferred flag | `fix(sdk): return empty capabilities when probe fails (audit C-7)` | 1h |
| F-P0-3 | status supervisor hybrid | `fix(sdk): implement status supervisor with Unavailable fallback (audit F-P0-3)` | 2h |
| C-2 | StartRunUseCase accepts experimentId | `fix(application): StartRunUseCase accepts experimentId, no NIL UUID (audit C-2)` | 1h |
| C-3 | StartRunUseCase propagates descriptor | `fix(application): StartRunUseCase propagates runtime descriptor (audit C-3)` | 1h (combined with C-2) |
| C-4 | AgentWorkflows submit newRunId | `fix(application): AgentWorkflows.mutateAndCompare submits newRunId to runner (audit C-4)` | 1h |
| F-P0-2 | variant persistence | `fix(sdk): VariantAndCompareAdapter.variant persists to studio.db.snapshots (audit F-P0-2)` | 4h |
| C-1 | HTTPS default | `feat(security): default HTTPS loopback with self-signed cert (audit C-1)` | 1 día |
| Release | tag v0.2.0-alpha.2 + push | `chore(release): v0.2.0-alpha.2 — close all 7 CRITICAL from 2026-10-10 audit` | 30 min |

## Acceptance criteria

- All 127 existing tests still pass
- 5 new tests added: agent-submits-real-runid, variant-persists, status-supervisor-resolves-live, capabilities-empty-on-failure, https-loopback-default
- `gradle build` exits 0
- `scripts/verify-architecture.sh` exits 0
- `scripts/verify-aat-rel-010.sh` exits 0
- Studio boots on https://127.0.0.1:7778 by default
- `git log --oneline main..HEAD` shows 9 conventional commits + 1 release commit
- `git tag -l` shows v0.2.0-alpha.2
- `git push origin main v0.2.0-alpha.2` succeeds

## Risks tracked

- R-04 (SDK SHA pin) — out of scope; documented in audit, not in this slice
- R-16 (external release harness) — out of scope; alpha does not require external verdict
- C-1 may require regenerating the OpenAPI doc — automated by studio-cli after fix
