import { useEffect, useState } from "react";
import { fetchWithTimeout } from "./lib/api";

/**
 * S1 diagnostics viewer. Polls `/runs/{runId}/diagnostics` and renders
 * the typed Diagnostic[] shape. Each diagnostic is a flat object with
 * `severity`, `phase`, `message`, `source`, `id`, `run_id` (see
 * docs/02-specifications/HTTP_AND_STREAMING_API.md § Diagnostics).
 *
 * Per the contract, 200 returns the array, 202 returns
 * DIAGNOSTICS_UNAVAILABLE — the component distinguishes the two and
 * never fabricates diagnostics. Empty array is a valid 200 (clean run).
 */
export interface DiagnosticDto {
  id: string;
  severity: string;
  phase: string;
  message: string;
  source: string;
  run_id: string;
  // S1 source-location diagnostic: present when the SDK's
  // CompilationFinished payload carried the structured diagnostic entry.
  // The backend expands those into per-diagnostic lines so the SPA
  // can render line/column/path. Both fields are optional in the wire
  // contract for back-compat with the S0 synthetic placeholders.
  line?: number;
  column?: number;
  path?: string;
}

export interface DiagnosticsViewerProps {
  runId: string;
  baseUrl?: string;
  pollMs?: number;
  /**
   * Current run state from the parent. When the run reaches a
   * terminal state (SUCCEEDED / FAILED / CANCELLED), the
   * diagnostics list is stable, so we stop polling. Without this
   * prop the previous implementation polled forever, sending a
   * /diagnostics request every pollMs for the lifetime of the
   * page.
   */
  runState?: string;
}

const TERMINAL_STATES = new Set(["SUCCEEDED", "FAILED", "CANCELLED"]);

export function DiagnosticsViewer(props: DiagnosticsViewerProps) {
  const { runId, baseUrl, pollMs = 3000, runState } = props;
  const [items, setItems] = useState<DiagnosticDto[]>([]);
  const [status, setStatus] = useState<"loading" | "ok" | "unavailable" | "error">("loading");
  const [message, setMessage] = useState<string | null>(null);

  useEffect(() => {
    if (!runId) return;
    const ac = new AbortController();
    let active = true;
    const fetchOnce = async () => {
      try {
        const r = await fetchWithTimeout(`${baseUrl}/runs/${encodeURIComponent(runId)}/diagnostics`, {
          signal: ac.signal,
        });
        const body = (await r.json().catch(() => ({}))) as {
          diagnostics?: DiagnosticDto[];
          code?: string;
          message?: string;
        };
        if (!active) return;
        if (r.status === 200) {
          setStatus("ok");
          setItems(body.diagnostics ?? []);
          setMessage(null);
        } else if (r.status === 202) {
          setStatus("unavailable");
          setItems([]);
          setMessage(body.message ?? null);
        } else {
          setStatus("error");
          setMessage(`HTTP ${r.status}`);
        }
      } catch (e) {
        if (!active) return;
        if ((e as { name?: string }).name !== "AbortError") {
          setStatus("error");
          setMessage(String(e));
        }
      }
    };
    void fetchOnce();
    // Once the run is terminal the diagnostics list is stable,
    // so polling forever would just be wasted requests. We do
    // one last fetch (the line above) and then stop.
    if (runState && TERMINAL_STATES.has(runState)) {
      return () => {
        active = false;
        ac.abort();
      };
    }
    const t = setInterval(fetchOnce, pollMs);
    return () => {
      active = false;
      ac.abort();
      clearInterval(t);
    };
  }, [runId, baseUrl, pollMs, runState]);

  return (
    <section
      role="region"
      aria-labelledby="diagnostics-title"
      data-testid="diagnostics-viewer"
      style={{
        border: "1px solid #ddd",
        padding: "1rem",
        marginTop: "1rem",
        borderRadius: "8px",
      }}
    >
      <h2 id="diagnostics-title">Diagnostics</h2>
      <p style={{ fontSize: "0.85em", color: "#666" }}>
        status: <strong>{status}</strong>
        {message && <span style={{ marginLeft: "0.5rem" }}>— {message}</span>}
      </p>
      {status === "ok" && items.length === 0 && (
        <p style={{ color: "#0a0" }}>No diagnostics — clean run.</p>
      )}
      {status === "ok" && items.length > 0 && (
        <ul aria-label={`Diagnostics for run ${runId}`} style={{ listStyle: "none", padding: 0 }}>
          {items.map((d) => (
            <li
              key={d.id}
              data-severity={d.severity}
              style={{
                fontFamily: "monospace",
                fontSize: "0.85em",
                padding: "0.25rem 0.5rem",
                borderLeft: `3px solid ${
                  d.severity === "ERROR"
                    ? "crimson"
                    : d.severity === "WARNING"
                      ? "orange"
                      : "#888"
                }`,
                marginBottom: "0.25rem",
                background: "#fafafa",
              }}
            >
              <strong style={{ color: d.severity === "ERROR" ? "crimson" : undefined }}>
                [{d.severity}]
              </strong>{" "}
              <em style={{ color: "#666" }}>{d.phase}</em>{" "}
              <span>{d.message}</span>{" "}
              {(d.line !== undefined || d.column !== undefined || d.path) && (
                <span style={{ color: "#666", fontSize: "0.9em" }}>
                  {" "}
                  @{" "}
                  <code>
                    {d.path ?? "?"}
                    {d.line !== undefined && `:${d.line}`}
                    {d.column !== undefined && `:${d.column}`}
                  </code>
                </span>
              )}{" "}
              <code style={{ fontSize: "0.8em", color: "#888" }}>· {d.source}</code>
            </li>
          ))}
        </ul>
      )}
    </section>
  );
}
