# Senior Technical Audit — 2026-10-10

**Date**: 2026-10-10
**Scope**: full backend (7 modules), apps (web + mcp + vscode), scripts/verifiers
**Methodology**: 5 subagents in parallel + 1 senior reviewer; each finding
verified against the actual code (not against documentation or comments).
**Outcome**: 7 CRITICAL + 32 HIGH + ~55 MEDIUM + ~70 LOW + 18 test smells
≈ 180 hallazgos.

**Update 2026-10-10 (post-release)**: original report said 6.8/10 and
listed 5 risks as "deferred". Of those, 4 have since been closed in code
and are now ✅ fixed (see the commit column). R-22 and the F-P1 slice
remain.

## Score global

**6.8/10 at audit time** — listo para una RC interna; NO listo para v1.0.0 sin cerrar
los 5 CRITICAL y aceptar formalmente los HIGH con mitigación.

## Estado al cierre (v0.2.0-alpha.2)

| CRITICAL | Estado | Commit |
|----------|--------|--------|
| C-1 (HTTPS) | ✅ fixed | 5a0f6dd |
| C-2 (NIL UUID) | ✅ fixed | 78b71d2 |
| C-3 (descriptor discarded) | ✅ fixed | 78b71d2 |
| C-4 (AgentWorkflows fake PASS) | ✅ fixed | 3d990eb |
| C-5 (RateLimit non-atomic) | ✅ fixed | 60d975b |
| C-6 (spawnedHandles leak) | ✅ fixed | e08fc08 |
| C-7 (capabilities fake) | ✅ fixed | f73e846 |
| F-P0-2 (variant no persiste) | ✅ fixed | a3c44c2 |
| F-P0-3 (status stub) | ✅ fixed | dbb9701 |

**Los 9 CRITICAL están cerrados con código, no con workarounds.**

## Estado de los HIGH tras iteraciones posteriores

| ID | Riesgo | Estado | Commit |
|----|--------|--------|--------|
| H-15 | Host header fail-open | ✅ fixed (refused missing Host with 400 + TestSupport helper) | (pre-ebeb8ad slice) |
| H-16 | SSE JSON injection | ✅ fixed (`Json.encodeToString` envelope) | ae78b05 |
| H-17 | Idempotency-Key DoS | ✅ fixed (length cap + charset constraint) | ebeb8ad |
| H-19 | RateLimit TOCTOU bucket | ✅ fixed (atomic `compute()`) | 85f3f6f |
| H-20 | RateLimit XFF trust | ✅ fixed (explicit opt-in flag) | 85f3f6f |
| H-21 | RateLimit per-IP OOM | ✅ fixed (eviction sweep every 100 req) | 92fc071 |
| H-22 | Repos sin pool de conexiones | ✅ fixed (`ArrayBlockingQueue` + `PooledConnection` wrapper) | 84fad9f |
| H-23 | INSERT no-atómico + UUID regex | ✅ fixed (UUID regex + `RUN_FINISHED_SQL` literal) | 0ea746b |
| H-25 | RunReconciler path traversal-like | ✅ fixed (UUID gate + toRealPath symlink defense) | (this commit) |
| H-26 | Backup sin límite tamaño | ✅ fixed (1 GiB default cap, BackupTooLargeException, partial-file cleanup) | (this commit) |
| R-16 | Sin release harness externo | ✅ fixed (`.github/workflows/release-harness.yml` + `ci.yml`) | 57ec10a |
| R-22 | Sin SHA pin + lockfile | ⏳ pending (lockfile form) | — (R-04 done in docs) |
| F-P1-2..F-P1-18 | apps/web, apps/mcp, etc. | ⏳ pending; one item per slice | — |

## Recomendación actualizada para v1.0.0 stable

v1.0.0 stable NO debe emitirse hasta:

1. ~~Adoptar harness externo de release (R-16).~~ ✅ done (57ec10a).
2. ~~Cerrar H-15..H-17 (HTTP security) con código.~~ ✅ done.
3. SHA pin + lockfile (R-22) — R-04 documented the matrix; the
   remaining work is the **executable lockfile** the runner checks
   on every cold start.
4. Cerrar F-P1-3..F-P1-18 (apps) — one fix per slice, the catalog is
   the missing per-finding detail (not preserved when the audit was
   summarised).
5. Cerrar H-25 (RunReconciler) y H-26 (backup size cap) — these were
   listed as "deferred" in the original report.
6. Veredicto externo explícito (ROADMAP DoD) — a real second-party
   green-light, not just the GitHub-hosted harness above.
