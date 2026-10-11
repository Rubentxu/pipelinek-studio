import { useCallback, useEffect, useRef, useState } from "react";

/**
 * R1.1 (2026-10-11) — Event Plane stream consumer. Connects to the SSE
 * endpoint at `/runs/{runId}/stream` and renders each event in an
 * accessible list.
 *
 * Behaviour updates over the S1 baseline:
 *
 *  - **Named event subscription** (R1.1.a): the backend emits
 *    `event: <kind>` per event (e.g. StepStarted, StepFinished). We add a
 *    listener per known kind — and a catch-all listener for unknown
 *    kinds — so the kind reaches the UI instead of being masked inside
 *    `data`.
 *  - **Last-Event-ID re-send on reconnect** (R1.1.b): when the browser's
 *    EventSource auto-reconnect fires (readyState=0 after a drop), we
 *    close the current source and re-open with `?after=<lastSequence>`
 *    so we resume from the backend's authoritative sequence cursor.
 *    The backend also honours the standard `Last-Event-ID` header; we
 *    use the query param because it is invariant across browsers and
 *    survives proxy rewrites.
 *  - **Dedup by sequence** (R1.1.c): each `data` envelope carries a
 *    `sequence` integer (the same `id:` field sent on the SSE wire).
 *    We keep a `seenSequences: Set<number>` and drop any envelope whose
 *    sequence we already rendered. The backend can re-emit the head
 *    event on a cursor transition; the UI must not double-render.
 *  - **End/resume event handling**: the backend emits
 *    `event: "resume" data: {"code":"SSE_RESUMED","after":N}` after a
 *    resume, and `event: "end" data: {"code":"SSE_IDLE_CLOSE"|"SSE_MAX_ITERATIONS_CLOSE"|"SSE_TIMEOUT"|...}`
 *    on stream termination. We surface the resume code as a status hint
 *    and the end code as the reason for closing.
 *
 * The reconnect behaviour is front-end managed (we close + open the
 * source ourselves); the backend never silently starts from the head
 * once we've published a `lastSequence`.
 */
export interface RunEvent {
  sequence: number;
  kind: string;
  occurredAt: string;
  payload?: string;
}

export interface EventStreamProps {
  runId: string;
  baseUrl?: string;
  maxEvents?: number;
  /** Test seam: inject a polyfill / stub for `EventSource`. Defaults to
   *  the global `EventSource`. */
  eventSourceCtor?: typeof EventSource;
}

interface EventSourceLikeMessage {
  data: string;
  lastEventId?: string;
}

interface EventSourceLike {
  url: string;
  onopen: (() => void) | null;
  onerror: (() => void) | null;
  onmessage: ((e: MessageEvent) => void) | null;
  readyState: number;
  addEventListener(name: string, cb: (e: MessageEvent) => void): void;
  close(): void;
}

export function EventStream(props: EventStreamProps) {
  const {
    runId,
    baseUrl,
    maxEvents = 200,
    eventSourceCtor = typeof EventSource === "undefined" ? undefined : EventSource,
  } = props;

  const [events, setEvents] = useState<RunEvent[]>([]);
  const [status, setStatus] = useState<
    "connecting" | "open" | "reconnecting" | "closed" | "error"
  >("connecting");
  const [error, setError] = useState<string | null>(null);
  const [resumeCode, setResumeCode] = useState<string | null>(null);
  const [endCode, setEndCode] = useState<string | null>(null);
  const [lastSequence, setLastSequence] = useState<number | null>(null);

  // Mutable refs survive re-renders. We persist `seen` and `lastSequence`
  // here so they don't depend on stale closures.
  const seenRef = useRef<Set<number>>(new Set());
  const lastSeqRef = useRef<number | null>(null);
  // Counter incremented on every (re)open; the effect tears down the
  // stream when its generation is superseded.
  const generationRef = useRef(0);

  const open = useCallback(
    (after: number | null) => {
      if (!eventSourceCtor) return; // test env without EventSource + no polyfill
      const qp = after == null ? "" : `?after=${after}`;
      const url = `${baseUrl}/runs/${encodeURIComponent(runId)}/stream${qp}`;
      const src = new eventSourceCtor(url) as EventSourceLike;
      const gen = generationRef.current;
      // R1.1.d: after the backend emits `event: end` we mark the
      // generation as terminally closed. Subsequent envelope events on
      // this source (which a FakeEventSource can still emit under
      // test) are dropped — the production browser closes the source
      // and never fires again.
      let serverClosed = false;
      let readyState = (src as unknown as { readyState: number }).readyState ?? 0;

      // R1.1.a: subscribe to named events. The backend emits
      // `event:<kind>` with each data envelope; this listener fires
      // per kind. The catch-all is `message` (the default SSE event),
      // which we still listen for as a safety net for any kind we did
      // not pre-register.
      const handleEnvelope = (raw: string) => {
        if (serverClosed) return; // drop late fires after `event: end`
        // R1.1.c: parse defensively. The backend JSON-encodes the data
        // via kotlinx.serialization, so the body is well-formed.
        let parsed: RunEvent;
        try {
          parsed = JSON.parse(raw) as RunEvent;
        } catch {
          return;
        }
        if (typeof parsed.sequence !== "number") return;
        // Dedup — drop re-emitted sequences.
        if (seenRef.current.has(parsed.sequence)) return;
        seenRef.current.add(parsed.sequence);
        lastSeqRef.current = parsed.sequence;
        setLastSequence(parsed.sequence);
        setEvents((prev) => {
          const next = [...prev, parsed];
          if (next.length > maxEvents) next.splice(0, next.length - maxEvents);
          return next;
        });
      };
      // Per-kind listeners (R1.1.a). The kind names are the SSE event names
      // emitted by studio-http. Unknown kinds fall through to `message`.
      const commonKinds = [
        "StepStarted",
        "StepFinished",
        "StepFailed",
        "StepCancelled",
        "StepRecovered",
        "JobScheduled",
        "JobStarted",
        "JobFinished",
        "SnapshotSealed",
        "ResumeStarted",
        "PauseRequested",
      ];
      const add = (name: string, cb: (e: MessageEvent) => void) => {
        src.addEventListener(name, cb);
      };
      for (const k of commonKinds) {
        add(k, (e) => handleEnvelope(e.data));
      }
      // `event: "resume"` from the backend — start-of-stream with state
      // transfer. Surface the code so the operator can see the stream
      // resumed from a non-head position.
      add("resume", (e) => {
        let code = "SSE_RESUMED";
        try {
          const body = JSON.parse(e.data) as { code?: string; after?: number };
          if (body.code) code = body.code;
        } catch {
          /* keep default */
        }
        setResumeCode(code);
      });
      // `event: "end"` from the backend — server-side close. We must
      // also close the client source so the browser doesn't auto-retry.
      add("end", (e) => {
        serverClosed = true;
        let code = "SSE_CLOSED";
        try {
          const body = JSON.parse(e.data) as { code?: string };
          if (body.code) code = body.code;
        } catch {
          /* keep default */
        }
        setEndCode(code);
        setStatus("closed");
        // Tear down the source so the browser doesn't auto-reconnect
        // after a server-side close.
        (src as unknown as { close: () => void }).close();
      });

      // Catch-all for the generic event type and unknown kinds.
      src.onmessage = (e: MessageEvent) => handleEnvelope(e.data as string);

      src.onopen = () => {
        // Guard against late-firing events from a stale generation.
        if (generationRef.current !== gen) {
          src.close();
          return;
        }
        setStatus("open");
      };
      src.onerror = () => {
        readyState = src.readyState ?? 0;
        // Discriminate readyState: CONNECTING (browser will retry) vs
        // CLOSED (browser has given up).
        if (readyState === 0) {
          // The browser will auto-reconnect; we close this source and
          // explicitly re-open with ?after=<lastSeq> so we resume from
          // where we left off (R1.1.b). We DO NOT rely on the browser
          // to send Last-Event-ID — it's inconsistent across browsers.
          if (gen !== generationRef.current) return;
          src.close();
          generationRef.current += 1;
          setStatus("reconnecting");
          // Schedule the reopen with the last sequence we saw; if it's
          // null, we fall back to the head (the very first connect).
          open(lastSeqRef.current);
          return;
        }
        // readyState=2: terminal. We stop retrying.
        if (gen !== generationRef.current) return;
        src.close();
        setStatus("closed");
      };
    },
    [baseUrl, runId, maxEvents, eventSourceCtor],
  );

  useEffect(() => {
    if (!runId) return;
    // Reset per-run state.
    seenRef.current = new Set();
    lastSeqRef.current = null;
    setEvents([]);
    setResumeCode(null);
    setEndCode(null);
    setLastSequence(null);
    setStatus("connecting");
    setError(null);

    const myGen = ++generationRef.current;
    void myGen;
    open(null);
    // We intentionally do NOT include `open` in the deps; the effect
    // tears down the stream on `runId` change by bumping `generationRef`
    // and reopening. The functions and refs are stable.
    return () => {
      generationRef.current += 1; // invalidates any late callbacks
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [runId, baseUrl]);

  return (
    <section
      role="region"
      aria-labelledby="event-stream-title"
      data-testid="event-stream"
      style={{
        border: "1px solid #ddd",
        padding: "1rem",
        marginTop: "1rem",
        borderRadius: "8px",
      }}
    >
      <h2 id="event-stream-title">Run events</h2>
      <p style={{ fontSize: "0.85em", color: "#666" }}>
        status: <strong>{status}</strong>
        {lastSequence != null && (
          <span style={{ marginLeft: "0.5rem" }}>
            last sequence: <code data-testid="last-sequence">{lastSequence}</code>
          </span>
        )}
        {resumeCode && (
          <span
            data-testid="resume-code"
            style={{ marginLeft: "0.5rem", color: "#0a6d0a" }}
          >
            resume: <code>{resumeCode}</code>
          </span>
        )}
        {endCode && (
          <span
            data-testid="end-code"
            style={{ marginLeft: "0.5rem", color: "#666" }}
          >
            end: <code>{endCode}</code>
          </span>
        )}
        {error && <span style={{ marginLeft: "0.5rem", color: "#900" }}>— {error}</span>}
      </p>
      <ul
        data-testid="event-list"
        role="log"
        aria-live="polite"
        style={{
          listStyle: "none",
          padding: 0,
          fontSize: "0.85em",
          maxHeight: 240,
          overflowY: "auto",
        }}
      >
        {events.map((ev) => (
          <li
            key={ev.sequence}
            data-testid={`event-${ev.sequence}`}
            style={{ padding: "0.25rem 0", borderBottom: "1px solid #eee" }}
          >
            <code>#{ev.sequence}</code> <strong>{ev.kind}</strong>{" "}
            <span style={{ color: "#888" }}>{ev.occurredAt}</span>
            {ev.payload && (
              <pre
                style={{
                  margin: "0.25rem 0 0",
                  padding: "0.25rem",
                  background: "#f8f8f8",
                  borderRadius: 4,
                  whiteSpace: "pre-wrap",
                }}
              >
                {ev.payload}
              </pre>
            )}
          </li>
        ))}
      </ul>
    </section>
  );
}
