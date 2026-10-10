import { defineConfig } from "vitest/config";

/**
 * Vitest config for the MCP stdio adapter. Pure unit tests of the
 * URL resolver and the timeout helper; the integration scenarios
 * that talk to a real studio are gated behind RUN_MCP_IT and
 * skipped in the default `npm test` run.
 */
export default defineConfig({
  test: {
    environment: "node",
    include: ["src/**/*.test.ts"],
  },
});
