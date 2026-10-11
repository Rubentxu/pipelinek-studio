import { describe, it, expect, vi, beforeEach, afterEach } from "vitest";
import { render, screen, act } from "@testing-library/react";
import { EventStream } from "./EventStream";

/**
 * R1.1 (2026-10-11) — adversarial test suite for the front-end Event
 * Plane consumer.
 *
 * R1.1.a — named event subscription: backend emits `event: <kind>` per
 * envelope. We assert the listener receives the data via the named-event
 * callback (NOT via `message`).
 *
 * R1.1.b — Last-Event-ID re-send: when the EventSource reconnect hits
 * (readyState=0), the component closes the source and re-opens with
 * `?after=<lastSequence>`. We assert the second FakeEventSource is
 * constructed with the cursor in the URL, proving the resume payload is
 * sent on every retry.
 *
 * R1.1.c — dedup by sequence: the backend can re-emit the head event
 * on a cursor transition (e.g. when the cursor lands exactly on a
 * boundary). We assert the same sequence arriving twice renders ONCE.
 *
 * R1.1.d — `event: "end"` handler: a server-side close triggers the
 * component's `closed` status (not `error`) and surfaces the end code,
 * preventing futile auto-reconnects.
 *
 * R1.1.e — `event: "resume"` handler: a successful resume surfaces the
 * SSE_RESUMED code so operators can confirm the stream was continued
 * from a non-head position.
 *
 * R1.1.f — backward compat: the OLD test assertions (open/close/
 * readyState discrimination) keep working unchanged.
 */

type FakeEventSourceListener = (e: MessageEvent) => void;

class FakeEventSource {
  static instances: FakeEventSource[] = [];
  static CONNECTING = 0;
  static OPEN = 1;
  static CLOSED = 2;
  readyState: number = FakeEventSource.CONNECTING;
  onopen: (() => void) | null = null;
  onerror: (() => void) | null = null;
  onmessage: ((e: MessageEvent) => void) | null = null;
  url: string;
  closed = false;
  private listeners = new Map<string, Set<FakeEventSourceListener>>();

  constructor(url: string) {
    this.url = url;
    FakeEventSource.instances.push(this);
  }

  addEventListener(name: string, cb: FakeEventSourceListener): void {
    let set = this.listeners.get(name);
    if (!set) {
      set = new Set();
      this.listeners.set(name, set);
    }
    set.add(cb);
  }

  close(): void {
    this.closed = true;
    this.readyState = FakeEventSource.CLOSED;
  }

  /** Test helper to fire a named event with arbitrary `lastEventId`. */
  trigger(name: string, data: string, lastEventId?: string): void {
    const ev = {
      data,
      lastEventId,
      type: name,
    } as unknown as MessageEvent;
    if (name === "message") {
      this.onmessage?.(ev);
    } else if (name === "open") {
      this.onopen?.();
    } else if (name === "error") {
      this.onerror?.();
    } else {
      const set = this.listeners.get(name);
      if (set) for (const cb of set) cb(ev);
    }
  }
}

describe("EventStream R1.1", () => {
  beforeEach(() => {
    FakeEventSource.instances = [];
    vi.stubGlobal("EventSource", FakeEventSource as unknown as typeof EventSource);
  });
  afterEach(() => {
    vi.unstubAllGlobals();
  });

  /** Read the current status word from the <strong> in the header. */
  function statusText(): string {
    const el = document.querySelector("section[aria-labelledby='event-stream-title'] strong");
    return el?.textContent ?? "";
  }

  // R1.1.f — backwards-compat: open/close/reconnecting semantics preserved.
  it("renders 'connecting' on mount, 'open' after onopen", () => {
    render(<EventStream runId="run-1" baseUrl="https://studio.test" />);
    expect(statusText()).toBe("connecting");
    const src = FakeEventSource.instances[0];
    act(() => {
      src.onopen?.();
    });
    expect(statusText()).toBe("open");
  });

  it("sets status to 'reconnecting' when onerror fires with readyState=0 (CONNECTING)", () => {
    render(<EventStream runId="run-2" baseUrl="https://studio.test" />);
    const src = FakeEventSource.instances[0];
    src.readyState = FakeEventSource.CONNECTING;
    act(() => {
      src.onerror?.();
    });
    expect(statusText()).toBe("reconnecting");
  });

  it("sets status to 'closed' (no retry) when onerror fires with readyState=2 (CLOSED)", () => {
    render(<EventStream runId="run-3" baseUrl="https://studio.test" />);
    const src = FakeEventSource.instances[0];
    src.readyState = FakeEventSource.CLOSED;
    act(() => {
      src.onerror?.();
    });
    expect(statusText()).toBe("closed");
  });

  it("transitions from 'reconnecting' back to 'open' when onopen fires after a drop", () => {
    // R1.1.b (2026-10-11): on browser-driven reconnect we close the
    // first source and open a NEW one with ?after=<lastSeq>. Driving
    // `onopen` on the OLD source intentionally does NOT transition to
    // 'open' (the gen check rejects the late fire). Driving onopen on
    // the NEW source IS what moves the status to 'open'.
    render(<EventStream runId="run-4" baseUrl="https://studio.test" />);
    const first = FakeEventSource.instances[0];
    first.readyState = FakeEventSource.CONNECTING;
    act(() => {
      first.onerror?.();
    });
    expect(statusText()).toBe("reconnecting");
    const second = FakeEventSource.instances[1];
    act(() => {
      second.onopen?.();
    });
    expect(statusText()).toBe("open");
  });

  it("does not open an EventSource when runId is empty", () => {
    render(<EventStream runId="" baseUrl="https://studio.test" />);
    expect(FakeEventSource.instances.length).toBe(0);
  });

  // R1.1.a — named events.
  it("delivers named events (e.g. StepStarted) to the UI via the data payload", () => {
    render(<EventStream runId="run-named" baseUrl="https://studio.test" />);
    const src = FakeEventSource.instances[0];
    act(() => {
      src.trigger(
        "StepStarted",
        JSON.stringify({ sequence: 1, kind: "StepStarted", occurredAt: "t1", payload: "{}" }),
      );
    });
    expect(screen.getByTestId("event-1")).toBeTruthy();
    expect(screen.getByText("StepStarted")).toBeTruthy();
  });

  // R1.1.c — dedup.
  it("dedups events with the same sequence and renders only the first", () => {
    render(<EventStream runId="run-dup" baseUrl="https://studio.test" />);
    const src = FakeEventSource.instances[0];
    const envelope = JSON.stringify({ sequence: 7, kind: "StepFinished", occurredAt: "t", payload: "{}" });
    act(() => {
      src.trigger("StepFinished", envelope);
      src.trigger("StepFinished", envelope);
      src.trigger("StepFinished", envelope);
    });
    // The list contains exactly one li with data-testid="event-7".
    const matches = screen.queryAllByTestId("event-7");
    expect(matches.length).toBe(1);
  });

  // R1.1.b — Last-Event-ID (or equivalent query param) re-send on reconnect.
  it("re-opens the stream with ?after=<lastSequence> on browser-driven reconnect", () => {
    render(<EventStream runId="run-resume" baseUrl="https://studio.test" />);
    const first = FakeEventSource.instances[0];
    // 1. Deliver two events; track the last sequence in the UI.
    act(() => {
      first.trigger("StepStarted", JSON.stringify({ sequence: 10, kind: "StepStarted", occurredAt: "t", payload: "{}" }));
      first.trigger("StepFinished", JSON.stringify({ sequence: 11, kind: "StepFinished", occurredAt: "t", payload: "{}" }));
    });
    expect(screen.getByTestId("last-sequence").textContent).toBe("11");

    // 2. Browser-driven reconnect (readyState=0).
    first.readyState = FakeEventSource.CONNECTING;
    act(() => {
      first.onerror?.();
    });

    // 3. A second EventSource was constructed with `?after=11` in the URL.
    const second = FakeEventSource.instances[1];
    expect(second, "expected a reconnect EventSource").toBeDefined();
    expect(second.url).toBe("https://studio.test/runs/run-resume/stream?after=11");
    // The first source was closed by us before reopening (we don't want
    // a double subscription).
    expect(first.closed).toBe(true);
  });

  // R1.1.b — no sequences yet → reconnect uses no `?after=` (head).
  it("re-opens without ?after= when no sequence has been delivered yet", () => {
    render(<EventStream runId="run-head" baseUrl="https://studio.test" />);
    const first = FakeEventSource.instances[0];
    first.readyState = FakeEventSource.CONNECTING;
    act(() => {
      first.onerror?.();
    });
    const second = FakeEventSource.instances[1];
    expect(second.url).toBe("https://studio.test/runs/run-head/stream");
    expect(second.url).not.toContain("after=");
  });

  // R1.1.d — end event surfaces the SSE close code.
  it("surfaces the SSE end code as the end: <code> pill on `event: end`", () => {
    render(<EventStream runId="run-end" baseUrl="https://studio.test" />);
    const src = FakeEventSource.instances[0];
    act(() => {
      src.trigger("end", JSON.stringify({ code: "SSE_IDLE_CLOSE" }));
    });
    expect(statusText()).toBe("closed");
    expect(screen.getByTestId("end-code").textContent).toContain("SSE_IDLE_CLOSE");
  });

  // R1.1.e — resume event surfaces the SSE_RESUMED code.
  it("surfaces the SSE resume code on `event: resume`", () => {
    render(<EventStream runId="run-resumed" baseUrl="https://studio.test" />);
    const src = FakeEventSource.instances[0];
    act(() => {
      src.trigger("resume", JSON.stringify({ code: "SSE_RESUMED", after: 42 }));
    });
    expect(screen.getByTestId("resume-code").textContent).toContain("SSE_RESUMED");
  });

  // R1.1.f — end event CLOSES the source so the browser doesn't auto-retry.
  it("closes the EventSource when the backend emits `event: end`", () => {
    render(<EventStream runId="run-end-close" baseUrl="https://studio.test" />);
    const src = FakeEventSource.instances[0];
    act(() => {
      src.trigger("end", JSON.stringify({ code: "SSE_MAX_ITERATIONS_CLOSE" }));
    });
    expect(src.closed, "expected the source to be closed on `event: end`").toBe(true);
    // No further events on the same source should be processed.
    const beforeLen = FakeEventSource.instances.length;
    act(() => {
      src.trigger("StepStarted", JSON.stringify({ sequence: 999, kind: "StepStarted", occurredAt: "t", payload: "{}" }));
    });
    const afterLen = FakeEventSource.instances.length;
    expect(afterLen).toBe(beforeLen); // no auto-reconnect after end
    expect(screen.queryByTestId("event-999")).toBeNull();
  });

  // Memory bound: bounded event list.
  it("caps the rendered event list at maxEvents (default 200)", () => {
    render(<EventStream runId="run-cap" baseUrl="https://studio.test" maxEvents={3} />);
    const src = FakeEventSource.instances[0];
    act(() => {
      for (let i = 1; i <= 7; i += 1) {
        src.trigger(
          "StepStarted",
          JSON.stringify({ sequence: i, kind: "StepStarted", occurredAt: `t${i}`, payload: "{}" }),
        );
      }
    });
    const items = screen.getAllByRole("listitem");
    // We rendered a section with a <ul role="log">. The list should hold
    // exactly 3 entries (the most recent 3 of 7 sent), not 7.
    expect(items.length).toBe(3);
    expect(screen.getByTestId("event-7")).toBeTruthy();
    expect(screen.queryByTestId("event-1")).toBeNull();
  });
});
