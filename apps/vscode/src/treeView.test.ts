// @vitest-environment node
//
// R5 EVO-C.2 (2026-10-11): unit tests for the sidebar tree
// model + provider. The provider is decoupled from the real
// `vscode` module via a `VscodeSurface` parameter; tests
// build a minimal in-memory surface and assert that the
// model+provider stay in sync with the ADR-013 SSE taxonomy.

import { describe, it, expect, vi } from "vitest";
import { EventEmitter, TreeItem, ThemeIcon, ThemeColor, TreeItemCollapsibleState } from "./__mocks__/vscode";
import { StudioTreeProvider, TreeModel, type VscodeSurface, type TreeElement } from "./treeView";
import type { TreeEvent } from "./sseClient";

const surface: VscodeSurface = {
  TreeItem: TreeItem as unknown as VscodeSurface["TreeItem"],
  ThemeIcon: ThemeIcon as unknown as VscodeSurface["ThemeIcon"],
  ThemeColor: ThemeColor as unknown as VscodeSurface["ThemeColor"],
  TreeItemCollapsibleState: TreeItemCollapsibleState as unknown as VscodeSurface["TreeItemCollapsibleState"],
  EventEmitter: EventEmitter as unknown as VscodeSurface["EventEmitter"],
};

function ev(kind: TreeEvent["kind"], p: Partial<TreeEvent>): TreeEvent {
  return { kind, ...p } as TreeEvent;
}

describe("TreeModel", () => {
  it("starts empty", () => {
    const m = new TreeModel();
    expect(m.experiments.size).toBe(0);
    expect(m.snapshots.size).toBe(0);
    expect(m.runs.size).toBe(0);
  });

  it("applies experiment.created (idempotent on duplicate name)", () => {
    const m = new TreeModel();
    expect(m.apply(ev("experiment.created", { id: "e1", name: "demo", createdAt: "t1", seq: 1 }))).toBe(true);
    expect(m.apply(ev("experiment.created", { id: "e1", name: "demo", createdAt: "t1", seq: 2 }))).toBe(false);
    expect(m.experiments.size).toBe(1);
  });

  it("applies experiment.deleted", () => {
    const m = new TreeModel();
    m.apply(ev("experiment.created", { id: "e1", name: "demo", createdAt: "t1", seq: 1 }));
    expect(m.apply(ev("experiment.deleted", { id: "e1", seq: 2 }))).toBe(true);
    expect(m.experiments.size).toBe(0);
  });

  it("applies snapshot.created (idempotent on duplicate id)", () => {
    const m = new TreeModel();
    m.apply(ev("experiment.created", { id: "e1", name: "demo", createdAt: "t1", seq: 1 }));
    expect(m.apply(ev("snapshot.created", {
      experimentId: "e1", snapshotId: "s1", bytes: 100, createdAt: "t2", seq: 2,
    }))).toBe(true);
    expect(m.apply(ev("snapshot.created", {
      experimentId: "e1", snapshotId: "s1", bytes: 100, createdAt: "t2", seq: 3,
    }))).toBe(false);
    expect(m.snapshots.size).toBe(1);
  });

  it("applies run.started and run.completed transitions", () => {
    const m = new TreeModel();
    m.apply(ev("experiment.created", { id: "e1", name: "demo", createdAt: "t1", seq: 1 }));
    m.apply(ev("run.started", { experimentId: "e1", runId: "r1", startedAt: "t2", seq: 2 }));
    expect(m.runs.get("r1")?.status).toBe("RUNNING");
    expect(m.apply(ev("run.completed", { runId: "r1", status: "OK", completedAt: "t3", seq: 3 }))).toBe(true);
    expect(m.runs.get("r1")?.status).toBe("OK");
    // Repeating the same completion is a no-op.
    expect(m.apply(ev("run.completed", { runId: "r1", status: "OK", completedAt: "t3", seq: 4 }))).toBe(false);
  });

  it("ignores run.step.changed (run-level state only in the MVP tree)", () => {
    const m = new TreeModel();
    m.apply(ev("experiment.created", { id: "e1", name: "demo", createdAt: "t1", seq: 1 }));
    m.apply(ev("run.started", { experimentId: "e1", runId: "r1", startedAt: "t2", seq: 2 }));
    expect(m.apply(ev("run.step.changed", {
      runId: "r1", stepId: "compile", status: "RUNNING", durationMs: 50, seq: 3,
    }))).toBe(false);
  });

  it("snapshotsFor + runsFor filter by experiment", () => {
    const m = new TreeModel();
    m.apply(ev("experiment.created", { id: "e1", name: "a", createdAt: "t1", seq: 1 }));
    m.apply(ev("experiment.created", { id: "e2", name: "b", createdAt: "t1", seq: 2 }));
    m.apply(ev("snapshot.created", { experimentId: "e1", snapshotId: "s1", bytes: 10, createdAt: "t2", seq: 3 }));
    m.apply(ev("snapshot.created", { experimentId: "e2", snapshotId: "s2", bytes: 20, createdAt: "t2", seq: 4 }));
    m.apply(ev("run.started", { experimentId: "e1", runId: "r1", startedAt: "t3", seq: 5 }));
    m.apply(ev("run.started", { experimentId: "e2", runId: "r2", startedAt: "t3", seq: 6 }));
    expect(m.snapshotsFor("e1").map((s) => s.snapshotId)).toEqual(["s1"]);
    expect(m.snapshotsFor("e2").map((s) => s.snapshotId)).toEqual(["s2"]);
    expect(m.runsFor("e1").map((r) => r.runId)).toEqual(["r1"]);
    expect(m.runsFor("e2").map((r) => r.runId)).toEqual(["r2"]);
  });
});

describe("StudioTreeProvider", () => {
  it("renders the root with experiments list", () => {
    const p = new StudioTreeProvider(new TreeModel(), surface);
    p.model.apply(ev("experiment.created", { id: "e1", name: "demo", createdAt: "t1", seq: 1 }));
    const root = p.getChildren();
    expect(root.map((c) => (c as Extract<TreeElement, { kind: "experiment" }>).id)).toEqual(["e1"]);
  });

  it("renders experiment children: Snapshots + Runs folders", () => {
    const p = new StudioTreeProvider(new TreeModel(), surface);
    p.model.apply(ev("experiment.created", { id: "e1", name: "demo", createdAt: "t1", seq: 1 }));
    const children = p.getChildren({ kind: "experiment", id: "e1", name: "demo", createdAt: "t1" });
    expect(children.map((c) => c.kind)).toEqual(["experiment.snapshots", "experiment.runs"]);
  });

  it("folds the snapshots list under the experiment", () => {
    const p = new StudioTreeProvider(new TreeModel(), surface);
    p.model.apply(ev("experiment.created", { id: "e1", name: "demo", createdAt: "t1", seq: 1 }));
    p.model.apply(ev("snapshot.created", {
      experimentId: "e1", snapshotId: "s1", bytes: 100, createdAt: "t2", seq: 2,
    }));
    const snaps = p.getChildren({ kind: "experiment.snapshots", experimentId: "e1" });
    expect(snaps).toHaveLength(1);
    expect(snaps[0]!.kind).toBe("snapshot");
  });

  it("folds the runs list under the experiment", () => {
    const p = new StudioTreeProvider(new TreeModel(), surface);
    p.model.apply(ev("experiment.created", { id: "e1", name: "demo", createdAt: "t1", seq: 1 }));
    p.model.apply(ev("run.started", { experimentId: "e1", runId: "r1", startedAt: "t2", seq: 2 }));
    p.model.apply(ev("run.completed", { runId: "r1", status: "FAILED", completedAt: "t3", seq: 3 }));
    const runs = p.getChildren({ kind: "experiment.runs", experimentId: "e1" });
    expect(runs).toHaveLength(1);
    const r = runs[0] as Extract<TreeElement, { kind: "run" }>;
    expect(r.runId).toBe("r1");
    expect(r.status).toBe("FAILED");
  });

  it("emits a refresh event when a real change happens; not for no-ops", () => {
    const p = new StudioTreeProvider(new TreeModel(), surface);
    const refresh = vi.fn();
    p.onDidChangeTreeData(refresh);
    // No-op (duplicate run.started of the same id with no new info):
    p.model.apply(ev("experiment.created", { id: "e1", name: "demo", createdAt: "t1", seq: 1 }));
    p.model.apply(ev("run.started", { experimentId: "e1", runId: "r1", startedAt: "t2", seq: 2 }));
    refresh.mockClear();
    // Repeat run.started of r1: provider.apply returns false, no refresh.
    p.apply(ev("run.started", { experimentId: "e1", runId: "r1", startedAt: "t2", seq: 3 }));
    expect(refresh).not.toHaveBeenCalled();
    // Real completion: refresh fires scoped to the experiment.
    p.apply(ev("run.completed", { runId: "r1", status: "OK", completedAt: "t3", seq: 4 }));
    expect(refresh).toHaveBeenCalledTimes(1);
  });

  it("TreeItem on a FAILED run uses an error color", () => {
    const p = new StudioTreeProvider(new TreeModel(), surface);
    p.model.apply(ev("experiment.created", { id: "e1", name: "demo", createdAt: "t1", seq: 1 }));
    p.model.apply(ev("run.started", { experimentId: "e1", runId: "r1", startedAt: "t2", seq: 2 }));
    p.model.apply(ev("run.completed", { runId: "r1", status: "FAILED", completedAt: "t3", seq: 3 }));
    const it = p.getTreeItem({
      kind: "run", experimentId: "e1", runId: "r1", status: "FAILED", startedAt: "t2",
    });
    const icon = it.iconPath as { id: string; color?: { id: string } };
    expect(icon.id).toBe("error");
    expect(icon.color?.id).toBe("errorForeground");
  });
});
