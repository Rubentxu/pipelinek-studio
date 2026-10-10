// @vitest-environment node
import { describe, it, expect } from "vitest";
import {
  requireString,
  optionalString,
  optionalNumber,
  McpInputError,
} from "./config.js";

describe("requireString", () => {
  it("returns the trimmed string when given a non-empty string", () => {
    expect(requireString({ name: "  hello  " }, "name")).toBe("hello");
  });

  it("throws McpInputError on missing arg", () => {
    expect(() => requireString({}, "name")).toThrow(McpInputError);
    try {
      requireString({}, "name");
    } catch (e) {
      const err = e as McpInputError;
      expect(err.code).toBe("INVALID_ARGUMENT");
      expect(err.argName).toBe("name");
      expect(err.message).toMatch(/required/);
    }
  });

  it("throws McpInputError on null", () => {
    expect(() => requireString({ name: null }, "name")).toThrow(/null/);
  });

  it("throws McpInputError on undefined", () => {
    expect(() => requireString({ name: undefined }, "name")).toThrow(/required/);
  });

  it("throws McpInputError on non-string", () => {
    expect(() => requireString({ name: 42 }, "name")).toThrow(/number/);
    expect(() => requireString({ name: true }, "name")).toThrow(/boolean/);
    expect(() => requireString({ name: {} }, "name")).toThrow(/object/);
    expect(() => requireString({ name: [] }, "name")).toThrow(/object/);
  });

  it("throws McpInputError on empty or whitespace-only string", () => {
    expect(() => requireString({ name: "" }, "name")).toThrow(/non-empty/);
    expect(() => requireString({ name: "   " }, "name")).toThrow(/non-empty/);
  });

  it("throws McpInputError when the trimmed string exceeds 4096 chars", () => {
    const big = "a".repeat(4097);
    expect(() => requireString({ name: big }, "name")).toThrow(/4096/);
    // 4096 is the limit
    expect(requireString({ name: "a".repeat(4096) }, "name")).toHaveLength(4096);
  });
});

describe("optionalString", () => {
  it("returns the default when the arg is missing", () => {
    expect(optionalString({}, "creator", "mcp")).toBe("mcp");
  });

  it("returns the default when the arg is null", () => {
    expect(optionalString({ creator: null }, "creator", "mcp")).toBe("mcp");
  });

  it("returns the value when the arg is a string", () => {
    expect(optionalString({ creator: "alice" }, "creator", "mcp")).toBe("alice");
  });

  it("throws when the arg is a non-string non-null", () => {
    expect(() => optionalString({ creator: 42 }, "creator", "mcp")).toThrow(McpInputError);
  });
});

describe("optionalNumber", () => {
  it("returns the default when the arg is missing", () => {
    expect(optionalNumber({}, "limit", 50)).toBe(50);
  });

  it("returns the value when the arg is a finite number", () => {
    expect(optionalNumber({ limit: 100 }, "limit", 50)).toBe(100);
  });

  it("throws when the arg is a non-number", () => {
    expect(() => optionalNumber({ limit: "100" }, "limit", 50)).toThrow(/finite number/);
    expect(() => optionalNumber({ limit: null }, "limit", 50)).not.toThrow();
    expect(() => optionalNumber({ limit: Infinity }, "limit", 50)).toThrow(/finite/);
    expect(() => optionalNumber({ limit: NaN }, "limit", 50)).toThrow(/finite/);
  });

  it("throws when the value is outside the [min, max] range", () => {
    expect(() => optionalNumber({ limit: 0 }, "limit", 50, 1, 200)).toThrow(/between/);
    expect(() => optionalNumber({ limit: 201 }, "limit", 50, 1, 200)).toThrow(/between/);
    expect(optionalNumber({ limit: 1 }, "limit", 50, 1, 200)).toBe(1);
    expect(optionalNumber({ limit: 200 }, "limit", 50, 1, 200)).toBe(200);
  });

  it("accepts negative cursors with a custom min", () => {
    expect(optionalNumber({ after: -5 }, "after", 0, -100, 100)).toBe(-5);
    expect(() => optionalNumber({ after: -500 }, "after", 0, -100, 100)).toThrow(/between/);
  });
});
