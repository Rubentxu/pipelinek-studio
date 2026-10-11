import { useCallback, useEffect, useState } from "react";
import { DiagnosticsViewer, type DiagnosticDto } from "./DiagnosticsViewer";
import { EventStream } from "./EventStream";
import { IrExplorer } from "./IrExplorer";
import { KotlinEditor } from "./KotlinEditor";
import { OutputViewer } from "./OutputViewer";
import { useKeyboardShortcuts } from "./useKeyboardShortcuts";
import { studioBaseUrl, fetchWithTimeout } from "./lib/api";
import { useDiagnosticsMarkers, type MarkerSeverityMap, type SetMarkersFn } from "./DiagnosticsMarkers";

// Audit F-P1-1 (2026-10-10): the previous implementation had the
// loopback URL duplicated in 5 files. The default now lives in
// src/lib/api.ts as a single source of truth; App.tsx imports
// studioBaseUrl() and passes the resolved value to every viewer
// as a required prop. The viewers' hardcoded fallback
// `baseUrl = "http://127.0.0.1:7777"` has been removed.
const API_BASE = studioBaseUrl();

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
  // R0.3.1 (2026-10-11): scenarios are first-class. The previous
  // S1 anti-pattern of using experiment_id as a scenario_id
  // surrogate is gone — the operator picks (or registers) a real
  // scenario here. Backend POST /scenarios is the source of truth.
  const [scenarios, setScenarios] = useState<Array<{ scenarioId: string; version: number; pluginLockDigest: string | null }>>([]);
  const [selectedScenario, setSelectedScenario] = useState<string | null>(null);
  const [newScenarioId, setNewScenarioId] = useState<string>("");
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

  // R3.7b (2026-10-11): Monaco markers wiring.
  //
  // The Monaco editor instance is captured via KotlinEditor's new
  // `onMount` prop. The diagnostics array is fetched in parallel to
  // the DiagnosticsViewer's own poll (a duplicated fetch — chosen
  // over refactoring DiagnosticsViewer's internal state because the
  // duplication is contained, the diagnostics endpoint is cheap,
  // and a state lift would touch every existing test. A later
  // iteration can collapse the two polls if the cost shows up in
  // profiles). Once R3.1 adapter wiring closes the
  // `currentSourceDigest` plumbing, the duplicate also lets us add
  // a digest-filter to the markers without touching DiagnosticsViewer.
  const [editorInstance, setEditorInstance] = useState<unknown | null>(null);
  const [diagnosticsForMarkers, setDiagnosticsForMarkers] = useState<DiagnosticDto[]>([]);

  // setCurrentRun wrapper that also persists. We use a single setter so
  // setCurrentRun wrapper that also persists. We use a single setter so
  // every update of the live run view lands in localStorage; nothing else
  // in the component writes to RUN_STORAGE_KEY.
  //
  // Audit F-P1-WEB-11 (2026-10-10): the previous implementation
  // persisted the run forever, even after it reached a terminal
  // state (SUCCEEDED / FAILED / CANCELLED). A user who closed
  // the tab after a run finished, then re-opened it the next
  // day, would rehydrate the stale entry and see the same
  // terminal state in the UI with no obvious way to start a
  // new run from the current editor source. The new behavior
  // clears the localStorage entry as soon as the run is
  // terminal, so a page refresh after the run finishes lands
  // on the "create experiment" flow again.
  const TERMINAL_STATES = new Set(["SUCCEEDED", "FAILED", "CANCELLED"]);
  const setCurrentRun = (next: { runId: string; state: string } | null) => {
    setCurrentRunState(next);
    try {
      if (typeof localStorage !== "undefined") {
        if (next === null || TERMINAL_STATES.has(next.state)) {
          localStorage.removeItem(RUN_STORAGE_KEY);
        } else {
          localStorage.setItem(RUN_STORAGE_KEY, JSON.stringify(next));
        }
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
    void fetchWithTimeout(`${API_BASE}/runs/${encodeURIComponent(currentRun.runId)}`)
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
    void fetchWithTimeout(`${API_BASE}/api/version`)
      .then(async (r) => {
        if (!r.ok) throw new Error(`HTTP ${r.status}`);
        return (await r.json()) as VersionDto;
      })
      .then(setVersion)
      .catch((e) => setVersionError(String(e)));
  }, []);

  // R3.7b (2026-10-11): duplicate diagnostics poll for the Monaco
  // markers hook. Duplicates DiagnosticsViewer's fetch intentionally
  // (see the comment on editorInstance state). Polls every 3s while
  // a run is live, stops on terminal state to mirror the viewer.
  useEffect(() => {
    if (!currentRun) {
      setDiagnosticsForMarkers([]);
      return;
    }
    const ac = new AbortController();
    let stillMounted = true;
    const fetchOnce = async () => {
      try {
        const r = await fetchWithTimeout(
          `${API_BASE}/runs/${encodeURIComponent(currentRun.runId)}/diagnostics`,
          { signal: ac.signal },
        );
        const body = (await r.json().catch(() => ({}))) as { diagnostics?: DiagnosticDto[] };
        if (stillMounted) setDiagnosticsForMarkers(body.diagnostics ?? []);
      } catch (e) {
        if (stillMounted && (e as { name?: string }).name !== "AbortError") {
          // leave existing diagnostics — better stale than blank
        }
      }
    };
    void fetchOnce();
    const TERMINAL_STATES_LOCAL = new Set(["SUCCEEDED", "FAILED", "CANCELLED"]);
    if (TERMINAL_STATES_LOCAL.has(currentRun.state)) {
      return () => {
        stillMounted = false;
        ac.abort();
      };
    }
    const t = setInterval(fetchOnce, 3000);
    return () => {
      stillMounted = false;
      ac.abort();
      clearInterval(t);
    };
  }, [currentRun]);

  // R3.7b (2026-10-11): read Monaco from the global namespace only
  // once it is loaded. The KotlinEditor only mounts the `<Editor>`
  // after `loader.init()` resolves, so by the time `editorInstance`
  // is non-null, `window.monaco` exists. We still defensively guard
  // because the timing of `<Editor>`'s `onMount` vs `loader.init()`
  // is Monaco's responsibility, not ours.
  type MonacoGlobal = {
    editor?: { setModelMarkers?: SetMarkersFn };
    MarkerSeverity?: MarkerSeverityMap;
  };
  const monacoGlobal = (globalThis as { monaco?: MonacoGlobal }).monaco;
  const setModelMarkers: SetMarkersFn =
    monacoGlobal?.editor?.setModelMarkers ?? (() => {});
  const markerSeverityMap: MarkerSeverityMap =
    monacoGlobal?.MarkerSeverity ?? { Error: 8, Warning: 4, Info: 1 };
  // `defaultPath` mirrors the value KotlinEditor.tsx passes to the
  // `<Editor>` of `@monaco-editor/react` (snapshot.pipeline.kts).
  // The path-filter uses this to decide which diagnostics to paint;
  // without it, a multi-file scenario would paint markers for files
  // the editor is not showing.
  const EDITOR_PATH = "snapshot.pipeline.kts";
  const editorModel =
    editorInstance && typeof (editorInstance as { getModel?: () => unknown }).getModel === "function"
      ? (editorInstance as { getModel: () => unknown }).getModel()
      : null;
  useDiagnosticsMarkers({
    model: editorModel,
    diagnostics: diagnosticsForMarkers,
    editorPath: EDITOR_PATH,
    setMarkers: setModelMarkers,
    severityMap: markerSeverityMap,
  });

  const createExperiment = async () => {
    setCreating(true);
    try {
      const r = await fetchWithTimeout(`${API_BASE}/experiments`, {
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

  // R0.3.1 (2026-10-11): register a fresh scenario under the explicit
  // id the operator typed (or auto-mint if empty). The backend
  // issues the ScenarioId when the request omits one.
  const registerScenario = async () => {
    setCreating(true); // reuse the same busy-state flag for a second-level action.
    try {
      const body: { scenario_id?: string } = {};
      if (newScenarioId.trim().length > 0) body.scenario_id = newScenarioId.trim();
      const r = await fetchWithTimeout(`${API_BASE}/scenarios`, {
        method: "POST",
        headers: { "content-type": "application/json" },
        body: JSON.stringify(body),
      });
      const resp = (await r.json()) as { scenario_id?: string; version?: number };
      if (resp.scenario_id) {
        await refreshScenarios();
        setSelectedScenario(resp.scenario_id);
        setNewScenarioId("");
      }
    } finally {
      setCreating(false);
    }
  };

  const refreshScenarios = async () => {
    try {
      const r = await fetchWithTimeout(`${API_BASE}/scenarios?limit=100`);
      const body = (await r.json()) as { items?: Array<{ scenario_id: string; version: number; plugin_lock_digest: string | null }> };
      const items = (body.items ?? []).map((it) => ({
        scenarioId: it.scenario_id,
        version: it.version,
        pluginLockDigest: it.plugin_lock_digest ?? null,
      }));
      setScenarios(items);
    } catch {
      // backend unavailable / no scenarios yet — leave the list empty.
    }
  };

  // Hydrate scenarios on mount so the picker is not empty when an
  // operator opens a fresh tab against an existing studio.db.
  useEffect(() => {
    void refreshScenarios();
  }, []);

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
      const r = await fetchWithTimeout(
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
  // otherwise).
  //
  // Audit F-P1-WEB-6 (2026-10-10): the previous implementation
  // generated a fresh key per submit call (`Date.now() +
  // Math.random()`). A double-click on the "Run snapshot" button
  // (or any other race) would fire two POSTs with two different
  // keys, and the backend would admit two distinct runs for the
  // same logical intent. The new key is stable per (experiment,
  // snapshot) pair so a repeat click is correctly de-duplicated
  // by the backend's idempotency layer (AAT-API-008). Changing
  // either input yields a new key, which is the correct
  // semantic: a new (experiment, snapshot) is a new intent.
  const submitRun = async () => {
    if (!lastSnapshot?.snapshot_id || !selectedExperiment || !selectedScenario) {
      setSubmitError("Pick an experiment, seal a snapshot, and pick a scenario before running.");
      return;
    }
    // R0.3.1 (2026-10-11): idempotency key now binds (experiment, snapshot,
    // scenario) so a different scenario against the same snapshot
    // becomes a new intent.
    const idemKey = `web-${selectedExperiment}-${lastSnapshot.snapshot_id}-${selectedScenario}`;
    setSubmitting(true);
    setSubmitError(null);
    try {
      const r = await fetchWithTimeout(`${API_BASE}/runs`, {
        method: "POST",
        headers: {
          "content-type": "application/json",
          "idempotency-key": idemKey,
        },
        body: JSON.stringify({
          experiment_id: selectedExperiment,
          snapshot_id: lastSnapshot.snapshot_id,
          // R0.3.1: scenario_id is now the operator-selected scenario,
          // NOT a duplicate of experiment_id (pre-R0.3.1 S1 surrogate).
          scenario_id: selectedScenario,
        }),
      });
      const body = (await r.json().catch(() => ({}))) as { run_id?: string; state?: string; code?: string; message?: string };
      // R0.2.5 (2026-10-11): the POST create family includes 200 OK
      // (legacy canonical body), 201 Created (ReservationOutcome.Admitted,
      // started), and 202 Accepted (ReservationOutcome.Reserved,
      // durable). Any of the three is a successful submit; the
      // state field in the body differentiates.
      if ((r.status === 200 || r.status === 201 || r.status === 202) && body.run_id) {
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
  // backend returns:
  //   200 + {acknowledged: true}      the SDK subprocess was killed
  //   202 + CANCEL_UNKNOWN           no live handle exists; the run
  //                                   already finished, so the cancel
  //                                   is a no-op (the run is already
  //                                   in a terminal state)
  // The previous implementation treated 202 as an error and
  // surfaced "HTTP 202" to the operator, which contradicted the
  // documented contract and made every "cancel an already-finished
  // run" interaction look like a failure. The new code accepts
  // both 200 and 202 as success paths and reconciles the local
  // state via the next /runs/{id} poll.
  const cancelRun = async () => {
    if (!currentRun) return;
    setCancelling(true);
    setCancelError(null);
    try {
      const r = await fetchWithTimeout(`${API_BASE}/runs/${encodeURIComponent(currentRun.runId)}/cancel`, {
        method: "POST",
      });
      const body = (await r.json().catch(() => ({}))) as { code?: string; message?: string };
      if (r.status === 200 || r.status === 202) {
        // Mark the run as CANCELLED locally; the next /runs/{id} poll
        // will reconcile against the studio.db row. For 202 the
        // backend already said the run is in a terminal state, so
        // CANCELLED is the correct local marker.
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
        <KotlinEditor onChange={setEditorSource} onMount={setEditorInstance} />
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
            onClick={registerScenario}
            disabled={creating}
            aria-busy={creating}
            aria-label="Register a new scenario (auto-minted id) or use the typed id below"
            data-testid="register-scenario-quick"
            style={{ padding: "0.25rem 0.5rem" }}
          >
            {creating ? "registering…" : "+ scenario"}
          </button>
        </div>
        {/* R0.3.1 (2026-10-11): dedicated scenario picker — no more
            experiment_id-as-scenario_id surrogate (S1 anti-pattern). */}
        <div style={{ marginTop: "0.5rem", display: "flex", gap: "0.5rem", alignItems: "center" }}>
          <label htmlFor="snapshot-scenario">Scenario:</label>
          <select
            id="snapshot-scenario"
            value={selectedScenario ?? ""}
            onChange={(e) => setSelectedScenario(e.target.value || null)}
            aria-label="Scenario to run against (must be a real registered scenario)"
            data-testid="scenario-select"
            style={{ flex: 1, padding: "0.25rem" }}
          >
            <option value="">(none — register one first)</option>
            {scenarios.map((s) => (
              <option key={s.scenarioId} value={s.scenarioId}>
                {s.scenarioId} (v{s.version})
              </option>
            ))}
          </select>
          <input
            type="text"
            placeholder="(auto-mint id)"
            value={newScenarioId}
            onChange={(e) => setNewScenarioId(e.target.value)}
            aria-label="Optional explicit scenario id (empty = server mints one)"
            data-testid="new-scenario-id"
            style={{ flex: 1, padding: "0.25rem", width: 180 }}
          />
        </div>
        <div style={{ marginTop: "0.5rem", display: "flex", gap: "0.5rem", alignItems: "center" }}>
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
          Tip: <kbd>Cmd-S</kbd> to seal, <kbd>Cmd-Enter</kbd> to create experiment, <kbd>Cmd-K</kbd> to focus the editor, <kbd>Esc</kbd> to clear focus.
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

      <EventStream runId={currentRun?.runId ?? ""} baseUrl={API_BASE} />

      <OutputViewer runId={currentRun?.runId ?? ""} baseUrl={API_BASE} />

      <DiagnosticsViewer
        runId={currentRun?.runId ?? ""}
        baseUrl={API_BASE}
        runState={currentRun?.state}
      />

      <IrExplorer
        runId={currentRun?.runId ?? ""}
        baseUrl={API_BASE}
        runState={currentRun?.state}
      />

      <p style={{ marginTop: "2rem", color: "#666" }}>
        Try also: <code>studio create-experiment</code> · <code>studio run</code> ·{" "}
        <code>echo '{`{"jsonrpc":"2.0","id":1,"method":"tools/list"}`}' | bun src/server.ts</code>{" "}
        (MCP from <code>apps/mcp/</code>)
      </p>
    </main>
  );
}
