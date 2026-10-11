// R5 EVO-C.2 (2026-10-11): the VS Code sidebar tree view
// that renders the live state of the studio's experiments,
// snapshots, and runs. The tree is a pure projection of the
// ADR-013 SSE event stream onto a `TreeDataProvider` — it
// never issues HTTP reads of its own (the journal is the
// authority, the tree is just the visual cache).
//
// Tree shape:
//
//   <root>  Experiments
//     <experiment>
//       Snapshots
//         <snapshot sha7>
//       Runs
//         <run id8> (state)
//
// The tree is bootstrapped from the SSE stream only; the
// studio's HTTP API has no list-experiments endpoint in the
// MVP, so after a server restart the tree is empty until
// fresh events arrive. A future iteration can add a
// snapshot-history ring buffer to the bus (R5.7 follow-up)
// and bootstrap from `lastEventId + 1`.

import * as vscode from "vscode";
import type { TreeEvent } from "./sseClient";

/** Tree element types. Each carries the data the TreeItem
 *  needs to render; the in-memory model lives in [TreeModel]. */
export type TreeElement =
  | { kind: "root" }
  | { kind: "experiment"; id: string; name: string; createdAt: string }
  | { kind: "experiment.snapshots"; experimentId: string }
  | { kind: "snapshot"; experimentId: string; snapshotId: string; createdAt: string; bytes: number }
  | { kind: "experiment.runs"; experimentId: string }
  | { kind: "run"; experimentId: string; runId: string; status: string; startedAt?: string };

/** Minimal vscode surface the model needs. Lets unit tests
 *  inject a fake without bringing in the real `vscode` module
 *  (the vitest node env doesn't have it). */
export interface VscodeSurface {
  readonly TreeItem: typeof vscode.TreeItem;
  readonly ThemeIcon: typeof vscode.ThemeIcon;
  readonly ThemeColor: typeof vscode.ThemeColor;
  readonly TreeItemCollapsibleState: typeof vscode.TreeItemCollapsibleState;
  /** A constructor for an EventEmitter whose `.event` is
   *  callable and returns a `Disposable`. Tests inject a
   *  fake implementation; the real vscode surface uses
   *  the global `vscode.EventEmitter` class. */
  readonly EventEmitter: new <T>() => {
    event: (listener: (e: T) => void) => { dispose: () => void };
    fire(data: T): void;
  };
}

const defaultSurface: VscodeSurface = vscode;

/**
 * The in-memory model. Pure data + change notification. The
 * tree data provider is a thin projection.
 */
export class TreeModel {
  /** experiments keyed by id; preserves insertion order. */
  readonly experiments = new Map<string, { id: string; name: string; createdAt: string }>();
  /** snapshots keyed by `${experimentId}::${snapshotId}`. */
  readonly snapshots = new Map<string, { experimentId: string; snapshotId: string; createdAt: string; bytes: number }>();
  /** runs keyed by runId. */
  readonly runs = new Map<string, { experimentId: string; runId: string; status: string; startedAt?: string }>();

  apply(ev: TreeEvent): boolean {
    switch (ev.kind) {
      case "experiment.created": {
        // The id and name come from the event payload. Idempotent
        // overwrite: a re-published ExperimentCreated is a no-op.
        const existing = this.experiments.get(ev.id);
        if (existing && existing.name === ev.name) return false;
        this.experiments.set(ev.id, { id: ev.id, name: ev.name, createdAt: ev.createdAt });
        return true;
      }
      case "experiment.deleted": {
        return this.experiments.delete(ev.id);
      }
      case "snapshot.created": {
        const k = `${ev.experimentId}::${ev.snapshotId}`;
        if (this.snapshots.has(k)) return false;
        this.snapshots.set(k, {
          experimentId: ev.experimentId,
          snapshotId: ev.snapshotId,
          createdAt: ev.createdAt,
          bytes: ev.bytes,
        });
        return true;
      }
      case "run.started": {
        const existing = this.runs.get(ev.runId);
        if (existing) {
          // The SSE taxonomy guarantees a run only starts
          // once, so a re-apply is normally a no-op. Update
          // only if the experiment mapping or timestamp
          // actually changed (defensive against bus replay
          // or upstream retries).
          if (existing.experimentId === ev.experimentId && existing.startedAt === ev.startedAt) {
            return false;
          }
          existing.experimentId = ev.experimentId;
          existing.startedAt = ev.startedAt;
          return true;
        }
        this.runs.set(ev.runId, {
          experimentId: ev.experimentId,
          runId: ev.runId,
          status: "RUNNING",
          startedAt: ev.startedAt,
        });
        return true;
      }
      case "run.step.changed": {
        // Step-level events are not modelled in the tree (the
        // MVP tree shows run-level state only). The run's
        // status remains whatever RunStarted/RunCompleted set
        // it to; we still touch a no-op marker so the model
        // returns `false` and the view doesn't refresh.
        void ev;
        return false;
      }
      case "run.completed": {
        const r = this.runs.get(ev.runId);
        if (!r) return false;
        if (r.status === ev.status) return false;
        r.status = ev.status;
        return true;
      }
    }
  }

  /** Clear all state. The tree treats this as a full
   *  reset; the SSE events that drove the previous state
   *  are gone (the bus is in-memory, not durable). */
  reset(): void {
    this.experiments.clear();
    this.snapshots.clear();
    this.runs.clear();
  }

  /** Snapshots for one experiment, ordered by createdAt ASC. */
  snapshotsFor(experimentId: string) {
    return [...this.snapshots.values()]
      .filter((s) => s.experimentId === experimentId)
      .sort((a, b) => a.createdAt.localeCompare(b.createdAt));
  }

  /** Runs for one experiment, ordered by startedAt DESC. */
  runsFor(experimentId: string) {
    return [...this.runs.values()]
      .filter((r) => r.experimentId === experimentId)
      .sort((a, b) => (b.startedAt ?? "").localeCompare(a.startedAt ?? ""));
  }
}

/**
 * R5 EVO-C.2 (2026-10-11): the tree data provider. Pure
 * projection of [TreeModel] onto the vscode TreeDataProvider
 * contract. Each [apply] call that returns `true` emits a
 * refresh signal scoped to the affected experiment.
 */
export class StudioTreeProvider implements vscode.TreeDataProvider<TreeElement> {
  private readonly _onDidChangeTreeData = new defaultSurface.EventEmitter<TreeElement | undefined>();
  readonly onDidChangeTreeData: vscode.Event<TreeElement | undefined> = this._onDidChangeTreeData.event;
  readonly model: TreeModel;

  constructor(
    model: TreeModel = new TreeModel(),
    private readonly surface: VscodeSurface = defaultSurface,
  ) {
    this.model = model;
  }

  /** Apply a typed SSE event. Emits a refresh signal only
   *  when the model's apply() reports a real change. */
  apply(ev: TreeEvent): void {
    const changed = this.model.apply(ev);
    if (!changed) return;
    // Scope the refresh: experiment.created/deleted refreshes
    // the root; snapshot.created, run.started, run.completed
    // refresh the parent experiment.
    if (ev.kind === "experiment.created" || ev.kind === "experiment.deleted") {
      this._onDidChangeTreeData.fire(undefined);
    } else if (ev.kind === "snapshot.created" || ev.kind === "run.started") {
      this._onDidChangeTreeData.fire({ kind: "experiment", id: ev.experimentId, name: "", createdAt: "" });
    } else if (ev.kind === "run.completed") {
      // run.completed does not carry experimentId in the SSE
      // payload (per ADR-013 §"Taxonomy"); look it up in the
      // model — the run.started that came before recorded it.
      const run = this.model.runs.get(ev.runId);
      if (run) {
        this._onDidChangeTreeData.fire({ kind: "experiment", id: run.experimentId, name: "", createdAt: "" });
      }
    }
  }

  /** Test-only: full reset (R5.7 follow-up will swap this
   *  for a bus replay of `lastEventId + 1`). */
  reset(): void {
    this.model.reset();
    this._onDidChangeTreeData.fire(undefined);
  }

  getTreeItem(element: TreeElement): vscode.TreeItem {
    const t = this.surface.TreeItem;
    const ti = this.surface.ThemeIcon;
    const tc = this.surface.ThemeColor;
    const cs = this.surface.TreeItemCollapsibleState;
    switch (element.kind) {
      case "root": {
        const it = new t("Experiments", cs.None);
        it.iconPath = new ti("root-folder");
        return it;
      }
      case "experiment": {
        const it = new t(element.name, cs.Collapsed);
        it.id = element.id;
        it.iconPath = new ti("beaker");
        it.tooltip = `${element.id}\ncreated ${element.createdAt}`;
        it.contextValue = "experiment";
        return it;
      }
      case "experiment.snapshots": {
        const it = new t("Snapshots", cs.Collapsed);
        it.iconPath = new ti("folder");
        return it;
      }
      case "snapshot": {
        const sha7 = element.snapshotId.slice(0, 7);
        const it = new t(sha7, cs.None);
        it.id = `snapshot::${element.experimentId}::${element.snapshotId}`;
        it.iconPath = new ti("file");
        it.tooltip = `${element.snapshotId}\n${element.bytes} bytes\ncreated ${element.createdAt}`;
        return it;
      }
      case "experiment.runs": {
        const it = new t("Runs", cs.Collapsed);
        it.iconPath = new ti("folder");
        return it;
      }
      case "run": {
        const id8 = element.runId.slice(0, 8);
        const it = new t(`${id8}  (${element.status})`, cs.None);
        it.id = `run::${element.runId}`;
        const status = element.status;
        // Build the icon in one step: ThemeIcon.color is a
        // constructor-only field on the real vscode type.
        const iconId = status === "OK"
          ? "pass"
          : status === "FAILED"
            ? "error"
            : status === "CANCELLED"
              ? "debug-disconnect"
              : "sync~spin";
        const iconColor = status === "FAILED"
          ? new tc("errorForeground")
          : status === "OK"
            ? new tc("testing.iconPassed")
            : undefined;
        it.iconPath = new ti(iconId, iconColor);
        it.tooltip = element.runId + (element.startedAt ? `\nstarted ${element.startedAt}` : "");
        it.contextValue = "run";
        return it;
      }
    }
  }

  getChildren(element?: TreeElement): TreeElement[] {
    if (!element || element.kind === "root") {
      return [
        ...[...this.model.experiments.values()].map((e) => ({
          kind: "experiment" as const,
          id: e.id,
          name: e.name,
          createdAt: e.createdAt,
        })),
      ];
    }
    if (element.kind === "experiment") {
      return [
        { kind: "experiment.snapshots", experimentId: element.id },
        { kind: "experiment.runs", experimentId: element.id },
      ];
    }
    if (element.kind === "experiment.snapshots") {
      return this.model
        .snapshotsFor(element.experimentId)
        .map((s) => ({
          kind: "snapshot" as const,
          experimentId: s.experimentId,
          snapshotId: s.snapshotId,
          createdAt: s.createdAt,
          bytes: s.bytes,
        }));
    }
    if (element.kind === "experiment.runs") {
      return this.model
        .runsFor(element.experimentId)
        .map((r) => ({
          kind: "run" as const,
          experimentId: r.experimentId,
          runId: r.runId,
          status: r.status,
          startedAt: r.startedAt,
        }));
    }
    return [];
  }
}

/** shortId, exported for the tests. */
export function shortId(id: string, n: number): string {
  return id.length <= n ? id : id.slice(0, n);
}
