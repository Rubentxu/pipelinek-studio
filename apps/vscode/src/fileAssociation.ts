// R5 EVO-C.T4 (2026-10-16) — persistent per-document experiment association.
//
// The operator chose "link this file to an existing experiment" instead of
// "create an experiment per click". That decision needs exactly one durable
// fact, and this module owns it:
//
//   document URI  ->  existing experiment id
//
// It is stored in `workspaceState` under a single key, so it survives a
// window reload and a new controller/session instance, and it is scoped to
// the workspace (a different project never inherits the link). Nothing here
// creates an experiment, contacts the studio, or reads the file: creating
// experiments and running them stays in the extension, which validates the
// id against the studio's authoritative collection first.
//
// A corrupted workspace state is treated as "no associations" instead of
// throwing or reporting a bogus link, and only non-empty string ids are
// accepted on write, so a half-written value can never become a run target.

/** `workspaceState` key holding the `{ [documentUri]: experimentId }` map. */
export const ASSOCIATION_STATE_KEY = "pipelinekStudio.fileExperimentAssociations";

/** The subset of `vscode.Memento` this module needs (injected for tests). */
export interface AssociationMemento {
  get<T>(key: string, defaultValue?: T): T | undefined;
  update(key: string, value: unknown): Thenable<void> | Promise<void>;
}

export class FileExperimentStore {
  constructor(
    private readonly memento: AssociationMemento,
    private readonly key: string = ASSOCIATION_STATE_KEY,
  ) {}

  /** Experiment id linked to this document URI, or undefined when unlinked. */
  get(uri: string): string | undefined {
    return this.read()[uri];
  }

  /** Every association, as a copy (mutating it cannot alter persisted state). */
  entries(): Record<string, string> {
    return this.read();
  }

  /** Link (or re-link) a document to an existing experiment id. */
  async set(uri: string, experimentId: string): Promise<void> {
    if (uri.trim() === "") {
      throw new Error("cannot associate an empty document uri");
    }
    const id = experimentId.trim();
    if (id === "") {
      throw new Error("cannot associate a document with an empty experiment id");
    }
    const record = this.read();
    record[uri] = id;
    await this.memento.update(this.key, record);
  }

  /** Explicit unlink. Leaves every other association untouched. */
  async clear(uri: string): Promise<void> {
    const record = this.read();
    if (!(uri in record)) return;
    delete record[uri];
    await this.memento.update(this.key, record);
  }

  private read(): Record<string, string> {
    const raw = this.memento.get<unknown>(this.key, {});
    if (!raw || typeof raw !== "object" || Array.isArray(raw)) return {};
    const record: Record<string, string> = {};
    for (const [uri, experimentId] of Object.entries(raw as Record<string, unknown>)) {
      if (typeof experimentId !== "string") continue;
      const id = experimentId.trim();
      if (uri === "" || id === "") continue;
      record[uri] = id;
    }
    return record;
  }
}
