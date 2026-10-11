import { useCallback, useEffect, useRef, useState } from "react";
import { fetchWithTimeout } from "./lib/api";

/**
 * R1.2 (2026-10-11) — Output Plane viewer.
 *
 * Behaviour updates over the S1 baseline:
 *
 *  - **Polling loop with `?after=<cursor>`** (R1.2.a). After the first
 *    fetch, we keep polling with the wire cursor the backend returned
 *    in `nextCursor`, until every known streamId has reported EOF or
 *    the backend refused (202). The poll cadence is 250 ms; it
 *    yields to the page render loop.
 *  - **Memory bound** (R1.2.b). We accumulate raw bytes per streamId
 *    but cap each at [MAX_BYTES_PER_STREAM] (default 256 KiB). When a
 *    streamId reaches the cap, the OLDER half is discarded and the
 *    UI shows a "discarded: N bytes" pill so the operator knows the
 *    full output is larger than what is on screen.
 *  - **Stream separation** (R1.2.c). The wire envelope carries
 *    `streamId` per chunk ("stdout" / "out" / "stderr" / etc.). We
 *    bucket chunks by streamId and render them in two `<pre>`s —
 *    stdout (default color) and stderr (red). Any other streamId falls
 *    through to stdout to preserve backward compat with the S1 tests
 *    that used `streamId: "out"`.
 *
 * Audit F-P1-WEB-4 (still honoured): per-chunk base64 decode,
 * concatenate raw bytes, decode the combined byte array as UTF-8 once
 * so multi-byte sequences that straddle a chunk boundary are not
 * corrupted.
 *
 * The components/pages that mount this with a still-viva run benefit
 * from the poll loop; mounting with a finalized run converges in one
 * poll cycle.
 *
 * Per the Studio contract (R-S0-F), 202 is not a hard error — it's an
 * honest "we don't have bytes yet" or "Output Plane not yet
 * certified". The component renders 202s in a neutral tone, not red.
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
  /** Memory bound per stream. Defaults to 256 KiB. */
  maxBytesPerStream?: number;
  /** Test seam: replace the poll function with a deterministic stub. */
  fetchImpl?: (url: string) => Promise<Response>;
  /** Test seam: replace the scheduling of follow-up polls. */
  schedulePoll?: (cb: () => void) => number | NodeJS.Timeout;
  /** Test seam: cancel a scheduled poll. */
  cancelPoll?: (token: number | NodeJS.Timeout) => void;
}

/**
 * The hard cap for in-memory output bytes per streamId. 256 KiB is
 * large enough for tens of thousands of lines of text and small
 * enough not to crash the DOM. Override per-instance via the
 * `maxBytesPerStream` prop (used by tests).
 */
export const DEFAULT_MAX_BYTES_PER_STREAM = 256 * 1024;

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

/**
 * Concatenate base64 chunks and decode the full byte stream as
 * UTF-8. Replaces the previous per-chunk decode + join.
 */
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

/**
 * Push a NEW chunk's bytes onto an existing buffer with a hard
 * memory cap. The first `maxBytes` are preserved; the older half is
 * discarded and the discarded count is returned so the UI can surface
 * a "discarded: N bytes" pill.
 *
 * Algorithm: when total > cap, drop the head — `Math.floor(over / 2)`
 * bytes — so successive calls shed recent growth instead of one huge
 * resize. Each call reports the accumulated `discarded` total.
 */
export function appendBytesWithCap(
  existing: Uint8Array,
  next: Uint8Array,
  cap: number,
  previousDiscarded: number,
): { buffer: Uint8Array; discarded: number } {
  if (cap <= 0) {
    return { buffer: new Uint8Array(0), discarded: previousDiscarded + next.length };
  }
  const combined = new Uint8Array(existing.length + next.length);
  combined.set(existing, 0);
  combined.set(next, existing.length);
  if (combined.length <= cap) {
    return { buffer: combined, discarded: previousDiscarded };
  }
  const over = combined.length - cap;
  // Drop at least the overflow + half the cap to amortize reslicing.
  const drop = Math.max(over, Math.floor(cap / 2));
  const head = combined.slice(drop);
  return {
    buffer: head,
    discarded: previousDiscarded + (combined.length - head.length),
  };
}

/** Pick `eof` for the bytes already in the buffer (we had no EOF before). */
function eofFor(streamId: string, byStream: Map<string, boolean>, nextEof: boolean): boolean {
  if (nextEof) return true;
  return byStream.get(streamId) === true;
}

/**
 * Test seam: schedule a follow-up poll after `delayMs` ms. Real impl
 * uses `setTimeout`. Tests pass a deterministic version.
 */
const defaultSchedulePoll = (cb: () => void, delayMs: number) =>
  setTimeout(cb, delayMs);

/** Default poll cadence for the live output tail. */
export const DEFAULT_POLL_INTERVAL_MS = 250;

export function OutputViewer(props: OutputViewerProps) {
  const {
    runId,
    baseUrl,
    maxBytesPerStream = DEFAULT_MAX_BYTES_PER_STREAM,
    fetchImpl = (url: string) => fetchWithTimeout(url, {}),
    schedulePoll = (cb: () => void) => defaultSchedulePoll(cb, DEFAULT_POLL_INTERVAL_MS),
    cancelPoll = (token: number | NodeJS.Timeout) => clearTimeout(token as unknown as ReturnType<typeof setTimeout>),
  } = props;
  const [streams, setStreams] = useState<Map<string, Uint8Array>>(new Map());
  const [eofStreams, setEofStreams] = useState<Set<string>>(new Set());
  const [discarded, setDiscarded] = useState<number>(0);
  const [status, setStatus] = useState<"loading" | "ok" | "refused" | "unknown" | "error">(
    "loading",
  );
  const [code, setCode] = useState<string | null>(null);
  const [message, setMessage] = useState<string | null>(null);
  const [polls, setPolls] = useState<number>(0);
  const [doneAtPoll, setDoneAtPoll] = useState<number | null>(null);

  // Refs survive re-renders without triggering them.
  const cursorRef = useRef<{ stdout: string | null; stderr: string | null }>({
    stdout: null,
    stderr: null,
  });
  const streamsRef = useRef<Map<string, Uint8Array>>(new Map());
  const eofRef = useRef<Set<string>>(new Set());
  const discardedRef = useRef<number>(0);
  const streamIdsRef = useRef<Set<string>>(new Set());
  const acRef = useRef<AbortController | null>(null);
  const pollTokenRef = useRef<number | NodeJS.Timeout | null>(null);
  const pollingRef = useRef<boolean>(false);

  const reset = useCallback(() => {
    acRef.current?.abort();
    acRef.current = null;
    if (pollTokenRef.current != null) {
      cancelPoll(pollTokenRef.current);
      pollTokenRef.current = null;
    }
    cursorRef.current = { stdout: null, stderr: null };
    streamsRef.current = new Map();
    eofRef.current = new Set();
    discardedRef.current = 0;
    streamIdsRef.current = new Set();
    pollingRef.current = false;
    setStreams(new Map());
    setEofStreams(new Set());
    setDiscarded(0);
    setStatus("loading");
    setCode(null);
    setMessage(null);
    setPolls(0);
    setDoneAtPoll(null);
  }, [cancelPoll]);

  const pollOnce = useCallback(async (): Promise<{ stop: boolean; refusedCode?: string }> => {
    if (!runId) return { stop: true };
    const ac = acRef.current ?? new AbortController();
    acRef.current = ac;
    if (pollingRef.current) return { stop: true };
    pollingRef.current = true;

    // Build ?after=<stdout cursor> for now; the wire shape is the same
    // for any streamId. Backend today hardcodes the cursor's streamId
    // to "stdout" in the request (studio-http StudioHttpModule.kt:879),
    // so polling with the stdout cursor covers everything the backend
    // will currently return. When the backend lifts the hardcode, the
    // query param shape already matches.
    const after = cursorRef.current.stdout;
    const qs = after == null ? "" : `?after=${encodeURIComponent(after)}`;
    const url = `${baseUrl}/runs/${encodeURIComponent(runId)}/output${qs}`;
    let polled = 0;
    let queued = false;
    try {
      polled = polls + 1;
      setPolls(polled);
      const r = await fetchImpl(url);
      const body = (await r.json().catch(() => ({}))) as
        | OutputReadResponseDto
        | { code?: string; message?: string };
      if (r.status === 200) {
        const env = body as OutputReadResponseDto;
        const chunks = env.chunks ?? [];
        if (chunks.length > 0) {
          // Aggregate the chunks by streamId. The wire can carry
          // multiple chunks per streamId in one response.
          for (const c of chunks) {
            const bytes = decodeBase64ToBytes(c.bytesBase64);
            if (bytes.length === 0) continue;
            streamIdsRef.current.add(c.streamId);
            const existing =
              streamsRef.current.get(c.streamId) ?? new Uint8Array(0);
            const { buffer, discarded: newDiscarded } = appendBytesWithCap(
              existing,
              bytes,
              maxBytesPerStream,
              discardedRef.current,
            );
            streamsRef.current.set(c.streamId, buffer);
            discardedRef.current = newDiscarded;
            // EOF is per-streamId; once seen, do not extend further.
            if (c.eof) eofRef.current.add(c.streamId);
          }
          // Cursor: pick the highest cursor across returned chunks of
          // this streamId. The wire rule: cursor is stable + monotonic.
          for (const c of chunks) {
            const cur =
              c.streamId === "stderr"
                ? cursorRef.current.stderr
                : cursorRef.current.stdout;
            const next = c.cursor;
            if (next == null) continue;
            if (cur == null || Number(next) > Number(cur)) {
              if (c.streamId === "stderr") {
                cursorRef.current.stderr = next;
              } else {
                cursorRef.current.stdout = next;
              }
            }
          }
          // Sync to React state.
          setStreams(new Map(streamsRef.current));
          setEofStreams(new Set(eofRef.current));
          setDiscarded(discardedRef.current);
        }
        queued = true;
        // Stop polling when every known streamId has EOF. We also
        // stop when the run returned no new chunks AND the cursor
        // didn't advance (an idle cycle that no longer needs our
        // attention).
        const allKnown =
          streamIdsRef.current.size === 0 ||
          Array.from(streamIdsRef.current).every((id) => eofRef.current.has(id));
        const cursorAdvanced =
          (env.nextCursor ?? null) != cursorRef.current.stdout;
        if (allKnown && !cursorAdvanced) {
          setStatus("ok");
          if (doneAtPoll == null) setDoneAtPoll(polled);
          return { stop: true };
        }
        return { stop: false };
      }
      if (r.status === 202) {
        const ref = body as { code?: string; message?: string };
        setStatus(ref.code === "OUTPUT_REFUSED" ? "refused" : "unknown");
        setCode(ref.code ?? null);
        setMessage(ref.message ?? null);
        if (ref.code === "OUTPUT_REFUSED") return { stop: true, refusedCode: ref.code };
        return { stop: true };
      }
      setStatus("error");
      setMessage(`HTTP ${r.status}: ${JSON.stringify(body)}`);
      return { stop: true };
    } catch (e) {
      if ((e as { name?: string }).name !== "AbortError") {
        setStatus("error");
        setMessage(String(e));
        return { stop: true };
      }
      return { stop: true };
    } finally {
      pollingRef.current = false;
      // If we know we should keep polling but this fetch yielded no
      // new chunks and no error, schedule the next one.
      if (queued && !ac.signal.aborted) {
        const token = schedulePoll(() => {
          pollTokenRef.current = null;
          void pollOnce().then((r) => {
            if (r.stop && pollTokenRef.current == null) return;
          });
        });
        pollTokenRef.current = token;
      }
    }
  }, [
    baseUrl,
    runId,
    fetchImpl,
    schedulePoll,
    cancelPoll,
    polls,
    doneAtPoll,
    maxBytesPerStream,
  ]);

  useEffect(() => {
    if (!runId) return;
    reset();
    void pollOnce();
    return () => {
      acRef.current?.abort();
      if (pollTokenRef.current != null) {
        cancelPoll(pollTokenRef.current);
        pollTokenRef.current = null;
      }
    };
    // We intentionally rely on `reset` + `pollOnce`; `pollOnce` is
    // stable per (runId, baseUrl). Tests pass mocks for stability.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [runId, baseUrl]);

  // Render: aggregate each known streamId into a `<pre>` group. stdout
  // is the default rendering; stderr is shown distinctly.
  const renderStreamId = (id: string) => id.toLowerCase();
  const streamEntries = Array.from(streamIdsRef.current).sort();
  // Lazy decode for display — we cache the decoded text in a memoized
  // helper. Reading `streams` from state (which we set when chunks
  // arrive) keeps the recompute tied to React's reconciliation.
  const streamViews = (() => {
    const out = new Map<string, { text: string; discarded: number }>();
    for (const [id, bytes] of streams) {
      out.set(id, {
        text: bytes.length === 0 ? "" : new TextDecoder("utf-8", { fatal: false }).decode(bytes),
        discarded: discarded,
      });
    }
    return out;
  })();

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
        <span style={{ marginLeft: "0.5rem" }}>
          polls: <code data-testid="poll-count">{polls}</code>
        </span>
        {code && <span style={{ marginLeft: "0.5rem" }}>code: <code>{code}</code></span>}
        {message && <span style={{ marginLeft: "0.5rem" }}>— {message}</span>}
        {discarded > 0 && (
          <span
            data-testid="discarded-bytes"
            style={{ marginLeft: "0.5rem", color: "#a05a00" }}
          >
            discarded: <code>{discarded}</code> bytes (over the {maxBytesPerStream}-byte window)
          </span>
        )}
        {doneAtPoll != null && (
          <span style={{ marginLeft: "0.5rem", color: "#0a6d0a" }}>
            converged after {doneAtPoll} polls
          </span>
        )}
      </p>
      {status === "ok" || status === "loading" ? (
        <div data-testid="streams" role="group" aria-label="Output streams">
          {streamEntries.length === 0 ? (
            <pre
              data-stream-id="stdout"
              aria-label="Stdout for run (no bytes yet)"
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
              (empty)
            </pre>
          ) : (
            streamEntries.map((id) => {
              const view = streamViews.get(id) ?? { text: "", discarded: 0 };
              const isStderr = renderStreamId(id) === "stderr";
              return (
                <pre
                  key={id}
                  data-testid={`stream-${id}`}
                  data-stream-id={id}
                  aria-label={isStderr ? `Stderr for run ${runId}` : `Stdout for run ${runId}`}
                  style={{
                    maxHeight: 240,
                    overflowY: "auto",
                    fontSize: "0.85em",
                    background: isStderr ? "#fff0f0" : "#f8f8f8",
                    color: isStderr ? "#a00000" : undefined,
                    padding: "0.5rem",
                    borderRadius: 4,
                    whiteSpace: "pre-wrap",
                    wordBreak: "break-word",
                  }}
                >
                  {view.text || "(empty)"}
                </pre>
              );
            })
          )}
        </div>
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
