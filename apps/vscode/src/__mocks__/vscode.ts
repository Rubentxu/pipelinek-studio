// Minimal stand-in for the `vscode` module, used only by
// the vitest node environment. The real `vscode` module
// comes from the VS Code extension host, which is not
// available outside the host.
//
// R5 EVO-C.T3 (2026-10-16): the mock grew from "just enough
// for the tree view" to a small, stateful fake of the
// command surface so `extension.test.ts` can drive the real
// `activate()` and assert the observable result (requests,
// messages, documents, progress) instead of poking at
// internals. Everything mutable lives in `__state`; tests
// call `__reset()` in `beforeEach` and enqueue responses on
// the queues below. The mock is excluded from the
// production tsconfig and from eslint.
//
// R5 EVO-C.T4 (2026-10-16): the mock also carries a durable
// `workspaceState` memento (`createMemento`/`__createContext`), the
// document-save listener (`__fireSave`), the CodeLens provider
// reference and the QuickPick payloads, so the file-association
// unit can assert persistence, lens refresh and the exact
// scenario/experiment choices offered to the operator.

export class EventEmitter<T> {
  private listeners: ((e: T) => void)[] = [];
  // Mirrors vscode.EventEmitter.event which is a callable
  // property (not a getter) of type `vscode.Event<T>`.
  readonly event = (listener: (e: T) => void): { dispose: () => void } => {
    this.listeners.push(listener);
    return {
      dispose: () => {
        this.listeners = this.listeners.filter((l) => l !== listener);
      },
    };
  };
  fire(data: T): void {
    for (const l of this.listeners) l(data);
  }
  dispose(): void {
    this.listeners = [];
  }
}

export enum TreeItemCollapsibleState {
  None = 0,
  Collapsed = 1,
  Expanded = 2,
}

export enum StatusBarAlignment {
  Left = 1,
  Right = 2,
}

export enum ProgressLocation {
  SourceControl = 1,
  Window = 10,
  Notification = 15,
}

export class ThemeIcon {
  constructor(public readonly id: string, public readonly color?: ThemeColor) {}
}

export class ThemeColor {
  constructor(public readonly id: string) {}
}

export class TreeItem {
  label: string;
  collapsibleState: TreeItemCollapsibleState;
  id?: string;
  iconPath?: ThemeIcon;
  tooltip?: string;
  contextValue?: string;
  command?: { command: string; title: string; arguments?: unknown[] };
  constructor(label: string, collapsibleState: TreeItemCollapsibleState = TreeItemCollapsibleState.None) {
    this.label = label;
    this.collapsibleState = collapsibleState;
  }
}

export class Position {
  constructor(public readonly line: number, public readonly character: number) {}
}

export class Range {
  constructor(public readonly start: Position, public readonly end: Position) {}
}

export class CodeLens {
  range: Range;
  command?: { title: string; command: string; arguments?: unknown[] };
  constructor(range: Range, command?: { title: string; command: string; arguments?: unknown[] }) {
    this.range = range;
    this.command = command;
  }
}

export class Uri {
  private constructor(
    public readonly scheme: string,
    public readonly path: string,
  ) {}
  static parse(value: string): Uri {
    const match = /^([a-zA-Z][a-zA-Z0-9+.-]*):\/\/(.*)$/.exec(value);
    if (!match) return new Uri("file", value);
    return new Uri(match[1]!.toLowerCase(), match[2]!);
  }
  get fsPath(): string {
    return this.path;
  }
  toString(): string {
    return `${this.scheme}://${this.path}`;
  }
}

export interface MockDocument {
  uri: Uri;
  languageId: string;
  content: string;
  getText(): string;
}

export interface MockStatusBarItem {
  text: string;
  tooltip?: string;
  command?: string;
  visible: boolean;
  show(): void;
  hide(): void;
  dispose(): void;
}

export interface MockMessage {
  message: string;
  items: unknown[];
}

export interface MockMemento {
  get<T>(key: string, defaultValue?: T): T | undefined;
  update(key: string, value: unknown): Promise<void>;
  keys(): string[];
}

/**
 * A `workspaceState`-shaped memento over a plain object. `update`
 * deep-copies through JSON (like the real extension host does), so a
 * caller cannot mutate persisted state by keeping a reference.
 */
export function createMemento(store: Record<string, unknown> = {}): MockMemento {
  return {
    get: <T>(key: string, defaultValue?: T): T | undefined =>
      (Object.prototype.hasOwnProperty.call(store, key) ? (store[key] as T) : defaultValue),
    update: async (key: string, value: unknown): Promise<void> => {
      if (value === undefined) {
        delete store[key];
        return;
      }
      store[key] = JSON.parse(JSON.stringify(value));
    },
    keys: () => Object.keys(store),
  };
}

export interface MockState {
  config: Record<string, unknown>;
  /** Backing store for every memento handed out by `__createContext`. */
  workspaceState: Record<string, unknown>;
  activeTextEditor?: MockDocument;
  information: MockMessage[];
  warnings: MockMessage[];
  errors: MockMessage[];
  informationResponses: unknown[];
  warningResponses: unknown[];
  inputResponses: (string | undefined)[];
  quickPickResponses: unknown[];
  progress: { location: number; title?: string }[];
  progressReports: string[];
  statusBarItems: MockStatusBarItem[];
  treeViews: { id: string; disposed: boolean }[];
  commands: Map<string, (...args: never[]) => unknown>;
  codeLensProviders: { pattern: string; provider: unknown }[];
  openedDocuments: MockDocument[];
  shownDocuments: MockDocument[];
  configurationListeners: ((e: { affectsConfiguration(section: string): boolean }) => void)[];
  saveListeners: ((doc: MockDocument) => void)[];
  /** Every `showQuickPick` receives its item array here, in call order. */
  quickPicks: unknown[][];
  fileTexts: Record<string, string>;
  reloadCount: number;
}

function freshState(): MockState {
  return {
    config: {
      baseUrl: "https://127.0.0.1:7778",
      allowInsecureHttp: false,
      profile: "default",
    },
    information: [],
    warnings: [],
    errors: [],
    informationResponses: [],
    warningResponses: [],
    inputResponses: [],
    quickPickResponses: [],
    progress: [],
    progressReports: [],
    statusBarItems: [],
    treeViews: [],
    commands: new Map(),
    codeLensProviders: [],
    openedDocuments: [],
    shownDocuments: [],
    configurationListeners: [],
    saveListeners: [],
    quickPicks: [],
    workspaceState: {},
    fileTexts: {},
    reloadCount: 0,
  };
}

/**
 * An `ExtensionContext` shaped stand-in. Pass the same backing store to
 * two contexts to simulate a second VS Code session over the same
 * workspace state (that is how the association persistence is proven).
 */
export function __createContext(store: Record<string, unknown> = __state.workspaceState): {
  subscriptions: { dispose(): unknown }[];
  workspaceState: MockMemento;
} {
  return { subscriptions: [], workspaceState: createMemento(store) };
}

/** Fire a document save for `uri`, as the editor would. */
export function __fireSave(uri: string): void {
  const parsed = Uri.parse(uri);
  const doc: MockDocument = {
    uri: parsed,
    languageId: "pipelinek",
    content: __state.fileTexts[uri] ?? "",
    getText: () => __state.fileTexts[uri] ?? "",
  };
  for (const listener of __state.saveListeners) listener(doc);
}

export let __state: MockState = freshState();

export function __reset(): void {
  for (const item of __state.statusBarItems) item.dispose();
  __state = freshState();
}

export function __fireConfigurationChange(): void {
  for (const listener of __state.configurationListeners) {
    listener({ affectsConfiguration: (section: string) => section.startsWith("pipelinekStudio") });
  }
}

function outputChannel(name: string) {
  const lines: string[] = [];
  return {
    name,
    lines,
    appendLine: (line: string) => lines.push(line),
    append: (text: string) => lines.push(text),
    show: () => undefined,
    dispose: () => undefined,
  };
}

export const window = {
  StatusBarAlignment,
  ProgressLocation,
  createOutputChannel: (name: string) => outputChannel(name),
  createStatusBarItem: (_alignment: number, _priority?: number): MockStatusBarItem => {
    const item: MockStatusBarItem = {
      text: "",
      visible: false,
      show() {
        item.visible = true;
      },
      hide() {
        item.visible = false;
      },
      dispose() {
        item.visible = false;
      },
    };
    __state.statusBarItems.push(item);
    return item;
  },
  createTreeView: (id: string) => {
    const record = { id, disposed: false };
    __state.treeViews.push(record);
    return {
      dispose: () => {
        record.disposed = true;
      },
      reveal: async () => undefined,
    };
  },
  showInformationMessage: async (message: string, ...items: unknown[]) => {
    __state.information.push({ message, items });
    return __state.informationResponses.shift();
  },
  showWarningMessage: async (message: string, ...rest: unknown[]) => {
    __state.warnings.push({ message, items: rest });
    return __state.warningResponses.shift();
  },
  showErrorMessage: async (message: string, ...items: unknown[]) => {
    __state.errors.push({ message, items });
    return undefined;
  },
  showInputBox: async (..._args: unknown[]) => __state.inputResponses.shift(),
  showQuickPick: async (items: unknown, ..._rest: unknown[]) => {
    __state.quickPicks.push(Array.isArray(items) ? [...items] : []);
    return __state.quickPickResponses.shift();
  },
  get activeTextEditor(): MockDocument | undefined {
    return __state.activeTextEditor;
  },
  showTextDocument: async (doc: MockDocument, options?: unknown) => {
    void options;
    __state.shownDocuments.push(doc);
    return doc;
  },
  withProgress: async (options: { location: number; title?: string }, task: (progress: { report(value: { message?: string }): void }, token: { isCancellationRequested: boolean }) => unknown) => {
    __state.progress.push({ location: options.location, title: options.title });
    const progress = {
      report(value: { message?: string }) {
        if (value.message) __state.progressReports.push(value.message);
      },
    };
    return task(progress, { isCancellationRequested: false });
  },
};

export const workspace = {
  getConfiguration: (section: string) => ({
    get: <T>(key: string, fallback?: T): T | undefined => {
      const value = __state.config[key];
      return (value === undefined ? fallback : value) as T | undefined;
    },
    has: (key: string) => __state.config[key] !== undefined,
    update: async (key: string, value: unknown) => {
      __state.config[key] = value;
    },
    inspect: () => undefined,
    section,
  }),
  onDidChangeConfiguration: (listener: (e: { affectsConfiguration(section: string): boolean }) => void) => {
    __state.configurationListeners.push(listener);
    return {
      dispose: () => {
        __state.configurationListeners = __state.configurationListeners.filter((l) => l !== listener);
      },
    };
  },
  onDidSaveTextDocument: (listener: (doc: MockDocument) => void) => {
    __state.saveListeners.push(listener);
    return {
      dispose: () => {
        __state.saveListeners = __state.saveListeners.filter((l) => l !== listener);
      },
    };
  },
  openTextDocument: async (arg: unknown): Promise<MockDocument> => {
    let doc: MockDocument;
    if (arg instanceof Uri) {
      const content = __state.fileTexts[arg.toString()] ?? "";
      doc = { uri: arg, languageId: "plaintext", content, getText: () => content };
    } else {
      const input = arg as { content: string; language?: string };
      doc = {
        uri: Uri.parse(`untitled:Untitled-${__state.openedDocuments.length + 1}`),
        languageId: input.language ?? "plaintext",
        content: input.content,
        getText: () => input.content,
      };
    }
    __state.openedDocuments.push(doc);
    return doc;
  },
  workspaceFolders: [],
  fs: {
    readFile: async () => new Uint8Array(),
  },
};

export const commands = {
  registerCommand: (id: string, handler: (...args: never[]) => unknown) => {
    __state.commands.set(id, handler);
    return {
      dispose: () => {
        __state.commands.delete(id);
      },
    };
  },
  executeCommand: async (id: string, ...args: unknown[]) => {
    const handler = __state.commands.get(id);
    if (!handler) throw new Error(`command not registered: ${id}`);
    return (handler as unknown as (...a: unknown[]) => unknown)(...args);
  },
};

export const languages = {
  registerCodeLensProvider: (selector: unknown, provider: unknown) => {
    const pattern = Array.isArray(selector)
      ? ((selector[0] as { pattern?: string } | undefined)?.pattern ?? "")
      : String((selector as { pattern?: string } | undefined)?.pattern ?? "");
    __state.codeLensProviders.push({ pattern, provider });
    return { dispose: () => undefined };
  },
};
