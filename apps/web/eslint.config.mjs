// ESLint 9 flat config for apps/web.
//
// R0.1.4 (2026-10-11): replaces `"lint": "echo 'lint deferred to S1'"`
// with a real lint check. See apps/mcp/eslint.config.mjs for the full
// rationale; this config mirrors it because both apps live under the
// same `apps/` root and have the same conventions.
//
// Future hardening (R3) can layer typescript-eslint, react-hooks, and
// a11y rules; for now we ship the recommended ruleset as a credible
// minimum.

import js from '@eslint/js';

export default [
  {
    ignores: [
      'dist/**',
      '*.d.ts',
      'node_modules/**',
      'coverage/**',
    ],
  },
  js.configs.recommended,
];