# ADR Review — PipelineK Studio v1.0.0-rc.1

**Date:** 2026-10-10
**Block:** B5.5 (S5 — Product readiness)
**Mandate:** `ROADMAP.md` §"S5: ADR review".

This document is the operator-facing index of every
Architecture Decision Record (ADR) shipped in the studio
distribution. Each row is an ADR + its current status
(Active / Superseded / Draft) + the date the status was
last touched.

A new ADR is a wire contract change: it adds a number
(ADR-NNN), a one-line summary, a Context section, a
Decision section, and the Consequences (positive +
negative + neutral).

## Index

| # | Title | Status | Last touched |
|---|-------|--------|--------------|
| ADR-001 | Studio web local + Kotlin headless | Active | 2026-10-10 |
| ADR-002 | PipelineK único motor; runner JVM segregado | Active | 2026-10-10 |
| ADR-003 | SQLite dividido y planos Event/Output separados | Active | 2026-10-10 |
| ADR-004 | API de aplicación canónica, HTTP + MCP + CLI | Active | 2026-10-10 |
| ADR-005 | SourceSnapshot inmutable y acciones idempotentes | Active | 2026-10-10 |
| ADR-006 | Análisis estático separado de Kotlin evaluation | Active | 2026-10-10 |
| ADR-007 | Trust profiles y control de capacidades fail-closed | Active | 2026-10-10 |
| ADR-008 | Inspect manifest antes de ejecutar código de plugin | Active | 2026-10-10 |
| ADR-009 | Extensiones tipadas sin Views de plugins particulares | Active | 2026-10-10 |
| ADR-010 | Detekt en proceso aislado; LSP no es gate del MVP | Active | 2026-10-10 |
| ADR-011 | Main remoto y release real tras cada bloque de valor | Active | 2026-10-10 |
| ADR-012 | VS Code consume servicios; nunca componentes React como contrato | Active | 2026-10-10 |

## Status legend

- **Active** — the decision is the current contract. A
  change to an Active ADR requires a new ADR (with a
  `Supersedes` field) and a release note.
- **Superseded** — the decision was replaced by a newer
  ADR. The original file stays in the tree for history;
  the supersession is named in the new ADR.
- **Draft** — the decision is under discussion. Draft
  ADRs are NOT a contract; a production deployment must
  not depend on a Draft.

## Review procedure

The S5 mandate says "ADR review". The review is a
quarterly pass that:

1. Re-reads every Active ADR.
2. Confirms the decision still matches the code
   (`docs/01-architecture/ARCHITECTURE.md`).
3. Surfaces any new ADRs that should be written
   (e.g. a B5.x block that introduced a new contract).
4. Surfaces any Active ADRs that should be Superseded
   (e.g. an S6 follow-up that changed the contract).
5. Updates the **Last touched** column.

The B5 review pass produced no supersession; all 12
ADRs are Active as of 2026-10-10.

## New ADRs proposed for v1.0.0-rc.1 (B5.7 follow-up)

- **ADR-013** (Draft) — `studio` CLI surface stable for
  v1.0.0-rc.1. The B5.0–B5.6 work added `studio stop`,
  `studio status`, the lock file at `--data-dir/.studio.lock`,
  the distribution extras (`manifest.json` + `sbom.json`),
  the `x-studio-api-version` OpenAPI pin, and the
  `SecretCanary` scanner. A new ADR is proposed to
  document the CLI surface as a stable contract.
- **ADR-014** (Draft) — `STUDIO_AGENT_CAPS` opt-in
  posture. The B4.0 capability model defaults to "all
  disabled"; the operator must set `STUDIO_AGENT_CAPS`
  to opt in. The ADR documents the posture, the env var
  semantics, and the closed trigger catalog for the
  prompt-injection guard.

Both proposed ADRs land in a follow-up release; the B5.7
release receipt references them.
