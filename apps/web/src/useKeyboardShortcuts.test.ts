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
    // cancelable: true is required for preventDefault() to set
    // defaultPrevented in JSDOM (KeyboardEvent defaults to
    // cancelable: false). Without this the hook's preventDefault()
    // is a no-op and the assertion below fails.
    const ev = new KeyboardEvent("keydown", {
      key,
      metaKey: opts.metaKey ?? false,
      ctrlKey: opts.ctrlKey ?? false,
      bubbles: true,
      cancelable: true,
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

  // Audit F-P1-WEB-5 (2026-10-10): the previous implementation
  // fired Cmd-S regardless of where the user's focus was. A user
  // typing in any input / textarea / [contenteditable] would
  // accidentally seal a snapshot AND lose the typed character,
  // AND the browser-native save dialog was hijacked. The new
  // contract is: preventDefault() always fires (so the
  // browser-native save dialog is suppressed), but onSave /
  // onCreate are NOT called when focus is in a text field.
  it("Cmd-S does NOT call onSave when focus is in an <input>", () => {
    const { onSave } = setup();
    const input = document.createElement("input");
    document.body.appendChild(input);
    input.focus();
    try {
      const ev = fireKey("s", { metaKey: true });
      expect(onSave).not.toHaveBeenCalled();
      // The browser-native save dialog is still suppressed even
      // when the shortcut is a no-op.
      expect(ev.defaultPrevented).toBe(true);
    } finally {
      document.body.removeChild(input);
    }
  });

  it("Cmd-Enter does NOT call onCreate when focus is in a <textarea>", () => {
    const { onCreate } = setup();
    const ta = document.createElement("textarea");
    document.body.appendChild(ta);
    ta.focus();
    try {
      const ev = fireKey("Enter", { metaKey: true });
      expect(onCreate).not.toHaveBeenCalled();
      expect(ev.defaultPrevented).toBe(true);
    } finally {
      document.body.removeChild(ta);
    }
  });

  it("Cmd-S still works when focus is on a non-text element", () => {
    const { onSave } = setup();
    const button = document.createElement("button");
    document.body.appendChild(button);
    button.focus();
    try {
      fireKey("s", { metaKey: true });
      expect(onSave).toHaveBeenCalledTimes(1);
    } finally {
      document.body.removeChild(button);
    }
  });
});
