// R5 EVO-C.2 (2026-10-11): the VS Code sidebar tree view
// that renders the live state of the studio's experiments,
// snapshots, and runs.
//
// R5 EVO-C.T3 (2026-10-16): the tree is no longer an
// event-only cache. The SSE stream is a live tail, so the
// provider supports authoritative reconciliation against the
// collection reads (`GET /experiments`,
// `GET /experiments/{id}/snapshots`, `GET /runs`):
//   - rows are reconciled by stable id (experiment id, run id,
//     snapshot id), never by name or position;
//   - mutations that arrive while a snapshot is being loaded are
//     queued and replayed after it, so a race cannot lose them;
//   - a partial (truncated) server view never deletes rows the
//     client already knows about.
//
// Tree shape:
//
//   <root>  Experiments
//     <experiment>
//       Snapshots
//         <snapshot sha7>
//       Runs
//         <run id8> (state)

import * as vscode from "vscode";
import type { TreeEvent } from "./sseClient";
import type { ExperimentMeta, RunMeta, SnapshotMeta, TreeSnapshot } from "./studioApi";

/** Tree element types. Each carries the data the TreeItem
 *  needs to render; the in-memory model lives in [TreeModel]. */
export type TreeElement =
  | { kind: "root" }
  | { kind: "experiment"; id: string; name: string; createdAt: string }
  | { kind: "experiment.snapshots"; experimentId: string }
  | { kind: "snapshot"; experimentId: string; snapshotId: string; createdAt: string; bytes?: number }
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
  /** experiments keyed by id; insertion order follows the server (newest first). */
  readonly experiments = new Map<string, { id: string; name: string; createdAt: string }>();
  /** snapshots keyed by `${experimentId}::${snapshotId}`. */
  readonly snapshots = new Map<string, {
    experimentId: string;
    snapshotId: string;
    createdAt: string;
    bytes?: number;
    sha256?: string;
  }>();
  /** runs keyed by runId. */
  readonly runs = new Map<string, {
    experimentId: string;
    runId: string;
    status: string;
    startedAt?: string;
    createdAt?: string;
  }>();

  /**
   * Events observed while one or more authoritative snapshots are in flight.
   * They are replayed (by id, idempotently) once the last one lands.
   */
  private pending: TreeEvent[] | null = null;
  /** Number of authoritative loads that started buffering and have not
   *  reconciled/flushed yet. Buffering stays paired with each serialized
   *  load, so an overlapping refresh cannot leave a window in which a live
   *  event is applied straight to the pre-snapshot model. */
  private loadsInFlight = 0;

  apply(ev: TreeEvent): boolean {
    if (this.loadsInFlight > 0) {
      // Do not touch the model mid-load: the snapshot that lands next may
      // not contain this mutation yet, and a replace would drop it.
      (this.pending ??= []).push(ev);
      return false;
    }
    return this.applyNow(ev);
  }

  private applyNow(ev: TreeEvent): boolean {
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

  /** Clear all state. Used by tests and by a full disconnect. */
  reset(): void {
    this.experiments.clear();
    this.snapshots.clear();
    this.runs.clear();
    this.pending = null;
    this.loadsInFlight = 0;
  }

  /**
   * Mark the start of an authoritative read. Mutations that arrive from
   * now on are queued until every in-flight read has been reconciled
   * (or flushed). Call once per load, in the same tick it is queued.
   */
  beginSnapshot(): void {
    this.loadsInFlight += 1;
    this.pending ??= [];
  }

  /**
   * Apply an authoritative read, reconciling by stable id. Queued mutations
   * are only replayed once no other read is still in flight — replaying them
   * earlier would let the next snapshot's replace drop them again. A
   * truncated read (the client stopped paging early) is merged, not
   * replacing: deleting rows the server did not get to return would be a lie
   * about the studio's state.
   */
  reconcile(snapshot: TreeSnapshot): boolean {
    const changed = this.replaceFrom(snapshot);
    return this.finishLoad() || changed;
  }

  /** Replay queued events without a snapshot (used when the load failed). */
  flushPending(): boolean {
    return this.finishLoad();
  }

  /** End one in-flight load; replay the queue when it was the last one. */
  private finishLoad(): boolean {
    if (this.loadsInFlight > 0) this.loadsInFlight -= 1;
    if (this.loadsInFlight > 0) return false;
    const queued = this.pending ?? [];
    this.pending = null;
    let changed = false;
    for (const ev of queued) changed = this.applyNow(ev) || changed;
    return changed;
  }

  /** Run counters for the status indicator. */
  counters(): { running: number; failed: number } {
    let running = 0;
    let failed = 0;
    for (const run of this.runs.values()) {
      if (RUNNING_STATES.has(run.status)) running += 1;
      else if (FAILED_STATES.has(run.status)) failed += 1;
    }
    return { running, failed };
  }

  /** Most recently started/created run, or undefined when none is known. */
  mostRecentRun(): { runId: string; status: string } | undefined {
    let best: { runId: string; status: string; key: string } | undefined;
    for (const run of this.runs.values()) {
      const key = run.startedAt ?? run.createdAt ?? "";
      if (!best || key > best.key) best = { runId: run.runId, status: run.status, key };
    }
    return best ? { runId: best.runId, status: best.status } : undefined;
  }

  private replaceFrom(snapshot: TreeSnapshot): boolean {
    let changed = false;

    if (!snapshot.truncated) {
      const keep = new Set(snapshot.experiments.map((e) => e.id));
      for (const id of [...this.experiments.keys()]) {
        if (!keep.has(id)) {
          this.experiments.delete(id);
          changed = true;
        }
      }
    }
    for (const e of snapshot.experiments) {
      const prev = this.experiments.get(e.id);
      if (prev && prev.name === e.name && prev.createdAt === e.createdAt) continue;
      this.experiments.set(e.id, { id: e.id, name: e.name, createdAt: e.createdAt });
      changed = true;
    }

    if (!snapshot.truncated) {
      const keep = new Set(snapshot.snapshots.map((snap) => `${snap.experimentId}::${snap.snapshotId}`));
      for (const key of [...this.snapshots.keys()]) {
        if (!keep.has(key)) {
          this.snapshots.delete(key);
          changed = true;
        }
      }
    }
    for (const snap of snapshot.snapshots) {
      const key = `${snap.experimentId}::${snap.snapshotId}`;
      const prev = this.snapshots.get(key);
      if (prev && prev.createdAt === snap.createdAt && prev.sha256 === snap.sha256) continue;
      // `bytes` comes from the SSE event, not the collection read; keep a
      // value learned from the stream when the row itself is unchanged.
      this.snapshots.set(key, {
        experimentId: snap.experimentId,
        snapshotId: snap.snapshotId,
        createdAt: snap.createdAt,
        sha256: snap.sha256,
        bytes: prev?.bytes,
      });
      changed = true;
    }

    if (!snapshot.truncated) {
      const keep = new Set(snapshot.runs.map((run) => run.runId));
      for (const id of [...this.runs.keys()]) {
        if (!keep.has(id)) {
          this.runs.delete(id);
          changed = true;
        }
      }
    }
    for (const run of snapshot.runs) {
      const prev = this.runs.get(run.runId);
      if (prev && prev.experimentId === run.experimentId && prev.status === run.state && (prev.createdAt ?? "") === run.createdAt) {
        continue;
      }
      this.runs.set(run.runId, {
        experimentId: run.experimentId,
        runId: run.runId,
        status: run.state,
        startedAt: prev?.startedAt,
        createdAt: run.createdAt,
      });
      changed = true;
    }

    return changed;
  }

  /** Snapshots for one experiment, ordered by createdAt ASC. */
  snapshotsFor(experimentId: string) {
    return [...this.snapshots.values()]
      .filter((s) => s.experimentId === experimentId)
      .sort((a, b) => a.createdAt.localeCompare(b.createdAt));
  }

  /** Runs for one experiment, ordered by startedAt/createdAt DESC. */
  runsFor(experimentId: string) {
    return [...this.runs.values()]
      .filter((r) => r.experimentId === experimentId)
      .sort((a, b) => runKey(b).localeCompare(runKey(a)));
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
  /** Serialises authoritative refreshes (see [reconcileWith]). */
  private refreshQueue: Promise<void> = Promise.resolve();

  constructor(
    model: TreeModel = new TreeModel(),
    private readonly surface: VscodeSurface = defaultSurface,
  ) {
    this.model = model;
  }

  /** Apply a typed SSE event. Emits a refresh signal only
   *  when the model's apply() reports a real change. Events
   *  that arrive while a snapshot is loading are queued and
   *  reported by the reconciliation instead. */
  apply(ev: TreeEvent): void {
    const changed = this.model.apply(ev);
    if (!changed) return;
    // Scope the refresh: experiment.created/deleted refreshes
    // the root; snapshot.created, run.started, run.completed
    // refresh the parent experiment.
    if (ev.kind === "experiment.created" || ev.kind === "experiment.deleted") {
      this.fire(undefined);
    } else if (ev.kind === "snapshot.created" || ev.kind === "run.started") {
      this.fire({ kind: "experiment", id: ev.experimentId, name: "", createdAt: "" });
    } else if (ev.kind === "run.completed") {
      // run.completed does not carry experimentId in the SSE
      // payload (per ADR-013 §"Taxonomy"); look it up in the
      // model — the run.started that came before recorded it.
      const run = this.model.runs.get(ev.runId);
      if (run) {
        this.fire({ kind: "experiment", id: run.experimentId, name: "", createdAt: "" });
      }
    }
  }

  /**
   * Load an authoritative snapshot and reconcile the tree against it.
   *
   * Mutations observed while `load()` is in flight are queued by
   * [TreeModel] and replayed once the last queued read has landed; if the
   * load fails the queued events are still applied (they are real, the read
   * is not). Refreshes are serialised so two overlapping reads cannot
   * interleave their begin/reconcile pairs, and each queued read starts its
   * own buffering so no event window is left uncovered.
   */
  reconcileWith(load: () => Promise<TreeSnapshot>): Promise<void> {
    // Start buffering synchronously: an event emitted in the same tick as the
    // refresh must not be applied to the pre-snapshot model.
    this.model.beginSnapshot();
    const run = this.refreshQueue.then(async () => {
      try {
        const snapshot = await load();
        if (this.model.reconcile(snapshot)) this.fire(undefined);
      } catch (e) {
        if (this.model.flushPending()) this.fire(undefined);
        throw e;
      }
    });
    this.refreshQueue = run.catch(() => undefined);
    return run;
  }

  /** Full local reset (used by tests; production refreshes reconcile). */
  reset(): void {
    this.model.reset();
    this.fire(undefined);
  }

  private fire(element: TreeElement | undefined): void {
    this._onDidChangeTreeData.fire(element);
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
        // `bytes` is only known from the SSE event that announced the
        // snapshot; the collection read does not carry it. Show what is
        // known instead of inventing a size.
        const size = element.bytes === undefined ? "size unknown" : `${element.bytes} bytes`;
        it.tooltip = `${element.snapshotId}\n${size}\ncreated ${element.createdAt}`;
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
        const iconId = SUCCEEDED_STATES.has(status)
          ? "pass"
          : FAILED_STATES.has(status)
            ? "error"
            : status === "CANCELLED"
              ? "debug-disconnect"
              : "sync~spin";
        const iconColor = FAILED_STATES.has(status)
          ? new tc("errorForeground")
          : SUCCEEDED_STATES.has(status)
            ? new tc("testing.iconPassed")
            : undefined;
        it.iconPath = new ti(iconId, iconColor);
        it.tooltip = element.runId + (element.startedAt ? `\nstarted ${element.startedAt}` : "");
        it.contextValue = "run";
        // Clicking the node opens the run's journal (S3). The command
        // receives the element, so it opens exactly this run.
        it.command = {
          command: "pipelinek-studio.openRun",
          title: "Open Run",
          arguments: [element],
        };
        return it;
      }
    }
  }

  getChildren(element?: TreeElement): TreeElement[] {
    if (!element || element.kind === "root") {
      return [...this.model.experiments.values()]
        .slice()
        .sort((a, b) => b.createdAt.localeCompare(a.createdAt))
        .map((e) => ({
          kind: "experiment" as const,
          id: e.id,
          name: e.name,
          createdAt: e.createdAt,
        }));
    }
    if (element.kind === "experiment") {
      // EVO-C S3 hierarchy: experiments -> runs -> snapshots.
      return [
        { kind: "experiment.runs", experimentId: element.id },
        { kind: "experiment.snapshots", experimentId: element.id },
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

/** Server run states that mean "still in flight" (R5 EVO-C.T3). */
const RUNNING_STATES = new Set(["CREATED", "QUEUED", "STARTING", "RUNNING"]);
/** Terminal state that is not a success: never rendered as a pass. */
const FAILED_STATES = new Set(["FAILED", "RECOVERY_REQUIRED"]);
/** `OK` is the SSE event spelling; `SUCCEEDED` is the persisted state. */
const SUCCEEDED_STATES = new Set(["OK", "SUCCEEDED"]);

function runKey(run: { startedAt?: string; createdAt?: string }): string {
  return run.startedAt ?? run.createdAt ?? "";
}
