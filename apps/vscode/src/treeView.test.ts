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
import type { TreeSnapshot } from "./studioApi";

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
    // EVO-C S3 hierarchy: experiments -> runs -> snapshots.
    expect(children.map((c) => c.kind)).toEqual(["experiment.runs", "experiment.snapshots"]);
  });

  it("opens the run journal when a run node is clicked", () => {
    const p = new StudioTreeProvider(new TreeModel(), surface);
    p.model.apply(ev("experiment.created", { id: "e1", name: "demo", createdAt: "t1", seq: 1 }));
    p.model.apply(ev("run.started", { experimentId: "e1", runId: "r1", startedAt: "t2", seq: 2 }));
    const item = p.getTreeItem({
      kind: "run", experimentId: "e1", runId: "r1", status: "RUNNING", startedAt: "t2",
    }) as unknown as { command?: { command: string; arguments?: unknown[] } };
    expect(item.command?.command).toBe("pipelinek-studio.openRun");
    expect((item.command?.arguments?.[0] as { runId: string }).runId).toBe("r1");
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

// R5 EVO-C.T3 (2026-10-16): the tree is no longer an event-only cache. On
// every (re)connect the extension reconciles it against the authoritative
// collection reads, and mutations captured while that read is in flight are
// queued so a race cannot lose them.
describe("TreeModel authoritative reconciliation", () => {
  const snapshot = (over: Partial<TreeSnapshot> = {}): TreeSnapshot => ({
    experiments: [],
    snapshots: [],
    runs: [],
    truncated: false,
    ...over,
  });

  it("reconciles by stable id: drops stale rows, adds new ones, updates state", () => {
    const m = new TreeModel();
    m.apply(ev("experiment.created", { id: "e1", name: "stale", createdAt: "t1", seq: 1 }));
    m.apply(ev("experiment.created", { id: "gone", name: "gone", createdAt: "t1", seq: 2 }));
    m.apply(ev("run.started", { experimentId: "e1", runId: "r1", startedAt: "t2", seq: 3 }));

    const changed = m.reconcile(
      snapshot({
        experiments: [
          { id: "e1", name: "renamed", createdAt: "t1" },
          { id: "e2", name: "fresh", createdAt: "t9" },
        ],
        runs: [
          { experimentId: "e1", runId: "r1", state: "FAILED", createdAt: "t2" },
          { experimentId: "e2", runId: "r9", state: "RUNNING", createdAt: "t10" },
        ],
      }),
    );

    expect(changed).toBe(true);
    expect([...m.experiments.keys()].sort()).toEqual(["e1", "e2"]);
    expect(m.experiments.get("e1")?.name).toBe("renamed");
    expect(m.runs.get("r1")?.status).toBe("FAILED");
    expect(m.runs.get("r9")?.experimentId).toBe("e2");
  });

  it("is a no-op when the snapshot matches the current state", () => {
    const m = new TreeModel();
    const s = snapshot({
      experiments: [{ id: "e1", name: "demo", createdAt: "t1" }],
      runs: [{ experimentId: "e1", runId: "r1", state: "RUNNING", createdAt: "t2" }],
    });
    expect(m.reconcile(s)).toBe(true);
    expect(m.reconcile(s)).toBe(false);
  });

  it("keeps rows outside a truncated server view instead of deleting them", () => {
    const m = new TreeModel();
    m.apply(ev("experiment.created", { id: "e1", name: "demo", createdAt: "t1", seq: 1 }));
    m.apply(ev("experiment.created", { id: "old", name: "outside the page", createdAt: "t0", seq: 2 }));

    m.reconcile(snapshot({ experiments: [{ id: "e1", name: "demo", createdAt: "t1" }], truncated: true }));

    expect(m.experiments.has("old")).toBe(true);
    expect(m.experiments.has("e1")).toBe(true);
  });

  it("reports counters and the most recent run for the status indicator", () => {
    const m = new TreeModel();
    m.reconcile(
      snapshot({
        experiments: [{ id: "e1", name: "demo", createdAt: "t1" }],
        runs: [
          { experimentId: "e1", runId: "r1", state: "RUNNING", createdAt: "t2" },
          { experimentId: "e1", runId: "r2", state: "FAILED", createdAt: "t3" },
        ],
      }),
    );
    expect(m.counters()).toEqual({ running: 1, failed: 1 });
    expect(m.mostRecentRun()?.runId).toBe("r2");
  });
});

describe("StudioTreeProvider.reconcileWith", () => {
  it("queues mutations that arrive while the snapshot is loading", async () => {
    const p = new StudioTreeProvider(new TreeModel(), surface);
    let release: (value: TreeSnapshot) => void = () => undefined;
    const loading = new Promise<TreeSnapshot>((resolve) => {
      release = resolve;
    });

    const inFlight = p.reconcileWith(() => loading);
    // A new run appears while the authoritative read is still on the wire.
    p.apply(ev("run.started", { experimentId: "e1", runId: "r2", startedAt: "t3", seq: 9 }));
    expect(p.model.runs.has("r2")).toBe(false);

    release({
      experiments: [{ id: "e1", name: "demo", createdAt: "t1" }],
      snapshots: [],
      runs: [{ experimentId: "e1", runId: "r1", state: "RUNNING", createdAt: "t2" }],
      truncated: false,
    });
    await inFlight;

    expect(p.model.runs.has("r1")).toBe(true);
    // The race did not lose the run that started mid-load.
    expect(p.model.runs.get("r2")?.status).toBe("RUNNING");
  });

  // R5 correction (P-C): buffering is paired with each serialized load, not
  // with the first `beginSnapshot()` only. A second overlapping refresh used
  // to leave no queue in place, so a mutation arriving during its load was
  // applied live and then deleted by that load's `replaceFrom`.
  it("keeps a mutation that arrives during the second of two overlapping refreshes", async () => {
    const p = new StudioTreeProvider(new TreeModel(), surface);
    const releases: ((value: TreeSnapshot) => void)[] = [];
    const load = (): Promise<TreeSnapshot> =>
      new Promise<TreeSnapshot>((resolve) => {
        releases.push(resolve);
      });
    const empty = (): TreeSnapshot => ({ experiments: [], snapshots: [], runs: [], truncated: false });
    const tick = (): Promise<void> => new Promise<void>((resolve) => setTimeout(resolve, 0));

    const first = p.reconcileWith(load);
    const second = p.reconcileWith(load);
    await tick();
    // Refreshes are serialised: the second load has not started yet.
    expect(releases).toHaveLength(1);

    releases[0]!(empty());
    await first;
    await tick();
    expect(releases).toHaveLength(2);

    // The mutation arrives while the SECOND load is on the wire.
    p.apply(ev("experiment.created", { id: "e2", name: "late", createdAt: "t2", seq: 2 }));
    expect(p.model.experiments.has("e2")).toBe(false);

    releases[1]!({
      experiments: [{ id: "e1", name: "demo", createdAt: "t1" }],
      snapshots: [],
      runs: [],
      truncated: false,
    });
    await second;

    // The second authoritative view is applied ...
    expect(p.model.experiments.has("e1")).toBe(true);
    // ... and it must not erase the mutation captured while it was loading.
    expect(p.model.experiments.get("e2")?.name).toBe("late");
  });

  it("still applies queued events when the snapshot load fails", async () => {
    const p = new StudioTreeProvider(new TreeModel(), surface);
    const inFlight = p.reconcileWith(async () => {
      throw new Error("boom");
    });
    p.apply(ev("experiment.created", { id: "e1", name: "demo", createdAt: "t1", seq: 1 }));
    await expect(inFlight).rejects.toThrow(/boom/);
    expect(p.model.experiments.has("e1")).toBe(true);
  });
});
