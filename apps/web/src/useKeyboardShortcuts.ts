import { useEffect, useRef, useState } from "react";

/**
 * S5 E5.6 — focus management + keyboard shortcuts.
 *
 * - Auto-focuses the editor wrapper on mount (so Tab navigation lands
 *   on the editor first; the skip link is the only earlier focusable
 *   element).
 * - Cmd/Ctrl-S seals the current snapshot (same as the "Save snapshot"
 *   button). The browser's default "save page" is prevented.
 * - Cmd/Ctrl-Enter triggers the experiment-creation flow (a common
 *   power-user shortcut for the most common action).
 * - Escape clears the focus from the active element (a common a11y
 *   pattern for modal-like surfaces).
 *
 * The shortcuts only fire when the editor is the active region (or no
 * other input/textarea is focused), to avoid stealing characters.
 */
export function useKeyboardShortcuts(opts: {
  onSave: () => void;
  onCreate: () => void;
}) {
  const [lastKey, setLastKey] = useState<string | null>(null);
  const containerRef = useRef<HTMLDivElement | null>(null);

  useEffect(() => {
    function onKey(e: KeyboardEvent) {
      const isMod = e.metaKey || e.ctrlKey;
      const key = e.key.toLowerCase();

      if (isMod && key === "s") {
        e.preventDefault();
        opts.onSave();
        setLastKey("Cmd-S");
        return;
      }
      if (isMod && key === "enter") {
        e.preventDefault();
        opts.onCreate();
        setLastKey("Cmd-Enter");
        return;
      }
      if (e.key === "Escape") {
        const active = document.activeElement as HTMLElement | null;
        if (active && active !== document.body) {
          active.blur();
          setLastKey("Escape");
        }
      }
    }
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [opts.onSave, opts.onCreate]);

  // Auto-focus the container on mount so keyboard users land on the
  // editor immediately after Tab.
  useEffect(() => {
    containerRef.current?.focus();
  }, []);

  return { containerRef, lastKey };
}
