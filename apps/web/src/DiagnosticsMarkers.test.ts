import { describe, expect, it, vi } from "vitest";
import { renderHook } from "@testing-library/react";
import {
  buildMarkers,
  severityToNumber,
  useDiagnosticsMarkers,
  type MarkerData,
  type MarkerSeverityMap,
  type SetMarkersFn,
} from "./DiagnosticsMarkers";

/**
 * R3.7b adversarial tests for the Monaco markers hook.
 *
 * The mandate item is "Integrar diagnósticos y markers en Monaco;
 * evitar diagnósticos obsoletos". The path-filter is the
 * pre-R3.1-wiring guard:
 *
 *   - Diagnostic with matching `path` -> marker painted.
 *   - Diagnostic with mismatched `path` -> marker NOT painted
 *     (discarded as stale; belongs to a different source).
 *   - Diagnostic with null/undefined `path` -> marker painted
 *     (no signal to filter).
 *   - Empty diagnostics -> markers cleared (no leftover from a
 *     previous run).
 *   - Unmount -> markers cleared on the active model.
 *   - Model swap -> markers cleared from the OLD model, painted on
 *     the NEW one (Monaco markers are per-model).
 */

const SEVERITY: MarkerSeverityMap = { Error: 8, Warning: 4, Info: 1 };

function fakeModel(id: string): { id: string } {
  return { id };
}

function diag(over: Partial<{
  id: string;
  severity: string;
  phase: string;
  message: string;
  source: string;
  run_id: string;
  line: number;
  column: number;
  path: string | null;
}> = {}) {
  return {
    id: "d1",
    severity: "ERROR",
    phase: "COMPILATION",
    message: "boom",
    source: "pipelinek:finished",
    run_id: "run-1",
    line: 3,
    column: 5,
    path: "echo.kts" as string | null,
    ...over,
  };
}

describe("R3.7b severityToNumber", () => {
  it("maps ERROR / WARNING / INFO to the numeric map", () => {
    expect(severityToNumber("ERROR", SEVERITY)).toBe(8);
    expect(severityToNumber("WARNING", SEVERITY)).toBe(4);
    expect(severityToNumber("INFO", SEVERITY)).toBe(1);
  });
  it("defaults unknown severity to Info (lowest visibility)", () => {
    expect(severityToNumber("FATAL", SEVERITY)).toBe(1);
    expect(severityToNumber("", SEVERITY)).toBe(1);
  });
});

describe("R3.7b buildMarkers path-filter", () => {
  it("paints diagnostics whose path equals the editor path", () => {
    const out = buildMarkers([diag({ path: "echo.kts" })], "echo.kts", SEVERITY);
    expect(out).toHaveLength(1);
    expect(out[0]!.message).toBe("boom");
    expect(out[0]!.severity).toBe(8);
    expect(out[0]!.startLineNumber).toBe(3);
    expect(out[0]!.startColumn).toBe(5);
  });

  it("discards diagnostics whose path does NOT match the editor path", () => {
    const out = buildMarkers([diag({ path: "other.kts" })], "echo.kts", SEVERITY);
    expect(out).toHaveLength(0);
  });

  it("paints diagnostics whose path is null (no signal to filter)", () => {
    const out = buildMarkers([diag({ path: null })], "echo.kts", SEVERITY);
    expect(out).toHaveLength(1);
  });

  it("paints diagnostics whose path is undefined", () => {
    const d = diag();
    // @ts-expect-error — exercising the runtime path where path is absent
    delete d.path;
    const out = buildMarkers([d], "echo.kts", SEVERITY);
    expect(out).toHaveLength(1);
  });

  it("filters a mixed list, keeping only matching + null-path diagnostics", () => {
    const out = buildMarkers(
      [
        diag({ id: "a", path: "echo.kts" }),
        diag({ id: "b", path: "other.kts" }),
        diag({ id: "c", path: null }),
      ],
      "echo.kts",
      SEVERITY,
    );
    const ids = out.map((m) => m.message);
    expect(out).toHaveLength(2);
  });

  it("defaults missing line/column to 1/1 (degraded-but-not-crash posture)", () => {
    const d = diag({ path: "echo.kts" });
    // @ts-expect-error — exercising the runtime path where line/col are absent
    delete d.line;
    // @ts-expect-error
    delete d.column;
    const out = buildMarkers([d], "echo.kts", SEVERITY);
    expect(out).toHaveLength(1);
    expect(out[0]!.startLineNumber).toBe(1);
    expect(out[0]!.startColumn).toBe(1);
  });
});

describe("R3.7b useDiagnosticsMarkers lifecycle", () => {
  function makeSetMarkersRecorder() {
    const calls: Array<{ modelId: string; owner: string; count: number; messages: unknown[] }> = [];
    const setMarkers: SetMarkersFn = (model, owner, markers) => {
      calls.push({
        modelId: (model as { id: string }).id,
        owner,
        count: markers.length,
        messages: markers.map((m) => (m as MarkerData).message),
      });
    };
    return { setMarkers, calls };
  }

  it("paints markers on mount and clears them on unmount", () => {
    const { setMarkers, calls } = makeSetMarkersRecorder();
    const model = fakeModel("m1");
    const { unmount } = renderHook(() =>
      useDiagnosticsMarkers({
        model,
        diagnostics: [diag({ path: "echo.kts" })],
        editorPath: "echo.kts",
        setMarkers,
        severityMap: SEVERITY,
      }),
    );
    expect(calls).toHaveLength(1);
    expect(calls[0]!.modelId).toBe("m1");
    expect(calls[0]!.owner).toBe("pipelinek-studio");
    expect(calls[0]!.count).toBe(1);
    unmount();
    // The unmount cleanup clears them.
    expect(calls).toHaveLength(2);
    expect(calls[1]!.modelId).toBe("m1");
    expect(calls[1]!.count).toBe(0);
  });

  it("clears markers from the OLD model and paints on the NEW model when the model swaps", () => {
    const { setMarkers, calls } = makeSetMarkersRecorder();
    const m1 = fakeModel("m1");
    const m2 = fakeModel("m2");
    const diagnostics = [diag({ path: "echo.kts" })];
    let model: unknown = m1;
    const { rerender } = renderHook(
      ({ m }) =>
        useDiagnosticsMarkers({
          model: m,
          diagnostics,
          editorPath: "echo.kts",
          setMarkers,
          severityMap: SEVERITY,
        }),
      { initialProps: { m: m1 } },
    );
    expect(calls).toHaveLength(1);
    expect(calls[0]!.modelId).toBe("m1");

    // Swap to m2.
    model = m2;
    rerender({ m: m2 });
    // 1: clear m1, 2: paint m2
    expect(calls).toHaveLength(3);
    expect(calls[1]!.modelId).toBe("m1");
    expect(calls[1]!.count).toBe(0);
    expect(calls[2]!.modelId).toBe("m2");
    expect(calls[2]!.count).toBe(1);
  });

  it("does nothing when model is null", () => {
    const { setMarkers, calls } = makeSetMarkersRecorder();
    renderHook(() =>
      useDiagnosticsMarkers({
        model: null,
        diagnostics: [diag({ path: "echo.kts" })],
        editorPath: "echo.kts",
        setMarkers,
        severityMap: SEVERITY,
      }),
    );
    expect(calls).toHaveLength(0);
  });

  it("re-paints when the diagnostics array changes", () => {
    const { setMarkers, calls } = makeSetMarkersRecorder();
    const model = fakeModel("m1");
    const { rerender } = renderHook(
      ({ diags }: { diags: ReturnType<typeof diag>[] }) =>
        useDiagnosticsMarkers({
          model,
          diagnostics: diags,
          editorPath: "echo.kts",
          setMarkers,
          severityMap: SEVERITY,
        }),
      { initialProps: { diags: [diag({ id: "a", path: "echo.kts" })] } },
    );
    expect(calls).toHaveLength(1);
    expect(calls[0]!.count).toBe(1);
    rerender({ diags: [diag({ id: "a", path: "echo.kts" }), diag({ id: "b", path: "echo.kts" })] });
    // 1: clear, 2: repaint with 2 markers
    expect(calls).toHaveLength(3);
    expect(calls[2]!.count).toBe(2);
  });
});