import { describe, expect, it, vi } from "vitest";
import { renderHook } from "@testing-library/react";
import { useKeyboardShortcuts } from "./useKeyboardShortcuts";

/**
 * S5 E5.6 — unit tests for the keyboard shortcut hook. Validates the
 * 3 documented shortcuts (Cmd-S seal, Cmd-Enter create, Esc clear
 * focus) plus the negative case (regular keys are ignored).
 */

describe("useKeyboardShortcuts", () => {
  function setup() {
    const onSave = vi.fn();
    const onCreate = vi.fn();
    renderHook(() => useKeyboardShortcuts({ onSave: () => onSave(), onCreate: () => onCreate() }));
    return { onSave, onCreate };
  }

  function fireKey(key: string, opts: { metaKey?: boolean; ctrlKey?: boolean } = {}) {
    const ev = new KeyboardEvent("keydown", {
      key,
      metaKey: opts.metaKey ?? false,
      ctrlKey: opts.ctrlKey ?? false,
      bubbles: true,
    });
    window.dispatchEvent(ev);
    return ev;
  }

  it("Cmd-S calls onSave and prevents default", () => {
    const { onSave } = setup();
    const ev = fireKey("s", { metaKey: true });
    expect(onSave).toHaveBeenCalledTimes(1);
    expect(ev.defaultPrevented).toBe(true);
  });

  it("Ctrl-S (no Meta) also triggers the save shortcut", () => {
    const { onSave } = setup();
    fireKey("s", { ctrlKey: true });
    expect(onSave).toHaveBeenCalledTimes(1);
  });

  it("Cmd-Enter calls onCreate and prevents default", () => {
    const { onCreate } = setup();
    const ev = fireKey("Enter", { metaKey: true });
    expect(onCreate).toHaveBeenCalledTimes(1);
    expect(ev.defaultPrevented).toBe(true);
  });

  it("plain s (no modifier) does NOT call onSave", () => {
    const { onSave } = setup();
    fireKey("s");
    expect(onSave).not.toHaveBeenCalled();
  });

  it("Escape blurs the active element if it is not body", () => {
    setup();
    const input = document.createElement("input");
    document.body.appendChild(input);
    input.focus();
    expect(document.activeElement).toBe(input);
    fireKey("Escape");
    // After Escape, focus is blurred; document.activeElement falls back to body.
    expect(document.activeElement === document.body || document.activeElement === null).toBe(true);
    document.body.removeChild(input);
  });

  it("plain Escape with body as activeElement does not throw", () => {
    setup();
    expect(() => fireKey("Escape")).not.toThrow();
  });
});
