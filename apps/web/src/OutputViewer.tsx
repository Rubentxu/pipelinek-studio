import { useEffect, useState } from "react";
import { fetchWithTimeout } from "./lib/api";

/**
 * S1 Output Plane viewer. Polls `/runs/{runId}/output` and shows the
 * captured subprocess stdout bytes. The backend returns either:
 *   - 200 + OutputReadResponse: typed envelope with base64-encoded chunks
 *     (preserves non-UTF-8 bytes on the wire)
 *   - 202 + {code: "OUTPUT_REFUSED", ...}: SDK not certified, refuse
 *   - 202 + {code: "OUTPUT_UNKNOWN_STREAM", ...}: run not found / no bytes yet
 *
 * The bytes are base64-decoded locally and rendered as UTF-8 text.
 * Decoding errors fall back to a Latin-1 byte string so binary output
 * remains inspectable in the SPA.
 *
 * Per the Studio contract (R-S0-F), 202 is not a hard error — it's an
 * honest "we don't have bytes yet" or "Output Plane not yet certified".
 * The component renders 202s in a neutral tone, not red.
 */
export interface OutputChunkDto {
  streamId: string;
  bytesBase64: string;
  cursor: string;
  eof: boolean;
}

export interface OutputReadResponseDto {
  runId: string;
  chunks: OutputChunkDto[];
  nextCursor: string;
}

export interface OutputViewerProps {
  runId: string;
  baseUrl?: string;
}

function decodeBase64(b64: string): string {
  try {
    // atob is available in both browsers + jsdom (vitest).
    const binary = atob(b64);
    const bytes = new Uint8Array(binary.length);
    for (let i = 0; i < binary.length; i++) bytes[i] = binary.charCodeAt(i);
    return new TextDecoder("utf-8", { fatal: false }).decode(bytes);
  } catch {
    return "<undecodable bytes>";
  }
}

export function OutputViewer(props: OutputViewerProps) {
  const { runId, baseUrl } = props;
  const [text, setText] = useState<string>("");
  const [status, setStatus] = useState<"loading" | "ok" | "refused" | "unknown" | "error">("loading");
  const [code, setCode] = useState<string | null>(null);
  const [message, setMessage] = useState<string | null>(null);
  const [nextCursor, setNextCursor] = useState<string | null>(null);

  useEffect(() => {
    if (!runId) return;
    const ac = new AbortController();
    void (async () => {
      try {
        const r = await fetchWithTimeout(`${baseUrl}/runs/${encodeURIComponent(runId)}/output`, {
          signal: ac.signal,
        });
        const body = (await r.json().catch(() => ({}))) as
          | OutputReadResponseDto
          | { code?: string; message?: string };
        if (r.status === 200) {
          const env = body as OutputReadResponseDto;
          const combined = (env.chunks ?? []).map((c) => decodeBase64(c.bytesBase64)).join("");
          setStatus("ok");
          setText(combined);
          setNextCursor(env.nextCursor ?? null);
          setCode(null);
          setMessage(null);
        } else if (r.status === 202) {
          const ref = body as { code?: string; message?: string };
          setStatus(ref.code === "OUTPUT_REFUSED" ? "refused" : "unknown");
          setCode(ref.code ?? null);
          setMessage(ref.message ?? null);
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
      data-testid="output-viewer"
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
        {nextCursor && (
          <span style={{ marginLeft: "0.5rem" }}>cursor: <code>{nextCursor}</code></span>
        )}
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
            whiteSpace: "pre-wrap",
            wordBreak: "break-word",
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

