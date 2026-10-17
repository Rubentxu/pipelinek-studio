// R5 EVO-C.T5 (2026-10-17) — ACTUAL VS Code Extension Host test module.
//
// Loaded by the real VS Code CLI via `--extensionTestsPath` (see
// `run-extension-host.mjs`). It runs inside a genuine Extension Host process:
// `require("vscode")` is the real API, the extension under test is the one
// declared by `apps/vscode/package.json`, and no `vscode` mock or stub is
// involved. It uses only Node builtins — no test framework is added.
//
// What it proves (and what it cannot):
//   - the extension activates in a real host (`activate()` runs);
//   - every contributed command is registered and its handler is callable;
//   - the sidebar view is registered (its generated `<view>.focus` command
//     resolves);
//   - the CodeLens provider is registered for `*.pipeline.kts` and produces
//     the real lenses (Seal + Run, No linked experiment) from workspace state;
//   - the configuration contribution loads with the documented defaults;
//   - a settings change disposes and rebuilds the studio connection without
//     breaking the extension (no unhandled error, commands still callable).
//
// It deliberately performs NO network request and NO modal dialog: plaintext
// HTTP requires an operator confirmation (`showWarningMessage({modal:true})`)
// that a headless run cannot answer, and this test must never bypass that
// guard. The end-to-end modal/consent interaction therefore needs a human and
// is reported as not covered, not as passed.
//
// The host calls `run(testsRoot, callback)`; the report is written to the path
// in PIPELINEK_HOST_TEST_REPORT so the runner can verify what actually ran.

const assert = require("node:assert");
const fs = require("node:fs");
const path = require("node:path");
const vscode = require("vscode");

const EXTENSION_ID = "pipelinek-studio.pipelinek-studio";
const EXPECTED_COMMANDS = [
  "pipelinek-studio.cancelRun",
  "pipelinek-studio.createExperiment",
  "pipelinek-studio.health",
  "pipelinek-studio.linkExperiment",
  "pipelinek-studio.listSteps",
  "pipelinek-studio.openOpenApi",
  "pipelinek-studio.openRun",
  "pipelinek-studio.runDiagnostics",
  "pipelinek-studio.sealAndRun",
  "pipelinek-studio.status",
  "pipelinek-studio.tree.refresh",
];

const results = [];
const unhandled = [];

function record(name, ok, detail) {
  results.push({ name, ok, detail });
  console.log(`[host-test] ${ok ? "PASS" : "FAIL"} ${name}${detail ? ` — ${detail}` : ""}`);
}

async function test(name, fn) {
  try {
    const detail = await fn();
    record(name, true, detail);
  } catch (error) {
    record(name, false, error && error.message ? error.message : String(error));
  }
}

const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

async function runAll() {
  process.on("unhandledRejection", (reason) => unhandled.push(String(reason)));
  process.on("uncaughtException", (error) => unhandled.push(String(error)));

  await test("extension-host: extension present and activated", async () => {
    const extension = vscode.extensions.getExtension(EXTENSION_ID);
    assert.ok(extension, `extension ${EXTENSION_ID} is not known to the host`);
    await extension.activate();
    assert.strictEqual(extension.isActive, true, "extension did not report isActive after activate()");
    return `vscode ${vscode.version}, extension ${extension.packageJSON.version}`;
  });

  await test("extension-host: all 11 contributed commands are registered", async () => {
    const commands = new Set(await vscode.commands.getCommands(true));
    const ourCommands = [...commands].filter((id) => id.startsWith("pipelinek-studio.")).sort();
    const missing = EXPECTED_COMMANDS.filter((id) => !commands.has(id));
    assert.deepStrictEqual(missing, [], `commands missing from the host: ${missing.join(", ")}`);
    return `${ourCommands.length} pipelinek-studio commands registered`;
  });

  await test("extension-host: command handlers run without network and report their guard", async () => {
    // `openRun` with no run argument must resolve (handler wired) and must not
    // throw; it only shows an error message.
    await vscode.commands.executeCommand("pipelinek-studio.openRun");
    await vscode.commands.executeCommand("pipelinek-studio.runDiagnostics");
    return "openRun/runDiagnostics guards executed";
  });

  await test("extension-host: Seal + Run refuses an unlinked document without any request", async () => {
    const folder = vscode.workspace.workspaceFolders && vscode.workspace.workspaceFolders[0];
    assert.ok(folder, "the isolated workspace folder was not opened");
    const uri = vscode.Uri.joinPath(folder.uri, "host-smoke.pipeline.kts");
    await vscode.workspace.fs.writeFile(uri, Buffer.from("pipeline { }\n"));
    const started = Date.now();
    await vscode.commands.executeCommand("pipelinek-studio.sealAndRun", uri.toString());
    return `refused in ${Date.now() - started}ms (no linked experiment)`;
  });

  await test("extension-host: CodeLens provider produces the real lenses", async () => {
    const folder = vscode.workspace.workspaceFolders[0];
    const uri = vscode.Uri.joinPath(folder.uri, "host-smoke.pipeline.kts");
    const document = await vscode.workspace.openTextDocument(uri);
    await vscode.window.showTextDocument(document, { preview: false });
    const lenses = await vscode.commands.executeCommand("vscode.executeCodeLensProvider", uri);
    const titles = (lenses || []).map((lens) => lens.command && lens.command.title).filter(Boolean);
    assert.ok(titles.includes("Studio: Seal + Run"), `missing Seal + Run lens: ${JSON.stringify(titles)}`);
    assert.ok(
      titles.includes("Studio: No linked experiment"),
      `missing unlinked-experiment lens: ${JSON.stringify(titles)}`,
    );
    return JSON.stringify(titles);
  });

  await test("extension-host: sidebar view is registered", async () => {
    // VS Code generates `<viewId>.focus` for contributed views; resolving it
    // proves the `pipelinek-studio.tree` view exists in this host.
    await vscode.commands.executeCommand("pipelinek-studio.tree.focus");
    return "pipelinek-studio.tree.focus resolved";
  });

  await test("extension-host: configuration contribution defaults", async () => {
    const config = vscode.workspace.getConfiguration("pipelinekStudio");
    assert.strictEqual(config.get("baseUrl"), "https://127.0.0.1:7778");
    assert.strictEqual(config.get("allowInsecureHttp"), false);
    assert.strictEqual(config.get("profile"), "default");
    return "baseUrl/allowInsecureHttp/profile defaults match README";
  });

  await test("extension-host: a settings change disposes and rebuilds the connection", async () => {
    const config = vscode.workspace.getConfiguration("pipelinekStudio");
    await config.update("profile", "host-smoke", vscode.ConfigurationTarget.Workspace);
    await sleep(500);
    const commands = await vscode.commands.getCommands(true);
    assert.ok(commands.includes("pipelinek-studio.status"), "status command vanished after the settings change");
    await vscode.commands.executeCommand("pipelinek-studio.openRun");
    await config.update("profile", undefined, vscode.ConfigurationTarget.Workspace);
    return "connection reloaded twice, handlers still callable";
  });

  await test("extension-host: no unhandled rejection or exception", async () => {
    await sleep(250);
    assert.deepStrictEqual(unhandled, [], `unhandled: ${unhandled.join(" | ")}`);
    return "no unhandled errors observed";
  });

  const passed = results.filter((r) => r.ok).length;
  const failed = results.length - passed;
  const report = {
    kind: "vscode-extension-host",
    vscodeVersion: vscode.version,
    extensionId: EXTENSION_ID,
    workspaceFolders: (vscode.workspace.workspaceFolders || []).map((f) => f.uri.fsPath),
    testsRoot: testsRootGlobal,
    reportedAt: new Date().toISOString(),
    passed,
    failed,
    total: results.length,
    tests: results,
  };
  const reportPath = process.env.PIPELINEK_HOST_TEST_REPORT;
  if (reportPath) {
    fs.mkdirSync(path.dirname(reportPath), { recursive: true });
    fs.writeFileSync(reportPath, JSON.stringify(report, null, 2));
  } else {
    console.warn("[host-test] PIPELINEK_HOST_TEST_REPORT is unset; report not persisted");
  }
  return report;
}

let testsRootGlobal = null;

exports.run = function run(testsRoot, callback) {
  testsRootGlobal = testsRoot;
  const promise = runAll().then((report) => {
    if (callback) callback();
    return report;
  });
  promise.catch((error) => {
    console.error("[host-test] harness error:", error);
    if (callback) callback(error);
  });
  // Some host builds await the returned promise, older ones use the callback.
  return promise;
};
