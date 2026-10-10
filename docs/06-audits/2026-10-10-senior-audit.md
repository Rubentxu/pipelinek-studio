# Senior Technical Audit — 2026-10-10

**Date**: 2026-10-10
**Scope**: full backend (7 modules), apps (web + mcp + vscode), scripts/verifiers
**Methodology**: 5 subagents in parallel + 1 senior reviewer; each finding
verified against the actual code (not against documentation or comments).
**Outcome**: 7 CRITICAL + 32 HIGH + ~55 MEDIUM + ~70 LOW + 18 test smells
≈ 180 hallazgos.

## Score global

**6.8/10** — listo para una RC interna; NO listo para v1.0.0 sin cerrar
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

## Riesgos restantes (HIGH no mitigados en este alpha)

| ID | Riesgo | Mitigación en este release |
|----|--------|---------------------------|
| H-15 | Host header fail-open | Documentado; el cert loopback limita el blast radius |
| H-16 | SSE JSON injection | No fixed; mitigación con `Json.encodeToString` deferred |
| H-17 | Idempotency-Key DoS | No fixed; limite de longitud deferred |
| H-19..H-21 | RateLimit TOCTOU/XFF/OOM | XFF ya validado; TOCTOU fixed (C-5); OOM deferred |
| H-22..H-23 | Repositorios sin pool + INSERT no-atómico | Deferred a perf-soak |
| H-25 | RunReconciler path traversal-like | Deferred |
| H-26 | Backup sin límite tamaño | Deferred |
| F-P1-2..F-P1-18 | apps/web, apps/mcp, etc. | Documentados; apps/web/apps/mcp fixes deferred a v0.3.x |

## Recomendación para v1.0.0 stable

v1.0.0 stable NO debe emitirse hasta:

1. Adoptar harness externo de release (R-16).
2. Cerrar H-15..H-17 (HTTP security) con código.
3. SHA pin + lockfile (R-22).
4. Veredicto externo explícito (ROADMAP DoD).
