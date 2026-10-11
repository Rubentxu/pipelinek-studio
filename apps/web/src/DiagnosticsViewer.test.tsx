import { describe, expect, it, vi, beforeEach, afterEach } from "vitest";
import React from "react";
import { render, screen, waitFor } from "@testing-library/react";
import { DiagnosticsViewer } from "./DiagnosticsViewer";

/**
 * R3.7 adversarial tests — pin the UNSUPPORTED-honesto contract on
 * the SPA diagnostics viewer. The pre-R3.7 component rendered
 * 'No diagnostics — clean run.' whenever the backend returned 200
 * with an empty array, regardless of whether the static analyzer
 * was actually configured. That is exactly the anti-pattern the
 * R3 mandate forbids: a list that may be empty for "couldn't run"
 * being shown as "no problems".
 *
 * The contract the R3.7 fix establishes:
 *  - Available capability + empty list -> 'clean run' shown.
 *  - Unavailable capability + empty list -> reason shown, NOT
 *    'clean run'. The pre-fix message must NOT be present.
 *  - Non-empty diagnostics -> rendered regardless of capability.
 */

type FetchResponse = {
  status: number;
  body: unknown;
};

function installFetchMock(responses: Record<string, FetchResponse>) {
  const calls: string[] = [];
  const fn = vi.fn(async (input: RequestInfo | URL) => {
    const url = typeof input === "string" ? input : (input as URL).toString();
    calls.push(url);
    const key = Object.keys(responses).find((k) => url.includes(k));
    if (!key) throw new Error(`unexpected fetch in test: ${url}`);
    const { status, body } = responses[key]!;
    return new Response(JSON.stringify(body), { status });
  });
  vi.stubGlobal("fetch", fn);
  return { calls, fn };
}

describe("DiagnosticsViewer R3.7 UNSUPPORTED-honesto", () => {
  beforeEach(() => {
    vi.useRealTimers();
  });
  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it("renders 'clean run' ONLY when capability is Available and diagnostics are empty", async () => {
    installFetchMock({
      "/api/static-analyze-capability": {
        status: 200,
        body: {
          analyzer_version: "studio-static-analyzer:1.0.0",
          analyzer_digest: "abc123",
        },
      },
      "/runs/run-clean/diagnostics": { status: 200, body: { diagnostics: [] } },
    });
    render(<DiagnosticsViewer runId="run-clean" baseUrl="https://studio.test" runState="SUCCEEDED" />);
    await waitFor(() => {
      expect(screen.getByTestId("diagnostics-clean")).toBeTruthy();
    });
    expect(screen.queryByTestId("diagnostics-unavailable")).toBeNull();
    expect(screen.queryByTestId("diagnostics-capability-loading")).toBeNull();
  });

  it("renders 'static analysis unavailable' (NOT 'clean run') when capability is Unavailable", async () => {
    installFetchMock({
      "/api/static-analyze-capability": {
        status: 200,
        body: { reason: "STUDIO_STATIC_ANALYZE_DISABLED=1 is set" },
      },
      "/runs/run-noop/diagnostics": { status: 200, body: { diagnostics: [] } },
    });
    render(<DiagnosticsViewer runId="run-noop" baseUrl="https://studio.test" runState="SUCCEEDED" />);
    await waitFor(() => {
      expect(screen.getByTestId("diagnostics-unavailable")).toBeTruthy();
    });
    // The pre-R3.7 anti-pattern was rendering 'No diagnostics —
    // clean run.' in exactly this state. That message MUST NOT be
    // present when the capability is Unavailable. The fix gates it
    // on capabilityStatus === 'available'; this test pins the gate.
    expect(screen.queryByTestId("diagnostics-clean")).toBeNull();
    // The honest reason must surface so the operator can act.
    expect(screen.getByText(/STUDIO_STATIC_ANALYZE_DISABLED=1/)).toBeTruthy();
  });

  it("renders the diagnostics list regardless of capability when it is non-empty", async () => {
    installFetchMock({
      "/api/static-analyze-capability": {
        status: 200,
        body: { reason: "disabled by env" },
      },
      "/runs/run-errs/diagnostics": {
        status: 200,
        body: {
          diagnostics: [
            {
              id: "d1",
              severity: "ERROR",
              phase: "COMPILATION",
              message: "Unresolved reference",
              source: "pipelinek:finished",
              run_id: "run-errs",
              line: 3,
              column: 5,
              path: "echo.kts",
            },
          ],
        },
      },
    });
    render(<DiagnosticsViewer runId="run-errs" baseUrl="https://studio.test" runState="FAILED" />);
    await waitFor(() => {
      expect(screen.getByText("Unresolved reference")).toBeTruthy();
    });
    // When diagnostics are non-empty the 'clean run' / 'unavailable'
    // branches are irrelevant; only the list renders.
    expect(screen.queryByTestId("diagnostics-clean")).toBeNull();
    expect(screen.queryByTestId("diagnostics-unavailable")).toBeNull();
  });
});