import { useEffect, useState } from "react";
import { fetchWithTimeout } from "./lib/api";

/**
 * S3 IR Explorer. Renders the typed IR snapshot the SDK produced for a
 * given run: stages + steps + the phases the read-side observed.
 *
 * Per docs/02-specifications/HTTP_AND_STREAMING_API.md §IR Snapshot,
 * the response shape is:
 *   {
 *     snapshot: {
 *       run_id: string,
 *       stages: [{ name, steps: [{ op_id, name, class_name,
 *                                  capabilities, input_schema_present,
 *                                  output_schema_present, phase }] }],
 *       phases_observed: string[]   // subset of ADMITTED, COMPILED, OBSERVED
 *     }
 *   }
 *
 * The component is read-only: the IR is the SDK's view of the compiled
 * pipeline; the SPA renders it as a tree of stages and steps. No
 * mutation, no fake fields.
 */
export interface IrStepDto {
  opId: string;
  name: string;
  className: string;
  capabilities: string[];
  inputSchemaPresent: boolean;
  outputSchemaPresent: boolean;
  phase: "ADMITTED" | "COMPILED" | "OBSERVED" | string;
}

export interface IrStageDto {
  name: string;
  steps: IrStepDto[];
}

export interface IrSnapshotDto {
  runId: string;
  stages: IrStageDto[];
  phasesObserved: string[];
}

export interface IrExplorerProps {
  runId: string;
  baseUrl?: string;
  pollMs?: number;
}

export function IrExplorer(props: IrExplorerProps) {
  const { runId, baseUrl, pollMs = 5000 } = props;
  const [snapshot, setSnapshot] = useState<IrSnapshotDto | null>(null);
  const [status, setStatus] = useState<"loading" | "ok" | "unavailable" | "error">("loading");
  const [message, setMessage] = useState<string | null>(null);

  useEffect(() => {
    if (!runId) return;
    const ac = new AbortController();
    let active = true;
    const fetchOnce = async () => {
      try {
        const r = await fetchWithTimeout(`${baseUrl}/runs/${encodeURIComponent(runId)}/ir`, {
          signal: ac.signal,
        });
        const body = (await r.json().catch(() => ({}))) as
          | { snapshot?: IrSnapshotDto; code?: string; message?: string };
        if (!active) return;
        if (r.status === 200 && body.snapshot) {
          setSnapshot(body.snapshot);
          setStatus("ok");
          setMessage(null);
        } else if (r.status === 202) {
          setSnapshot(null);
          setStatus("unavailable");
          setMessage(body.message ?? null);
        } else {
          setStatus("error");
          setMessage(`HTTP ${r.status}: ${JSON.stringify(body)}`);
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
    const t = setInterval(fetchOnce, pollMs);
    return () => {
      active = false;
      ac.abort();
      clearInterval(t);
    };
  }, [runId, baseUrl, pollMs]);

  return (
    <section
      role="region"
      aria-labelledby="ir-title"
      data-testid="ir-explorer"
      style={{
        border: "1px solid #ddd",
        padding: "1rem",
        marginTop: "1rem",
        borderRadius: "8px",
      }}
    >
      <h2 id="ir-title">IR snapshot</h2>
      <p style={{ fontSize: "0.85em", color: "#666" }}>
        status: <strong>{status}</strong>
        {message && <span style={{ marginLeft: "0.5rem" }}>— {message}</span>}
        {snapshot && (
          <span style={{ marginLeft: "0.5rem" }}>
            phases observed:{" "}
            <code>{snapshot.phasesObserved.join(", ")}</code>
          </span>
        )}
      </p>
      {status === "ok" && snapshot && (
        <div
          aria-label={`IR for run ${runId}`}
          style={{ fontFamily: "monospace", fontSize: "0.85em" }}
        >
          {snapshot.stages.length === 0 ? (
            <p style={{ color: "#888" }}>(no stages)</p>
          ) : (
            snapshot.stages.map((stage) => (
              <div key={stage.name} style={{ marginTop: "0.5rem" }}>
                <h3 style={{ margin: "0.25rem 0", fontSize: "1em" }}>
                  stage: <code>{stage.name}</code> ({stage.steps.length} step{stage.steps.length === 1 ? "" : "s"})
                </h3>
                <ul style={{ listStyle: "none", padding: 0, margin: 0 }}>
                  {stage.steps.map((step) => (
                    <li
                      key={step.opId}
                      data-phase={step.phase}
                      style={{
                        borderLeft: "2px solid #6cf",
                        paddingLeft: "0.5rem",
                        marginBottom: "0.25rem",
                        background: "#fafdff",
                      }}
                    >
                      <strong>{step.name}</strong>{" "}
                      <em style={{ color: "#666" }}>({step.className})</em>{" "}
                      <span
                        style={{
                          fontSize: "0.9em",
                          color: step.phase === "OBSERVED" ? "#0a0" : "#888",
                        }}
                      >
                        [{step.phase}]
                      </span>{" "}
                      <code style={{ fontSize: "0.8em", color: "#888" }}>
                        {step.capabilities.join(", ")}
                      </code>{" "}
                      <span style={{ fontSize: "0.8em", color: "#888" }}>
                        in:{step.inputSchemaPresent ? "✓" : "—"} out:
                        {step.outputSchemaPresent ? "✓" : "—"}
                      </span>
                    </li>
                  ))}
                </ul>
              </div>
            ))
          )}
        </div>
      )}
      {status === "unavailable" && (
        <p style={{ color: "#666" }}>IR snapshot not yet certified for this run.</p>
      )}
    </section>
  );
}
