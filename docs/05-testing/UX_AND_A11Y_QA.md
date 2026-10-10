# UX / Keyboard / ARIA QA — PipelineK Studio v1.0.0-rc.1

**Date:** 2026-10-10
**Block:** B5.5 (S5 — Product readiness)
**Mandate:** `ROADMAP.md` §"S5: QA de UX/keyboard/ARIA".

This document is the operator-facing QA checklist for the
Studio SPA. Every row is a property the SPA must satisfy
for v1.0.0-rc.1. A row that fails is a release-blocker; a
row that is "TBD" is a known gap that lands in a follow-up
release.

## Keyboard

- [ ] **Tab order is logical** — Tab moves through
  the interactive elements in the order a sighted
  user would expect (top-to-bottom, left-to-right).
- [ ] **No keyboard traps** — every modal can be
  dismissed with `Esc`; the focus ring is visible.
- [ ] **Skip-to-content link** — the first Tab
  surfaces a "Skip to main content" link.
- [ ] **Run cancel is keyboard-reachable** — the
  Cancel button on a running run is reachable via
  Tab + Enter, not only via mouse.
- [ ] **List navigation uses arrow keys** — the
  Runs list, Steps list, and Events list respond
  to Up/Down/Home/End.
- [ ] **Modal `Esc` is consistent** — every modal
  closes on `Esc` and returns focus to the trigger
  element.

## ARIA

- [ ] **Landmarks are present** — the SPA exposes
  `<header>`, `<main>`, `<nav>`, `<aside>`,
  `<footer>` landmarks. The IR Explorer has
  `role="region"` with an `aria-label`.
- [ ] **All buttons have accessible names** —
  every `<button>` has a visible label, an
  `aria-label`, or an `aria-labelledby` reference.
  No "icon-only" buttons without a name.
- [ ] **Form fields have labels** — every
  `<input>`, `<select>`, `<textarea>` has an
  associated `<label>` or `aria-label`.
- [ ] **Live regions for async updates** — the
  run-state badge, the events log, and the
  diagnostics panel are `aria-live="polite"`.
- [ ] **Tab panels use `role="tablist"` +
  `role="tab"` + `aria-selected`** — the
  IR Explorer tabs (Source / Steps / Events)
  use the ARIA tab pattern.
- [ ] **Modal focus trap** — when a modal opens,
  focus moves to the first focusable element
  inside; Tab cycles within the modal; the
  focus returns to the trigger on close.

## Visual + responsive

- [ ] **Color contrast ≥ 4.5:1** for body text.
  The light + dark theme tokens are pinned by
  the design system.
- [ ] **Focus ring is visible** in both light
  and dark modes.
- [ ] **No information conveyed by color alone**
  — every status badge also has an icon or
  text label (e.g. "SUCCEEDED" with a green
  dot, "FAILED" with a red dot).
- [ ] **Layout works at 320px width** — the
  narrowest reasonable viewport. The SPA does
  not horizontal-scroll.
- [ ] **Layout works at 1920px width** — the
  IR Explorer uses the available width without
  becoming hard to scan.

## Error handling

- [ ] **No silent failures** — every error
  response from the backend surfaces a toast +
  a structured message. The "no problems
  found" anti-pattern (B3.3) is documented in
  the design system.
- [ ] **Capability refusals are visible** — when
  `studio.agent` returns a `Refused` (B4.5), the
  SPA shows a banner naming the disabled
  capability, not a generic "action failed".
- [ ] **Prompt-injection refusals are visible** —
  when the B4.6 guard returns `Refused`, the
  SPA surfaces the trigger phrase to the
  operator (never silently).
- [ ] **Network errors are recoverable** — a
  dropped SSE stream auto-reconnects with
  exponential backoff; the operator sees a
  "reconnecting" indicator.

## Test coverage

The UX/keyboard/ARIA QA is verified by:

- The `useKeyboardShortcuts` test under
  `apps/web/src/useKeyboardShortcuts.test.ts`
  (Tab/Enter/Esc/arrow behavior).
- A live `vitest` pass against the rendered
  components (axe-core integration in
  `apps/web/vitest.config.ts`).
- A manual QA pass by the operator using the
  built SPA bundle (no source).

## Out of scope for v1.0.0-rc.1

- **Voice control** — the SPA does not advertise
  ARIA support for Dragon / VoiceOver rotor
  commands. A future S6 pass can add role +
  aria-roledescription.
- **High-contrast theme** — the design system
  ships a light + dark theme. A high-contrast
  theme is a follow-up.
- **Screen reader live-region fine-tuning** —
  the live regions are present but the
  announcer phrasing is best-effort. A future
  pass can run NVDA + JAWS against a screen-
  reader test corpus.
