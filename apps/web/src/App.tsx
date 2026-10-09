import { useEffect, useState } from "react";

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
    <main style={{ fontFamily: "system-ui", margin: "2rem", color: "#222" }}>
      <h1>PipelineK Studio</h1>
      <p>
        React + Vite + Monaco shell wired to the Studio backend at{" "}
        <code>{API_BASE}</code>. Read-side events use the SSE stream at
        <code>/runs/{`{id}`}/stream</code>. Editor lands in S1.2.
      </p>

      <section
        style={{
          border: "1px solid #ddd",
          padding: "1rem",
          marginTop: "1rem",
          borderRadius: "8px",
        }}
      >
        <h2>Studio + SDK status</h2>
        {versionError && <p style={{ color: "crimson" }}>error: {versionError}</p>}
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
          <p>loading…</p>
        ) : null}
      </section>

      <section
        style={{
          border: "1px solid #ddd",
          padding: "1rem",
          marginTop: "1rem",
          borderRadius: "8px",
        }}
      >
        <h2>Experiment exploration (proves MCP/CLI/HTTP parity)</h2>
        <button onClick={createExperiment} disabled={creating}>
          {creating ? "creating…" : "create experiment"}
        </button>
        <ul>
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
