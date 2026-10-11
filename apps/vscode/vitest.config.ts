import { defineConfig } from "vitest/config";
import { fileURLToPath } from "node:url";

/**
 * Vitest config for the VS Code extension. Pure unit tests of
 * the URL resolver, the timeout helper, the SSE client, and
 * the tree data provider. No VS Code runtime required.
 *
 * The `vscode` module alias points the test runtime at the
 * in-tree `__mocks__/vscode.ts` so the tree view can be
 * exercised in node. The real `vscode` module is only
 * available inside the extension host.
 */
export default defineConfig({
  resolve: {
    alias: {
      vscode: fileURLToPath(new URL("./src/__mocks__/vscode.ts", import.meta.url)),
    },
  },
  test: {
    environment: "node",
    include: ["src/**/*.test.ts"],
  },
});
