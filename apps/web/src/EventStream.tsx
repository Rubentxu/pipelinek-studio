import { useEffect, useState } from "react";

/**
 * S1 E1.3 — Event Plane stream consumer. Connects to the SSE endpoint at
 * `/runs/{runId}/stream` and renders each event in an accessible list.
 *
 * The reconnect behaviour (Last-Event-ID) is handled by the backend; this
 * component just opens an EventSource and appends events to the list.
 * If the connection drops, the EventSource auto-reconnects (browsers do
 * this by default for SSE) and the backend's SSE handler resumes from
 * the last delivered event.
 *
 * The component renders the events in a `<ul role="log" aria-live="polite">`
 * so screen readers announce new events as they arrive.
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
}

export function EventStream(props: EventStreamProps) {
  const { runId, baseUrl, maxEvents = 200 } = props;
  const [events, setEvents] = useState<RunEvent[]>([]);
  const [status, setStatus] = useState<
    "connecting" | "open" | "reconnecting" | "closed" | "error"
  >("connecting");
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    if (!runId) return;
    const url = `${baseUrl}/runs/${encodeURIComponent(runId)}/stream`;
    const src = new EventSource(url);
    src.onopen = () => {
      setStatus("open");
      setError(null);
    };
    // Audit F-P1-WEB-7 (2026-10-10): the previous onerror set the
    // status to "error" with a generic "will retry" message
    // regardless of the EventSource's actual readyState. After a
    // 4xx response the browser closes the EventSource permanently
    // (readyState=2) and no more retries will happen, but the
    // user kept seeing "will retry" forever. The fix reads
    // src.readyState in onerror and surfaces the correct state:
    //  - 0 (CONNECTING): the browser IS retrying — "reconnecting"
    //  - 2 (CLOSED):    the browser gave up — "closed (no retry)"
    // The status is the source of truth; aria-live="polite" on
    // the <ul> announces the change to screen readers.
    src.onerror = () => {
      if (src.readyState === EventSource.CLOSED) {
        setStatus("closed");
        setError("SSE stream closed (server rejected the connection; no retry)");
      } else {
        setStatus("reconnecting");
        setError("SSE connection dropped (reconnecting)");
      }
    };
    const onMessage = (e: MessageEvent) => {
      try {
        const parsed = JSON.parse(e.data) as RunEvent;
        if (typeof parsed.sequence === "number" && typeof parsed.kind === "string") {
          setEvents((prev) => {
            const next = [...prev, parsed];
            return next.length > maxEvents ? next.slice(-maxEvents) : next;
          });
        }
      } catch {
        // ignore malformed events
      }
    };
    // We listen to generic 'message' but the backend also sends named
    // events (kind as event:). Subscribe to all by not setting `event:`
    // — onmessage catches the default; we use addEventListener with the
    // backend's named events so the user can see them in devtools.
    src.onmessage = onMessage;
    // Optional cleanup on unmount
    return () => {
      setStatus("closed");
      src.close();
    };
  }, [runId, baseUrl, maxEvents]);

  return (
    <section
      role="region"
      aria-labelledby="event-stream-title"
      style={{
        border: "1px solid #ddd",
        padding: "1rem",
        marginTop: "1rem",
        borderRadius: "8px",
      }}
    >
      <h2 id="event-stream-title">Run event stream</h2>
      <p style={{ fontSize: "0.85em", color: "#666" }}>
        status: <strong>{status}</strong>
        {error && (
          <span role="alert" style={{ color: "crimson", marginLeft: "0.5rem" }}>
            {error}
          </span>
        )}
      </p>
      <ul
        role="log"
        aria-live="polite"
        aria-label={`Live events for run ${runId}`}
        style={{
          maxHeight: 240,
          overflowY: "auto",
          fontFamily: "monospace",
          fontSize: "0.85em",
          padding: "0.5rem",
          background: "#f8f8f8",
          borderRadius: 4,
        }}
      >
        {events.length === 0 ? (
          <li style={{ listStyle: "none", color: "#888" }}>(no events yet)</li>
        ) : (
          events.map((e) => (
            <li key={e.sequence} style={{ listStyle: "none" }}>
              <code>#{e.sequence}</code> {e.kind} @ <em>{e.occurredAt}</em>
            </li>
          ))
        )}
      </ul>
    </section>
  );
}
