// Minimal stand-in for the `vscode` module, used only by
// the vitest node environment. The real `vscode` module
// comes from the VS Code extension host, which is not
// available outside the host. The mock is intentionally
// thin: just enough surface for the tree view and the
// extension's CodeLens to render without crashing.

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
}

export enum TreeItemCollapsibleState {
  None = 0,
  Collapsed = 1,
  Expanded = 2,
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
