import { useEffect, useState } from "react";
import { KotlinEditor } from "./KotlinEditor";

const API_BASE =
  (typeof import.meta !== "undefined" && (import.meta as any).env?.VITE_STUDIO_HTTP) ||
  "http://127.0.0.1:7777";

interface VersionDto {
  studio: string;
  pipelinekRuntime?: string;
  pipelinekRuntimeDigest?: string;
  pipelinekRuntimeCapabilities?: string[];
}

export default function App() {
  const [version, setVersion] = useState<VersionDto | null>(null);
  const [versionError, setVersionError] = useState<string | null>(null);
  const [experiments, setExperiments] = useState<string[]>([]);
  const [creating, setCreating] = useState(false);
  const [editorSource, setEditorSource] = useState<string>("");

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
      if (body.experiment_id) setExperiments((e) => [...e, body.experiment_id!]);
    } finally {
      setCreating(false);
    }
  };

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

      <p style={{ marginTop: "2rem", color: "#666" }}>
        Try also: <code>studio create-experiment</code> · <code>studio run</code> ·{" "}
        <code>echo '{`{"jsonrpc":"2.0","id":1,"method":"tools/list"}`}' | bun src/server.ts</code>{" "}
        (MCP from <code>apps/mcp/</code>)
      </p>
    </main>
  );
}
