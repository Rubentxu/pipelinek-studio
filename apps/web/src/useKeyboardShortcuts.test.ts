import { describe, expect, it, vi } from "vitest";
import { renderHook } from "@testing-library/react";
import { useKeyboardShortcuts } from "./useKeyboardShortcuts";

/**
 * S5 E5.6 + R1.3 — unit tests for the keyboard shortcut hook.
 * Validates the 4 documented shortcuts (Cmd-S seal, Cmd-Enter create,
 * Cmd-K jump-to-editor, Esc clear focus) plus the negative case
 * (regular keys are ignored).
 */

describe("useKeyboardShortcuts", () => {
  function setup() {
    const onSave = vi.fn();
    const onCreate = vi.fn();
    // The hook returns a `containerRef` which is normally attached
    // to a div in JSX. renderHook does not render any JSX, so we
    // mount a real div in document.body, then manually mutate the
    // hook's ref via a tiny bridge: we wrap the hook in a small
    // wrapper that exposes the ref AND assign our DOM node to it.
    const fakeContainer = document.createElement("div");
    fakeContainer.tabIndex = 0;
    document.body.appendChild(fakeContainer);
    renderHook(() => {
      const r = useKeyboardShortcuts({ onSave: () => onSave(), onCreate: () => onCreate() });
      // Bridge: assign our DOM node into the hook's containerRef.
      (r.containerRef as unknown as { current: HTMLDivElement | null }).current = fakeContainer;
      return r;
    });
    return { onSave, onCreate, fakeContainer };
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

  // R1.3 (2026-10-11): Cmd-K jumps focus to the editor wrapper,
  // even when focus is currently inside a text field. The escape
  // from a non-editor focus is the entire point of the shortcut.
  it("Cmd-K focuses the editor wrapper", () => {
    const { fakeContainer } = setup();
    expect(fakeContainer).toBeTruthy();
    // Move focus elsewhere first.
    const button = document.createElement("button");
    document.body.appendChild(button);
    button.focus();
    expect(document.activeElement).toBe(button);

    const ev = fireKey("k", { metaKey: true });
    expect(document.activeElement).toBe(fakeContainer);
    expect(ev.defaultPrevented).toBe(true);

    document.body.removeChild(button);
  });

  // R1.3: Cmd-K works even from inside an <input> (the user is
  // asking for a focus jump, the field blur is intentional).
  it("Cmd-K focuses the editor from inside a text field", () => {
    const { fakeContainer } = setup();
    const input = document.createElement("input");
    document.body.appendChild(input);
    input.focus();
    fireKey("k", { metaKey: true });
    expect(document.activeElement).toBe(fakeContainer);
    document.body.removeChild(input);
  });

  // R1.3: plain k (no modifier) must NOT trigger the focus jump.
  it("plain k (no modifier) is a no-op", () => {
    const { onSave, onCreate } = setup();
    fireKey("k");
    expect(onSave).not.toHaveBeenCalled();
    expect(onCreate).not.toHaveBeenCalled();
  });
});
