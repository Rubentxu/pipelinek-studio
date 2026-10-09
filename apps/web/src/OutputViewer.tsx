import { useEffect, useState } from "react";

/**
 * S1 E1.5 — Output Plane viewer. Polls `/runs/{runId}/output` and shows the
 * captured subprocess stdout bytes. The backend returns either:
 *   - 200 + JSON: chunks of stdout bytes
 *   - 202 + {code: "OUTPUT_REFUSED", ...}: SDK not certified, refuse
 *   - 202 + {code: "OUTPUT_UNKNOWN_STREAM", ...}: run not found / no bytes yet
 *
 * Per the Studio contract (R-S0-F), 202 is not a hard error — it's an
 * honest "we don't have bytes yet" or "Output Plane not yet certified".
 * The component renders 202s in a neutral tone, not red.
 */
export interface OutputViewerProps {
  runId: string;
  baseUrl?: string;
}

export function OutputViewer(props: OutputViewerProps) {
  const { runId, baseUrl = "http://127.0.0.1:7777" } = props;
  const [text, setText] = useState<string>("");
  const [status, setStatus] = useState<"loading" | "ok" | "refused" | "unknown" | "error">("loading");
  const [code, setCode] = useState<string | null>(null);
  const [message, setMessage] = useState<string | null>(null);

  useEffect(() => {
    if (!runId) return;
    const ac = new AbortController();
    void (async () => {
      try {
        const r = await fetch(`${baseUrl}/runs/${encodeURIComponent(runId)}/output`, {
          signal: ac.signal,
        });
        const body = (await r.json().catch(() => ({}))) as { code?: string; message?: string; chunks?: string[] };
        if (r.status === 200) {
          setStatus("ok");
          setText((body.chunks ?? []).join(""));
          setCode(null);
          setMessage(null);
        } else if (r.status === 202) {
          setStatus(body.code === "OUTPUT_REFUSED" ? "refused" : "unknown");
          setCode(body.code ?? null);
          setMessage(body.message ?? null);
        } else {
          setStatus("error");
          setMessage(`HTTP ${r.status}: ${JSON.stringify(body)}`);
        }
      } catch (e) {
        if ((e as { name?: string }).name !== "AbortError") {
          setStatus("error");
          setMessage(String(e));
        }
      }
    })();
    return () => ac.abort();
  }, [runId, baseUrl]);

  return (
    <section
      role="region"
      aria-labelledby="output-title"
      style={{
        border: "1px solid #ddd",
        padding: "1rem",
        marginTop: "1rem",
        borderRadius: "8px",
      }}
    >
      <h2 id="output-title">Run output</h2>
      <p style={{ fontSize: "0.85em", color: "#666" }}>
        status: <strong>{status}</strong>
        {code && <span style={{ marginLeft: "0.5rem" }}>code: <code>{code}</code></span>}
        {message && <span style={{ marginLeft: "0.5rem" }}>— {message}</span>}
      </p>
      {status === "ok" ? (
        <pre
          aria-label={`Stdout for run ${runId}`}
          style={{
            maxHeight: 240,
            overflowY: "auto",
            fontSize: "0.85em",
            background: "#f8f8f8",
            padding: "0.5rem",
            borderRadius: 4,
          }}
        >
          {text || "(empty)"}
        </pre>
      ) : status === "refused" ? (
        <p style={{ color: "#666" }}>
          Output Plane not yet certified. The Studio's read-side Output port
          is honest about that — it never returns a fake success.
        </p>
      ) : status === "unknown" ? (
        <p style={{ color: "#666" }}>No bytes for this run yet.</p>
      ) : null}
    </section>
  );
}
