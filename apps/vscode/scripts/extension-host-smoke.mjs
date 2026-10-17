// R5 EVO-C.T5 (2026-10-17) — VSIX ARCHIVE + MANIFEST CHECK.
//
// Scope, stated honestly: this validates the bytes vsce produced (zip tree,
// entry point, manifest inventory). It does NOT run an Extension Host and it
// cannot prove activation, provider registration, command execution or the
// SSE transport. The real host run lives in `scripts/run-extension-host.mjs`
// (`--extensionTestsPath` against the installed VS Code build); the real
// Studio SSE/reconnect probe lives in `scripts/real-studio-smoke.mjs`.
//
// R5 EVO-C.T5 also corrected the command inventory: the archive check listed
// 9 commands with presence-only assertions, so `linkExperiment` and
// `runDiagnostics` were never checked even though the manifest contributes
// them. The inventory is now the exact 11-command set and the comparison is
// bidirectional (a contributed-but-unlisted command is drift too).
//
// Usage: node scripts/extension-host-smoke.mjs <path-to-vsix>

import { execSync } from "node:child_process";

const vsix = process.argv[2];
if (!vsix) {
  console.error("usage: node scripts/extension-host-smoke.mjs <path-to-vsix>");
  process.exit(2);
}

console.log("VSIX ARCHIVE/MANIFEST CHECK (not an Extension Host run)");

const listing = execSync(`unzip -l ${JSON.stringify(vsix)}`).toString();
const expected = [
  "extension/dist/extension.js",
  "extension/package.json",
  "extension/dist/sseClient.js",
  "extension/dist/treeView.js",
  "extension/dist/sdk.js",
  "extension/dist/config.js",
  "extension/readme.md",
];
for (const entry of expected) {
  if (!listing.includes(entry)) {
    console.error(`MISSING in VSIX: ${entry}`);
    process.exit(1);
  }
}
const distCount = listing.split("\n").filter((line) => line.includes("extension/dist/")).length;
console.log(`  archive tree OK: ${distCount} dist files`);

// Parse package.json from the VSIX to assert the entry point + the R5 EVO-C
// surface (command inventory + sidebar container).
const pkgJson = execSync(`unzip -p ${JSON.stringify(vsix)} extension/package.json`).toString();
const pkg = JSON.parse(pkgJson);
if (pkg.main !== "dist/extension.js") {
  console.error(`VSIX package.json main is ${pkg.main}, expected dist/extension.js`);
  process.exit(1);
}

const expectedCommands = [
  "pipelinek-studio.health",
  "pipelinek-studio.createExperiment",
  "pipelinek-studio.listSteps",
  "pipelinek-studio.openOpenApi",
  "pipelinek-studio.status",
  "pipelinek-studio.openRun",
  "pipelinek-studio.runDiagnostics",
  "pipelinek-studio.cancelRun",
  "pipelinek-studio.linkExperiment",
  "pipelinek-studio.sealAndRun",
  "pipelinek-studio.tree.refresh",
];
const contributed = (pkg.contributes?.commands ?? []).map((command) => command.command);
const missing = expectedCommands.filter((id) => !contributed.includes(id));
const unlisted = contributed.filter((id) => !expectedCommands.includes(id));
if (missing.length > 0 || unlisted.length > 0) {
  console.error(`VSIX command inventory mismatch: missing=${JSON.stringify(missing)} unlisted=${JSON.stringify(unlisted)}`);
  process.exit(1);
}
const seen = new Set();
for (const command of contributed) {
  if (seen.has(command)) {
    console.error(`VSIX package.json declares duplicate command id: ${command}`);
    process.exit(1);
  }
  seen.add(command);
}
console.log(`  command inventory OK: ${contributed.length}/11 commands (incl linkExperiment, runDiagnostics)`);

// Command-based activation events must point at commands the package really
// contributes; a stale activation event is silently never fired in the host.
const commandActivations = (pkg.activationEvents ?? [])
  .filter((event) => event.startsWith("onCommand:"))
  .map((event) => event.slice("onCommand:".length));
const staleActivations = commandActivations.filter((id) => !contributed.includes(id));
if (staleActivations.length > 0) {
  console.error(`VSIX activationEvents reference unregistered commands: ${JSON.stringify(staleActivations)}`);
  process.exit(1);
}
console.log(`  activation events OK: ${commandActivations.length} onCommand entries all registered`);

if (!pkg.contributes?.views?.["pipelinek-studio"]) {
  console.error("VSIX package.json missing 'pipelinek-studio' view container");
  process.exit(1);
}
if (!pkg.contributes.views["pipelinek-studio"].some((view) => view.id === "pipelinek-studio.tree")) {
  console.error("VSIX package.json missing the 'pipelinek-studio.tree' view");
  process.exit(1);
}
console.log("  sidebar view container + tree view OK");

console.log(`VSIX archive/manifest check OK: ${vsix}`);
