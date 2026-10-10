import Editor, { loader } from "@monaco-editor/react";
import { useEffect, useRef, useState } from "react";

/**
 * S1 E1.1 — Monaco editor for Kotlin source. Wraps @monaco-editor/react with:
 *   - the right language id and theme
 *   - graceful "loading" + "unavailable" states when Monaco can't load
 *   - a debounced onChange so the SPA doesn't issue a fetch per keystroke
 *
 * The component is loaded lazily via dynamic import so the SPA's
 * initial bundle stays small; the heavy Monaco bundles are only
 * fetched when an editor is actually mounted.
 */

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
  height?: number | string;
}

export function KotlinEditor(props: KotlinEditorProps) {
  const { initialSource = KOTLIN_TEMPLATE, onChange, height = 320 } = props;
  const [source, setSource] = useState(initialSource);
  const [status, setStatus] = useState<"loading" | "ready" | "unavailable">("loading");
  const debounceRef = useRef<ReturnType<typeof setTimeout> | null>(null);

  useEffect(() => {
    // Configure the Monaco Environment once. @monaco-editor/react will
    // load Monaco from the CDN by default; for a fully offline build
    // configure loader.config({ paths: { vs: "/monaco/vs" } }) here.
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
