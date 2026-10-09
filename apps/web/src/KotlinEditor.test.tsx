import { describe, expect, it } from "vitest";
import React from "react";
import { render, screen } from "@testing-library/react";
import { KotlinEditor } from "./KotlinEditor";

/**
 * S5 E5.6 — minimal component smoke tests for the Kotlin editor.
 * Monaco itself is heavy and not loaded in the test env; we assert
 * only on the loading / unavailable states the wrapper exposes.
 */

describe("KotlinEditor", () => {
  it("renders the loading state on first render", () => {
    render(<KotlinEditor />);
    // Either the loading or the unavailable state is acceptable depending
    // on whether Monaco's CDN is reachable from the test runner.
    const loading = screen.queryByTestId("monaco-loading");
    const unavailable = screen.queryByTestId("monaco-unavailable");
    const editor = screen.queryByTestId("monaco-editor");
    expect(loading || unavailable || editor).toBeTruthy();
  });
});
