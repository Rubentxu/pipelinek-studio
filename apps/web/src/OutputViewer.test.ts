// @vitest-environment node
import { describe, it, expect } from "vitest";
import { decodeBase64ToBytes, chunksToText } from "./OutputViewer";

/** Helper: encode raw bytes to base64 (browser btoa handles binary via charCodeAt). */
function bytesToBase64(bytes: Uint8Array): string {
  let bin = "";
  for (let i = 0; i < bytes.length; i++) bin += String.fromCharCode(bytes[i]);
  return Buffer.from(bin, "binary").toString("base64");
}

describe("decodeBase64ToBytes", () => {
  it("decodes a single-chunk ASCII payload", () => {
    const got = decodeBase64ToBytes(bytesToBase64(new TextEncoder().encode("hello")));
    expect(new TextDecoder().decode(got)).toBe("hello");
  });

  it("returns an empty Uint8Array on bad base64", () => {
    const got = decodeBase64ToBytes("!!!not-base64!!!");
    expect(got.length).toBe(0);
  });
});

describe("chunksToText", () => {
  it("renders a single ASCII chunk", () => {
    const text = "hello world";
    const chunks = [{ streamId: "out", bytesBase64: bytesToBase64(new TextEncoder().encode(text)), cursor: "0", eof: false }];
    expect(chunksToText(chunks)).toBe(text);
  });

  // Audit F-P1-WEB-4 (2026-10-10): the previous implementation
  // decoded each chunk to a string independently and joined the
  // strings. A multi-byte UTF-8 sequence that straddled a chunk
  // boundary would be split, producing U+FFFD replacement chars
  // in the rendered output. The fix is to concatenate the raw
  // bytes first, then decode the full byte array as UTF-8 once.
  it("correctly decodes a multi-byte UTF-8 sequence split across chunks", () => {
    // "héllo" — the é is 0xC3 0xA9. We split mid-sequence:
    //   chunk A: "h" (0x68) + first byte of é (0xC3)
    //   chunk B: second byte of é (0xA9) + "llo" (0x6C 0x6C 0x6F)
    const chunkA = new Uint8Array([0x68, 0xc3]);
    const chunkB = new Uint8Array([0xa9, 0x6c, 0x6c, 0x6f]);
    const chunks = [
      { streamId: "out", bytesBase64: bytesToBase64(chunkA), cursor: "0", eof: false },
      { streamId: "out", bytesBase64: bytesToBase64(chunkB), cursor: "2", eof: false },
    ];
    const got = chunksToText(chunks);
    expect(got).toBe("héllo");
    // The previous bug produced 'h��to' (or similar). The
    // new contract must not contain any replacement character.
    expect(got).not.toContain("�");
  });

  it("renders an emoji (4-byte UTF-8) split across two chunks", () => {
    // U+1F600 "😀" is 0xF0 0x9F 0x98 0x80 (4 bytes).
    const chunkA = new Uint8Array([0x68, 0x69, 0x20, 0xf0]);
    const chunkB = new Uint8Array([0x9f, 0x98, 0x80, 0x21]); // 0x21 = "!"
    const chunks = [
      { streamId: "out", bytesBase64: bytesToBase64(chunkA), cursor: "0", eof: false },
      { streamId: "out", bytesBase64: bytesToBase64(chunkB), cursor: "4", eof: false },
    ];
    expect(chunksToText(chunks)).toBe("hi 😀!");
  });

  it("renders an empty chunk list as empty text", () => {
    expect(chunksToText([])).toBe("");
  });

  it("skips a chunk whose base64 is invalid and renders the rest", () => {
    const chunks = [
      { streamId: "out", bytesBase64: "!!!not-base64!!!", cursor: "0", eof: false },
      { streamId: "out", bytesBase64: bytesToBase64(new TextEncoder().encode("ok")), cursor: "0", eof: false },
    ];
    expect(chunksToText(chunks)).toBe("ok");
  });

  it("preserves the chunk order", () => {
    const chunks = [
      { streamId: "out", bytesBase64: bytesToBase64(new TextEncoder().encode("foo")), cursor: "0", eof: false },
      { streamId: "out", bytesBase64: bytesToBase64(new TextEncoder().encode("bar")), cursor: "3", eof: false },
      { streamId: "out", bytesBase64: bytesToBase64(new TextEncoder().encode("baz")), cursor: "6", eof: false },
    ];
    expect(chunksToText(chunks)).toBe("foobarbaz");
  });
});
