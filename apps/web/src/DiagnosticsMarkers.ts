import { useEffect, useRef } from "react";

/**
 * R3.7b (2026-10-11) — Monaco markers for the Diagnostics surface.
 *
 * The mandate item is "Integrar diagnósticos y markers en Monaco;
 * evitar diagnósticos obsoletos". This hook is the wire between
 * `DiagnosticDto[]` (the typed diagnostics payload, owned by the
 * DiagnosticsViewer port) and `monaco.editor.setModelMarkers` (the
 * Monaco API that paints the gutter + inline markers).
 *
 * Stale-marker guard (R3.7b path-filter):
 *   Until R3.1 wiring ships the sourceDigest plumbing for the
 *   editor's current snapshot, we cannot compare `diagnostic.sourceDigest`
 *   to the editor's current digest. The guard we CAN apply without
 *   that plumbing is the path-based filter:
 *
 *     - If `diagnostic.path` is null/undefined, we have no signal
 *       to filter on -> the marker is kept (best effort).
 *     - If `diagnostic.path` is non-null and != the editor's
 *       current path, the diagnostic is from a different source
 *       file -> the marker is NOT painted.
 *     - If `diagnostic.path` matches `editorPath`, the marker IS
 *       painted.
 *
 *   When R3.1 wiring closes, the path-filter will be augmented
 *   with a digest-filter (markers whose `diagnostic.sourceDigest`
 *   != the editor's current sourceDigest are discarded). For the
 *   pre-R3.1-wiring world, path-filter is the safe minimum.
 */

export interface DiagnosticDtoLike {
  id: string;
  severity: string;
  phase: string;
  message: string;
  source: string;
  run_id: string;
  line?: number;
  column?: number;
  // path may be `undefined` (absent) OR `null` (explicit JSON null
  // from the backend). Both mean "no signal" for the path-filter.
  path?: string | null;
}

/**
 * Subset of `monaco.editor.IMarkerData` we use — declared as a
 * structural type so this module does not need to import monaco
 * types at build time (the editor package is heavy + not present
 * in the jsdom test env).
 */
export interface MarkerData {
  startLineNumber: number;
  startColumn: number;
  endLineNumber: number;
  endColumn: number;
  message: string;
  severity: number;
}

export interface MarkerSeverityMap {
  Error: number;
  Warning: number;
  Info: number;
}

export type SetMarkersFn = (
  model: unknown,
  owner: string,
  markers: MarkerData[],
) => void;

/**
 * Map DiagnosticDto severity to a numeric Monaco severity. The
 * numeric values match `monaco.MarkerSeverity` (Error=8, Warning=4,
 * Info=1) but the mapping is injected so tests can verify it
 * without loading the Monaco package.
 */
export function severityToNumber(
  sev: string,
  map: MarkerSeverityMap,
): number {
  switch (sev) {
    case "ERROR":
      return map.Error;
    case "WARNING":
      return map.Warning;
    case "INFO":
      return map.Info;
    default:
      // Unknown severity is rendered as Info (lowest visibility) —
      // we never escalate to Error without evidence.
      return map.Info;
  }
}

/**
 * Build the filtered marker list from a diagnostics array and an
 * editor path. Exported for unit testing in isolation.
 */
export function buildMarkers(
  diagnostics: DiagnosticDtoLike[],
  editorPath: string | null,
  severityMap: MarkerSeverityMap,
): MarkerData[] {
  return diagnostics
    .filter((d) => {
      // Path-filter: keep null/undefined paths (no signal) and
      // exact-matches; discard mismatched paths.
      if (d.path === undefined || d.path === null) return true;
      return d.path === editorPath;
    })
    .map((d) => {
      const line = d.line ?? 1;
      const column = d.column ?? 1;
      return {
        startLineNumber: line,
        startColumn: column,
        endLineNumber: line,
        endColumn: column + 1,
        message: d.message,
        severity: severityToNumber(d.severity, severityMap),
      };
    });
}

const OWNER = "pipelinek-studio";

export interface UseDiagnosticsMarkersArgs {
  /** The Monaco text model to paint on, or null if no editor is mounted. */
  model: unknown | null;
  diagnostics: DiagnosticDtoLike[];
  /** The path of the file currently open in the editor (for the path-filter). */
  editorPath: string | null;
  /** Injected Monaco API so the hook stays testable without the heavy monaco package. */
  setMarkers: SetMarkersFn;
  /** Injected severity map (numeric values for Error/Warning/Info). */
  severityMap: MarkerSeverityMap;
}

/**
 * React hook: paints the given diagnostics as Monaco markers on
 * the given model, applying the path-filter stale-marker guard.
 * On unmount OR when the model/diagnostics/editorPath change, the
 * markers are cleared from the previous model and re-painted on the
 * new one. Without this teardown, stale markers from a previous
 * run would linger in the editor.
 */
export function useDiagnosticsMarkers(args: UseDiagnosticsMarkersArgs): void {
  const { model, diagnostics, editorPath, setMarkers, severityMap } = args;
  // Snapshot of the previous model so we can clear markers from
  // it when the editor swaps to a new file (Monaco markers are
  // per-model, not per-editor). useRef gives a stable handle
  // across re-renders.
  const prevModelRef = useRef<unknown | null>(null);

  useEffect(() => {
    // Clear markers from the previous model if any.
    if (prevModelRef.current && prevModelRef.current !== model) {
      setMarkers(prevModelRef.current, OWNER, []);
    }
    prevModelRef.current = model;
    if (!model) return;
    const markers = buildMarkers(diagnostics, editorPath, severityMap);
    setMarkers(model, OWNER, markers);
    // On unmount, clear markers from the current model so they
    // do not leak into a subsequent run.
    return () => {
      setMarkers(model, OWNER, []);
      prevModelRef.current = null;
    };
  }, [model, diagnostics, editorPath, setMarkers, severityMap]);
}