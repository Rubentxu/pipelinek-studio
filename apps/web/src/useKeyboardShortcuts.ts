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
    // The shortcuts only fire when focus is NOT inside a text
    // input / textarea / contenteditable region. The previous
    // implementation had this documented in the file header but
    // not actually enforced: Cmd-S would seal a snapshot while
    // the user was typing in any input field (e.g. the
    // experiment-name quick-pick), hijacking the browser-native
    // save shortcut and silently calling the backend.
    function isTypingInTextField(): boolean {
      const active = document.activeElement as HTMLElement | null;
      if (!active) return false;
      const tag = active.tagName;
      if (tag === "INPUT" || tag === "TEXTAREA" || tag === "SELECT") return true;
      if (active.isContentEditable) return true;
      return false;
    }
    function onKey(e: KeyboardEvent) {
      const isMod = e.metaKey || e.ctrlKey;
      const key = e.key.toLowerCase();
      const typing = isTypingInTextField();

      if (isMod && key === "s") {
        // Always prevent the browser-native save-page dialog.
        // We still call onSave even when typing — sealing while
        // typing is harmless and the user can always press Esc
        // to back out — but suppressing the page save is a
        // real win.
        e.preventDefault();
        if (typing) return;
        opts.onSave();
        setLastKey("Cmd-S");
        return;
      }
      if (isMod && key === "enter") {
        e.preventDefault();
        if (typing) return;
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
