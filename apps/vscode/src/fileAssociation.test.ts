// @vitest-environment node
//
// R5 EVO-C.T4 (2026-10-16) — the per-document experiment association.
//
// The store owns exactly one persisted fact: "this document URI belongs to
// this existing experiment id". These tests pin that contract against a
// `workspaceState`-shaped memento: one key, JSON-safe values, a fresh store
// instance over the same memento sees the same associations (a new VS Code
// session), and a corrupted stored value is ignored instead of throwing or
// being reported as a valid link.

import { describe, it, expect } from "vitest";
import { ASSOCIATION_STATE_KEY, FileExperimentStore, type AssociationMemento } from "./fileAssociation";

const FILE_URI = "file:///work/demo.pipeline.kts";

interface FakeMemento extends AssociationMemento {
  readonly store: Record<string, unknown>;
  readonly writes: unknown[];
}

function fakeMemento(store: Record<string, unknown> = {}): FakeMemento {
  const writes: unknown[] = [];
  return {
    store,
    writes,
    get<T>(key: string, defaultValue?: T): T | undefined {
      return Object.prototype.hasOwnProperty.call(store, key) ? (store[key] as T) : defaultValue;
    },
    async update(key: string, value: unknown): Promise<void> {
      writes.push(value);
      // Like the real extension host: persisted state round-trips through
      // JSON, so a caller cannot keep a live reference to it.
      store[key] = JSON.parse(JSON.stringify(value));
    },
  };
}

describe("FileExperimentStore", () => {
  it("persists one association under a single workspaceState key", async () => {
    const memento = fakeMemento();
    const store = new FileExperimentStore(memento);

    await store.set(FILE_URI, "e1");

    expect(store.get(FILE_URI)).toBe("e1");
    expect(store.entries()).toEqual({ [FILE_URI]: "e1" });
    expect(Object.keys(memento.store)).toEqual([ASSOCIATION_STATE_KEY]);
    expect(memento.store[ASSOCIATION_STATE_KEY]).toEqual({ [FILE_URI]: "e1" });
  });

  it("is visible to a new store instance over the same workspace state", async () => {
    const memento = fakeMemento();
    await new FileExperimentStore(memento).set(FILE_URI, "e1");

    // A new controller/session instance: same persisted state, new object.
    const second = new FileExperimentStore(memento);
    expect(second.get(FILE_URI)).toBe("e1");
    expect(second.get("file:///work/other.pipeline.kts")).toBeUndefined();
  });

  it("changes an existing association without losing the other documents", async () => {
    const memento = fakeMemento();
    const store = new FileExperimentStore(memento);
    await store.set(FILE_URI, "e1");
    await store.set("file:///work/other.pipeline.kts", "e2");

    await store.set(FILE_URI, "e3");

    expect(store.entries()).toEqual({
      [FILE_URI]: "e3",
      "file:///work/other.pipeline.kts": "e2",
    });
  });

  it("ignores a corrupted stored value instead of trusting it", () => {
    const corrupted: unknown[] = [
      "e1",
      ["e1"],
      42,
      null,
      { [FILE_URI]: 7, "file:///work/number.pipeline.kts": 3, "file:///work/blank.pipeline.kts": "  " },
    ];
    for (const value of corrupted) {
      const memento = fakeMemento({ [ASSOCIATION_STATE_KEY]: value });
      const store = new FileExperimentStore(memento);
      expect(store.get(FILE_URI)).toBeUndefined();
      expect(store.entries()).toEqual({});
    }
  });

  it("drops only the malformed entries of a partly valid record", () => {
    const memento = fakeMemento({
      [ASSOCIATION_STATE_KEY]: { [FILE_URI]: "e1", "file:///work/broken.pipeline.kts": null },
    });
    const store = new FileExperimentStore(memento);
    expect(store.entries()).toEqual({ [FILE_URI]: "e1" });
  });

  it("refuses to persist an empty uri or experiment id", async () => {
    const memento = fakeMemento();
    const store = new FileExperimentStore(memento);

    await expect(store.set("", "e1")).rejects.toThrow(/uri/i);
    await expect(store.set(FILE_URI, "   ")).rejects.toThrow(/experiment/i);
    expect(memento.writes).toEqual([]);
    expect(memento.store[ASSOCIATION_STATE_KEY]).toBeUndefined();
  });

  it("removes an association on explicit unlink and leaves the rest intact", async () => {
    const memento = fakeMemento();
    const store = new FileExperimentStore(memento);
    await store.set(FILE_URI, "e1");

    await store.clear(FILE_URI);

    expect(store.get(FILE_URI)).toBeUndefined();
    expect(memento.store[ASSOCIATION_STATE_KEY]).toEqual({});
  });
});
