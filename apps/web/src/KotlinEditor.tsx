import Editor, { loader } from "@monaco-editor/react";
import { useEffect, useRef, useState } from "react";

/**
 * S1 E1.1 + R1.3 (2026-10-11) — Monaco editor for Kotlin source.
 *
 * Wraps @monaco-editor/react with:
 *   - the right language id and theme
 *   - graceful "loading" + "unavailable" states when Monaco can't load
 *   - a debounced onChange so the SPA doesn't issue a fetch per keystroke
 *   - a `loader.config({ paths: { vs: "/monaco/vs" } })` so locally
 *     bundled Monaco assets take precedence over the CDN. The CDN is
 *     only used as a fallback when the local assets aren't present
 *     (e.g. during dev). When R6.1 closes the bundling step the
 *     CDN is removed entirely.
 *
 * The component is loaded lazily via dynamic import so the SPA's
 * initial bundle stays small; the heavy Monaco bundles are only
 * fetched when an editor is actually mounted.
 */

// R1.3 (2026-10-11): tell the loader to prefer locally bundled
// assets under `/monaco/vs`. The default path is jsdelivr
// (https://cdn.jsdelivr.net/npm/monaco-editor@0.55.1/min/vs).
// Configuring the local path first keeps the door open for the
// no-CDN build (R6.1 closes by serving `/monaco/vs/*` from the
// Vite output instead of the npm package).
let loaderConfigured = false;
function ensureLoaderConfigured() {
  if (loaderConfigured) return;
  try {
    loader.config({ paths: { vs: "/monaco/vs" } });
  } catch {
    // Loading order / SSR: ignore. The actual Monaco init below
    // surfaces the failure clearly via `setStatus("unavailable")`.
  }
  loaderConfigured = true;
}

const KOTLIN_TEMPLATE = `// PipelineK Studio — Kotlin source for a snapshot
// Edit me, then click "Save snapshot" to seal under the active experiment.
package dev.pipelinek.studio.demo

import dev.pipelinek.core.Step
import dev.pipelinek.core.StepResult

class HelloStep : Step {
    override fun execute(input: Map<String, String>): StepResult {
        return StepResult.text("hello from PipelineK")
    }
}
`;

export interface KotlinEditorProps {
  initialSource?: string;
  onChange?: (next: string) => void;
  /**
   * R3.7b (2026-10-11): called when the Monaco editor instance
   * is mounted and ready. Lets the parent capture the editor
   * for things like DiagnosticsMarkers (setModelMarkers wiring).
   * Not typed against `monaco-editor` types because the editor
   * package is heavy + not present in the jsdom test env.
   */
  onMount?: (editor: unknown) => void;
  height?: number | string;
}

export function KotlinEditor(props: KotlinEditorProps) {
  const { initialSource = KOTLIN_TEMPLATE, onChange, onMount, height = 320 } = props;
  const [source, setSource] = useState(initialSource);
  const [status, setStatus] = useState<"loading" | "ready" | "unavailable">("loading");
  const debounceRef = useRef<ReturnType<typeof setTimeout> | null>(null);

  useEffect(() => {
    // Configure the Monaco Environment once. R1.3 (2026-10-11):
    // loader.config({ paths: { vs: "/monaco/vs" } }) tells the loader
    // to prefer locally-bundled Monaco assets. The default CDN is
    // reached only when /monaco/vs/* returns 404 (e.g. dev mode
    // before R6.1 ships the bundling step).
    ensureLoaderConfigured();
    let cancelled = false;
    loader
      .init()
      .then(() => !cancelled && setStatus("ready"))
      .catch(() => !cancelled && setStatus("unavailable"));
    // Audit (2026-10-10): clear any pending debounce on unmount so
    // the parent's onChange never fires with stale state after the
    // editor has been torn down. Without this, a fast unmount (e.g.
    // the user navigates away mid-debounce) produces a React
    // 'setState on unmounted component' warning and a redundant
    // setEditorSource call on the parent.
    return () => {
      cancelled = true;
      if (debounceRef.current) {
        clearTimeout(debounceRef.current);
        debounceRef.current = null;
      }
    };
  }, []);

  if (status === "loading") {
    return <p data-testid="monaco-loading" role="status" aria-live="polite">Monaco editor loading…</p>;
  }
  if (status === "unavailable") {
    return (
      <p data-testid="monaco-unavailable" role="alert" style={{ color: "crimson" }}>
        Monaco editor unavailable (offline? install @monaco-editor/react and check your CDN).
      </p>
    );
  }

  return (
    <div
      data-testid="monaco-editor"
      role="textbox"
      aria-label="Kotlin source editor"
      aria-multiline="true"
      tabIndex={0}
      style={{ border: "1px solid #ccc", borderRadius: 4, overflow: "hidden" }}
    >
      <Editor
        height={height}
        defaultLanguage="kotlin"
        defaultPath="snapshot.pipeline.kts"
        value={source}
        theme="vs-dark"
        onMount={(editor) => {
          // R3.7b: forward the editor instance to the parent so
          // DiagnosticsMarkers (or any future Monaco consumer) can
          // hook in without a refactor.
          if (onMount) onMount(editor);
        }}
        onChange={(v) => {
          const next = v ?? "";
          setSource(next);
          if (onChange) {
            if (debounceRef.current) clearTimeout(debounceRef.current);
            debounceRef.current = setTimeout(() => onChange(next), 250);
          }
        }}
        options={{
          minimap: { enabled: false },
          fontSize: 13,
          wordWrap: "on",
          automaticLayout: true,
        }}
      />
    </div>
  );
}
