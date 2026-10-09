import { useCallback, useEffect, useState } from "react";
import { DiagnosticsViewer } from "./DiagnosticsViewer";
import { EventStream } from "./EventStream";
import { KotlinEditor } from "./KotlinEditor";
import { OutputViewer } from "./OutputViewer";
import { useKeyboardShortcuts } from "./useKeyboardShortcuts";

const API_BASE =
  (typeof import.meta !== "undefined" && (import.meta as any).env?.VITE_STUDIO_HTTP) ||
  "http://127.0.0.1:7777";

interface VersionDto {
  studio: string;
  pipelinekRuntime?: string;
  pipelinekRuntimeDigest?: string;
  pipelinekRuntimeCapabilities?: string[];
}

interface SealedSnapshotDto {
  snapshot_id?: string;
  experiment_id?: string;
  sha256?: string;
  created_at?: string;
}

// S1 E1.6 — localStorage persistence for the live run subscription.
// The EventStream + OutputViewer + DiagnosticsViewer all subscribe to
// the runId. Without persistence, a browser refresh drops the
// subscription and the operator loses the live view of an in-flight
// run. We persist currentRun (just the run_id + state) under a single
// key so a refresh rehydrates the same view automatically. The
// backend is the source of truth — on rehydrate we also re-fetch
// /runs/{id} to confirm the run still exists and to refresh the state
// from the studio.db row (which may have advanced from QUEUED to
// RUNNING/SUCCEEDED/... while the page was closed).
const RUN_STORAGE_KEY = "pipelinek-studio:currentRun";

function readPersistedRun(): { runId: string; state: string } | null {
  try {
    const raw = typeof localStorage !== "undefined" ? localStorage.getItem(RUN_STORAGE_KEY) : null;
    if (!raw) return null;
    const parsed = JSON.parse(raw) as { runId?: string; state?: string };
    if (typeof parsed.runId === "string" && parsed.runId.length > 0) {
      return { runId: parsed.runId, state: parsed.state ?? "UNKNOWN" };
    }
  } catch {
    // localStorage may be unavailable (private mode, SSR); fall through.
  }
  return null;
}

export default function App() {
  const [version, setVersion] = useState<VersionDto | null>(null);
  const [versionError, setVersionError] = useState<string | null>(null);
  const [experiments, setExperiments] = useState<string[]>([]);
  const [selectedExperiment, setSelectedExperiment] = useState<string | null>(null);
  const [creating, setCreating] = useState(false);
  const [sealing, setSealing] = useState(false);
  const [lastSnapshot, setLastSnapshot] = useState<SealedSnapshotDto | null>(null);
  const [sealError, setSealError] = useState<string | null>(null);
  const [editorSource, setEditorSource] = useState<string>("");
  // S1 wire-up: after submitting a run, hold the run_id so the EventStream
  // and OutputViewer subscribe to the real run, not the (incorrect) last
  // experiment. The submit response carries run_id + state.
  const [submitting, setSubmitting] = useState(false);
  // Rehydrate from localStorage on first render so a page refresh keeps
  // the EventStream + OutputViewer + DiagnosticsViewer subscriptions alive.
  const [currentRun, setCurrentRunState] = useState<{ runId: string; state: string } | null>(
    () => readPersistedRun(),
  );
  const [submitError, setSubmitError] = useState<string | null>(null);
  const [cancelling, setCancelling] = useState(false);
  const [cancelError, setCancelError] = useState<string | null>(null);

  // setCurrentRun wrapper that also persists. We use a single setter so
  // every update of the live run view lands in localStorage; nothing else
  // in the component writes to RUN_STORAGE_KEY.
  const setCurrentRun = (next: { runId: string; state: string } | null) => {
    setCurrentRunState(next);
    try {
      if (typeof localStorage !== "undefined") {
        if (next === null) localStorage.removeItem(RUN_STORAGE_KEY);
        else localStorage.setItem(RUN_STORAGE_KEY, JSON.stringify(next));
      }
    } catch {
      // ignore — best-effort persistence
    }
  };

  // On rehydrate (or whenever currentRun changes via rehydrate), refresh
  // the state from the backend so the operator sees an up-to-date state
  // (SUCCEEDED/FAILED/...) instead of the stale state stored at submit
  // time. Only fires when currentRun came from localStorage on mount.
  useEffect(() => {
    if (!currentRun) return;
    const fromStorage = readPersistedRun();
    if (!fromStorage || fromStorage.runId !== currentRun.runId) return; // already current
    void fetch(`${API_BASE}/runs/${encodeURIComponent(currentRun.runId)}`)
      .then(async (r) => (r.ok ? (await r.json()) : null))
      .then((body) => {
        if (body && typeof body.state === "string") {
          setCurrentRun({ runId: currentRun.runId, state: body.state });
        }
      })
      .catch(() => { /* ignore — keep the persisted state */ });
    // We intentionally re-run only when the runId changes; not on every
    // state update (which would loop).
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [currentRun?.runId]);

  useEffect(() => {
    void fetch(`${API_BASE}/api/version`)
      .then(async (r) => {
        if (!r.ok) throw new Error(`HTTP ${r.status}`);
        return (await r.json()) as VersionDto;
      })
      .then(setVersion)
      .catch((e) => setVersionError(String(e)));
  }, []);

  const createExperiment = async () => {
    setCreating(true);
    try {
      const r = await fetch(`${API_BASE}/experiments`, {
        method: "POST",
        headers: { "content-type": "application/json" },
        body: JSON.stringify({ name: `from-web-${Date.now()}` }),
      });
      const body = (await r.json()) as { experiment_id?: string };
      if (body.experiment_id) {
        setExperiments((e) => [...e, body.experiment_id!]);
        setSelectedExperiment(body.experiment_id!);
      }
    } finally {
      setCreating(false);
    }
  };

  // S1 E1.2 — seal the current editor source as a snapshot under the
  // selected experiment. POST /experiments/{id}/snapshots with the
  // raw Kotlin source as the body. The backend computes SHA-256 and
  // persists the source immutably (ADR-005).
  const sealSnapshot = async () => {
    if (!selectedExperiment) return;
    if (editorSource.trim().length === 0) {
      setSealError("Source is empty — type something to seal.");
      return;
    }
    setSealing(true);
    setSealError(null);
    try {
      const r = await fetch(
        `${API_BASE}/experiments/${encodeURIComponent(selectedExperiment)}/snapshots`,
        {
          method: "POST",
          headers: { "content-type": "application/json" },
          body: JSON.stringify({ source: editorSource, creator: "from-web" }),
        },
      );
      const body = (await r.json()) as SealedSnapshotDto;
      if (!r.ok) {
        setSealError(`HTTP ${r.status}: ${JSON.stringify(body)}`);
        return;
      }
      setLastSnapshot(body);
    } catch (e) {
      setSealError(String(e));
    } finally {
      setSealing(false);
    }
  };

  // S1 wire-up: submit the sealed snapshot to /runs. Returns the new
  // run_id which the EventStream + OutputViewer then subscribe to.
  // Idempotency-Key is required (the backend returns 400 MISSING_HEADER
  // otherwise) — we generate a fresh key per submit.
  const submitRun = async () => {
    if (!lastSnapshot?.snapshot_id || !selectedExperiment) return;
    setSubmitting(true);
    setSubmitError(null);
    try {
      const idemKey = `web-${Date.now()}-${Math.random().toString(36).slice(2, 8)}`;
      const r = await fetch(`${API_BASE}/runs`, {
        method: "POST",
        headers: {
          "content-type": "application/json",
          "idempotency-key": idemKey,
        },
        body: JSON.stringify({
          experiment_id: selectedExperiment,
          snapshot_id: lastSnapshot.snapshot_id,
          scenario_id: selectedExperiment, // S1 uses experiment_id as scenario_id surrogate
        }),
      });
      const body = (await r.json().catch(() => ({}))) as { run_id?: string; state?: string; code?: string; message?: string };
      if (r.status === 200 && body.run_id) {
        setCurrentRun({ runId: body.run_id, state: body.state ?? "QUEUED" });
      } else {
        setSubmitError(
          body.code ? `${body.code}: ${body.message ?? ""}` : `HTTP ${r.status}: ${JSON.stringify(body)}`,
        );
      }
    } catch (e) {
      setSubmitError(String(e));
    } finally {
      setSubmitting(false);
    }
  };

  // S1 wire-up: cancel an in-flight run. POST /runs/{id}/cancel. The
  // backend returns 200 + {acknowledged: true} when the SDK subprocess
  // was killed, or 202 + CANCEL_UNKNOWN when no live handle exists
  // (run already finished). The SPA surfaces both honestly and
  // updates currentRun so the EventStream + OutputViewer
  // subscriptions tear down.
  const cancelRun = async () => {
    if (!currentRun) return;
    setCancelling(true);
    setCancelError(null);
    try {
      const r = await fetch(`${API_BASE}/runs/${encodeURIComponent(currentRun.runId)}/cancel`, {
        method: "POST",
      });
      const body = (await r.json().catch(() => ({}))) as { code?: string; message?: string };
      if (r.status === 200) {
        // Mark the run as CANCELLED locally; the next /runs/{id} poll
        // will reconcile against the studio.db row.
        setCurrentRun({ runId: currentRun.runId, state: "CANCELLED" });
      } else {
        setCancelError(body.code ? `${body.code}: ${body.message ?? ""}` : `HTTP ${r.status}`);
      }
    } catch (e) {
      setCancelError(String(e));
    } finally {
      setCancelling(false);
    }
  };

  // S5 E5.6 — keyboard shortcuts: Cmd-S to seal, Cmd-Enter to create
  // experiment, Escape to clear focus.
  const { containerRef, lastKey } = useKeyboardShortcuts({
    onSave: () => void sealSnapshot(),
    onCreate: () => void createExperiment(),
  });

  return (
    <main
      id="main-content"
      role="main"
      aria-labelledby="page-title"
      tabIndex={-1}
      style={{ fontFamily: "system-ui", margin: "2rem", color: "#222" }}
    >
      <h1 id="page-title">PipelineK Studio</h1>
      <p>
        React + Vite + Monaco wired to the Studio backend at{" "}
        <code>{API_BASE}</code>. The editor below (S1 E1.1) is the Kotlin
        source view for a snapshot; the save action lands in S1 E1.2.
      </p>

      <section
        role="region"
        aria-labelledby="status-title"
        style={{
          border: "1px solid #ddd",
          padding: "1rem",
          marginTop: "1rem",
          borderRadius: "8px",
        }}
      >
        <h2 id="status-title">Studio + SDK status</h2>
        {versionError && (
          <p role="alert" style={{ color: "crimson" }}>error: {versionError}</p>
        )}
        {version ? (
          <ul>
            <li>Studio: <code>{version.studio}</code></li>
            <li>
              PipelineK runtime:{" "}
              <code>{version.pipelinekRuntime ?? "— (UNAVAILABLE — install SDK and pass --sdk-classpath)"}</code>
            </li>
            {version.pipelinekRuntimeDigest && (
              <li>SHA-256: <code>{version.pipelinekRuntimeDigest.slice(0, 16)}…</code></li>
            )}
            {version.pipelinekRuntimeCapabilities && version.pipelinekRuntimeCapabilities.length > 0 && (
              <li>Capabilities: {version.pipelinekRuntimeCapabilities.join(", ")}</li>
            )}
          </ul>
        ) : !versionError ? (
          <p aria-live="polite">loading…</p>
        ) : null}
      </section>

      <section
        role="region"
        aria-labelledby="editor-title"
        style={{
          border: "1px solid #ddd",
          padding: "1rem",
          marginTop: "1rem",
          borderRadius: "8px",
        }}
      >
        <h2 id="editor-title">Kotlin source (Monaco)</h2>
        <KotlinEditor onChange={setEditorSource} />
        <p
          aria-live="polite"
          aria-atomic="true"
          style={{ fontSize: "0.85em", color: "#666", marginTop: "0.5rem" }}
        >
          {editorSource.length} chars typed
        </p>
        <div style={{ marginTop: "0.75rem", display: "flex", gap: "0.5rem", alignItems: "center" }}>
          <label htmlFor="snapshot-experiment">Experiment:</label>
          <select
            id="snapshot-experiment"
            value={selectedExperiment ?? ""}
            onChange={(e) => setSelectedExperiment(e.target.value || null)}
            style={{ flex: 1, padding: "0.25rem" }}
          >
            <option value="">(none — create one first)</option>
            {experiments.map((id) => (
              <option key={id} value={id}>{id}</option>
            ))}
          </select>
          <button
            onClick={sealSnapshot}
            disabled={!selectedExperiment || sealing}
            aria-busy={sealing}
            aria-label="Seal the current editor source as a snapshot under the selected experiment"
          >
            {sealing ? "sealing…" : "Save snapshot"}
          </button>
        </div>
        {sealError && (
          <p role="alert" style={{ color: "crimson", marginTop: "0.5rem" }}>error: {sealError}</p>
        )}
        {lastSnapshot && lastSnapshot.snapshot_id && (
          <p
            role="status"
            aria-live="polite"
            style={{ fontSize: "0.85em", color: "#0a0", marginTop: "0.5rem" }}
          >
            sealed: <code>{lastSnapshot.snapshot_id}</code> · sha256:{" "}
            <code>{lastSnapshot.sha256?.slice(0, 16)}…</code>
          </p>
        )}
        {lastSnapshot && lastSnapshot.snapshot_id && (
          <div style={{ marginTop: "0.5rem" }}>
            <button
              onClick={submitRun}
              disabled={submitting}
              aria-busy={submitting}
              aria-label="Submit the sealed snapshot as a new run against the live SDK"
              data-testid="run-button"
            >
              {submitting ? "submitting…" : "Run snapshot"}
            </button>
            {submitError && (
              <p role="alert" style={{ color: "crimson", marginTop: "0.5rem" }}>
                submit error: {submitError}
              </p>
            )}
            {currentRun && (
              <p
                role="status"
                aria-live="polite"
                data-testid="current-run"
                style={{ fontSize: "0.85em", color: "#06c", marginTop: "0.5rem" }}
              >
                run: <code>{currentRun.runId}</code> · state:{" "}
                <strong>{currentRun.state}</strong>
              </p>
            )}
            {currentRun && currentRun.state !== "SUCCEEDED" && currentRun.state !== "FAILED" && currentRun.state !== "CANCELLED" && (
              <div style={{ marginTop: "0.5rem" }}>
                <button
                  onClick={cancelRun}
                  disabled={cancelling}
                  aria-busy={cancelling}
                  aria-label="Cancel the in-flight run (kills the SDK subprocess)"
                  data-testid="cancel-button"
                >
                  {cancelling ? "cancelling…" : "Cancel run"}
                </button>
                {cancelError && (
                  <p role="alert" style={{ color: "crimson", marginTop: "0.25rem" }}>
                    cancel: {cancelError}
                  </p>
                )}
              </div>
            )}
          </div>
        )}
        <p
          aria-live="polite"
          aria-atomic="true"
          style={{ fontSize: "0.75em", color: "#888", marginTop: "0.25rem" }}
        >
          Tip: <kbd>Cmd-S</kbd> to seal, <kbd>Cmd-Enter</kbd> to create experiment, <kbd>Esc</kbd> to clear focus.
          {lastKey && <span style={{ marginLeft: "0.5rem" }}>last: <code>{lastKey}</code></span>}
        </p>
      </section>

      <section
        role="region"
        aria-labelledby="experiments-title"
        style={{
          border: "1px solid #ddd",
          padding: "1rem",
          marginTop: "1rem",
          borderRadius: "8px",
        }}
      >
        <h2 id="experiments-title">Experiment exploration (proves MCP/CLI/HTTP parity)</h2>
        <button
          onClick={createExperiment}
          disabled={creating}
          aria-busy={creating}
          aria-label="Create a new experiment"
        >
          {creating ? "creating…" : "create experiment"}
        </button>
        <ul aria-label="Created experiments">
          {experiments.map((id) => (
            <li key={id}>
              <code>{id}</code>
            </li>
          ))}
        </ul>
      </section>

      <EventStream runId={currentRun?.runId ?? ""} />

      <OutputViewer runId={currentRun?.runId ?? ""} />

      <DiagnosticsViewer runId={currentRun?.runId ?? ""} />

      <p style={{ marginTop: "2rem", color: "#666" }}>
        Try also: <code>studio create-experiment</code> · <code>studio run</code> ·{" "}
        <code>echo '{`{"jsonrpc":"2.0","id":1,"method":"tools/list"}`}' | bun src/server.ts</code>{" "}
        (MCP from <code>apps/mcp/</code>)
      </p>
    </main>
  );
}
