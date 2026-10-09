/// <reference types="vitest" />
import { defineConfig } from "vitest/config";

/**
 * S5 E5.6 — Vitest config for the SPA. The web package gains a
 * `npm test` entry point that exercises the React components in a
 * jsdom environment.
 *
 * Tests live next to the components as `*.test.tsx` so the discovery
 * is automatic. We use @testing-library/react for the user-facing
 * assertions (e.g. "the Save button is disabled when no experiment
 * is selected"). Pure unit tests for hooks and helpers stay as
 * `*.test.ts`.
 */
export default defineConfig({
  test: {
    environment: "jsdom",
    globals: true,
    setupFiles: ["./src/test/setup.ts"],
    css: false,
  },
});
