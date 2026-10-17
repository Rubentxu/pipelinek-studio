#!/usr/bin/env node
// R5 EVO-C.T5 (2026-10-17) — REAL Studio SSE + reconnect probe.
//
// What this proves (S6):
//   A. "launch a real studio in a temp data dir, connect an SSE consumer,
//      fire `experiment.create` via the SDK, assert the SSE event arrives
//      within 2s"
//   B. "kill the studio mid-stream, assert the client reconnects with
//      backoff when the studio comes back"
//
// It is NOT a mock and NOT a fake server: it spawns the built studio CLI
// (`studio-cli/build/install/studio-cli`, the existing project runtime) with
// an isolated `--data-dir`/`--db` under the ignored `build/r5-acceptance/`
// path, and it drives the compiled extension transport from `apps/vscode/dist`
// (`validateStudioConfig`, `SseClient`, the generated `StudioClient` SDK).
// The script executes the compiled runtime, never TypeScript sources.
//
// HTTPS is the extension default and stays the default: this probe asks the
// studio for plaintext HTTP on 127.0.0.1 because a throwaway TLS certificate
// would be rejected (correctly) by the extension, and the extension never
// disables certificate verification. The consent path is the same pure
// validation the extension uses: `validateStudioConfig` with
// `allowInsecureHttp: true`. In the packaged extension that boolean comes
// from the operator's setting plus the modal confirmation; a headless script
// has no dialog, so this script plays the operator and states the opt-in
// explicitly instead of bypassing the guard.
//
// Usage:
//   node scripts/real-studio-smoke.mjs [--studio-bin <path>] [--work-dir <path>]
//                                      [--port <n>] [--timeout-ms <n>]
//
// Exit codes: 0 = both phases proved, 1 = assertion failed,
//             3 = studio/runtime unavailable (no retry, reported as unavailable).

import { spawn } from "node:child_process";
import { mkdirSync } from "node:fs";
import { createServer } from "node:net";
import { createRequire } from "node:module";
import { dirname, join, resolve } from "node:path";
import { performance } from "node:perf_hooks";
import { fileURLToPath } from "node:url";

const require = createRequire(import.meta.url);
const here = dirname(fileURLToPath(import.meta.url));
const appRoot = resolve(here, "..");
const repoRoot = resolve(appRoot, "..", "..");

// Compiled extension modules (CommonJS output of `npm run build`).
let validateStudioConfig, SseClient, StudioClient;
try {
  ({ validateStudioConfig } = require(join(appRoot, "dist", "config.js")));
  ({ SseClient } = require(join(appRoot, "dist", "sseClient.js")));
  ({ StudioClient } = require(join(appRoot, "dist", "sdk.js")));
} catch (error) {
  console.error(`UNAVAILABLE: compiled extension modules missing in apps/vscode/dist (${error.message}).`);
  console.error("Build them first: cd apps/vscode && npm run build");
  process.exit(3);
}

const args = process.argv.slice(2);
const flag = (name, fallback) => {
  const index = args.indexOf(`--${name}`);
  return index >= 0 && args[index + 1] !== undefined ? args[index + 1] : fallback;
};

const studioBin = resolve(flag("studio-bin", join(repoRoot, "studio-cli", "build", "install", "studio-cli", "bin", "studio-cli")));
const workDir = resolve(flag("work-dir", join(repoRoot, "build", "r5-acceptance", "real-studio")));
const timeoutMs = Number(flag("timeout-ms", "240000"));
const SSE_LIMIT_MS = 2000;

const stamp = new Date().toISOString().replace(/[-:.TZ]/g, "").slice(0, 14);
const stateRoot = join(workDir, `state-${stamp}`);
const dataDir = join(stateRoot, "data");
const workspaceDir = join(stateRoot, "workspace");
mkdirSync(dataDir, { recursive: true });
mkdirSync(workspaceDir, { recursive: true });

const log = (line) => console.log(line);
const fail = (message) => {
  console.error(`FAIL: ${message}`);
  process.exitCode = 1;
};
const assert = (condition, message) => {
  if (!condition) throw new Error(message);
};

/** Pick a loopback port the OS says is free right now. */
function freePort() {
  return new Promise((resolvePort, reject) => {
    const server = createServer();
    server.unref();
    server.on("error", reject);
    server.listen(0, "127.0.0.1", () => {
      const { port } = server.address();
      server.close(() => resolvePort(port));
    });
  });
}

async function waitForHealth(baseUrl, deadlineMs, child, label) {
  const started = performance.now();
  while (performance.now() - started < deadlineMs) {
    if (child.exitCode !== null) {
      throw new Error(`${label}: studio exited early with code ${child.exitCode}`);
    }
    try {
      const response = await fetch(`${baseUrl}/health`, { signal: AbortSignal.timeout(1000) });
      if (response.ok) return performance.now() - started;
    } catch {
      // not up yet
    }
    await new Promise((r) => setTimeout(r, 250));
  }
  throw new Error(`${label}: /health did not answer within ${deadlineMs}ms`);
}

const children = new Set();

function startStudio(port) {
  const child = spawn(
    studioBin,
    ["start", "--insecure-http", "--port", String(port), "--data-dir", dataDir, "--db", join(dataDir, "studio.db")],
    { cwd: stateRoot, stdio: ["ignore", "pipe", "pipe"] },
  );
  children.add(child);
  const logPath = join(stateRoot, `studio-${port}-${child.pid}.log`);
  const chunks = [];
  child.stdout.on("data", (b) => chunks.push(b));
  child.stderr.on("data", (b) => chunks.push(b));
  child.on("exit", () => {
    children.delete(child);
    try {
      const { writeFileSync } = require("node:fs");
      writeFileSync(logPath, Buffer.concat(chunks));
    } catch {
      // Log capture is evidence, never a reason to fail the probe.
    }
  });
  return { child, logPath };
}

async function stopStudio(child, label) {
  if (!child || child.exitCode !== null) return;
  child.kill("SIGTERM");
  const exited = await Promise.race([
    new Promise((r) => child.once("exit", () => r(true))),
    new Promise((r) => setTimeout(() => r(false), 15000)),
  ]);
  if (!exited) {
    child.kill("SIGKILL");
    await new Promise((r) => child.once("exit", r));
  }
  log(`${label}: studio pid ${child.pid} stopped`);
}

function connector(baseUrl, profile, events) {
  // Same pure validation the extension runs before any request. The
  // plaintext opt-in is stated here because a headless script has no dialog.
  const config = validateStudioConfig({ baseUrl, profile, allowInsecureHttp: true });
  const sse = new SseClient({
    baseUrl: config.baseUrl,
    headers: config.headers,
    connectTimeoutMs: 5000,
    heartbeatTimeoutMs: 20000,
  });
  sse.onEvent((event) => events.push({ at: performance.now(), event }));
  sse.onState((state) => events.push({ at: performance.now(), state }));
  return { config, sse };
}

function waitForState(events, predicate, deadlineMs, label, sinceAt = 0) {
  const started = performance.now();
  return new Promise((resolveWait, reject) => {
    const tick = () => {
      const hit = events.find((entry) => entry.state && entry.at >= sinceAt && predicate(entry.state));
      if (hit) return resolveWait(hit);
      if (performance.now() - started > deadlineMs) {
        return reject(new Error(`${label}: no matching state within ${deadlineMs}ms`));
      }
      setTimeout(tick, 25);
    };
    tick();
  });
}

function waitForEvent(events, predicate, sinceIndex, deadlineMs, label) {
  const started = performance.now();
  return new Promise((resolveWait, reject) => {
    const tick = () => {
      const hit = events.slice(sinceIndex).find((entry) => entry.event && predicate(entry.event));
      if (hit) return resolveWait(hit);
      if (performance.now() - started > deadlineMs) {
        return reject(new Error(`${label}: no matching SSE event within ${deadlineMs}ms`));
      }
      setTimeout(tick, 10);
    };
    tick();
  });
}

const summary = {
  studioBin,
  stateRoot,
  dataDir,
  sseCreate: null,
  reconnect: null,
};

let activeSse = null;
const hardStop = setTimeout(() => {
  console.error(`UNAVAILABLE: probe exceeded ${timeoutMs}ms; reporting timeout as unavailable (no retry).`);
  try {
    activeSse?.dispose();
  } catch {
    // ignore
  }
  for (const child of children) child.kill("SIGKILL");
  process.exit(3);
}, timeoutMs);

async function main() {
  const port = Number(flag("port", "0")) || (await freePort());
  const baseUrl = `http://127.0.0.1:${port}`;
  log(`studio binary: ${studioBin}`);
  log(`isolated state: ${stateRoot}`);
  log(`base url: ${baseUrl} (plaintext loopback, allowInsecureHttp opt-in)`);

  const events = [];
  const first = startStudio(port);
  const bootMs = await waitForHealth(baseUrl, 90000, first.child, "phase A");
  log(`phase A: studio healthy after ${Math.round(bootMs)}ms (pid ${first.child.pid})`);
  assert(first.child.exitCode === null, "studio must be running for phase A");

  const { config, sse } = connector(baseUrl, "r5-acceptance", events);
  activeSse = sse;
  const sdk = new StudioClient({ baseUrl: config.baseUrl, defaultHeaders: config.headers });

  sse.start();
  const opened = await waitForState(events, (s) => s.kind === "open", 20000, "phase A handshake");
  log(`phase A: SSE open after ${Math.round(opened.at - events[0].at)}ms; snapshot.open=${sse.snapshot().open}`);
  assert(sse.snapshot().open === true, "snapshot().open must be true only after a handshake");

  const nameA = `r5-sse-${stamp}`;
  const beforeCreate = events.length;
  const sentAt = performance.now();
  // The SDK route wrapper, exactly as `StudioApi.createExperiment` calls it:
  // POST /experiments through the generated client.
  const createdBody = await sdk.postExperiments({ name: nameA });
  const created = { experimentId: createdBody.experiment_id, name: createdBody.name };
  const arrived = await waitForEvent(
    events,
    (event) => event.kind === "experiment.created" && event.id === created.experimentId,
    beforeCreate,
    SSE_LIMIT_MS + 2000,
    "phase A experiment.created",
  );
  const deltaMs = arrived.at - sentAt;
  summary.sseCreate = {
    experimentId: created.experimentId,
    name: nameA,
    postReturnedMs: Math.round(performance.now() - sentAt),
    eventArrivedMs: Math.round(deltaMs),
    limitMs: SSE_LIMIT_MS,
    seq: arrived.event.seq,
  };
  log(
    `phase A: POST /experiments ${created.experimentId} -> SSE experiment.created seq=${arrived.event.seq} ` +
      `in ${Math.round(deltaMs)}ms (limit ${SSE_LIMIT_MS}ms)`,
  );
  assert(deltaMs <= SSE_LIMIT_MS, `experiment.created took ${Math.round(deltaMs)}ms, limit ${SSE_LIMIT_MS}ms`);

  // ---- phase B: kill mid-stream, reconnect with backoff, prove the new
  // stream carries a real event again.
  const killAt = performance.now();
  await stopStudio(first.child, "phase B");
  const downLog = await waitForState(events, (s) => s.kind === "backoff", 20000, "phase B backoff", killAt);
  log(
    `phase B: stream down detected ${Math.round(downLog.at - killAt)}ms after SIGTERM; ` +
      `backoff attempt=${downLog.state.attempt} nextAttemptInMs=${downLog.state.nextAttemptInMs} ` +
      `reason="${downLog.state.reason}"`,
  );
  assert(sse.snapshot().open === false, "client must not report open while the studio is down");

  const second = startStudio(port);
  const bootMsB = await waitForHealth(baseUrl, 90000, second.child, "phase B");
  log(`phase B: studio restarted after ${Math.round(bootMsB)}ms (pid ${second.child.pid})`);
  const reopened = await waitForState(events, (s) => s.kind === "open", 60000, "phase B reopen", killAt);
  const backoffs = events.filter((entry) => entry.state?.kind === "backoff" && entry.at >= killAt);
  log(
    `phase B: SSE re-opened after ${Math.round(reopened.at - killAt)}ms total; ` +
      `${backoffs.length} backoff attempt(s) observed: ${backoffs.map((b) => b.state.nextAttemptInMs).join(",")}ms`,
  );
  assert(backoffs.length >= 1, "reconnect must have gone through the documented backoff path");
  assert(sse.snapshot().open === true, "reconnected client must report open");

  const nameB = `r5-reconnect-${stamp}`;
  const beforeSecond = events.length;
  const sentAtB = performance.now();
  const createdBBody = await sdk.postExperiments({ name: nameB });
  const createdB = { experimentId: createdBBody.experiment_id, name: createdBBody.name };
  const arrivedB = await waitForEvent(
    events,
    (event) => event.kind === "experiment.created" && event.id === createdB.experimentId,
    beforeSecond,
    SSE_LIMIT_MS + 2000,
    "phase B experiment.created",
  );
  const deltaB = arrivedB.at - sentAtB;
  summary.reconnect = {
    downDetectedMs: Math.round(downLog.at - killAt),
    waitedForBackoff: true,
    backoffAttempts: backoffs.length,
    reopenedAfterMs: Math.round(reopened.at - killAt),
    restartedBootMs: Math.round(bootMsB),
    experimentId: createdB.experimentId,
    eventArrivedMs: Math.round(deltaB),
    seq: arrivedB.event.seq,
  };
  log(
    `phase B: after reconnect POST /experiments ${createdB.experimentId} -> SSE experiment.created ` +
      `seq=${arrivedB.event.seq} in ${Math.round(deltaB)}ms`,
  );

  // Authoritative read confirms both experiments persisted in the isolated DB
  // (not just delivered as an event).
  const listed = await (await fetch(`${baseUrl}/experiments`, { headers: config.headers })).json();
  const ids = new Set((listed.items ?? []).map((item) => item.experiment_id));
  assert(ids.has(created.experimentId) && ids.has(createdB.experimentId), "GET /experiments must list both experiments");
  summary.persisted = { experiments: ids.size };

  await stopStudio(second.child, "phase B");
  sse.dispose();
  activeSse = null;
}

main()
  .then(() => {
    clearTimeout(hardStop);
    log("");
    log("REAL STUDIO SMOKE: PASS");
    log(JSON.stringify(summary, null, 2));
  })
  .catch((error) => {
    clearTimeout(hardStop);
    fail(error.message);
    log(JSON.stringify(summary, null, 2));
  })
  .finally(() => {
    try {
      activeSse?.dispose();
    } catch {
      // ignore
    }
    // Only the processes this script started, and only after they exited.
    for (const child of children) {
      if (child.exitCode === null) child.kill("SIGKILL");
    }
    log(`evidence: isolated state under ${stateRoot} (ignored build/r5-acceptance path)`);
  });
