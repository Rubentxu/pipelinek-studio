import { defineConfig } from "vitest/config";

/**
 * Vitest config for the VS Code extension. Pure unit tests of the
 * URL resolver and the timeout helper; no VS Code runtime required.
 * Integration tests of the extension itself are out of scope here
 * (they would need the `vscode` module + a real editor host).
 */
export default defineConfig({
  test: {
    environment: "node",
    include: ["src/**/*.test.ts"],
  },
});
