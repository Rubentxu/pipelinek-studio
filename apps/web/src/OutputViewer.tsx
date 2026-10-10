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
 *
 * Audit F-P1-WEB-4 (2026-10-10): the previous implementation decoded
 * each base64 chunk to a string independently and then joined the
 * strings. A multi-byte UTF-8 sequence (e.g. `é` = 0xC3 0xA9) that
 * straddles a chunk boundary would be split: the first chunk ended
 * in an incomplete byte and decoded to `�`, the second chunk
 * started with a stray continuation byte and decoded to another
 * replacement. The user saw `??` instead of `é`. The fix is
 * straight-line: decode each chunk to its raw bytes, concatenate
 * the byte arrays, then decode the full byte array as UTF-8 once.
 * TextDecoder handles split multi-byte sequences correctly when
 * given the full byte stream.
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

/** Decode a base64 string to its raw bytes. Returns an empty
 *  Uint8Array on input error (caller can decide how to render). */
export function decodeBase64ToBytes(b64: string): Uint8Array {
  try {
    const binary = atob(b64);
    const out = new Uint8Array(binary.length);
    for (let i = 0; i < binary.length; i++) out[i] = binary.charCodeAt(i);
    return out;
  } catch {
    return new Uint8Array(0);
  }
}

/** Concatenate base64 chunks and decode the full byte stream as
 *  UTF-8. Replaces the previous per-chunk decode + join. The
 *  `fatal: false` mode replaces malformed sequences with U+FFFD
 *  so a single bad byte never blanks the whole output. */
export function chunksToText(chunks: ReadonlyArray<OutputChunkDto>): string {
  let totalLen = 0;
  const parts: Uint8Array[] = [];
  for (const c of chunks) {
    const bytes = decodeBase64ToBytes(c.bytesBase64);
    if (bytes.length > 0) {
      parts.push(bytes);
      totalLen += bytes.length;
    }
  }
  if (totalLen === 0) return "";
  const combined = new Uint8Array(totalLen);
  let offset = 0;
  for (const p of parts) {
    combined.set(p, offset);
    offset += p.length;
  }
  return new TextDecoder("utf-8", { fatal: false }).decode(combined);
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
          setStatus("ok");
          setText(chunksToText(env.chunks ?? []));
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

