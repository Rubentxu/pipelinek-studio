// R5 EVO-C.P (2026-10-11): minimal extension-host smoke.
// Validates the VSIX file tree + dist/entry point exports.
// A real extension-host run needs VS Code + xvfb; this
// script is the proxy that fits in CI and the dev
// environment.
//
// Usage: node scripts/extension-host-smoke.mjs <path-to-vsix>

import { execSync } from "node:child_process";

const vsix = process.argv[2];
if (!vsix) {
  console.error("usage: node scripts/extension-host-smoke.mjs <path-to-vsix>");
  process.exit(2);
}

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
for (const e of expected) {
  if (!listing.includes(e)) {
    console.error(`MISSING in VSIX: ${e}`);
    process.exit(1);
  }
}
const distCount = listing.split("\n").filter((l) => l.includes("extension/dist/")).length;
console.log(`VSIX smoke OK: ${vsix}`);
console.log(`  ${distCount} dist files`);

// Parse package.json from the VSIX to assert the entry +
// the R5 EVO-C surface (commands + sidebar container).
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
  "pipelinek-studio.cancelRun",
  "pipelinek-studio.sealAndRun",
  "pipelinek-studio.tree.refresh",
];
for (const cmd of expectedCommands) {
  if (!pkg.contributes?.commands?.some((c) => c.command === cmd)) {
    console.error(`VSIX package.json missing command: ${cmd}`);
    process.exit(1);
  }
}
console.log(`  ${pkg.contributes.commands.length} commands declared ✓`);

if (!pkg.contributes?.views?.["pipelinek-studio"]) {
  console.error("VSIX package.json missing 'pipelinek-studio' view container");
  process.exit(1);
}
console.log("  sidebar view container ✓");
