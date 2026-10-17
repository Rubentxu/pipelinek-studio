#!/usr/bin/env node
// R5 EVO-C.T5 (2026-10-17) — run the ACTUAL VS Code Extension Host.
//
// Launches the installed VS Code desktop build with `--extensionTestsPath`
// (the CLI's real extension-test entry point) against the real extension in
// `apps/vscode`, using an isolated user-data dir, extensions dir and
// workspace, all inside the ignored `build/r5-acceptance/` path. The test
// module (`extension-host-test.cjs`) is dependency-free and runs inside the
// host, so activation, command registration, the sidebar view, the CodeLens
// provider and the settings-reload path are exercised by real VS Code code,
// not by a `vscode` mock.
//
// This is a desktop Electron window: it needs a display/session. Without one
// the run is reported as UNAVAILABLE (exit 3) — never simulated.
//
// Usage:
//   node scripts/run-extension-host.mjs [--code-bin <path>] [--work-dir <path>]
//                                       [--timeout-ms <n>]
//
// Exit codes: 0 = host ran and every host test passed,
//             1 = host ran and reported a failure,
//             2 = the host test report was missing/inconsistent,
//             3 = host/display unavailable or timed out (no retry).

import { spawn, spawnSync } from "node:child_process";
import { existsSync, mkdirSync, readFileSync, realpathSync, writeFileSync } from "node:fs";
import { dirname, join, resolve } from "node:path";
import { performance } from "node:perf_hooks";
import { fileURLToPath } from "node:url";

const here = dirname(fileURLToPath(import.meta.url));
const appRoot = resolve(here, "..");
const repoRoot = resolve(appRoot, "..", "..");

const args = process.argv.slice(2);
const flag = (name, fallback) => {
  const index = args.indexOf(`--${name}`);
  return index >= 0 && args[index + 1] !== undefined ? args[index + 1] : fallback;
};

const installedCode = "/home/linuxbrew/.linuxbrew/bin/code";
const requestedCode = resolve(flag("code-bin", process.env.VSCODE_BIN || installedCode));
/**
 * The installed `bin/code` is a shell wrapper that runs `cli.js` with
 * ELECTRON_RUN_AS_NODE=1 and returns as soon as the desktop app has been
 * handed off, so the wrapper's exit code is not the Extension Host's. Launch
 * the Electron binary the wrapper itself resolves (`<install>/code`) so the
 * process we wait on IS the host, with a real exit code and a report on disk.
 * Same installed VS Code build, same CLI arguments.
 */
function resolveHostBinary(binPath) {
  try {
    const real = realpathSync(binPath);
    const electron = join(dirname(real), "..", "code");
    if (existsSync(electron)) return { hostBin: electron, launcher: binPath };
  } catch {
    // fall through to the path the operator gave us
  }
  return { hostBin: binPath, launcher: null };
}

const { hostBin: codeBin, launcher: codeCli } = resolveHostBinary(requestedCode);
const timeoutMs = Number(flag("timeout-ms", "240000"));
const stamp = new Date().toISOString().replace(/[-:.TZ]/g, "").slice(0, 14);
const runRoot = resolve(flag("work-dir", join(repoRoot, "build", "r5-acceptance", "extension-host", `run-${stamp}`)));
const userData = join(runRoot, "user-data");
const extensionsDir = join(runRoot, "extensions");
const workspace = join(runRoot, "workspace");
const sharedData = join(runRoot, "shared-data");
const logPath = join(runRoot, "extension-host.log");
const reportPath = join(runRoot, "host-report.json");

const log = (line) => console.log(line);
const fail = (code, message) => {
  console.error(message);
  process.exit(code);
};

if (!existsSync(codeBin)) {
  fail(3, `UNAVAILABLE: VS Code CLI not found at ${codeBin} (set VSCODE_BIN or --code-bin).`);
}const distEntry = join(appRoot, "dist", "extension.js");
if (!existsSync(distEntry)) {
  fail(3, `UNAVAILABLE: ${distEntry} is missing; run "npm run build" in apps/vscode first.`);
}
if (!process.env.DISPLAY && !process.env.WAYLAND_DISPLAY) {
  fail(3, "UNAVAILABLE: no DISPLAY/WAYLAND_DISPLAY session; a real desktop Extension Host cannot start.");
}

for (const dir of [runRoot, userData, extensionsDir, workspace, sharedData]) mkdirSync(dir, { recursive: true });

// Keep VS Code's global shared state out of the run: the desktop app reads and
// writes `~/.vscode-shared` by default, which would be an out-of-scope write.
const isolatedSharedArg = `--shared-data-dir=${sharedData}`;

// Side-load mode: install the packaged VSIX into the isolated extensions dir
// and read it back. This proves the artifact is installable; activation of the
// same `dist/` code in a real host is the default (test) mode below. The
// install/list commands are implemented by the CLI (`cli.js`), so this mode
// uses the installed launcher rather than the desktop binary.
const vsixArg = flag("install-vsix", null);
if (vsixArg) {
  const vsix = resolve(vsixArg);
  if (!existsSync(vsix)) fail(3, `UNAVAILABLE: VSIX not found at ${vsix}`);
  const cli = codeCli || requestedCode;
  const cliEnv = { ...process.env };
  delete cliEnv.VSCODE_IPC_HOOK_CLI;
  log("SIDE-LOAD CHECK (installs the packaged VSIX into an isolated extensions dir; activation is proven by the host run)");
  log(`vsix: ${vsix}`);
  log(`installed via: ${cli}`);
  log(`isolated extensions: ${extensionsDir}`);
  const install = spawnSync(
    cli,
    [
      "--install-extension",
      vsix,
      "--extensions-dir",
      extensionsDir,
      "--user-data-dir",
      userData,
      isolatedSharedArg,
      "--force",
    ],
    { encoding: "utf8", timeout: timeoutMs, env: cliEnv },
  );
  if (install.stdout.trim()) log(install.stdout.trim());
  if (install.stderr.trim()) log(install.stderr.trim());
  if (install.error || install.status !== 0) {
    fail(1, `SIDE-LOAD FAILURE: install exited status=${install.status} error=${install.error?.message ?? "none"}`);
  }
  const installedPackage = join(extensionsDir, "pipelinek-studio.pipelinek-studio-1.0.0", "package.json");
  if (!existsSync(installedPackage)) {
    fail(1, `SIDE-LOAD FAILURE: ${installedPackage} is missing after install`);
  }
  const installedManifest = JSON.parse(readFileSync(installedPackage, "utf8"));
  const list = spawnSync(
    cli,
    ["--list-extensions", "--show-versions", "--extensions-dir", extensionsDir, "--user-data-dir", userData, isolatedSharedArg],
    { encoding: "utf8", timeout: timeoutMs, env: cliEnv },
  );
  const listed = list.stdout
    .replace(/\u001b\[[0-9;]*m/g, "")
    .split("\n")
    .map((line) => line.trim())
    .filter((line) => /^[\w-]+\.[\w-]+@\d/.test(line));
  const installedId = `${installedManifest.publisher}.${installedManifest.name}@${installedManifest.version}`;
  log(
    `installed extension: ${installedId} (${installedManifest.contributes.commands.length} commands, main ${installedManifest.main})`,
  );
  log(`--list-extensions reports: ${JSON.stringify(listed)}`);
  if (!listed.includes(installedId)) {
    fail(1, `SIDE-LOAD FAILURE: ${installedId} is not listed after install`);
  }
  log("VSIX SIDE-LOAD: PASS (installed and listed from the isolated extensions dir)");
  log(`isolated state left under ${runRoot} (ignored build/r5-acceptance path).`);
  process.exit(0);
}
// The extension test writes its own *.pipeline.kts document; an empty
// isolated workspace keeps the run independent from the developer's tree.
writeFileSync(join(workspace, "README.md"), "isolated R5 Extension Host acceptance workspace\n");

const codeArgs = [
  `--extensionDevelopmentPath=${appRoot}`,
  `--extensionTestsPath=${join(appRoot, "scripts", "extension-host-test.cjs")}`,
  `--user-data-dir=${userData}`,
  `--extensions-dir=${extensionsDir}`,
  isolatedSharedArg,
  "--disable-workspace-trust",
  "--skip-welcome",
  "--skip-release-notes",
  "--disable-telemetry",
  "--disable-updates",
  "--disable-gpu",
  "--new-window",
  workspace,
];

const env = { ...process.env, PIPELINEK_HOST_TEST_REPORT: reportPath };
// The shell wrapper delegates to a running remote CLI when this is set, which
// would attach the request to the developer's window instead of a test host.
delete env.VSCODE_IPC_HOOK_CLI;

log(`vscode binary: ${codeBin}${codeCli ? ` (desktop app behind ${codeCli})` : ""}`);
log(`extension under test: ${appRoot}`);
log(`isolated user-data: ${userData}`);
log(`isolated extensions: ${extensionsDir}`);
log(`isolated shared-data: ${sharedData}`);
log(`isolated workspace: ${workspace}`);

const child = spawn(codeBin, codeArgs, { cwd: workspace, env, stdio: ["ignore", "pipe", "pipe"] });
const chunks = [];
child.stdout.on("data", (b) => chunks.push(b));
child.stderr.on("data", (b) => chunks.push(b));

let timedOut = false;
const started = performance.now();
const timer = setTimeout(() => {
  timedOut = true;
  child.kill("SIGTERM");
  setTimeout(() => child.kill("SIGKILL"), 10000).unref();
}, timeoutMs);

// Never leave our own VS Code process behind if this script dies first.
process.on("exit", () => {
  if (child.exitCode === null && child.signalCode === null) child.kill("SIGKILL");
});

child.on("exit", (code, signal) => {
  clearTimeout(timer);
  const elapsed = Math.round(performance.now() - started);
  const output = Buffer.concat(chunks).toString("utf8");
  writeFileSync(logPath, output);

  if (timedOut) {
    console.error(`UNAVAILABLE: Extension Host did not finish within ${timeoutMs}ms (reported as unavailable, no retry).`);
    console.error(`host log: ${logPath}`);
    process.exit(3);
  }

  log(`extension host exited code=${code} signal=${signal || "none"} after ${elapsed}ms`);
  log(`host log: ${logPath}`);

  if (!existsSync(reportPath)) {
    console.error(`HOST FAILURE: no test report at ${reportPath}; the Extension Host did not run the tests.`);
    console.error(output.split("\n").slice(-40).join("\n"));
    process.exit(2);
  }

  const report = JSON.parse(readFileSync(reportPath, "utf8"));
  for (const entry of report.tests ?? []) {
    log(`  ${entry.ok ? "PASS" : "FAIL"} ${entry.name}${entry.detail ? ` — ${entry.detail}` : ""}`);
  }
  log(
    `host report: vscode ${report.vscodeVersion}, ${report.passed}/${report.total} passed, ${report.failed} failed`,
  );
  log(`host report path: ${reportPath}`);
  if (report.passed + report.failed !== report.total) {
    console.error("HOST FAILURE: report counts are inconsistent.");
    process.exit(2);
  }
  if (code !== 0 || report.failed > 0) {
    console.error(`HOST FAILURE: exit=${code}, failed=${report.failed}`);
    process.exit(1);
  }
  log("");
  log("REAL EXTENSION HOST: PASS");
  log(`note: launch left isolated state under ${runRoot} (ignored build/r5-acceptance path).`);
  process.exit(0);
});
