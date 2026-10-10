import { describe, it, expect, vi, beforeEach, afterEach } from "vitest";
import { render, screen, act } from "@testing-library/react";
import { EventStream } from "./EventStream";

/**
 * Audit F-P1-WEB-7 (2026-10-10): the previous EventStream onerror
 * handler always set the status to 'error' with a generic
 * 'will retry' message, regardless of the EventSource's
 * readyState. The browser may close an EventSource permanently
 * (readyState = 2) after a 4xx response, but the user kept
 * seeing the same message forever.
 *
 * The fix is a discriminated state: 'reconnecting' for
 * readyState=0, 'closed' for readyState=2. The tests below
 * exercise both branches with a stub EventSource whose
 * readyState and onerror we control directly.
 */

type EventSourceLike = EventSource & {
  readyState: number;
};

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
  constructor(url: string) {
    this.url = url;
    FakeEventSource.instances.push(this);
  }
  close(): void {
    this.closed = true;
    this.readyState = FakeEventSource.CLOSED;
  }
}

describe("EventStream", () => {
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
    // The error message is the explicit "no retry" hint.
    expect(screen.getByText(/no retry/i)).toBeTruthy();
  });

  it("transitions from 'reconnecting' back to 'open' when onopen fires after a drop", () => {
    render(<EventStream runId="run-4" baseUrl="https://studio.test" />);
    const src = FakeEventSource.instances[0];
    src.readyState = FakeEventSource.CONNECTING;
    act(() => {
      src.onerror?.();
    });
    expect(statusText()).toBe("reconnecting");
    act(() => {
      src.onopen?.();
    });
    expect(statusText()).toBe("open");
  });

  it("does not open an EventSource when runId is empty", () => {
    render(<EventStream runId="" baseUrl="https://studio.test" />);
    expect(FakeEventSource.instances.length).toBe(0);
  });
});
